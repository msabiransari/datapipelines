package co.datapipelines.web.config

import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.application.visualization.PipelineReleaseFactsReader
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.DashboardValidator
import co.datapipelines.visualization.ParameterSetFacts
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.TemplateContractFacts
import co.datapipelines.visualization.VisualizationConfig
import co.datapipelines.visualization.VisualizationProperties
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.visualization.VisualizationValidator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

/**
 * The visualization module's surface wiring (#10 L1b; the implementation spec's §1) — `web` is the assembling
 * layer and `visualization` ships no Spring configuration of its own, the [ParametersConfiguration] shape.
 *
 * ONE [VisualizationConfig], bound from `datapipelines.visualization.*` ([VisualizationProperties] — its
 * `toConfig()` enforces every bound, naming the key), feeds BOTH readers: the seven document bounds live in the
 * readers, so every surface that reads a body through these beans — REST and MCP alike — is bounded by the same
 * numbers.
 *
 * The ports are the product's existing answers, never a second copy:
 * - the template facts are `TemplateContractFacts.over(templateDryRenderer, templateVersionStatuses)` — the
 *   registry lookup and the status read the pipeline save path uses;
 * - the pipeline facts are [PipelineReleaseFactsReader] over the save-time [PipelineResolver], the published-
 *   endpoint [ReadOnlyPipelineRule] and the registry's transform contract;
 * - the set facts read the one [ParameterSetRepository]; the visualization pins are the repository's own;
 * - the release cascade releases a DRAFT transform pin through the template's OWN [TemplateReleaser], and both
 *   services run their atomic work in a [TransactionTemplate] over the metadata transaction manager.
 *
 * The visualization release gate (`ReleaseEvidence`) is NOT wired: its default refuses every release
 * (`visualization.release.tests_missing`, `reason: gate_not_installed`) until L4 installs the evidence tables.
 */
@Configuration
@EnableConfigurationProperties(VisualizationProperties::class)
class VisualizationConfiguration {
    @Bean
    fun visualizationConfig(properties: VisualizationProperties): VisualizationConfig = properties.toConfig()

    @Bean
    fun visualizationReader(config: VisualizationConfig): VisualizationReader = VisualizationReader(config)

    @Bean
    fun dashboardReader(config: VisualizationConfig): DashboardReader = DashboardReader(config)

    @Bean
    fun visualizationRepository(jdbc: NamedParameterJdbcTemplate): VisualizationRepository = VisualizationRepository(jdbc)

    @Bean
    fun dashboardRepository(jdbc: NamedParameterJdbcTemplate): DashboardRepository = DashboardRepository(jdbc)

    @Bean
    fun visualizationValidator(
        renderer: TemplateDryRenderer,
        statuses: TemplateVersionStatuses,
    ): VisualizationValidator = VisualizationValidator(TemplateContractFacts.over(renderer, statuses))

    /** The dashboard validator's pipeline port — [PipelineReleaseFactsReader], `application`'s implementation. */
    @Bean
    fun pipelineReleaseFacts(
        pipelines: PipelineResolver,
        readOnly: ReadOnlyPipelineRule,
        renderer: TemplateDryRenderer,
    ): PipelineReleaseFacts = PipelineReleaseFactsReader(pipelines, readOnly, renderer::transformContract)

    @Bean
    fun parameterSetFacts(sets: ParameterSetRepository): ParameterSetFacts = ParameterSetFacts.over(sets)

    @Bean
    fun dashboardValidator(
        pipelines: PipelineReleaseFacts,
        sets: ParameterSetFacts,
        visualizations: VisualizationRepository,
        runtime: DashboardRuntimeConfig,
    ): DashboardValidator =
        // L2: the validator's two runtime limits — the refresh deadline cap and the most distinct executions a refresh runs.
        DashboardValidator(pipelines, sets, visualizations.pins, runtime.maxRefreshSeconds, runtime.maxExecutionsPerRefresh)

    @Bean
    @Suppress("LongParameterList") // the aggregate's ports ARE the wiring
    fun visualizationService(
        repository: VisualizationRepository,
        validator: VisualizationValidator,
        dashboards: DashboardRepository,
        authoring: AuthoringGuard,
        statuses: TemplateVersionStatuses,
        releaser: TemplateReleaser,
        transactionManager: PlatformTransactionManager,
    ): VisualizationService =
        VisualizationService(
            repository,
            validator,
            dashboards,
            authoring,
            statuses,
            templateReleaser = releaser,
            transactions = TransactionTemplate(transactionManager) as TransactionOperations,
        )

    @Bean
    @Suppress("LongParameterList") // the aggregate's ports ARE the wiring
    fun dashboardService(
        repository: DashboardRepository,
        validator: DashboardValidator,
        visualizations: VisualizationService,
        sets: ParameterSetFacts,
        authoring: AuthoringGuard,
        transactionManager: PlatformTransactionManager,
    ): DashboardService =
        DashboardService(
            repository,
            validator,
            visualizations,
            sets,
            authoring,
            transactions = TransactionTemplate(transactionManager) as TransactionOperations,
        )
}
