package co.datapipelines.web.config

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.visualization.ReleaseEvidence
import co.datapipelines.visualization.RenderedStateCheck
import co.datapipelines.visualization.RendererConfigValidator
import co.datapipelines.visualization.RendererConfigValidators
import co.datapipelines.visualization.TemplateContractFacts
import co.datapipelines.visualization.TestFixtureEvaluator
import co.datapipelines.visualization.TestRunRepository
import co.datapipelines.visualization.VisualizationConfig
import co.datapipelines.visualization.VisualizationMechanicalCheck
import co.datapipelines.visualization.VisualizationProperties
import co.datapipelines.visualization.VisualizationReleaseEvidence
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.visualizations.WebVisualizationFixtureEvaluator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

/**
 * The test-session and evidence wiring (#352, the implementation spec's §11) — the L4a beans the
 * visualization lifecycle consumes, in ONE file beside [VisualizationConfiguration] (which stays the
 * family's own; only its release factory gains the evidence argument).
 *
 * - the runs repository — V42's evidence tables plus V44's capability columns;
 * - the fixture evaluator — [WebVisualizationFixtureEvaluator], the mechanical test's step 3 over the
 *   real bounded evaluator ([TemplateEvaluateService]), admitting DRAFT pins as authoring does;
 * - the rendered-state check — the spec's §11.3 step 5 no-op, recording `not_available` (L6 renders);
 * - the mechanical check — §11.3's steps 1–4 over the deep renderer validator (the reduced Plotly
 *   schema the save-time validator also reads) and the template facts;
 * - the release evidence — [VisualizationReleaseEvidence], the production `ReleaseEvidence`: the exact
 *   draft locked, the latest run judged, the mechanics re-run at release;
 * - the session service — start/submit/screenshot/expiry, TTL-bound by `VisualizationConfig`.
 *
 * #353 binds these to HTTP and MCP surfaces; nothing here opens a route.
 */
@Configuration
@EnableConfigurationProperties(VisualizationProperties::class)
class VisualizationTestConfiguration {
    @Bean
    fun visualizationTestRunRepository(jdbc: NamedParameterJdbcTemplate): TestRunRepository = TestRunRepository(jdbc)

    @Bean
    fun visualizationFixtureEvaluator(
        evaluate: TemplateEvaluateService,
        contracts: TemplateDryRenderer,
    ): TestFixtureEvaluator = WebVisualizationFixtureEvaluator(evaluate, contracts)

    /** The spec's §15 guard: the no-op is the production state until a headless render check lands. */
    @Bean
    fun visualizationRenderedStateCheck(): RenderedStateCheck = RenderedStateCheck.NOT_AVAILABLE

    /**
     * The mechanical check's renderer validator — the SAME deep composition the save-time validator bean
     * reads (declared in [VisualizationConfiguration]), so save, submit and release refuse the same
     * configurations with the same paths.
     */
    @Bean
    fun visualizationMechanicalCheck(
        renderers: RendererConfigValidator,
        fixtures: TestFixtureEvaluator,
        renderer: TemplateDryRenderer,
        statuses: TemplateVersionStatuses,
        rendered: RenderedStateCheck,
    ): VisualizationMechanicalCheck =
        VisualizationMechanicalCheck(renderers, fixtures, TemplateContractFacts.over(renderer, statuses), rendered)

    @Bean
    @Suppress("LongParameterList") // the session service's ports ARE the wiring
    fun visualizationTestSessionService(
        runs: TestRunRepository,
        visualizations: VisualizationRepository,
        mechanical: VisualizationMechanicalCheck,
        config: VisualizationConfig,
        transactionManager: PlatformTransactionManager,
    ): VisualizationTestSessionService =
        VisualizationTestSessionService(
            runs,
            visualizations,
            mechanical,
            config,
            transactions = TransactionTemplate(transactionManager) as TransactionOperations,
        )

    /** The production release gate — [VisualizationConfiguration.visualizationService] consumes this bean. */
    @Bean
    fun visualizationReleaseEvidence(
        runs: TestRunRepository,
        mechanical: VisualizationMechanicalCheck,
    ): ReleaseEvidence = VisualizationReleaseEvidence(runs, mechanical)
}
