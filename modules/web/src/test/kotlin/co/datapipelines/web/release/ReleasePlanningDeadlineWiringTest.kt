package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * The production wiring of the release planning deadline (#868 G1 section D.3, record §25.4): ONE
 * [ReleasePlanningDeadline] bean. G1 pinned it to the unbounded `Deadline.none()`; #786 §E.3 (owner ruling 2026-11-08,
 * 868 review R1-4 option 1) binds it to `metadata-timeout-seconds`, and every plan opens a FRESH deadline.
 */
class ReleasePlanningDeadlineWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            ReleaseConfiguration::class.java,
            ReleaseWiringCollaborators::class.java,
        )

    @Test
    fun `the release planning deadline is one bean and it is finite, the documented 30 seconds by default`() {
        context.run { context ->
            context.getBeansOfType(ReleasePlanningDeadline::class.java).size shouldBe 1
            val deadline = context.getBean(ReleasePlanningDeadline::class.java).open()
            deadline.unbounded shouldBe false
            deadline.expired() shouldBe false
            checkNotNull(deadline.remaining()) shouldBeGreaterThan Duration.ofSeconds(29)
            checkNotNull(deadline.remaining()) shouldBeLessThanOrEqualTo Duration.ofSeconds(30)
        }
    }

    @Test
    fun `the deadline follows metadata-timeout-seconds and each plan opens its own`() {
        context.withPropertyValues("datapipelines.release.dependencies.metadata-timeout-seconds=7").run { context ->
            val supplier = context.getBean(ReleasePlanningDeadline::class.java)
            val first = supplier.open()
            checkNotNull(first.remaining()) shouldBeGreaterThan Duration.ofSeconds(6)
            checkNotNull(first.remaining()) shouldBeLessThanOrEqualTo Duration.ofSeconds(7)
            supplier.open() shouldNotBeSameInstanceAs first
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
