package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The DI-assembled registry refuses to start (#868 G1, record §3.2): a second descriptor for a registered key, and a
 * descriptor for a key outside `FamilyKey.PRODUCTION` (the synthetic `test_widget` family), each fail the context at
 * startup with the registry's own message. `RegistryAndSyntheticFamilyTest` pins the same two refusals on the constructor.
 */
class DependencyFamilyRegistryWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            ReleaseConfiguration::class.java,
            ReleaseWiringCollaborators::class.java,
        )

    @Test
    fun `a duplicate family descriptor bean refuses the context`() {
        context.withUserConfiguration(DuplicatePipeline::class.java).run { context ->
            startupMessages(context.startupFailure) shouldContain "duplicate family registration: pipeline"
        }
    }

    @Test
    fun `a non-production family descriptor bean refuses the context`() {
        context.withUserConfiguration(SyntheticFamily::class.java).run { context ->
            startupMessages(context.startupFailure) shouldContain "is not a production family"
        }
    }

    private fun startupMessages(failure: Throwable?): String =
        generateSequence(failure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Configuration
    @Suppress("unused") // read by the context runner
    class DuplicatePipeline {
        @Bean
        fun secondPipelineFamily(): FamilyDescriptor<*> =
            ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, DependencySource { null }, StatementCeiling.NONE)
    }

    @Configuration
    @Suppress("unused") // read by the context runner
    class SyntheticFamily {
        @Bean
        fun widgetFamily(): FamilyDescriptor<*> {
            val pipeline = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, DependencySource { null }, StatementCeiling.NONE)
            return FamilyDescriptor(
                key = FamilyKey("test_widget"),
                rootSelectors = pipeline.rootSelectors,
                capabilities = pipeline.capabilities,
                extractorSchemaVersion = pipeline.extractorSchemaVersion,
                provider = pipeline.provider,
                safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
            )
        }
    }
}
