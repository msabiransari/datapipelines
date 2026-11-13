package co.datapipelines.pipeline.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #873 §F.3 b/c (record erratum (7)): the plan wire's ONE new key and the prerequisite refusal. `prerequisites` follows
 * `tree_hash` and is never a `tree_hash` input; an available prerequisite changes nothing else on the wire; an unavailable
 * one blocks the plan with its versionless `{kind, name, reason}` blocker after the node blockers, inside the same
 * `max_blockers` bound; an unavailable row's `readonly` is null by construction; the refusal is the ROOT family's
 * `dependency_not_released` over the same blocker shape (409 by the catalog's mapping of the code).
 */
class ReleasePrerequisiteTest {
    private val fx = ReleaseGraphFixture()

    /** A DRAFT pipeline pinning a DRAFT template and a MISSING one (one node blocker). */
    private fun closure(): ReleaseClosure {
        val template = fx.node(fx.id("region", version = 4), ReleaseNodeStatus.DRAFT)
        val root = fx.node(fx.id("acme/orders", version = 7, family = ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT)
        fx.pin(root, template, "nodes[0].template")
        fx.pin(root, fx.id("gone"), "nodes[1].template")
        return requireNotNull(fx.graph().walk(root))
    }

    private val warehouse = ReleasePrerequisite("warehouse", available = true, readonly = false)
    private val ghost = ReleasePrerequisite("ghost", available = false, readonly = null)
    private val theirs = ReleasePrerequisite("theirs", available = false, readonly = null)

    @Test
    fun `an available prerequisite adds its row and nothing else - nodes, edges, blockers and tree_hash unchanged`() {
        val closure = closure()
        val before = ReleasePlan.session(closure, ReleaseBounds.DEFAULT_MAX_WIRE_BYTES).toWire(ReleaseBounds.DEFAULT_MAX_BLOCKERS)

        val after =
            ReleasePlan
                .session(closure, ReleaseBounds.DEFAULT_MAX_WIRE_BYTES, listOf(warehouse))
                .toWire(ReleaseBounds.DEFAULT_MAX_BLOCKERS)

        after.keys.toList() shouldBe listOf("root", "nodes", "edges", "complete", "blockers", "tree_hash", "prerequisites")
        after - "prerequisites" shouldBe before - "prerequisites"
        before["prerequisites"] shouldBe emptyList<Any>()
        after["prerequisites"] shouldBe
            listOf(mapOf("kind" to "datasource", "name" to "warehouse", "available" to true, "readonly" to false))
    }

    @Test
    fun `an unavailable prerequisite blocks the plan - its versionless blocker follows the node blockers`() {
        val wire =
            ReleasePlan
                .session(closure(), ReleaseBounds.DEFAULT_MAX_WIRE_BYTES, listOf(warehouse, ghost))
                .toWire(ReleaseBounds.DEFAULT_MAX_BLOCKERS)

        @Suppress("UNCHECKED_CAST")
        val blockers = wire["blockers"] as List<Map<String, Any?>>
        blockers.map { it["name"] } shouldBe listOf("acme/gone", "ghost")
        blockers.last() shouldBe mapOf("kind" to "datasource", "name" to "ghost", "reason" to "unavailable")
        wire["prerequisites"] shouldBe
            listOf(
                mapOf("kind" to "datasource", "name" to "warehouse", "available" to true, "readonly" to false),
                mapOf("kind" to "datasource", "name" to "ghost", "available" to false, "readonly" to null),
            )
    }

    @Test
    fun `the blockers stay inside max_blockers - node blockers first, prerequisites fill what is left`() {
        val plan = ReleasePlan.session(closure(), ReleaseBounds.DEFAULT_MAX_WIRE_BYTES, listOf(ghost, theirs))

        @Suppress("UNCHECKED_CAST")
        fun names(max: Int) = (plan.toWire(max)["blockers"] as List<Map<String, Any?>>).map { it["name"] }

        names(1) shouldBe listOf("acme/gone")
        names(2) shouldBe listOf("acme/gone", "ghost")
        names(3) shouldBe listOf("acme/gone", "ghost", "theirs")
    }

    @Test
    fun `the tree_hash is never fed a prerequisite - the digest of a blocked plan is the artifact closure's`() {
        val closure = closure()

        ReleasePlan.session(closure, ReleaseBounds.DEFAULT_MAX_WIRE_BYTES, listOf(warehouse, ghost)).treeHash shouldBe
            TreeHash.of(closure, ReleaseBounds.DEFAULT_MAX_WIRE_BYTES)
    }

    @Test
    fun `an unavailable row has no readonly value - the constructor refuses one`() {
        shouldThrow<IllegalArgumentException> { ReleasePrerequisite("ghost", available = false, readonly = false) }
        shouldThrow<IllegalArgumentException> { ReleasePrerequisite("ghost", available = false, readonly = true) }
    }

    @Test
    fun `the refusal is the root family's dependency_not_released over versionless blockers, bounded`() {
        val closure = closure()

        val one = PrerequisiteCheck.refusal(closure, listOf(ghost), ReleaseBounds.DEFAULT_MAX_BLOCKERS)
        val two = PrerequisiteCheck.refusal(closure, listOf(ghost, theirs), maxBlockers = 1)

        one.code shouldBe "pipeline.release.dependency_not_released"
        one.message shouldBe "Release blocked: 1 datasource this release needs is not available in this workspace. Nothing was released."
        one.details shouldBe
            mapOf(
                "root" to ReleaseBlockers.identityOf(closure.rootNode),
                "blockers" to listOf(mapOf("kind" to "datasource", "name" to "ghost", "reason" to "unavailable")),
                "blockers_truncated" to false,
            )
        two.message shouldBe "Release blocked: 2 datasources this release needs are not available in this workspace. Nothing was released."
        two.details["blockers"] shouldBe listOf(mapOf("kind" to "datasource", "name" to "ghost", "reason" to "unavailable"))
        two.details["blockers_truncated"] shouldBe true
    }
}
