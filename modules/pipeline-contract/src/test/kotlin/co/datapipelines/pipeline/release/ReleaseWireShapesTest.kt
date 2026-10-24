package co.datapipelines.pipeline.release

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The small wire shapes the coordinator and the surfaces share: a released dependency is `{family, name, version}` in that
 * order and nothing else (record §6, §11), and a plan is PRESENT when any one of its fields was supplied — the arming
 * rule's input (record §6) — with an empty list still counting as supplied.
 */
class ReleaseWireShapesTest {
    @Test
    fun `a released dependency is family, name and version - never a body or an internal id`() {
        val wire = ReleasedDependency(ReleaseFamily.PARAMETER_SET, "acme/markets", 3).toWire()

        wire.keys.toList() shouldBe listOf("family", "name", "version")
        wire shouldBe mapOf("family" to ReleaseFamily.PARAMETER_SET.wire, "name" to "acme/markets", "version" to 3)
    }

    @Test
    fun `a plan is present when any field is supplied, empty included, and absent only when none is`() {
        ReleasePlanRequest().present shouldBe false
        ReleasePlanRequest(treeHash = "v1:abc").present shouldBe true
        ReleasePlanRequest(includeDependencies = emptyList()).present shouldBe true
        ReleasePlanRequest(checkOverrides = listOf(CheckOverride("n1", "flaky upstream"))).present shouldBe true
    }

    @Test
    fun `a request without plan fields is the legacy shape - no alias, no plan`() {
        val request = PipelineReleaseRequest(UUID(0, 1), UUID(0, 2), expectedHash = "h", actor = UUID(0, 3))

        request.releasePinnedTemplates shouldBe false
        request.plan shouldBe null
        request.overrideChecksReason shouldBe null
    }

    @Test
    fun `a prepared node and its flip carry the node they were made for`() {
        val fx = ReleaseGraphFixture()
        val node = requireNotNull(fx.graph().walk(fx.node(fx.id("orders", family = ReleaseFamily.PIPELINE)))).rootNode
        val context = ReleaseContext(UUID(0, 4), requireNotNull(fx.graph().walk(node.identity)), setOf(node.identity), "h", null)
        val prepared = PreparedRelease(node, context.expectedHash)

        ReleaseFlip(prepared.node, result = "released") shouldBe ReleaseFlip(node, "released")
        prepared.expectedHash shouldBe "h"
        context.flipping shouldBe setOf(node.identity)
        val row = ReleaseAuditRow("pipeline.version.released", context.actor, keyId = null, details = mapOf("scope" to "version"))
        row.details shouldBe mapOf("scope" to "version")
    }
}
