package co.datapipelines.web.config

import co.datapipelines.application.visualization.PipelineReleaseFactsReader
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.ReadOutcome
import co.datapipelines.visualization.VisualizationConfig
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationKey
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The visualization module's surface graph (#10 L1b): `VisualizationConfiguration` builds both services, both
 * validators and both readers from the collaborators the application already declares; the
 * `datapipelines.visualization.*` block BINDS (an override reaches the readers the surfaces read bodies with); an
 * out-of-window value refuses the context naming its key; and the pipeline port is `application`'s reader.
 */
class VisualizationConfigurationWiringTest {
    private val context =
        ApplicationContextRunner()
            .withUserConfiguration(VisualizationConfiguration::class.java, Collaborators::class.java)

    @Test
    fun `the whole graph builds - both services, both readers, the production pipeline port`() {
        context.run { context ->
            context.getBean(VisualizationService::class.java) shouldNotBe null
            context.getBean(DashboardService::class.java) shouldNotBe null
            context.getBean(VisualizationReader::class.java) shouldNotBe null
            context.getBean(DashboardReader::class.java) shouldNotBe null
            context.getBean(PipelineReleaseFacts::class.java).shouldBeInstanceOf<PipelineReleaseFactsReader>()
            context.getBean(VisualizationConfig::class.java) shouldBe VisualizationConfig()
        }
    }

    @Test
    fun `a property override binds through VisualizationProperties into the readers the surfaces use`() {
        context.withPropertyValues("${VisualizationKey.MAX_BINDINGS_PER_VISUALIZATION.path}=1").run { context ->
            context.getBean(VisualizationConfig::class.java).maxBindingsPerVisualization shouldBe 1
            // The BEAN reader is bounded by the bound value: two bindings are refused before they are walked.
            tooMany(context.getBean(VisualizationReader::class.java).read(document(bindings = 2))) shouldBe listOf("bindings")
        }
        // The control: the default window (64) admits the same two bindings — the refusal above is the override's.
        context.run { context ->
            tooMany(context.getBean(VisualizationReader::class.java).read(document(bindings = 2))) shouldBe emptyList()
        }
    }

    /** The paths refused `too_many` — the bound checks, whatever else the minimal document lacks. */
    private fun tooMany(outcome: ReadOutcome<*>): List<String> =
        when (outcome) {
            is ReadOutcome.Read -> {
                emptyList()
            }

            is ReadOutcome.Refused -> {
                outcome.result.failures
                    .filter { it.code == VisualizationErrorCodes.BODY_INVALID && it.details["reason"] == "too_many" }
                    .map { it.path }
            }
        }

    @Test
    fun `a value outside its window refuses the context, naming the key`() {
        context.withPropertyValues("${VisualizationKey.MAX_CASES_PER_VISUALIZATION.path}=0").run { context ->
            val failure = checkNotNull(context.startupFailure)
            generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
                VisualizationKey.MAX_CASES_PER_VISUALIZATION.path
        }
    }

    /** A minimal visualization document with [bindings] binding entries — enough for the reader's bound check. */
    private fun document(bindings: Int) =
        ObjectMapper().readTree(
            """
            {"name":"acme/visualizations/revenue","display_name":"Revenue",
             "renderer":{"kind":"plotly","version":"4"},
             "inputs":{"revenue":{"columns":[{"name":"amount","type":"DECIMAL","nullable":false}]}},
             "config":{"data":[{"type":"bar"}]},
             "bindings":{${(0 until bindings).joinToString(",") { "\"data[0].y$it\":\"amount\"" }}}}
            """.trimIndent(),
        )

    /** The collaborators the graph needs, as beans a test context can resolve (the ParametersConfiguration twin). */
    @Configuration
    @Suppress("unused")
    class Collaborators {
        private fun dataSource(): javax.sql.DataSource = io.mockk.mockk(relaxed = true)

        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource())

        /** The runtime's numbers (`DashboardRuntimeConfiguration`'s in the application) — the validator reads two of them. */
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
    }
}
