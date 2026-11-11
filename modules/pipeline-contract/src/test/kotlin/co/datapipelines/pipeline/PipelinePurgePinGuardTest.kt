package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #786 B.4 — the shared pin preflight's two new families (record §14, A39): which scope each asks (exact live version
 * for a version discard or a nonsole draft purge, every version for the entity purge) and the refusal shape that names
 * them. The adapters' SQL is proven against Postgres in `ApiAndScheduleConsumersIntegrationTest`.
 */
class PipelinePurgePinGuardTest {
    private val pipelines = mockk<PipelineRepository>()
    private val record = PipelineRecord(PIPELINE_ID, NAME, "P", "", UUID.randomUUID(), 2, Instant.EPOCH, Instant.EPOCH)

    private val exactApi = EndpointVersionPin("/acme/v1/rides", 3, PipelineVersionStatus.DRAFT, 2)
    private val anyApi = EndpointVersionPin("/acme/v1/older", 1, PipelineVersionStatus.RELEASED, 1)
    private val exactSchedule = SchedulePin("nightly", 2)
    private val anySchedule = SchedulePin("waiting", null)

    private val endpointVersions =
        object : EndpointVersionConsumers {
            override fun liveVersionPins(
                workspaceId: UUID,
                pipelineName: String,
                version: Int,
            ) = listOf(exactApi)

            override fun anyVersionPins(
                workspaceId: UUID,
                pipelineName: String,
            ) = listOf(anyApi)
        }

    private val schedules =
        object : ScheduleConsumers {
            override fun liveVersionPins(
                workspaceId: UUID,
                pipelineName: String,
                version: Int,
            ) = listOf(exactSchedule)

            override fun anyVersionPins(
                workspaceId: UUID,
                pipelineName: String,
            ) = listOf(anySchedule)
        }

    private val guard = PipelinePurgePinGuard(pipelines, NoDashboards, endpointVersions, schedules)

    @Test
    fun `a version discard is refused by the exact live API versions and schedules, each named`() {
        every { pipelines.findLiveParentsPinningVersion(WORKSPACE, NAME, 2) } returns emptyList()

        val refused = shouldThrow<DatapipelinesException> { guard.refuseIfPinned(WORKSPACE, record, 2) }

        refused.code shouldBe PipelineErrorCodes.Versioning.PINNED
        refused.message shouldContain "1 API version(s) and 1 schedule(s)"
        refused.details["referencing_api_versions"] shouldBe
            listOf(mapOf("path" to "/acme/v1/rides", "version" to 3, "status" to "DRAFT", "pipeline_version" to 2))
        refused.details["referencing_schedules"] shouldBe listOf(mapOf("schedule" to "nightly", "pipeline_version" to 2))
        refused.details.containsKey("referencing_dashboards") shouldBe false
    }

    @Test
    fun `a sole draft's purge takes the entity, so it asks every version`() {
        every { pipelines.listVersions(WORKSPACE, PIPELINE_ID) } returns
            listOf(PipelineVersionRecord(PIPELINE_ID, 1, PipelineVersionStatus.DRAFT, createdAt = Instant.EPOCH, createdBy = USER))
        every { pipelines.findLiveParentsPinningVersion(WORKSPACE, NAME, 1) } returns emptyList()

        val refused = shouldThrow<DatapipelinesException> { guard.refuseIfDraftPinned(WORKSPACE, record, 1) }

        refused.details["referencing_api_versions"] shouldBe
            listOf(mapOf("path" to "/acme/v1/older", "version" to 1, "status" to "RELEASED", "pipeline_version" to 1))
        refused.details["referencing_schedules"] shouldBe listOf(mapOf("schedule" to "waiting", "pipeline_version" to null))
    }

    @Test
    fun `a nonsole draft's purge asks the exact version only`() {
        every { pipelines.listVersions(WORKSPACE, PIPELINE_ID) } returns
            listOf(
                PipelineVersionRecord(PIPELINE_ID, 1, createdAt = Instant.EPOCH, createdBy = USER),
                PipelineVersionRecord(PIPELINE_ID, 2, PipelineVersionStatus.DRAFT, createdAt = Instant.EPOCH, createdBy = USER),
            )
        every { pipelines.findLiveParentsPinningVersion(WORKSPACE, NAME, 2) } returns emptyList()

        val pinners = guard.pinnersOf(WORKSPACE, record, 2)

        pinners.endpointVersions shouldBe listOf(exactApi)
        pinners.schedules shouldBe listOf(exactSchedule)
        shouldThrow<DatapipelinesException> { guard.refuseIfDraftPinned(WORKSPACE, record, 2) }
            .details["referencing_api_versions"] shouldBe
            listOf(mapOf("path" to "/acme/v1/rides", "version" to 3, "status" to "DRAFT", "pipeline_version" to 2))
    }

    @Test
    fun `nothing pinning passes`() {
        every { pipelines.findLiveParentsPinningVersion(WORKSPACE, NAME, 2) } returns emptyList()
        val quiet = PipelinePurgePinGuard(pipelines, NoDashboards, NoApiVersionPins, NoSchedulePins)

        quiet.refuseIfPinned(WORKSPACE, record, 2)
        quiet.pinnersOf(WORKSPACE, record, 2).isEmpty shouldBe true
    }

    private object NoDashboards : PipelineVersionConsumers {
        override fun liveVersionPins(
            workspaceId: UUID,
            pipelineName: String,
            version: Int,
        ): List<DashboardPin> = emptyList()

        override fun anyVersionPins(
            workspaceId: UUID,
            pipelineName: String,
        ): List<DashboardPin> = emptyList()
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000786")
        val PIPELINE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        const val NAME = "acme/rides/daily"
    }
}
