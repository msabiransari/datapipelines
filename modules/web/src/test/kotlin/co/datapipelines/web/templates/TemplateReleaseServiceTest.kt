package co.datapipelines.web.templates

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 142 — [TemplateReleaseService.releasePinned], the leg the pipeline release cascade rides
 * over mocked repositories: it releases exactly the PINNED version at the draft's current
 * hash through the ordinary [TemplateReleaseService.release] path (one implementation), and
 * refuses — with the template's own codes, unmapped — when the template holds no draft or
 * its draft is a different version than the pin names.
 */
class TemplateReleaseServiceTest {
    private val templates = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val pipelines = mockk<PipelineRepository>()
    private val service = TemplateReleaseService(templates, validator, AuthoringGuard(true), pipelines)

    private val workspaceId = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    private fun draft(version: Int) =
        TemplateVersionDetail(
            templateId = "test/t.sql",
            version = version,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "draft-hash-$version",
            createdAt = Instant.EPOCH,
            createdBy = actor,
        )

    private fun stored(version: Int) =
        Template(
            id = "test/t.sql",
            version = version,
            dialect = Dialect.H2,
            displayName = "T",
            description = "d",
            body = "SELECT 1",
            createdAt = Instant.EPOCH,
            createdBy = actor,
        )

    @Test
    fun `releasePinned releases the pinned version at the draft's own hash, through the ordinary release`() {
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(2)
        every { templates.findVersion(workspaceId, "test/t.sql", 2) } returns stored(2)
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.releaseDraft(workspaceId, "test/t.sql", "draft-hash-2", actor) } returns
            draft(2).copy(status = PipelineVersionStatus.RELEASED)

        val released = service.releasePinned(workspaceId, "test/t.sql", 2, actor)

        released.detail.version shouldBe 2
        released.detail.status shouldBe PipelineVersionStatus.RELEASED
        // The flip carried the DRAFT's hash — the pin names the version, the hash follows.
        verify(exactly = 1) { templates.releaseDraft(workspaceId, "test/t.sql", "draft-hash-2", actor) }
        // And the save-time validation re-ran, exactly as a direct release does.
        verify(exactly = 1) { validator.validateOrThrow(any(), workspaceId) }
    }

    @Test
    fun `releasePinned refuses not_draft when the template holds no draft`() {
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns null

        val error = shouldThrow<DatapipelinesException> { service.releasePinned(workspaceId, "test/t.sql", 2, actor) }

        error.code shouldBe PipelineErrorCodes.Template.VERSION_NOT_DRAFT
        verify(exactly = 0) { templates.releaseDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `releasePinned refuses conflict when the draft is not the pinned version - never a newer one`() {
        every { templates.findDraftDetail(workspaceId, "test/t.sql") } returns draft(3)

        val error = shouldThrow<DatapipelinesException> { service.releasePinned(workspaceId, "test/t.sql", 2, actor) }

        error.code shouldBe PipelineErrorCodes.Template.VERSION_CONFLICT
        error.details["pinned_version"] shouldBe 2
        error.details["draft_version"] shouldBe 3
        verify(exactly = 0) { templates.releaseDraft(any(), any(), any(), any()) }
    }
}
