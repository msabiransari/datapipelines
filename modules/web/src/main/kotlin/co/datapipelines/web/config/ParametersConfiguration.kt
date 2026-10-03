package co.datapipelines.web.config

import co.datapipelines.parameters.ParameterEvaluationRecorder
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetValidator
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.parameters.ParametersProperties
import co.datapipelines.parameters.SelectorPool
import co.datapipelines.parameters.SelectorRunner
import co.datapipelines.parameters.StoredParameterEvaluationRecorder
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * The parameter engine's surface wiring (record §2.4, lane D's A.1): `web` is the assembling
 * layer, `parameters` ships no Spring configuration of its own. ONE `ParametersConfig`, built
 * from the bound [ParametersProperties], feeds the [SelectorRunner], the [ParameterEvaluator]
 * AND the one [SelectorPool] — the record's §17, C27: the runner applies the statement-level
 * limits, the evaluator the evaluate-level ones, and three constructions from one config is
 * what keeps them from disagreeing.
 *
 * The template ports are the same objects the pipeline save path uses: the engines bean is
 * `templates`' (`TemplatesConfiguration`) and the registry bean its `templateDryRenderer`
 * (the not-found / version-not-found split — the validator renders through the REQUIRED
 * [co.datapipelines.parameters.SelectorProbe] port, which is the [SelectorRunner] bean), the
 * contract-side datasource port is `DomainConfiguration.contractDatasourceRegistry` (the
 * workspace-scoped visible read), and the 142 cascade's releaser releases through the
 * template's OWN statement — `ParametersHarness`'s production shape.
 */
@Configuration
@EnableConfigurationProperties(ParametersProperties::class)
class ParametersConfiguration {
    @Bean
    fun parametersConfig(properties: ParametersProperties): ParametersConfig = properties.toConfig()

    @Bean
    fun parameterSetRepository(jdbc: NamedParameterJdbcTemplate): ParameterSetRepository = ParameterSetRepository(jdbc)

    /** The selector bulkhead — ONE per process (record P31); the evaluate fan-out's statements are admitted here. */
    @Bean
    fun selectorPool(config: ParametersConfig): SelectorPool = SelectorPool(config)

    /**
     * The real selector runtime — the save-time probe AND the evaluate's tasks from one object,
     * over the workspace engines and the registry's pools (record §6.3, lane C).
     */
    @Bean
    fun selectorRunner(
        templateEngines: co.datapipelines.templates.WorkspaceTemplateEngines,
        datasources: co.datapipelines.datasources.DatasourceRegistry,
        config: ParametersConfig,
    ): SelectorRunner = SelectorRunner(templateEngines, datasources, config)

    @Bean
    fun parameterSetValidator(
        config: ParametersConfig,
        registry: TemplateDryRenderer,
        statuses: TemplateVersionStatuses,
        datasources: co.datapipelines.pipeline.DatasourceRegistry,
        probe: SelectorRunner,
    ): ParameterSetValidator = ParameterSetValidator(config, registry, statuses, datasources, probe)

    @Bean
    fun parameterSetService(
        repository: ParameterSetRepository,
        validator: ParameterSetValidator,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
        statuses: TemplateVersionStatuses,
        // #320 — the dashboards that pin a set release (`parameter.in_use`), from DependencyGuardsConfiguration.
        consumers: co.datapipelines.parameters.ParameterSetConsumers,
        releaser: TemplateReleaser,
        transactionManager: PlatformTransactionManager,
    ): ParameterSetService =
        ParameterSetService(
            repository,
            validator,
            authoring,
            statuses,
            consumers,
            releaser,
            // The metadata transaction manager (056's explicit declaration) — the release
            // cascade and the import are all-or-nothing over the same rows the template
            // import writes (the PromotionReceiveService precedent).
            transactions = TransactionTemplate(transactionManager) as TransactionOperations,
        )

    /** #376 — the durable evaluation history's tables (V48), read by the History tab and the hourly housekeeping. */
    @Bean
    fun parameterEvaluationRepository(jdbc: NamedParameterJdbcTemplate): ParameterEvaluationRepository = ParameterEvaluationRepository(jdbc)

    /** #376 — every evaluation records through this one recorder; a storage fault never fails the evaluation. */
    @Bean
    fun parameterEvaluationRecorder(repository: ParameterEvaluationRepository): ParameterEvaluationRecorder =
        StoredParameterEvaluationRecorder(repository)

    @Bean
    fun parameterEvaluator(
        runner: SelectorRunner,
        pool: SelectorPool,
        config: ParametersConfig,
        org: co.datapipelines.pipeline.OrgContext,
        recorder: ParameterEvaluationRecorder,
    ): ParameterEvaluator = ParameterEvaluator(runner, pool, config, org, Clock.systemUTC(), recorder)

    /**
     * The REST import/export path (the `PipelineImportService` precedent — the import is never
     * re-implemented per route). The promotion receive is the ATOMIC twin since #302 (record C36):
     * it validates before its transaction and lands through `ParameterSetService.importValidated`,
     * not through this service (C35 keeps the REST import the non-atomic one-call path).
     */
    @Bean
    fun parameterSetTransferService(
        sets: ParameterSetService,
        repository: ParameterSetRepository,
        templates: co.datapipelines.templates.TemplateRepository,
        templateImport: co.datapipelines.web.templates.TemplateImportService,
    ): co.datapipelines.web.parameters.ParameterSetTransferService =
        co.datapipelines.web.parameters
            .ParameterSetTransferService(sets, repository, templates, templateImport)

    /**
     * `parameters.selectors.abandoned` (record §11) — the pool's abandoned-statement count as a
     * gauge, the `transform.evaluations.abandoned` shape. The scrape ALSO calls
     * [SelectorPool.abandonedThreadsAlive] (the 194c security pass, observation 4): the prune
     * runs with every scrape, so an abandoned worker's dead `Thread` object is released by the
     * next metrics read instead of accumulating for the process's life.
     */
    private companion object {
        const val GAUGE_DESCRIPTION =
            "Selector statements abandoned at their evaluate's deadline (cancel + discard; " +
                "the worker keeps its slot until the driver returns)"
    }

    @Bean
    fun parametersSelectorsAbandonedGauge(
        pool: SelectorPool,
        meters: MeterRegistry,
    ): Gauge =
        Gauge
            .builder("parameters.selectors.abandoned") {
                pool.abandonedThreadsAlive() // prune the dead worker threads with the scrape
                pool.abandoned.sum().toDouble()
            }.description(GAUGE_DESCRIPTION)
            .register(meters)
}
