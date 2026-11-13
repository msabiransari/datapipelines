package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The draft PURGE over mocked repositories (versioning §5.4, 101): the authoring guard, the stale-hash and not-draft
 * refusals before any pin is asked or any row deleted, and the version-or-entity outcome.
 *
 * Moved here from `modules/web` by 056 with the service itself. Since #877 §E.3 retired the native release entry point,
 * this class's RELEASE cases run through the release coordinator in `web` (`PipelineReleaseCoordinatorCasesTest`, one
 * case each, same stubs, same assertions); what stays here is the purge, the half of the service no coordinator wraps.
 */
class PipelineReleaseServiceTest {
    private val pipelines = mockk<PipelineRepository>()
    private val validator = mockk<PipelineValidator>()
    private val dashboards = mockk<PipelineVersionConsumers>(relaxed = true)
    private val service =
        PipelineReleaseService(
            pipelines,
            validator,
            AuthoringGuard(true),
            dashboards = dashboards,
            endpointVersions = NoApiVersionPins,
            schedules = NoSchedulePins,
        )

    @Test
    fun `purge refuses when authoring is disabled`() {
        // versioning §5.5: the draft purge is an authoring action — a receiver refuses (the release half of this case
        // runs through the coordinator: `PipelineReleaseCoordinatorCasesTest` "release refuses when authoring is disabled").
        val receiver =
            PipelineReleaseService(
                pipelines,
                validator,
                AuthoringGuard(false),
                dashboards = dashboards,
                endpointVersions = NoApiVersionPins,
                schedules = NoSchedulePins,
            )

        val purge =
            shouldThrow<DatapipelinesException> {
                receiver.purge(UUID.randomUUID(), UUID.randomUUID(), "hash")
            }
        purge.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        verify { pipelines wasNot Called }
    }

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun pipelineRecord() =
        co.datapipelines.pipeline.PipelineRecord(
            id = pipelineId,
            name = "test/monthly_revenue",
            displayName = "M",
            description = "d",
            ownerId = userId,
            currentVersion = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun draftDetail(hash: String = "draft-hash") =
        PipelineVersionDetail(
            pipelineId = pipelineId,
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = hash,
            createdAt = Instant.EPOCH,
            createdBy = userId,
        )

    @Test
    fun `462 stale hash and missing draft refuse before asking pins or deleting`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail("current-hash")
        val conflict = shouldThrow<DatapipelinesException> { service.purge(workspaceId, pipelineId, "stale") }
        conflict.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        conflict.details["current_body_hash"] shouldBe "current-hash"
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null
        shouldThrow<DatapipelinesException> { service.purge(workspaceId, pipelineId, "stale") }.code shouldBe
            PipelineErrorCodes.Versioning.NOT_DRAFT
        verify(exactly = 0) { pipelines.findLiveParentsPinningVersion(any(), any(), any()) }
        verify(exactly = 0) { pipelines.purgeDraft(any(), any(), any(), any()) }
        verify { dashboards wasNot Called }
    }

    @Test
    fun `purge deletes the version or the entity, or refuses - never clobbers`() {
        every { pipelines.findByIdAnyStatus(workspaceId, pipelineId) } returns pipelineRecord()
        every { pipelines.listVersions(workspaceId, pipelineId) } returns emptyList()
        every { pipelines.findLiveParentsPinningVersion(any(), any(), any()) } returns emptyList()
        // 101: the draft verb is PURGE — the row (and its executions) are gone either way;
        // the flip-to-DISCARDED branch is withdrawn.
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.purgeDraft(workspaceId, pipelineId, "draft-hash", true) } returns
            co.datapipelines.pipeline.PurgeOutcome
                .VersionPurged(3, pipelineRecord())
        val purged = service.purge(workspaceId, pipelineId, "draft-hash")
        (purged as PipelineReleaseService.Purged.Version).executionsDeleted shouldBe 3

        every { pipelines.purgeDraft(workspaceId, pipelineId, "draft-hash", true) } returns
            co.datapipelines.pipeline.PurgeOutcome
                .EntityPurged(1)
        (service.purge(workspaceId, pipelineId, "draft-hash") as PipelineReleaseService.Purged.Entity)
            .executionsDeleted shouldBe 1

        // The repository's guard failed (0 rows) and no draft exists: not_draft, and the
        // conflict path never fires.
        every { pipelines.purgeDraft(workspaceId, pipelineId, "draft-hash", true) } returns null
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null
        shouldThrow<DatapipelinesException> { service.purge(workspaceId, pipelineId, "draft-hash") }
            .code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT
    }
}
