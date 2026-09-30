package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.visualization.ArtifactDependents
import co.datapipelines.visualization.ArtifactPin
import co.datapipelines.visualization.PinScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The two ports over [ArtifactDependents] (#320): each verb's question maps to its scope — the exact-pin question to
 * LIVE with the version, the entity question to ANY with no version — and the answer is the dashboard, its version
 * and its status. The scan itself is `ArtifactDependentsIntegrationTest`'s; this pins the MAPPING, so a port answering
 * the wrong question (an entity purge asking LIVE, a discard asking ANY) is red here and not at the gate.
 */
class DashboardConsumersTest {
    private val dependents = mockk<ArtifactDependents>()
    private val workspaceId = UUID.randomUUID()
    private val pin = ArtifactPin(UUID.randomUUID(), "acme/boards/revenue", 4, PipelineVersionStatus.RELEASED, 2)
    private val expected = listOf(DashboardPin("acme/boards/revenue", 4, PipelineVersionStatus.RELEASED))

    @Test
    fun `a pipeline discard asks the LIVE exact-pin scan - and an entity purge asks ANY version`() {
        every { dependents.dashboardsPinningPipeline(workspaceId, "acme/p", 2, PinScope.LIVE) } returns listOf(pin)
        every { dependents.dashboardsPinningPipeline(workspaceId, "acme/p", null, PinScope.ANY) } returns listOf(pin, pin)
        val consumers = DashboardPipelineConsumers(dependents)

        consumers.liveVersionPins(workspaceId, "acme/p", 2) shouldContainExactly expected
        consumers.anyVersionPins(workspaceId, "acme/p").size shouldBe 2
        verify(exactly = 1) { dependents.dashboardsPinningPipeline(workspaceId, "acme/p", 2, PinScope.LIVE) }
        verify(exactly = 1) { dependents.dashboardsPinningPipeline(workspaceId, "acme/p", null, PinScope.ANY) }
    }

    @Test
    fun `a parameter-set discard asks the LIVE exact-pin scan - and an entity purge asks ANY version`() {
        every { dependents.dashboardsPinningParameterSet(workspaceId, "acme/s", 3, PinScope.LIVE) } returns listOf(pin)
        every { dependents.dashboardsPinningParameterSet(workspaceId, "acme/s", null, PinScope.ANY) } returns emptyList()
        val consumers = DashboardParameterSetConsumers(dependents)

        consumers.liveVersionPins(workspaceId, "acme/s", 3) shouldContainExactly expected
        consumers.anyVersionPins(workspaceId, "acme/s") shouldBe emptyList()
    }
}
