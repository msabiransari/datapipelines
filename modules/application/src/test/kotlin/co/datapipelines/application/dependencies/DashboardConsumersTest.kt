package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The two dashboard ports over their inverse arms (#320; #877 §C): each verb's question maps to its scope — the
 * exact-pin question to LIVE with the version, the entity question to ANY with no version — and the answer is the
 * dashboard, its version and its status, one per dashboard version however many elements pin the target. The arm
 * itself is the inverse conformance suite's; this pins the MAPPING, so a port answering the wrong question (an entity
 * purge asking LIVE, a discard asking ANY) is red here and not at the gate.
 */
class DashboardConsumersTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboard = UUID.randomUUID().toString()
    private val expected = listOf(DashboardPin("acme/boards/revenue", 4, PipelineVersionStatus.RELEASED))
    private val asked = mutableListOf<Triple<InverseArm, Int?, InverseScope>>()

    private fun row(element: String) =
        InverseRow(dashboard, "acme/boards/revenue", 4, PipelineVersionStatus.RELEASED, "elements[0].pipeline", 2, element)

    private fun incoming(answers: Map<Triple<InverseArm, Int?, InverseScope>, List<InverseRow>>) =
        IncomingRows { ws, arm, _, version, scope ->
            ws shouldBe workspaceId
            Triple(arm, version, scope).also(asked::add).let { answers[it].orEmpty() }
        }

    @Test
    fun `a pipeline discard asks the LIVE exact-pin arm - and an entity purge asks ANY version`() {
        val arm = InverseArm.PIPELINE_FROM_DASHBOARD
        val consumers =
            DashboardPipelineConsumers(
                incoming(
                    mapOf(
                        Triple(arm, 2, InverseScope.LIVE) to listOf(row("e1")),
                        Triple(arm, null, InverseScope.ANY) to listOf(row("e1"), row("e2")),
                    ),
                ),
            )

        consumers.liveVersionPins(workspaceId, "acme/p", 2) shouldContainExactly expected
        // two elements of ONE dashboard version are one pin
        consumers.anyVersionPins(workspaceId, "acme/p") shouldContainExactly expected
        asked shouldContainExactly listOf(Triple(arm, 2, InverseScope.LIVE), Triple(arm, null, InverseScope.ANY))
    }

    @Test
    fun `a parameter-set discard asks the LIVE exact-pin arm - and an entity purge asks ANY version`() {
        val arm = InverseArm.PARAMETER_SET_FROM_DASHBOARD
        val consumers = DashboardParameterSetConsumers(incoming(mapOf(Triple(arm, 3, InverseScope.LIVE) to listOf(row("e1")))))

        consumers.liveVersionPins(workspaceId, "acme/s", 3) shouldContainExactly expected
        consumers.anyVersionPins(workspaceId, "acme/s") shouldBe emptyList()
        asked shouldContainExactly listOf(Triple(arm, 3, InverseScope.LIVE), Triple(arm, null, InverseScope.ANY))
    }
}
