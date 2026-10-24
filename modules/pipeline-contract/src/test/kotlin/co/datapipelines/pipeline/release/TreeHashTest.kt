package co.datapipelines.pipeline.release

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/**
 * The plan's concurrency token (record §2.1). A fixed fixture hashes to a PINNED digest — a serialisation change is a
 * format bump, never a silent edit; walk order does not move it; every covered fact (status, body hash, current pointer,
 * pin path, identity) does; display text does not.
 */
class TreeHashTest {
    private fun closure(
        reverse: Boolean = false,
        templateStatus: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        templateHash: String = "hash-region",
        current: Int? = 3,
        path: String = "nodes[0].template",
        name: String = "acme/region",
        childVersion: Int = 2,
    ): ReleaseClosure {
        val fx = ReleaseGraphFixture()
        val template = fx.node(fx.id("region", version = 4), templateStatus, bodyHash = templateHash, currentVersion = current, name = name)
        val child = fx.node(fx.id("acme/child", version = childVersion, family = ReleaseFamily.PIPELINE), ReleaseNodeStatus.RELEASED)
        val gone = fx.id("gone")
        val pins = listOf(template to path, child to "nodes[1].pipeline", gone to "nodes[2].template")
        val root = fx.node(fx.id("acme/orders", version = 7, family = ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT)
        (if (reverse) pins.reversed() else pins).forEach { (target, at) -> fx.pin(root, target, at) }
        return requireNotNull(fx.graph().walk(root))
    }

    private fun hash(closure: ReleaseClosure): String = TreeHash.of(closure, ReleaseBounds.DEFAULT_MAX_WIRE_BYTES)

    @Test
    fun `a fixed fixture hashes to the pinned v1 digest over the pinned canonical form`() {
        val fixture = closure()
        TreeHash.canonical(fixture) shouldBe GOLDEN_CANONICAL
        hash(fixture) shouldBe GOLDEN_DIGEST
        hash(fixture) shouldMatch TreeHash.SHAPE
    }

    @Test
    fun `walk order and the node ids it assigns do not move the digest`() {
        val forward = closure()
        val reversed = closure(reverse = true)
        forward.nodes.map { it.nodeId to it.identity } shouldNotBe reversed.nodes.map { it.nodeId to it.identity }
        hash(reversed) shouldBe hash(forward)
    }

    @Test
    fun `display names do not move the digest`() {
        hash(closure(name = "acme/renamed_display")) shouldBe hash(closure())
    }

    @Test
    fun `every covered fact moves the digest`() {
        val base = hash(closure())
        listOf(
            closure(templateStatus = ReleaseNodeStatus.RELEASED),
            closure(templateHash = "hash-region-edited"),
            closure(current = null),
            closure(current = 4),
            closure(path = "nodes[9].template"),
            closure(childVersion = 3),
        ).map { hash(it) }
            .forEach { it shouldNotBe base }
    }

    @Test
    fun `a serialisation over the wire bound is refused with the wire_bytes limit`() {
        val fixture = closure()
        val size = TreeHash.canonical(fixture).toByteArray(Charsets.UTF_8).size
        TreeHash.of(fixture, size) shouldBe GOLDEN_DIGEST
        val refused = shouldThrow<DatapipelinesException> { TreeHash.of(fixture, size - 1) }
        refused.code shouldBe "pipeline.release.dependency_limit"
        refused.details shouldBe mapOf("limit" to "wire_bytes", "maximum" to size - 1)
    }

    @Test
    fun `control characters and quotes in a pin path are escaped, never spliced into the structure`() {
        val canonical = TreeHash.canonical(closure(path = "a\"b\\c\nd"))
        canonical.contains("\"path\":\"a\\\"b\\\\c\\u000ad\"") shouldBe true
    }

    @Test
    fun `the session plan selects only DRAFT dependencies and blocks only on MISSING and DISCARDED`() {
        val fixture = closure()
        val plan = ReleasePlan.session(fixture, ReleaseBounds.DEFAULT_MAX_WIRE_BYTES)
        plan.treeHash shouldBe GOLDEN_DIGEST
        plan.blockers.map { it.first.name to it.second } shouldBe listOf("acme/gone" to BlockerReason.MISSING)
        val wire = plan.toWire(ReleaseBounds.DEFAULT_MAX_BLOCKERS)
        wire.keys.toList() shouldBe listOf("root", "nodes", "edges", "complete", "blockers", "tree_hash")
        @Suppress("UNCHECKED_CAST")
        val nodes = wire["nodes"] as List<Map<String, Any?>>
        nodes.map { it["name"] to it["selectable"] } shouldBe
            listOf("acme/acme/orders" to false, "acme/region" to true, "acme/acme/child" to false, "acme/gone" to false)
    }

    private companion object {
        /** The v1 canonical form of the fixture, byte for byte (one raw string per node and edge, concatenated). */
        const val GOLDEN_CANONICAL =
            """{"format":"v1","workspace":"00000000-0000-0000-0000-0000000005a7","root":["pipeline","acme/orders",7],"nodes":[""" +
                """{"id":["pipeline","acme/child",2],"status":"RELEASED","body_hash":"hash-acme/child-2","current":null},""" +
                """{"id":["pipeline","acme/orders",7],"status":"DRAFT","body_hash":"hash-acme/orders-7","current":null},""" +
                """{"id":["template","gone",1],"status":"MISSING","body_hash":null,"current":null},""" +
                """{"id":["template","region",4],"status":"DRAFT","body_hash":"hash-region","current":3}],"edges":[""" +
                """{"from":["pipeline","acme/orders",7],"to":["pipeline","acme/child",2],"path":"nodes[1].pipeline"},""" +
                """{"from":["pipeline","acme/orders",7],"to":["template","gone",1],"path":"nodes[2].template"},""" +
                """{"from":["pipeline","acme/orders",7],"to":["template","region",4],"path":"nodes[0].template"}]}"""
        const val GOLDEN_DIGEST = "v1:972bd561d5159b0d2c36a62bf054149b68cc1c67915b5569672a74fc27e46b84"
    }
}
