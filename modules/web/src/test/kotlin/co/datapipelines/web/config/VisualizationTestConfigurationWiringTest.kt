package co.datapipelines.web.config

import co.datapipelines.application.charts.JsRenderedChartEngine
import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.JavaScriptRuntime
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.visualization.ChartEngine
import co.datapipelines.visualization.EngineRenderedStateCheck
import co.datapipelines.visualization.ReleaseEvidence
import co.datapipelines.visualization.RenderedStateCheck
import co.datapipelines.visualization.TestFixtureEvaluator
import co.datapipelines.visualization.TestRunRepository
import co.datapipelines.visualization.VisualizationMechanicalCheck
import co.datapipelines.visualization.VisualizationReleaseEvidence
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.visualizations.WebVisualizationFixtureEvaluator
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The L4a wiring (#352): `VisualizationTestConfiguration` builds the runs repository, the fixture
 * evaluator, the chart engine and the rendered-state check over it (#986: engine-backed where the runtime is
 * available — this graph; the no-op where it is not — `VisualizationRenderedStateWiringTest`), the mechanical
 * check, the session service and — the production
 * release gate — the `ReleaseEvidence` bean the visualization release factory consumes. THE GUARD: the
 * factory's evidence argument is load-bearing; removing it from `VisualizationConfiguration` is a red
 * build here (reflection over the compiled factory method), and the wired evidence is the REAL gate.
 */
class VisualizationTestConfigurationWiringTest {
    private val context =
        ApplicationContextRunner()
            .withUserConfiguration(
                VisualizationTestConfiguration::class.java,
                VisualizationConfiguration::class.java,
                Collaborators::class.java,
            )

    @Test
    fun `the session graph builds - runs repository, evaluator, engine-backed rendered state, mechanical check, sessions, gate`() {
        context.run { ctx ->
            ctx.getBean(TestRunRepository::class.java).shouldNotBeNull()
            ctx.getBean(TestFixtureEvaluator::class.java).shouldBeInstanceOf<WebVisualizationFixtureEvaluator>()
            // The runtime answers Available (the collaborator below): the check is the engine's, over the wired engine.
            val engine = ctx.getBean(ChartEngine::class.java).shouldBeInstanceOf<JsRenderedChartEngine>()
            ctx.getBean(RenderedStateCheck::class.java).shouldBeInstanceOf<EngineRenderedStateCheck>().engine shouldBe engine
            ctx.getBean(VisualizationMechanicalCheck::class.java).shouldNotBeNull()
            ctx.getBean(VisualizationTestSessionService::class.java).shouldNotBeNull()
            ctx.getBean(ReleaseEvidence::class.java).shouldBeInstanceOf<VisualizationReleaseEvidence>()
        }
    }

    @Test
    fun `the production release factory consumes the evidence bean - the guard fails if the argument is removed`() {
        // Reflection over the COMPILED factory: the ReleaseEvidence parameter is part of the production
        // wiring, so an editor who drops the argument (and with it the gate) turns this test red.
        val factory =
            VisualizationConfiguration::class.java.declaredMethods.single { it.name == "visualizationService" }
        factory.parameters.any { it.type == ReleaseEvidence::class.java } shouldBe true
        // And the wired evidence IS the real gate, composed over the real runs repository and mechanics.
        context.run { ctx ->
            ctx.getBean(ReleaseEvidence::class.java).shouldBeInstanceOf<VisualizationReleaseEvidence>()
        }
    }

    /** The collaborators the two configurations resolve (the VisualizationConfigurationWiringTest twin). */
    @Configuration
    @Suppress("unused")
    class Collaborators {
        /** #517 — the workspace writer guard the services now require; the graph, not the lock, is under test here. */
        @Bean
        fun releaseMutationGuard(): co.datapipelines.pipeline.release.ReleaseMutationGuard =
            co.datapipelines.pipeline.release.ReleaseMutationGuard.NONE

        /** #873 §D — the transitive restore recheck the restores now take; the graph, not the walk, is under test here. */
        @Bean
        fun restoreDependencyGuard(): co.datapipelines.pipeline.release.RestoreDependencyGuard =
            co.datapipelines.pipeline.release.RestoreDependencyGuard.DIRECT_ONLY

        @Bean
        fun releaseBounds(): co.datapipelines.pipeline.release.ReleaseBounds =
            co.datapipelines.pipeline.release
                .ReleaseBounds()

        private fun dataSource(): javax.sql.DataSource = io.mockk.mockk(relaxed = true)

        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource())

        @Bean
        fun dashboardRuntimeConfig(): co.datapipelines.visualization.DashboardRuntimeConfig =
            co.datapipelines.visualization.DashboardRuntimeConfig()

        /** The template bundle's collaborators (L1c's `templateBundle` bean in VisualizationConfiguration). */
        @Bean
        fun templateRepository(): co.datapipelines.templates.TemplateRepository = io.mockk.mockk(relaxed = true)

        @Bean
        fun templateImportService(): co.datapipelines.web.templates.TemplateImportService = io.mockk.mockk(relaxed = true)

        @Bean
        fun templateDryRenderer(): co.datapipelines.pipeline.TemplateDryRenderer = io.mockk.mockk(relaxed = true)

        @Bean
        fun templateVersionStatuses(): co.datapipelines.pipeline.TemplateVersionStatuses =
            co.datapipelines.pipeline.TemplateVersionStatuses { _, _, _ -> null }

        @Bean
        fun templateReleaser(): co.datapipelines.pipeline.TemplateReleaser =
            co.datapipelines.pipeline.TemplateReleaser { _, _, _, _ -> error("not reached in a wiring test") }

        @Bean
        fun pipelineResolver(): co.datapipelines.pipeline.PipelineResolver = co.datapipelines.pipeline.PipelineResolver { _, _, _ -> null }

        @Bean
        fun readOnlyPipelineRule(
            resolver: co.datapipelines.pipeline.PipelineResolver,
        ): co.datapipelines.application.endpoints.ReadOnlyPipelineRule =
            co.datapipelines.application.endpoints
                .ReadOnlyPipelineRule(resolver, maxCompositionDepth = 5)

        @Bean
        fun parameterSetRepository(jdbc: NamedParameterJdbcTemplate): co.datapipelines.parameters.ParameterSetRepository =
            co.datapipelines.parameters.ParameterSetRepository(jdbc)

        @Bean
        fun authoringGuard(): co.datapipelines.pipeline.AuthoringGuard = co.datapipelines.pipeline.AuthoringGuard(true)

        @Bean
        fun transactionManager(): org.springframework.transaction.PlatformTransactionManager = io.mockk.mockk(relaxed = true)

        @Bean
        fun templateEvaluateService(): TemplateEvaluateService = io.mockk.mockk(relaxed = true)

        /**
         * #986 — the chart engine's collaborators. The runtime answers Available: this module's tests carry no isolate,
         * so the available graph's runtime is a stand-in whose only answer read at wiring is `availability()`.
         */
        @Bean
        fun javaScriptRuntime(): JavaScriptRuntime =
            io.mockk.mockk<JavaScriptRuntime>(relaxed = true).also {
                io.mockk.every { it.availability() } returns Availability.Available
            }

        @Bean
        fun scriptEvaluationPool(): ScriptEvaluationPool =
            ScriptEvaluationPool(1, 1, java.time.Duration.ofSeconds(1), ScriptEvaluationPool.SYSTEM)

        @Bean
        fun scriptProperties(): ScriptProperties = ScriptProperties()
    }
}
