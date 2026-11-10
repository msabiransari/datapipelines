package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #786 §E.3 (record §25.4 erratum 6; owner ruling 2026-11-08, 868 review R1-4 option 1): a pipeline-root release plan
 * whose CUMULATIVE metadata time passes `metadata-timeout-seconds` is refused `pipeline.release.metadata_unavailable` —
 * nothing prepared, flipped or audited, and no partial plan returned. The fake clock moves THREE seconds per provider
 * read, so every batch alone is well inside the five-second budget and only the sum of two batches is not.
 */
class ReleasePlanningDeadlineTest {
    private val fx = CoordinatorFixture()
    private val t = fx.template("test/t.sql")
    private val c = fx.pipeline("test/c", listOf(t))
    private val a = fx.pipeline("test/a", listOf(c))

    /** Time is the number of provider reads so far, times three seconds. */
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = Instant.EPOCH.plusSeconds(SECONDS_PER_READ * fx.reads.size)
        }

    @Test
    fun `the deadline expires across two provider batches - refused metadata_unavailable, no mutation, no partial plan`() {
        val preview = fx.preview(a)
        fx.reads.clear()
        fx.log.clear()

        val error =
            shouldThrow<DatapipelinesException> {
                fx
                    .coordinator(deadline = ReleasePlanningDeadline.after(Duration.ofSeconds(5), clock))
                    .release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t, c))))
            }

        error.code shouldBe "pipeline.release.metadata_unavailable"
        error.details shouldBe emptyMap()
        error.message.orEmpty() shouldNotContain "test/"
        // Batch one (the root) at 0 s, batch two (its pipeline pin) at 3 s; the third batch would start at 6 s > 5 s.
        fx.reads shouldContainExactly listOf(a, c)
        fx.reads shouldNotContain t
        // No mutation: nothing prepared, no transaction, no flip, no audit.
        fx.writes() shouldBe emptyList()
        fx.audited shouldBe emptyList()
    }

    @Test
    fun `control - inside the budget the same release succeeds, and each walk opens its own deadline`() {
        val preview = fx.preview(a)
        fx.reads.clear()

        fx
            .coordinator(deadline = ReleasePlanningDeadline.after(Duration.ofSeconds(10), clock))
            .release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t, c))))

        // Two walks of three reads each (the plan and the guarded re-walk): 18 s in all, more than ONE 10 s budget — so
        // the success proves the deadline is per walk, not per release.
        fx.reads.size shouldBe 6
        fx.writes() shouldContainExactly
            listOf(
                "prepare:test/c",
                "prepare:test/a",
                "begin",
                "guard:pipeline",
                "flip:test/t.sql@hash-test/t.sql",
                "flip:test/c@hash-test/c",
                "flip:test/a@hash-test/a",
                "audit:template.version.released:test/t.sql",
                "audit:pipeline.version.released:test/c",
                "audit:pipeline.version.released:test/a",
                "commit",
            )
    }

    private companion object {
        const val SECONDS_PER_READ = 3L
    }
}
