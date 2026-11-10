package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.DATASOURCE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #868 R2: root resolution is bounded BEFORE provider calls are issued — the distinct requests read never pass the
 * `nodes` bound through roots or speculation, whatever the root list's length, its duplicates or its families — and the
 * truncated graph carries the `nodes` LIMIT and is never complete. Every case asserts the provider's request count, not
 * only the returned nodes.
 */
class RootBoundTest {
    private val bounded = TestGraph.LIMITS.copy(nodes = 10)

    @Test
    fun `300 distinct roots under nodes 10 cost 10 requests in one call, not 300`() {
        val g = TestGraph()
        val roots = leaves(g, DATASOURCE, 300)

        val snapshot = g.walk(*roots.toTypedArray(), limits = bounded)

        g.family(DATASOURCE).calls.map { it.size } shouldContainExactly listOf(10)
        g.family(DATASOURCE).requested() shouldContainExactly roots.take(10)
        snapshot.nodes.size shouldBe 10
        snapshot.roots.size shouldBe 10
        snapshot.limitStop() shouldBe (LimitKind.NODES to 10)
    }

    @Test
    fun `duplicated roots are read once and counted once`() {
        val g = TestGraph()
        val roots = leaves(g, DATASOURCE, 300)

        val snapshot = g.walk(*(roots + roots.reversed()).toTypedArray(), limits = bounded)

        g.family(DATASOURCE).requested() shouldContainExactly roots.take(10)
        snapshot.limitStop() shouldBe (LimitKind.NODES to 10)
        // Under the bound, a duplicate adds nothing: 5 roots listed twice are 5 nodes, 5 requests, complete.
        val small = TestGraph()
        val five = leaves(small, DATASOURCE, 5)
        small.walk(*(five + five).toTypedArray(), limits = bounded).let {
            it.nodes.size shouldBe 5
            it.complete shouldBe true
        }
        small.family(DATASOURCE).requested().size shouldBe 5
    }

    @Test
    fun `the capacity is shared across families and across batches`() {
        val g = TestGraph()
        val roots = leaves(g, DATASOURCE, 150).zip(leaves(g, TEMPLATE, 150)).flatMap { (d, t) -> listOf(d, t) }

        val snapshot = g.walk(*roots.toTypedArray(), limits = bounded.copy(resolveBatchSize = 3))

        // Ten requests in all, one family's share in calls of at most three, the other family's likewise.
        g.family(DATASOURCE).calls.map { it.size } shouldContainExactly listOf(3, 2)
        g.family(TEMPLATE).calls.map { it.size } shouldContainExactly listOf(3, 2)
        snapshot.limitStop() shouldBe (LimitKind.NODES to 10)
    }

    @Test
    fun `roots that expand share the capacity with their references - at most 2 nodes + 1 requests, never one per root`() {
        val g = TestGraph()
        val children = (1..20).map { g.node(exact(TEMPLATE, "t$it", 1), Lifecycle.RELEASED) }
        val parts = children.mapIndexed { i, child -> pin(TEST_WIDGET, child, "parts[$i]") }
        val first = g.node(exact(TEST_WIDGET, "w0", 1), Lifecycle.DRAFT, *parts.toTypedArray())
        val roots = listOf(first) + (1 until 300).map { g.node(exact(TEST_WIDGET, "w$it", 1), Lifecycle.DRAFT) }

        val snapshot = g.walk(*roots.toTypedArray(), limits = bounded)

        val requests = g.families.values.sumOf { it.requested().size }
        requests shouldBeLessThanOrEqual 2 * bounded.nodes + 1
        // The first read took the first root and the next nine as speculation; each child is then read as it is needed.
        g.family(TEST_WIDGET).calls.map { it.size } shouldContainExactly listOf(10)
        g.family(TEMPLATE).calls.map { it.size } shouldContainExactly List(10) { 1 }
        snapshot.nodes.size shouldBe 10
        snapshot.limitStop() shouldBe (LimitKind.NODES to 10)
    }

    @Test
    fun `under the bound every root is read in batches and the graph is complete`() {
        val g = TestGraph()
        val roots = leaves(g, DATASOURCE, 300)

        val snapshot = g.walk(*roots.toTypedArray())

        g.family(DATASOURCE).calls.map { it.size } shouldContainExactly listOf(256, 44)
        snapshot.nodes.size shouldBe 300
        snapshot.complete shouldBe true
    }

    /** [n] leaf objects of [family] (a datasource is a §6 leaf), registered in [g]. */
    private fun leaves(
        g: TestGraph,
        family: FamilyKey,
        n: Int,
    ): List<ObjectRequest> = (1..n).map { g.node(exact(family, "${family.wire}_$it", 1), Lifecycle.RELEASED) }
}
