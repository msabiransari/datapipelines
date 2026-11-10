package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The production wiring of the release planning deadline (#868 G1 section D.3, record §25.4): ONE
 * [ReleasePlanningDeadline] bean, and in G1 it answers the unbounded `Deadline.none()` — today's plan path has no
 * timeout and G1 adds no refusal code. #786 replaces the bean with deadlines bound to `metadata-timeout-seconds` and
 * flips this test (owner ruling 2026-11-08, 868 review R1-4 option 1).
 */
class ReleasePlanningDeadlineWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            ReleaseConfiguration::class.java,
            ReleaseWiringCollaborators::class.java,
        )

    @Test
    fun `the release planning deadline is one bean and it is unbounded until 786`() {
        context.run { context ->
            context.getBeansOfType(ReleasePlanningDeadline::class.java).size shouldBe 1
            val deadline = context.getBean(ReleasePlanningDeadline::class.java).open()
            deadline.unbounded shouldBe true
            deadline.remaining() shouldBe null
            deadline.expired() shouldBe false
        }
    }

    @Test
    fun `the context assembles the registry from the pipeline and template descriptors and builds the coordinator`() {
        context.run { context ->
            context.getBean(FamilyRegistry::class.java).keys shouldBe setOf(FamilyKey.PIPELINE, FamilyKey.TEMPLATE)
            context.getBean(PipelineReleaseCoordination::class.java).shouldBeInstanceOf<ReleaseCoordinator>()
        }
    }
}
