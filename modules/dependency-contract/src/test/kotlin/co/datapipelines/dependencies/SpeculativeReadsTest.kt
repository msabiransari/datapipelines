package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * #868 R1 at the walker: a read the walk did not need yet never decides its outcome. The walk's result is the one a
 * walk reading one needed request at a time gives — every check runs before the read it guards, a failing speculative
 * call is forgotten, and a failure on a request the walk does reach propagates there.
 */
class SpeculativeReadsTest {
    private val root = exact(TEST_WIDGET, "w", 1)
    private val a = exact(TEMPLATE, "a", 1)
    private val b = exact(TEMPLATE, "b", 1)

    @Test
    fun `a failing call that also held the needed request is re-issued for it alone, and a stop reached first wins`() {
        val g = TestGraph()
        g.node(a, Lifecycle.RELEASED)
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, b, "parts[1]"))
        g.family(TEMPLATE).failing = setOf(b)

        val snapshot = g.walk(root, limits = TestGraph.LIMITS.copy(edges = 1))

        snapshot.limitStop() shouldBe (LimitKind.EDGES to 1)
        g.family(TEMPLATE).calls shouldContainExactly listOf(listOf(a, b), listOf(a))
    }

    @Test
    fun `a failure on a request the walk reaches propagates there - read alone, at its point in the walk`() {
        val g = TestGraph()
        g.node(a, Lifecycle.RELEASED)
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, b, "parts[1]"))
        g.family(TEMPLATE).failing = setOf(b)

        shouldThrow<IllegalStateException> { g.walk(root) }.message shouldBe "metadata source unavailable: b"
        g.family(TEMPLATE).calls shouldContainExactly listOf(listOf(a, b), listOf(a), listOf(b))
    }

    @Test
    fun `a speculative-only call of another family that fails is forgotten`() {
        val g = TestGraph()
        val p = exact(PIPELINE, "p", 1)
        g.node(a, Lifecycle.RELEASED)
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, p, "parts[1]"))
        g.family(PIPELINE).failing = setOf(p)

        g.walk(root, limits = TestGraph.LIMITS.copy(edges = 1)).limitStop() shouldBe (LimitKind.EDGES to 1)
        g.family(TEMPLATE).calls shouldContainExactly listOf(listOf(a))
        g.family(PIPELINE).calls shouldContainExactly listOf(listOf(p))
    }

    @Test
    fun `the requests of a failed speculative call are never speculated on again - any of them may be the cause`() {
        val g = TestGraph()
        val c = exact(TEMPLATE, "c", 1)
        val d = exact(TEMPLATE, "d", 1)
        g.node(d, Lifecycle.RELEASED)
        g.node(c, Lifecycle.RELEASED)
        g.node(a, Lifecycle.DRAFT, pin(TEMPLATE, d, "imports[0]"))
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, b, "parts[1]"), pin(TEST_WIDGET, c, "parts[2]"))
        g.family(TEMPLATE).failing = setOf(b)

        // root→a, a→d, then root→b is the third edge: the walk stops before it needs b.
        g.walk(root, limits = TestGraph.LIMITS.copy(edges = 2)).limitStop() shouldBe (LimitKind.EDGES to 2)
        g.family(TEMPLATE).calls shouldContainExactly listOf(listOf(a, b, c), listOf(a), listOf(d))
    }

    @Test
    fun `an expired deadline skips speculation without refusing - only a needed read refuses`() {
        val clock = SettableClock(Instant.parse("2026-11-10T00:00:00Z"))
        val g = TestGraph()
        val p = exact(PIPELINE, "p", 1)
        g.node(p, Lifecycle.RELEASED)
        g.node(a, Lifecycle.RELEASED)
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, p, "parts[1]"))
        g.family(TEMPLATE).beforeBatch = { clock.advance(Duration.ofSeconds(31)) } // a's read spends the budget

        val budget = Duration.ofSeconds(30)
        val bounded = g.walker(TestGraph.LIMITS.copy(edges = 1)).walk(listOf(root), context(Deadline.after(budget, clock)))
        bounded.limitStop() shouldBe (LimitKind.EDGES to 1)
        g.family(PIPELINE).calls.shouldBeEmpty()

        clock.advance(Duration.ofSeconds(-31))
        val refused = shouldThrow<DeadlineExceeded> { g.walker().walk(listOf(root), context(Deadline.after(budget, clock))) }
        refused.family shouldBe PIPELINE // the walk needed p, and the deadline had expired
        g.family(PIPELINE).calls.shouldBeEmpty()
    }

    @Test
    fun `a provider that breaks the contract in a speculative call is a defect, never swallowed`() {
        val g = TestGraph()
        val p = exact(PIPELINE, "p", 1)
        g.node(a, Lifecycle.RELEASED)
        g.node(p, Lifecycle.RELEASED)
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, a, "parts[0]"), pin(TEST_WIDGET, p, "parts[1]"))
        g.family(PIPELINE).omitted = setOf(p)

        // p is speculation (the walk stops at the edges bound before it needs p), yet the defect is not swallowed.
        shouldThrow<IllegalStateException> { g.walk(root, limits = TestGraph.LIMITS.copy(edges = 1)) }.message shouldContain
            "family pipeline answered 0 outcomes for 1 requests"
    }

    @Test
    fun `the depth bound refuses a target before it is read`() {
        val g = TestGraph()
        val child = exact(TEST_WIDGET, "child", 1)
        g.node(child, Lifecycle.DRAFT, pin(TEST_WIDGET, b, "parts[0]"))
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, child, "parts[0]"))
        g.family(TEMPLATE).failing = setOf(b)

        g.walk(root, limits = TestGraph.LIMITS.copy(depth = 1)).limitStop() shouldBe (LimitKind.DEPTH to 1)
        g.family(TEMPLATE).calls.shouldBeEmpty()
    }

    @Test
    fun `under STOP the first cycle ends the walk and nothing more is read - under DIAGNOSE the walk enumerates on`() {
        val g = TestGraph()
        g.node(root, Lifecycle.DRAFT, pin(TEST_WIDGET, root, "parts[0]"), pin(TEST_WIDGET, b, "parts[1]"))
        g.family(TEMPLATE).failing = setOf(b)

        val stopped = DependencyWalker(g.registry(), TestGraph.LIMITS, FIXED_CLOCK, CycleHandling.STOP).walk(listOf(root), context())
        val cycle = stopped.diagnostics.single()
        cycle.kind shouldBe DiagnosticKind.CYCLE
        stopped.keysOf(cycle.trail) shouldContainExactly listOf("w", "w")
        stopped.complete shouldBe false
        stopped.acyclic.shouldBeNull()
        stopped.dependencyFirst.shouldBeNull()
        g.family(TEMPLATE).calls.shouldBeEmpty()

        shouldThrow<IllegalStateException> { g.walk(root) }.message shouldBe "metadata source unavailable: b"
    }
}
