package co.datapipelines.web.config

import co.datapipelines.application.charts.JsRenderedChartEngine
import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JavaScriptEngine
import co.datapipelines.scripting.JavaScriptRuntime
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.visualization.ChartEngine
import co.datapipelines.visualization.EngineRenderedStateCheck
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
import java.time.Duration

/**
 * The test-session and evidence wiring (#352, the implementation spec's §11) — the L4a beans the
 * visualization lifecycle consumes, in ONE file beside [VisualizationConfiguration] (which stays the
 * family's own; only its release factory gains the evidence argument).
 *
 * - the runs repository — V42's evidence tables plus V44's capability columns;
 * - the fixture evaluator — [WebVisualizationFixtureEvaluator], the mechanical test's step 3 over the
 *   real bounded evaluator ([TemplateEvaluateService]), admitting DRAFT pins as authoring does;
 * - the chart engine — [JsRenderedChartEngine], the js-rendered engine in the server's sandbox over the script
 *   pool and the per-evaluation limits (js-rendered record §5.3); no route, no tool reaches it (O-11);
 * - the rendered-state check — the engine's canonical render where the JavaScript runtime is available (the
 *   runtime's own answer, record §6), the no-op recording `not_available` where it is not;
 * - the mechanical check — §11.3's steps 1–5 over the renderer validator the save-time validator also reads
 *   (the house `table`/`kpi` schemas; a `chart` document judged whole by the shared `ChartDocumentRules`),
 *   the template facts and the rendered-state check (a chart's assertions judged by §4.12's table);
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
    fun visualizationTestRunRepository(
        jdbc: NamedParameterJdbcTemplate,
        // #517 — session start and evidence completion acquire the workspace writer guard (release-dependencies §4.3).
        guard: co.datapipelines.pipeline.release.ReleaseMutationGuard,
    ): TestRunRepository = TestRunRepository(jdbc, guard)

    @Bean
    fun visualizationFixtureEvaluator(
        evaluate: TemplateEvaluateService,
        contracts: TemplateDryRenderer,
    ): TestFixtureEvaluator = WebVisualizationFixtureEvaluator(evaluate, contracts)

    /**
     * The chart engine (js-rendered record §5.3; brief 986 D2): built on every host — a call needs the runtime, so
     * only [visualizationRenderedStateCheck] decides whether anything calls it. A save never does (O-12).
     */
    @Bean
    fun chartEngine(
        runtime: JavaScriptRuntime,
        pool: ScriptEvaluationPool,
        properties: ScriptProperties,
    ): ChartEngine =
        JsRenderedChartEngine(
            JavaScriptEngine(runtime),
            pool,
            // One evaluation's wall clock and depth, as every product evaluation; the heap, statement and byte bounds
            // are the runtime's own (`datapipelines.script.js`), which a per-call limit may only lower.
            EvaluationLimits(Duration.ofSeconds(properties.evaluateTimeoutSeconds), properties.maxDepth),
        )

    /**
     * §11.3 step 5 (record §4.12, §6): the engine-backed check where the runtime is available, read once from the
     * runtime's own answer as the script engines are; the no-op where it is not — a chart case then records
     * `not_available` and its chart assertions are unjudged, never failures.
     */
    @Bean
    fun visualizationRenderedStateCheck(
        runtime: JavaScriptRuntime,
        charts: ChartEngine,
    ): RenderedStateCheck =
        if (runtime.availability() is Availability.Available) EngineRenderedStateCheck(charts) else RenderedStateCheck.NOT_AVAILABLE

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
