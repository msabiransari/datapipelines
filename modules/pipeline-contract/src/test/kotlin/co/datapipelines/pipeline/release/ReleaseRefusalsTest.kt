package co.datapipelines.pipeline.release

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The release-plan refusals (record §14): every code carries the ROOT's family prefix, never the blocker's, and every
 * detail is a field locator, a bound, a word or a visible identity — never a submitted value or a hidden total.
 */
class ReleaseRefusalsTest {
    private val fx = ReleaseGraphFixture()
    private val root = fx.id("orders", family = ReleaseFamily.PIPELINE)

    @Test
    fun `dependency_not_released carries the root family's code and the shared blockers details`() {
        val gone = fx.id("gone")
        val draft = fx.node(fx.id("draft"))
        fx.node(root, ReleaseNodeStatus.DRAFT, gone to "nodes[0].template", draft to "nodes[1].template")
        val closure = requireNotNull(fx.graph().walk(root))
        val blockers = ReleaseBlockers.blockers(closure, included = emptySet())

        val refused = ReleaseRefusals.dependencyNotReleased(closure, blockers, maxBlockers = 100)

        refused.code shouldBe "pipeline.release.dependency_not_released"
        refused.message shouldContain "2 dependencies are not released"
        refused.details shouldBe ReleaseBlockers.details(closure, blockers, maxBlockers = 100)
        ReleaseRefusals.dependencyNotReleased(closure, blockers.take(1), maxBlockers = 100).message shouldContain
            "1 dependency is not released"
    }

    @Test
    fun `a cycle names its active path by visible identity only`() {
        val node = requireNotNull(fx.node(root).let { fx.graph().walk(it) }).rootNode

        val refused = ReleaseRefusals.cycle(ReleaseFamily.TEMPLATE, listOf(node, node))

        refused.code shouldBe "template.release.dependency_cycle"
        refused.message shouldContain "acme/orders v1 → acme/orders v1"
        refused.details shouldBe mapOf("path" to listOf(ReleaseBlockers.identityOf(node), ReleaseBlockers.identityOf(node)))
    }

    @Test
    fun `a limit names the exhausted bound and its maximum, nothing else`() {
        val refused = ReleaseRefusals.limit(ReleaseFamily.PARAMETER_SET, ReleaseLimit.WIRE_BYTES, 1024)

        refused.code shouldBe "parameter.release.dependency_limit"
        refused.details shouldBe mapOf("limit" to "wire_bytes", "maximum" to 1024)
    }

    @Test
    fun `plan_conflict carries the changed node only when one is named`() {
        val bare = ReleaseRefusals.planConflict(ReleaseFamily.DASHBOARD)
        bare.code shouldBe "dashboard.release.plan_conflict"
        bare.details shouldBe mapOf("reason" to "snapshot_changed")

        val located = ReleaseRefusals.planConflict(ReleaseFamily.DASHBOARD, nodeId = "n3", path = "nodes[1].template")
        located.details shouldBe mapOf("reason" to "snapshot_changed", "node_id" to "n3", "path" to "nodes[1].template")
    }

    @Test
    fun `plan_required has no details and plan_invalid echoes the locator and the reason, never the value`() {
        val required = ReleaseRefusals.planRequired(ReleaseFamily.VISUALIZATION)
        required.code shouldBe "visualization.release.plan_required"
        required.details shouldBe emptyMap()

        val invalid = ReleaseRefusals.planInvalid(ReleaseFamily.PIPELINE, "include_dependencies[2]", "unknown node id")
        invalid.code shouldBe "pipeline.release.plan_invalid"
        invalid.message shouldBe "The release plan is not valid: unknown node id."
        invalid.details shouldBe mapOf("field" to "include_dependencies[2]", "reason" to "unknown node id")
    }

    @Test
    fun `busy names the writer's own family and the lock wait, and keeps the driver cause`() {
        val cause = IllegalStateException("55P03")

        val refused = ReleaseRefusals.busy(ReleaseFamily.TEMPLATE, lockWaitSeconds = 5, cause = cause)

        refused.code shouldBe "template.release.busy"
        refused.details shouldBe mapOf("lock_wait_seconds" to 5)
        refused.cause shouldBe cause
    }
}
