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
 * The release/discard service's §5.3 preconditions over mocked repositories: the not-draft
 * refusal, the §6 templates-lock-first pin rule, and the stale-hash conflict — the guard
 * ordering the repository cannot express on its own.
 *
 * Moved here from `modules/web` by 056 with the service itself: release is a PIPELINE-aggregate
 * use case and belongs in the module that owns the aggregate. The only wiring change is that
 * `templates` is now the [TemplateVersionStatuses] port rather than `TemplateRepository`
 * directly — `pipeline-contract` cannot depend on `templates` (the arrow runs the other way), so
 * the aggregation layer supplies the one fact the release gate asks for. Not one assertion
 * changed.
 */
class PipelineReleaseServiceTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateVersionStatuses>()
    private val validator = mockk<PipelineValidator>()
    private val service = PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(true))

    @Test
    fun `release and purge refuse when authoring is disabled`() {
        // versioning §5.5: release and the draft purge are authoring actions — a receiver refuses.
        val receiver = PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(false))

        val release =
            shouldThrow<DatapipelinesException> {
                receiver.release(UUID.randomUUID(), UUID.randomUUID(), "hash", UUID.randomUUID())
            }
        release.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED

        val purge =
            shouldThrow<DatapipelinesException> {
                receiver.purge(UUID.randomUUID(), UUID.randomUUID(), "hash")
            }
        purge.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        io.mockk.verify { pipelines wasNot Called }
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

    /** A deserializable body pinning one template at v2 — what release re-validates. */
    private val draftBody =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]}]}"""

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
    fun `release without a draft is the catalogued not_draft`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null

        val error = shouldThrow<DatapipelinesException> { service.release(workspaceId, pipelineId, "h", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT
    }

    @Test
    fun `release re-runs save-time validation on the draft body`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } throws
            co.datapipelines.pipeline.PipelineValidationException(
                co.datapipelines.pipeline.ValidationResult(
                    listOf(co.datapipelines.pipeline.ValidationFailure("pipeline.validation.cycle_detected", "nodes", "cycle")),
                ),
            )

        val error =
            shouldThrow<co.datapipelines.pipeline.PipelineValidationException> {
                service.release(workspaceId, pipelineId, "draft-hash", userId)
            }

        error.code shouldBe "pipeline.validation.cycle_detected"
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a DRAFT template pin blocks release - templates lock first`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.DRAFT

        val error = shouldThrow<DatapipelinesException> { service.release(workspaceId, pipelineId, "draft-hash", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED
        error.details["template_id"] shouldBe "test/t.sql"
        error.details["template_version"] shouldBe 2
        error.details["template_status"] shouldBe "DRAFT"
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `all pins released - the flip happens with the draft body's metadata`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } returns
            PipelineRepository.Released(
                co.datapipelines.pipeline.PipelineRecord(
                    id = pipelineId,
                    name = "test/monthly_revenue",
                    displayName = "M",
                    description = "d",
                    ownerId = userId,
                    currentVersion = 2,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
            )

        val released = service.release(workspaceId, pipelineId, "draft-hash", userId)

        released.record.currentVersion shouldBe 2
        released.version.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a stale hash on release is the conflict carrying the draft's current hash`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail("current-hash")
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every { pipelines.releaseDraft(workspaceId, pipelineId, any(), any(), any(), "stale", any()) } returns null

        val error = shouldThrow<DatapipelinesException> { service.release(workspaceId, pipelineId, "stale", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        error.details["current_body_hash"] shouldBe "current-hash"
        error.details["current_status"] shouldBe "DRAFT"
    }

    @Test
    fun `purge deletes the version or the entity, or refuses - never clobbers`() {
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
