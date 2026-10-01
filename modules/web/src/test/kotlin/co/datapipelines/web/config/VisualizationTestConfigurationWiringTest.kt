package co.datapipelines.web.config

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.visualization.PipelineReleaseFacts
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
 * evaluator, the rendered-state no-op, the mechanical check, the session service and — the production
 * release gate — the `ReleaseEvidence` bean the visualization release factory consumes. THE GUARD: the
 * factory's evidence argument is load-bearing; removing it from `VisualizationConfiguration` is a red
 * build here (reflection over the compiled factory method), and the wired evidence is the REAL gate.
 */
class VisualizationTestConfigurationWiringTest {
    private val context =
        ApplicationContextRunner()
            .withUserConfiguration(VisualizationTestConfiguration::class.java, VisualizationConfiguration::class.java, Collaborators::class.java)

    @Test
    fun `the session graph builds - runs repository, evaluator, no-op rendered state, mechanical check, sessions, gate`() {
        context.run { it ->
            it.getBean(TestRunRepository::class.java).shouldNotBeNull()
            it.getBean(TestFixtureEvaluator::class.java).shouldBeInstanceOf<WebVisualizationFixtureEvaluator>()
            it.getBean(RenderedStateCheck::class.java) shouldBe RenderedStateCheck.NOT_AVAILABLE
            it.getBean(VisualizationMechanicalCheck::class.java).shouldNotBeNull()
            it.getBean(VisualizationTestSessionService::class.java).shouldNotBeNull()
            it.getBean(ReleaseEvidence::class.java).shouldBeInstanceOf<VisualizationReleaseEvidence>()
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
        context.run { it ->
            it.getBean(ReleaseEvidence::class.java).shouldBeInstanceOf<VisualizationReleaseEvidence>()
        }
    }

    /** The collaborators the two configurations resolve (the VisualizationConfigurationWiringTest twin). */
    @Configuration
    @Suppress("unused")
    class Collaborators {
        private fun dataSource(): javax.sql.DataSource = io.mockk.mockk(relaxed = true)

        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource())

        @Bean
        fun dashboardRuntimeConfig(): co.datapipelines.visualization.DashboardRuntimeConfig =
            co.datapipelines.visualization.DashboardRuntimeConfig()

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
    }
}
