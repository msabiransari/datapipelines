package co.datapipelines.web.config

import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.JavaScriptRuntime
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactValidation
import co.datapipelines.visualization.ChartEngine
import co.datapipelines.visualization.RenderedStateCheck
import co.datapipelines.visualization.VisualizationMechanicalCheck
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationValidator
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * The runtime-unavailable graph (brief 986 D4, A.2, G9): the same two configurations over a REAL [JavaScriptRuntime]
 * on this module's test classpath, which carries no isolate — the host where JavaScript does not run. The runtime's
 * own answer is Unavailable, so the rendered-state check is the no-op: a chart case records `not_available` and its
 * chart assertions are unjudged. The engine bean is still built (nothing calls it); the available graph is
 * [VisualizationTestConfigurationWiringTest]'s.
 */
class VisualizationRenderedStateWiringTest {
    private val context =
        ApplicationContextRunner()
            .withAllowBeanDefinitionOverriding(true)
            .withUserConfiguration(
                VisualizationTestConfiguration::class.java,
                VisualizationConfiguration::class.java,
                VisualizationTestConfigurationWiringTest.Collaborators::class.java,
                RealRuntime::class.java,
            )

    @Test
    fun `without the isolate the runtime answers Unavailable and the rendered-state check is the no-op`() {
        context.run { ctx ->
            ctx.getBean(JavaScriptRuntime::class.java).availability().shouldBeInstanceOf<Availability.Unavailable>()
            ctx.getBean(ChartEngine::class.java).shouldNotBeNull()
            ctx.getBean(RenderedStateCheck::class.java) shouldBe RenderedStateCheck.NOT_AVAILABLE
            ctx.getBean(VisualizationMechanicalCheck::class.java).shouldNotBeNull()
        }
    }

    @Test
    fun `a chart saves where JavaScript does not run - the save path never calls the engine (O-12, G8)`() {
        context.run { ctx ->
            ctx.getBean(JavaScriptRuntime::class.java).availability().shouldBeInstanceOf<Availability.Unavailable>()
            val document = ctx.getBean(VisualizationReader::class.java).readOrThrow(ArtifactJson.mapper.readTree(CHART))
            val valid =
                ctx
                    .getBean(VisualizationValidator::class.java)
                    .validate(UUID.randomUUID(), document)
                    .shouldBeInstanceOf<ArtifactValidation.Valid<*>>()
            // The save derived the bindings, as on any host: the document is accepted whole, not skipped.
            valid.document.toString().contains("amount") shouldBe true
        }
    }

    private companion object {
        /** A direct-input bar chart: no transform, so no template is consulted; bindings absent, derived at save. */
        val CHART =
            """
            {"name": "acme/charts/no_runtime", "display_name": "Sales", "renderer": {"kind": "chart", "version": "1"},
             "inputs": {"sales": {"columns": [{"name": "month", "type": "STRING", "nullable": false},
                                              {"name": "amount", "type": "DECIMAL", "nullable": true}]}},
             "config": {"mark": {"type": "bar"}, "encoding": {"x": {"field": "month", "type": "nominal", "sort": null},
               "y": {"field": "amount", "type": "quantitative", "aggregate": "sum"}}}}
            """.trimIndent()
    }

    /** Replaces the collaborators' stand-in with the product's own runtime, built as `ScriptConfiguration` builds it. */
    @Configuration
    @Suppress("unused")
    class RealRuntime {
        @Bean(destroyMethod = "close")
        fun javaScriptRuntime(): JavaScriptRuntime = ScriptConfiguration().javaScriptRuntime(ScriptProperties())
    }
}
