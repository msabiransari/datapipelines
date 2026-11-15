package co.datapipelines.web.config

import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.JavaScriptRuntime
import co.datapipelines.visualization.ChartEngine
import co.datapipelines.visualization.RenderedStateCheck
import co.datapipelines.visualization.VisualizationMechanicalCheck
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The runtime-unavailable graph (brief 986 D4, A.2, G9): the same two configurations over a REAL [JavaScriptRuntime]
 * on this module's test classpath, which carries no isolate — the host where JavaScript does not run. The runtime's
 * own answer is Unavailable, so the rendered-state check is the no-op: a chart case records `not_available` and its
 * chart assertions are unjudged. The engine bean is still built (nothing calls it); the available graph is
 * [VisualizationTestConfigurationWiringTest]'s.
 */
class VisualizationRenderedStateWiringTest {
    @Test
    fun `without the isolate the runtime answers Unavailable and the rendered-state check is the no-op`() {
        ApplicationContextRunner()
            .withAllowBeanDefinitionOverriding(true)
            .withUserConfiguration(
                VisualizationTestConfiguration::class.java,
                VisualizationConfiguration::class.java,
                VisualizationTestConfigurationWiringTest.Collaborators::class.java,
                RealRuntime::class.java,
            ).run { ctx ->
                ctx.getBean(JavaScriptRuntime::class.java).availability().shouldBeInstanceOf<Availability.Unavailable>()
                ctx.getBean(ChartEngine::class.java).shouldNotBeNull()
                ctx.getBean(RenderedStateCheck::class.java) shouldBe RenderedStateCheck.NOT_AVAILABLE
                ctx.getBean(VisualizationMechanicalCheck::class.java).shouldNotBeNull()
            }
    }

    /** Replaces the collaborators' stand-in with the product's own runtime, built as `ScriptConfiguration` builds it. */
    @Configuration
    @Suppress("unused")
    class RealRuntime {
        @Bean(destroyMethod = "close")
        fun javaScriptRuntime(): JavaScriptRuntime = ScriptConfiguration().javaScriptRuntime(ScriptProperties())
    }
}
