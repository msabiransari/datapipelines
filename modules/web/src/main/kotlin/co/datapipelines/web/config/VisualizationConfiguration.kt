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
import co.datapipelines.visualization.ReleaseEvidence
import co.datapipelines.visualization.RendererConfigValidator
import co.datapipelines.visualization.RendererConfigValidators
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
 * The release gate (#352): the factory consumes the `ReleaseEvidence` bean [VisualizationTestConfiguration]
 * declares — [co.datapipelines.visualization.VisualizationReleaseEvidence], the production §11.4 gate. The
 * renderer validator is the deep composition (`RendererConfigValidators.deep()`): Plotly through the reduced
 * 4.1.1 plot-schema, `table`/`kpi` the house schemas — the same composition the mechanical test reads, so
 * save, submit and release refuse the same configurations.
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
        renderers: RendererConfigValidator,
    ): VisualizationValidator = VisualizationValidator(TemplateContractFacts.over(renderer, statuses), renderers)

    /** The deep renderer schemas (L4a): the reduced Plotly plot-schema behind the same port the mechanical test reads. */
    @Bean
    fun rendererConfigValidator(): RendererConfigValidator = RendererConfigValidators.deep()

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

    /**
     * O2 of the L1c pass — an import that lands RELEASED judges its pins by the RELEASE rules: the same
     * status read, set facts and pin facts the validators and the release paths use, one judge beside them.
     */
    @Bean
    fun artifactImportReleaseRules(
        statuses: TemplateVersionStatuses,
        sets: ParameterSetFacts,
        visualizations: VisualizationRepository,
    ): co.datapipelines.visualization.ArtifactImportReleaseRules =
        co.datapipelines.visualization.ArtifactImportReleaseRules(statuses, sets, visualizations.pins)

    @Bean
    @Suppress("LongParameterList") // the aggregate's ports ARE the wiring
    fun visualizationService(
        repository: VisualizationRepository,
        validator: VisualizationValidator,
        dashboards: DashboardRepository,
        authoring: AuthoringGuard,
        statuses: TemplateVersionStatuses,
        releaser: TemplateReleaser,
        evidence: ReleaseEvidence,
        transactionManager: PlatformTransactionManager,
    ): VisualizationService =
        VisualizationService(
            repository,
            validator,
            dashboards,
            authoring,
            statuses,
            templateReleaser = releaser,
            evidence = evidence,
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

    /**
     * The transfer's template half (#10 L1c) — the port's web composition over [TemplateImportService], the
     * parameter-set transfer's composition. The transfer SERVICE lives in `modules/visualization`; only this
     * adapter needs the `web`-owned template import.
     */
    @Bean
    fun templateBundle(
        templates: co.datapipelines.templates.TemplateRepository,
        templateImport: co.datapipelines.web.templates.TemplateImportService,
    ): co.datapipelines.visualization.TemplateBundle =
        co.datapipelines.web.visualizations
            .TemplateBundleAdapter(templates, templateImport)

    /**
     * The export/import acts of both families (rest-api §22/§23) — the import and the promotion receive bind
     * through BOTH readers, so the document bounds hold off the save path too (the L1c HIGH item). The
     * dashboard import runs in ONE transaction over the metadata manager (F1 of the L1c pass). The envelope
     * arrays' count ceiling is the operator's configured [VisualizationConfig] (C1 of the L1c-c round: the
     * constructor's default would silently stand in for the operator's value — the bean passes it explicitly,
     * and [co.datapipelines.web.config.ArtifactTransferConfigWiringTest] proves the override both ways
     * through this factory).
     */
    @Bean
    @Suppress("LongParameterList") // the aggregate's ports ARE the wiring
    fun artifactTransferService(
        visualizations: VisualizationService,
        dashboards: DashboardService,
        bundle: co.datapipelines.visualization.TemplateBundle,
        visualizationReader: VisualizationReader,
        dashboardReader: DashboardReader,
        transactionManager: PlatformTransactionManager,
        releaseRules: co.datapipelines.visualization.ArtifactImportReleaseRules,
        config: VisualizationConfig,
    ): co.datapipelines.visualization.ArtifactTransferService =
        co.datapipelines.visualization
            .ArtifactTransferService(
                visualizations,
                dashboards,
                bundle,
                visualizationReader,
                dashboardReader,
                transactions = TransactionTemplate(transactionManager) as TransactionOperations,
                releaseRules = releaseRules,
                config = config,
            )
}
