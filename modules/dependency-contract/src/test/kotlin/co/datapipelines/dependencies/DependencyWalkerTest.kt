package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.DASHBOARD
import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The §7.2 walk: memoization (A02), longest path (A03), the bounds (A04), leaves (A05), cycles and determinism. */
class DependencyWalkerTest {
    @Test
    fun `A02 a diamond loads its shared node once, keeps every edge occurrence and orders dependencies first`() {
        val g = TestGraph()
        val c = g.node(exact(TEMPLATE, "c", 1))
        val a = g.node(exact(TEMPLATE, "a", 1), Lifecycle.DRAFT, pin(TEMPLATE, c, "imports[0]"))
        val b = g.node(exact(TEMPLATE, "b", 1), Lifecycle.RELEASED, pin(TEMPLATE, c, "imports[0]"))
        val p =
            g.node(
                exact(PIPELINE, "p", 1),
                Lifecycle.DRAFT,
                pin(PIPELINE, a, "nodes[0].template"),
                pin(PIPELINE, b, "nodes[1].template"),
            )

        val snapshot = g.walk(p)

        g.family(TEMPLATE).requested().count { it == c } shouldBe 1 // loaded once (A02)
        snapshot.nodes.map { it.requestedAddress.lookupKey } shouldContainExactly listOf("p", "a", "c", "b")
        snapshot.nodes.map { it.nodeId } shouldContainExactly listOf("n0", "n1", "n2", "n3")
        snapshot.edges.map { Triple(it.fromNodeId, it.toNodeId, it.sourcePath) } shouldContainExactly
            listOf(
                Triple("n0", "n1", "nodes[0].template"),
                Triple("n1", "n2", "imports[0]"),
                Triple("n0", "n3", "nodes[1].template"),
                Triple("n3", "n2", "imports[0]"),
            )
        snapshot.edges.map { it.relationship } shouldContainExactly
            listOf(Relationship.TEMPLATE, Relationship.IMPORT, Relationship.TEMPLATE, Relationship.IMPORT)
        snapshot.keysOf(snapshot.dependencyFirst) shouldContainExactly listOf("c", "a", "b", "p")
        snapshot.complete shouldBe true
        snapshot.acyclic shouldBe true
        snapshot.roots shouldContainExactly listOf("n0")
        snapshot.diagnostics shouldHaveSize 0
    }

    @Test
    fun `A02 an alias and a moving selector resolving to one identity are one node with both edges' provenance`() {
        val g = TestGraph()
        val identity = ResolvedIdentity(TEMPLATE, OwnerScope.Workspace(WS), "id-t", 3)
        val facts = TestGraph.facts(exact(TEMPLATE, "t", 3), Lifecycle.RELEASED)
        g.family(TEMPLATE).put(exact(TEMPLATE, "t", 3), facts, emptyList(), identity)
        g.family(TEMPLATE).put(current(TEMPLATE, "t"), facts, emptyList(), identity)
        val p =
            g.node(
                exact(PIPELINE, "p", 1),
                Lifecycle.DRAFT,
                pin(PIPELINE, current(TEMPLATE, "t"), "a"),
                pin(PIPELINE, exact(TEMPLATE, "t", 3), "b"),
            )

        val snapshot = g.walk(p)

        snapshot.nodes shouldHaveSize 2
        snapshot.edges.map { it.requestedSelector } shouldContainExactly listOf(Selector.Current, Selector.ExactVersion(3))
        snapshot.edges.map { it.resolvedVersion } shouldContainExactly listOf(3, 3)
        snapshot.edges.map { it.toNodeId }.distinct() shouldContainExactly listOf("n1")
    }

    @Test
    fun `A03 depth is the longest path, also through a memoised subtree first reached by a shorter one`() {
        fun graph(): Pair<TestGraph, ObjectRequest> {
            val g = TestGraph()
            val c3 = g.node(exact(TEMPLATE, "c3", 1))
            val c2 = g.node(exact(TEMPLATE, "c2", 1), Lifecycle.DRAFT, pin(TEMPLATE, c3, "imports[0]"))
            val c1 = g.node(exact(TEMPLATE, "c1", 1), Lifecycle.DRAFT, pin(TEMPLATE, c2, "imports[0]"))
            val leaf = g.node(exact(TEMPLATE, "leaf", 1))
            // x's FIRST child is a leaf, its second a three-long chain: its height is 3, not the first-seen 1.
            val x = g.node(exact(TEMPLATE, "x", 1), Lifecycle.DRAFT, pin(TEMPLATE, leaf, "imports[0]"), pin(TEMPLATE, c1, "imports[1]"))
            val b = g.node(exact(TEMPLATE, "b", 1), Lifecycle.DRAFT, pin(TEMPLATE, x, "imports[0]"))
            val a = g.node(exact(TEMPLATE, "a", 1), Lifecycle.DRAFT, pin(TEMPLATE, b, "imports[0]"))
            return g to
                g.node(
                    exact(PIPELINE, "r", 1),
                    Lifecycle.DRAFT,
                    pin(PIPELINE, x, "nodes[0].template"),
                    pin(PIPELINE, a, "nodes[1].template"),
                )
        }
        // r → a → b → x → c1 → c2 → c3 is six edges long, entering x (memoised at depth 1) at depth 3.
        val (atLimit, root) = graph()
        atLimit.walk(root, limits = TestGraph.LIMITS.copy(depth = 6)).complete shouldBe true
        val (over, root2) = graph()
        val refused = over.walk(root2, limits = TestGraph.LIMITS.copy(depth = 5))
        refused.limitStop() shouldBe (LimitKind.DEPTH to 5)
    }

    @Test
    fun `A04 nodes, edges and depth are complete at their limit and a LIMIT diagnostic one past it`() {
        fun fan(n: Int): Pair<TestGraph, ObjectRequest> {
            val g = TestGraph()
            val pins = (1..n).map { pin(PIPELINE, g.node(exact(TEMPLATE, "t$it", 1)), "nodes[$it].template") }
            return g to g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, *pins.toTypedArray())
        }
        val (nodesAt, r1) = fan(4)
        nodesAt.walk(r1, limits = TestGraph.LIMITS.copy(nodes = 5)).complete shouldBe true
        val (nodesOver, r2) = fan(4)
        nodesOver.walk(r2, limits = TestGraph.LIMITS.copy(nodes = 4)).limitStop() shouldBe (LimitKind.NODES to 4)

        val (edgesAt, r3) = fan(3)
        edgesAt.walk(r3, limits = TestGraph.LIMITS.copy(edges = 3)).complete shouldBe true
        val (edgesOver, r4) = fan(3)
        edgesOver.walk(r4, limits = TestGraph.LIMITS.copy(edges = 2)).limitStop() shouldBe (LimitKind.EDGES to 2)

        chain(32).let { (g, root) -> g.walk(root).complete shouldBe true }
        chain(33).let { (g, root) -> g.walk(root).limitStop() shouldBe (LimitKind.DEPTH to 32) }
    }

    @Test
    fun `A04 a stopped walk is a bounded diagnostic, never a partial graph marked complete`() {
        val (g, root) = chain(40)
        val snapshot = g.walk(root)
        snapshot.complete shouldBe false
        snapshot.acyclic.shouldBeNull()
        snapshot.dependencyFirst.shouldBeNull()
        snapshot.diagnostics.single().message shouldBe "The depth limit (32) was reached; the graph is incomplete."
        snapshot.edges.forEach { edge -> snapshot.node(edge.toNodeId) } // no dangling edge
    }

    @Test
    fun `A05 MISSING and DISCARDED nodes are leaves and a discarded definition is never expanded`() {
        val g = TestGraph()
        val beneath = g.node(exact(TEMPLATE, "beneath", 1))
        val discarded = g.node(exact(TEMPLATE, "gone", 2), Lifecycle.DISCARDED, pin(TEMPLATE, beneath, "imports[0]"))
        val missing = exact(TEMPLATE, "absent", 1)
        val p =
            g.node(
                exact(PIPELINE, "p", 1),
                Lifecycle.RELEASED,
                pin(PIPELINE, missing, "nodes[0].template"),
                pin(PIPELINE, discarded, "nodes[1].template"),
            )

        val snapshot = g.walk(p)

        snapshot.nodes.map { it.requestedAddress.lookupKey } shouldContainExactly listOf("p", "absent", "gone")
        g.family(TEMPLATE).requested() shouldNotContain beneath
        snapshot.node(snapshot.idOf("absent")).resolution shouldBe Resolution.MISSING
        snapshot.node(snapshot.idOf("absent")).name shouldBe "acme/absent" // the name the reference spelled
        snapshot.node(snapshot.idOf("gone")).lifecycle shouldBe Lifecycle.DISCARDED
        snapshot.diagnostics.map { it.kind to it.nodeId } shouldContainExactly
            listOf(DiagnosticKind.MISSING to "n1", DiagnosticKind.DISCARDED to "n2")
        snapshot.complete shouldBe false // the discarded definition was not expanded
        snapshot.acyclic.shouldBeNull()
    }

    @Test
    fun `a MISSING leaf keeps the graph complete, and identical missing references share one node`() {
        val g = TestGraph()
        val missing = exact(TEMPLATE, "absent", 1)
        val a = g.node(exact(TEMPLATE, "a", 1), Lifecycle.DRAFT, pin(TEMPLATE, missing, "imports[0]"))
        val p =
            g.node(
                exact(PIPELINE, "p", 1),
                Lifecycle.DRAFT,
                pin(PIPELINE, missing, "nodes[0].template"),
                pin(PIPELINE, a, "nodes[1].template"),
            )

        val snapshot = g.walk(p)

        snapshot.nodes shouldHaveSize 3
        snapshot.edges.count { it.toNodeId == snapshot.idOf("absent") } shouldBe 2
        snapshot.diagnostics.map { it.kind } shouldContainExactly listOf(DiagnosticKind.MISSING)
        snapshot.complete shouldBe true
        snapshot.keysOf(snapshot.dependencyFirst) shouldContainExactly listOf("absent", "a", "p")
    }

    @Test
    fun `an unregistered family or an unsupported request is an UNSUPPORTED leaf that makes the graph incomplete`() {
        val g = TestGraph()
        val odd = exact(TEMPLATE, "odd", 1)
        g.family(TEMPLATE).unsupported = setOf(odd)
        val dashboard = exact(DASHBOARD, "d", 1)
        val p = g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, pin(PIPELINE, dashboard, "x"), pin(PIPELINE, odd, "y"))

        val snapshot = g.walk(p)

        snapshot.nodes.drop(1).map { it.resolution } shouldContainExactly listOf(Resolution.UNSUPPORTED, Resolution.UNSUPPORTED)
        snapshot.nodes.drop(1).map { it.capabilities } shouldContainExactly listOf(Capabilities.NONE, Capabilities.NONE)
        snapshot.diagnostics.map { it.kind } shouldContainExactly
            listOf(DiagnosticKind.UNSUPPORTED_FAMILY, DiagnosticKind.UNSUPPORTED_FAMILY)
        snapshot.complete shouldBe false
    }

    @Test
    fun `a self pin and a three-node cycle are CYCLE diagnostics with their trail, and the walk goes on`() {
        val g = TestGraph()
        val self = exact(PIPELINE, "self", 1)
        val after = g.node(exact(TEMPLATE, "after", 1))
        g.node(self, Lifecycle.DRAFT, pin(PIPELINE, self, "nodes[0].pipeline"), pin(PIPELINE, after, "nodes[1].template"))
        val selfSnapshot = g.walk(self)
        selfSnapshot.diagnostics.single().trail shouldContainExactly listOf("n0", "n0")
        selfSnapshot.diagnostics.single().edgeId shouldBe "e0"
        selfSnapshot.nodes shouldHaveSize 2 // the sibling after the back edge is still enumerated
        selfSnapshot.complete shouldBe true
        selfSnapshot.acyclic shouldBe false
        selfSnapshot.dependencyFirst.shouldBeNull()

        val h = TestGraph()
        val a = exact(PIPELINE, "a", 1)
        val c = h.node(exact(PIPELINE, "c", 1), Lifecycle.DRAFT, pin(PIPELINE, a, "nodes[0].pipeline"))
        val b = h.node(exact(PIPELINE, "b", 1), Lifecycle.DRAFT, pin(PIPELINE, c, "nodes[0].pipeline"))
        h.node(a, Lifecycle.DRAFT, pin(PIPELINE, b, "nodes[0].pipeline"))
        h.walk(a).diagnostics.single().let {
            it.kind shouldBe DiagnosticKind.CYCLE
            it.trail shouldContainExactly listOf("n0", "n1", "n2", "n0")
        }
    }

    @Test
    fun `retained diagnostics are bounded, the overflow is one LIMIT and the first cycle is always kept`() {
        val g = TestGraph()
        val root = exact(PIPELINE, "p", 1)
        g.family(TEMPLATE) // registered and empty: every template reference is MISSING, not UNSUPPORTED
        val missing = (1..5).map { pin(PIPELINE, exact(TEMPLATE, "m$it", 1), "nodes[$it].template") }
        g.node(root, Lifecycle.DRAFT, *missing.toTypedArray(), pin(PIPELINE, root, "nodes[9].pipeline"))

        val snapshot = g.walk(root, limits = TestGraph.LIMITS.copy(diagnostics = 3))

        snapshot.diagnostics.map { it.kind } shouldContainExactly
            listOf(DiagnosticKind.MISSING, DiagnosticKind.MISSING, DiagnosticKind.MISSING, DiagnosticKind.LIMIT, DiagnosticKind.CYCLE)
        snapshot.diagnostics[3].limit shouldBe LimitKind.DIAGNOSTICS
        snapshot.nodes shouldHaveSize 6 // enumeration continued past the overflow
        snapshot.complete shouldBe false
    }

    @Test
    fun `roots are validated first, deduplicated, and share one memo`() {
        val g = TestGraph()
        val shared = g.node(exact(TEMPLATE, "shared", 1))
        val p = g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, pin(PIPELINE, shared, "nodes[0].template"))
        val q = g.node(exact(PIPELINE, "q", 1), Lifecycle.DRAFT, pin(PIPELINE, shared, "nodes[0].template"))
        val both = g.walk(p, q, p)
        both.roots shouldContainExactly listOf("n0", "n2")
        both.nodes shouldHaveSize 3

        shouldThrow<IllegalArgumentException> { g.walker().walk(emptyList(), context()) }
        val exactOnly = FakeFamily(PIPELINE, rootSelectors = setOf(SelectorKind.EXACT_VERSION))
        shouldThrow<IllegalArgumentException> {
            DependencyWalker(
                FamilyRegistry(listOf(exactOnly.descriptor())),
                TestGraph.LIMITS,
            ).walk(listOf(current(PIPELINE, "p")), context())
        }
    }

    @Test
    fun `an unresolved or unsupported root is a node, never a silently dropped root`() {
        val snapshot = TestGraph().also { it.family(PIPELINE) }.walk(exact(PIPELINE, "nothing", 1), exact(DASHBOARD, "d", 1))
        snapshot.roots shouldContainExactly listOf("n0", "n1")
        snapshot.nodes.map { it.resolution } shouldContainExactly listOf(Resolution.MISSING, Resolution.UNSUPPORTED)
        snapshot.nodes.map { it.name } shouldContainExactly listOf(null, null)
    }

    @Test
    fun `the walk is deterministic, unresolved node keys included`() {
        fun graph(): TestGraph {
            val g = TestGraph()
            g.node(
                exact(PIPELINE, "p", 1),
                Lifecycle.DRAFT,
                pin(PIPELINE, exact(TEMPLATE, "m", 1), "a"),
                pin(PIPELINE, exact(TEMPLATE, "m", 1), "b"),
            )
            return g
        }
        graph().walk(exact(PIPELINE, "p", 1)) shouldBe graph().walk(exact(PIPELINE, "p", 1))
    }

    @Test
    fun `an infrastructure failure propagates and is never turned into an empty graph`() {
        val g = TestGraph()
        val p = g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, pin(PIPELINE, exact(TEMPLATE, "t", 1), "nodes[0].template"))
        g.family(TEMPLATE).beforeBatch = { throw IllegalStateException("metadata database unavailable") }
        shouldThrow<IllegalStateException> { g.walk(p) }.message shouldBe "metadata database unavailable"
    }

    @Test
    fun `a provider that answers a different key set than it was asked breaks the contract loudly`() {
        val lying =
            object : DependencyProvider<FakeBody> {
                override fun resolveMany(
                    requests: List<ObjectRequest>,
                    context: ResolveContext,
                ): Map<ObjectRequest, Outcome<FakeBody>> = emptyMap()

                override fun outgoing(resolved: Outcome.Resolved<FakeBody>): List<Reference> = emptyList()
            }
        val descriptor =
            FamilyDescriptor(PIPELINE, setOf(SelectorKind.EXACT_VERSION), Capabilities.NONE, 1, lying, FamilyDescriptor.NO_SAFE_SUMMARY)
        shouldThrow<IllegalStateException> {
            DependencyWalker(FamilyRegistry(listOf(descriptor)), TestGraph.LIMITS).walk(listOf(exact(PIPELINE, "p", 1)), context())
        }
    }

    private fun chain(edges: Int): Pair<TestGraph, ObjectRequest> {
        val g = TestGraph()
        var next = g.node(exact(TEMPLATE, "c$edges", 1))
        for (i in edges - 1 downTo 1) next = g.node(exact(TEMPLATE, "c$i", 1), Lifecycle.DRAFT, pin(TEMPLATE, next, "imports[0]"))
        return g to g.node(exact(PIPELINE, "root", 1), Lifecycle.DRAFT, pin(PIPELINE, next, "nodes[0].template"))
    }
}

/** The LIMIT that stopped [this] walk, as (limit, maximum), after asserting the bounded-diagnostic shape. */
internal fun GraphSnapshot.limitStop(): Pair<LimitKind?, Int?> {
    complete shouldBe false
    acyclic.shouldBeNull()
    dependencyFirst.shouldBeNull()
    val limit = diagnostics.last { it.kind == DiagnosticKind.LIMIT }
    return limit.limit to limit.maximum
}
