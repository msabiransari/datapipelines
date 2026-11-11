package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * §25.4 batching and the cumulative deadline: ⌈n / resolveBatchSize⌉ `resolveMany` calls per provider per frontier,
 * never one per node, each frontier clamped by the remaining node budget; no batch starts after the deadline.
 */
class BatchingAndDeadlineTest {
    @Test
    fun `a frontier of n requests costs ceil(n over 256) provider calls - 256 is 1, 257 is 2, 300 is 2`() {
        mapOf(256 to listOf(256), 257 to listOf(256, 1), 300 to listOf(256, 44)).forEach { (n, sizes) ->
            val (g, root) = fan(n)
            g.walk(root).nodes.size shouldBe n + 1
            g.family(TEMPLATE).calls.map { it.size } shouldContainExactly sizes
            g.family(TEST_WIDGET).calls.size shouldBe 1 // the root's own batch
        }
    }

    @Test
    fun `a frontier is clamped by the remaining node budget`() {
        val (g, root) = fan(300)
        val snapshot = g.walk(root, limits = TestGraph.LIMITS.copy(nodes = 10))
        // Nine slots remain after the root: one call of nine, then the tenth child alone, refused at the nodes bound.
        g.family(TEMPLATE).calls.map { it.size } shouldContainExactly listOf(9, 1)
        snapshot.limitStop() shouldBe (LimitKind.NODES to 10)
    }

    @Test
    fun `a smaller configured batch size is honoured`() {
        val (g, root) = fan(10)
        g.walk(root, limits = TestGraph.LIMITS.copy(resolveBatchSize = 4))
        g.family(TEMPLATE).calls.map { it.size } shouldContainExactly listOf(4, 4, 2)
    }

    @Test
    fun `no batch starts after the deadline expired - the walk refuses with DeadlineExceeded, never a partial graph`() {
        val clock = SettableClock(Instant.parse("2026-11-10T00:00:00Z"))
        val deadline = Deadline.after(Duration.ofSeconds(30), clock)
        val (g, root) = fan(3)
        g.family(TEST_WIDGET).beforeBatch = { clock.advance(Duration.ofSeconds(31)) } // the root's read spends the budget
        val refused = shouldThrow<DeadlineExceeded> { g.walker().walk(listOf(root), context(deadline)) }
        refused.family shouldBe TEMPLATE
        g.family(TEMPLATE).calls.size shouldBe 0
    }

    @Test
    fun `an already expired deadline refuses before the first batch, an open one never interferes`() {
        val clock = SettableClock(Instant.parse("2026-11-10T00:00:00Z"))
        val (g, root) = fan(3)
        shouldThrow<DeadlineExceeded> { g.walker().walk(listOf(root), context(Deadline.after(Duration.ZERO, clock))) }
        g.family(TEST_WIDGET).calls.size shouldBe 0
        g.walker().walk(listOf(root), context(Deadline.after(Duration.ofSeconds(30), clock))).complete shouldBe true
    }

    /** A synthetic root fanning out to [n] templates — the root's family differs, so the template calls are the frontier's. */
    private fun fan(n: Int): Pair<TestGraph, ObjectRequest> {
        val g = TestGraph()
        val pins = (1..n).map { pin(TEST_WIDGET, g.node(exact(TEMPLATE, "t$it", 1), Lifecycle.RELEASED), "parts[$it]") }
        return g to g.node(exact(TEST_WIDGET, "w", 1), Lifecycle.DRAFT, *pins.toTypedArray())
    }
}

/** A clock a test moves by hand. */
internal class SettableClock(
    private var now: Instant,
) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
