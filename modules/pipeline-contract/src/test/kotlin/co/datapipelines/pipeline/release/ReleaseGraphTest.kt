package co.datapipelines.pipeline.release

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The closure walk in isolation (record §1.2): a diamond is one node released once, a cycle is refused with its active
 * path, the depth is the LONGEST path even when the long route reaches a memoised node second, each bound admits exactly
 * N and refuses N + 1, and every identity is read once.
 */
class ReleaseGraphTest {
    private val fx = ReleaseGraphFixture()
    private val root = fx.id("orders", family = ReleaseFamily.PIPELINE)

    @Test
    fun `a diamond's shared node is one node, read once, released once after both parents`() {
        val shared = fx.node(fx.id("shared"))
        val left = fx.node(fx.id("left"), ReleaseNodeStatus.DRAFT, shared to "template")
        val right = fx.node(fx.id("right"), ReleaseNodeStatus.DRAFT, shared to "template")
        fx.node(root, ReleaseNodeStatus.DRAFT, left to "nodes[0].template", right to "nodes[1].template")

        val closure = requireNotNull(fx.graph().walk(root))

        closure.nodes.map { it.identity } shouldContainExactly listOf(root, left, shared, right)
        closure.nodes.map { it.nodeId } shouldContainExactly listOf("n0", "n1", "n2", "n3")
        closure.edges.size shouldBe 4
        closure.depth shouldBe 2
        closure.dependencyFirst.map { it.identity } shouldContainExactly listOf(shared, left, right, root)
        fx.reads.values.toSet() shouldBe setOf(1)
        fx.reads.keys shouldBe setOf(root, left, right, shared)
        closure.pathsTo(shared) shouldContainExactly listOf("nodes[0].template/template", "nodes[1].template/template")
    }

    @Test
    fun `a cycle is refused with the root's family and the active path, the repeated node closing it`() {
        val a = fx.id("a")
        val b = fx.id("b")
        fx.node(b, ReleaseNodeStatus.DRAFT, a to "template")
        fx.node(a, ReleaseNodeStatus.DRAFT, b to "template")
        fx.node(root, ReleaseNodeStatus.DRAFT, a to "nodes[0].template")

        val refused = shouldThrow<DatapipelinesException> { fx.graph().walk(root) }

        refused.code shouldBe "pipeline.release.dependency_cycle"
        @Suppress("UNCHECKED_CAST")
        val path = (refused.details["path"] as List<Map<String, Any?>>).map { it["name"] to it["node_id"] }
        path shouldContainExactly listOf("acme/a" to "n1", "acme/b" to "n2", "acme/a" to "n1")
    }

    @Test
    fun `a self pin is a cycle of one`() {
        val a = fx.id("a")
        fx.node(a, ReleaseNodeStatus.DRAFT, a to "template")
        fx.node(root, ReleaseNodeStatus.DRAFT, a to "nodes[0].template")

        shouldThrow<DatapipelinesException> { fx.graph().walk(root) }.code shouldBe "pipeline.release.dependency_cycle"
    }

    @Test
    fun `a diamond is not a cycle - a memoised node reached again is not on the active path`() {
        val shared = fx.node(fx.id("shared"))
        val mid = fx.node(fx.id("mid"), ReleaseNodeStatus.DRAFT, shared to "template")
        fx.node(root, ReleaseNodeStatus.DRAFT, shared to "nodes[0].template", mid to "nodes[1].template")

        requireNotNull(fx.graph().walk(root)).depth shouldBe 2
    }

    @Test
    fun `depth exactly at the bound is admitted and one more edge is refused naming the depth bound only`() {
        fx.node(root)
        fx.chain(root, 32)
        requireNotNull(fx.graph().walk(root)).depth shouldBe 32

        val deeper = ReleaseGraphFixture()
        val deepRoot = deeper.node(deeper.id("orders", family = ReleaseFamily.PIPELINE))
        deeper.chain(deepRoot, 33)
        val refused = shouldThrow<DatapipelinesException> { deeper.graph().walk(deepRoot) }
        refused.code shouldBe "pipeline.release.dependency_limit"
        refused.details shouldBe mapOf("limit" to "depth", "maximum" to 32)
    }

    @Test
    fun `the longest path counts even when it reaches a memoised subtree second`() {
        val bounds = ReleaseBounds(maxDepth = 6)
        val shared = fx.node(fx.id("shared"))
        fx.chain(shared, 3, prefix = "below")
        val shortcut = fx.node(fx.id("shortcut"), ReleaseNodeStatus.DRAFT, shared to "template")
        fx.node(root, ReleaseNodeStatus.DRAFT, shortcut to "nodes[0].template")
        // root → shortcut → shared → 3 below: depth 5, admitted.
        requireNotNull(fx.graph(bounds).walk(root)).depth shouldBe 5

        // The long route arrives SECOND, at depth 3: 3 + 1 + shared's memoised height 3 = 7 > 6.
        val long = fx.chain(root, 3, prefix = "long")
        fx.pin(long.last(), shared, "template")
        val refused = shouldThrow<DatapipelinesException> { fx.graph(bounds).walk(root) }
        refused.details shouldBe mapOf("limit" to "depth", "maximum" to 6)
    }

    @Test
    fun `versions exactly at the bound are admitted and one more is refused`() {
        val children = (1..4).map { fx.node(fx.id("t$it")) }
        fx.node(root, ReleaseNodeStatus.DRAFT, *children.map { it to "nodes[${it.entityId}].template" }.toTypedArray())
        requireNotNull(fx.graph(ReleaseBounds(maxVersions = 5)).walk(root)).nodes.size shouldBe 5

        val refused = shouldThrow<DatapipelinesException> { fx.graph(ReleaseBounds(maxVersions = 4)).walk(root) }
        refused.details shouldBe mapOf("limit" to "versions", "maximum" to 4)
    }

    @Test
    fun `edges count every pin occurrence and are bounded at exactly N`() {
        val child = fx.node(fx.id("child"))
        // One version pinned three times: three edges, one node.
        fx.node(root, ReleaseNodeStatus.DRAFT, child to "nodes[0].template", child to "nodes[1].template", child to "nodes[2].template")
        val closure = requireNotNull(fx.graph(ReleaseBounds(maxEdges = 3)).walk(root))
        closure.edges.size shouldBe 3
        closure.nodes.size shouldBe 2

        val refused = shouldThrow<DatapipelinesException> { fx.graph(ReleaseBounds(maxEdges = 2)).walk(root) }
        refused.details shouldBe mapOf("limit" to "edges", "maximum" to 2)
    }

    @Test
    fun `an absent pin is a MISSING leaf named by the pin and a DISCARDED version's pins are never walked`() {
        val gone = fx.id("gone")
        val beyond = fx.node(fx.id("beyond"))
        val discarded = fx.node(fx.id("old"), ReleaseNodeStatus.DISCARDED, beyond to "template")
        fx.node(root, ReleaseNodeStatus.DRAFT, gone to "nodes[0].template", discarded to "nodes[1].template")

        val closure = requireNotNull(fx.graph().walk(root))

        val missing = closure.node(gone)
        missing.status shouldBe ReleaseNodeStatus.MISSING
        missing.name shouldBe "acme/gone"
        missing.bodyHash shouldBe null
        closure.node(discarded).status shouldBe ReleaseNodeStatus.DISCARDED
        closure.nodes.map { it.identity } shouldContainExactly listOf(root, gone, discarded)
        fx.reads.containsKey(beyond) shouldBe false
    }

    @Test
    fun `a family without a source resolves as MISSING, never as released`() {
        val viz = fx.id("chart", family = ReleaseFamily.VISUALIZATION)
        fx.node(viz, ReleaseNodeStatus.RELEASED)
        fx.node(root, ReleaseNodeStatus.DRAFT, viz to "nodes[0].visualization")
        val pipelineOnly = ReleaseGraph(mapOf(ReleaseFamily.PIPELINE to fx.source()), ReleaseBounds())

        requireNotNull(pipelineOnly.walk(root)).node(viz).status shouldBe ReleaseNodeStatus.MISSING
    }

    @Test
    fun `an unresolved root answers null so the caller keeps its native refusal`() {
        fx.graph().walk(root) shouldBe null
    }

    @Test
    fun `blockers list MISSING, DISCARDED and unincluded DRAFT nodes in discovery order, truncated at the bound`() {
        val gone = fx.id("gone")
        val old = fx.node(fx.id("old"), ReleaseNodeStatus.DISCARDED)
        val draft = fx.node(fx.id("draft"))
        val released = fx.node(fx.id("done"), ReleaseNodeStatus.RELEASED)
        fx.node(root, ReleaseNodeStatus.DRAFT, gone to "a", old to "b", draft to "c", released to "d")
        val closure = requireNotNull(fx.graph().walk(root))

        val all = ReleaseBlockers.blockers(closure, included = emptySet())
        all.map { it.first.identity to it.second } shouldContainExactly
            listOf(gone to BlockerReason.MISSING, old to BlockerReason.DISCARDED, draft to BlockerReason.NOT_INCLUDED)
        ReleaseBlockers.blockers(closure, included = setOf(draft)).map { it.first.identity } shouldContainExactly listOf(gone, old)

        val details = ReleaseBlockers.details(closure, all, maxBlockers = 2)
        details["root"] shouldBe mapOf("node_id" to "n0", "family" to "pipeline", "name" to "acme/orders", "version" to 1)
        details["blockers_truncated"] shouldBe true
        details["blockers"] shouldBe
            listOf(
                mapOf(
                    "node_id" to "n1",
                    "family" to "template",
                    "name" to "acme/gone",
                    "version" to 1,
                    "status" to "MISSING",
                    "reason" to "missing",
                    "paths" to listOf("a"),
                ),
                mapOf(
                    "node_id" to "n2",
                    "family" to "template",
                    "name" to "acme/old",
                    "version" to 1,
                    "status" to "DISCARDED",
                    "reason" to "discarded",
                    "paths" to listOf("b"),
                ),
            )
    }
}
