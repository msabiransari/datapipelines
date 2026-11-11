package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations
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
 *
 * #786 R2 (landing review r3): the walker checks the deadline BEFORE each read, so a read that runs PAST it — the final
 * needed batch, a batch answering Missing — was accepted. The walk now refuses when its deadline has expired at its end,
 * on the plan path and on the guarded re-walk alike (the API restore recheck walks through the latter).
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

    @Test
    fun `the deadline expires DURING the final provider batch - refused metadata_unavailable, no mutation`() {
        val root = fx.pipeline("test/one")

        val error =
            shouldThrow<DatapipelinesException> {
                fx
                    .coordinator(deadline = ReleasePlanningDeadline.after(Duration.ofSeconds(1), clock))
                    .release(fx.request(root, ReleasePlanRequest(fx.preview(root).treeHash, emptyList())))
            }

        error.code shouldBe "pipeline.release.metadata_unavailable"
        error.details shouldBe emptyMap()
        // The preview's own (unbounded) walk reads once, so the plan opens its 1 s budget at 3 s: its only read starts
        // at 3 s, inside the budget, and ends at 6 s, past it — the final batch is the one that ran out of time.
        fx.writes() shouldBe emptyList()
        fx.audited shouldBe emptyList()
    }

    @Test
    fun `a one-node plan whose only read ends past the deadline is refused, and inside the budget returns its node`() {
        val root = fx.pipeline("test/one")

        val refused = shouldThrow<DatapipelinesException> { walk(Duration.ofSeconds(1)).plan(root, fx.actor) }
        refused.code shouldBe "pipeline.release.metadata_unavailable"
        fx.reads shouldContainExactly listOf(root)

        fx.reads.clear()
        val closure = checkNotNull(walk(Duration.ofSeconds(4)).plan(root, fx.actor))
        closure.nodes.map { it.identity } shouldContainExactly listOf(root)
        fx.reads shouldContainExactly listOf(root)
    }

    @Test
    fun `a final batch answering Missing past the deadline is refused - never a closure with a MISSING node`() {
        val gone = fx.absent("test/gone.sql")
        val root = fx.pipeline("test/m", listOf(gone))
        // Root read at 0 s, the missing pin's read starts at 3 s (inside 4 s) and ends at 6 s (past it).
        val refused = shouldThrow<DatapipelinesException> { walk(Duration.ofSeconds(4)).plan(root, fx.actor) }
        refused.code shouldBe "pipeline.release.metadata_unavailable"
        fx.reads shouldContainExactly listOf(root, gone)

        fx.reads.clear()
        val closure = checkNotNull(walk(Duration.ofSeconds(7)).plan(root, fx.actor))
        closure.nodes.map { it.status } shouldContainExactly listOf(ReleaseNodeStatus.DRAFT, ReleaseNodeStatus.MISSING)
    }

    @Test
    fun `the guarded re-walk refuses the same final-batch expiry - the restore recheck's entry point`() {
        val root = fx.pipeline("test/one")

        val refused = shouldThrow<DatapipelinesException> { walk(Duration.ofSeconds(1)).walkInCurrentTransaction(root, fx.actor) }

        refused.code shouldBe "pipeline.release.metadata_unavailable"
        refused.details shouldBe emptyMap()
        checkNotNull(walk(Duration.ofSeconds(4)).walkInCurrentTransaction(root, fx.actor)).nodes.size shouldBe 1
    }

    /** The production walk over the fixture's sources, its deadline [budget] on the read-counting clock. */
    private fun walk(budget: Duration) =
        ReleasePlanningWalk(
            ReleaseDependencyFamilies.registry(fx.sources),
            ReleaseBounds(),
            TransactionOperations.withoutTransaction(),
            ReadIsolation.NONE,
            ReleasePlanningDeadline.after(budget, clock),
        )

    private companion object {
        const val SECONDS_PER_READ = 3L
    }
}
