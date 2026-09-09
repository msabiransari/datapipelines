package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.web.anonymousActors
import io.mockk.every
import io.mockk.mockk
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersionSummary
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The template twin of [PipelineLifecycleDialogModelTest]: the wrong-shape guard arms and
 * the in-use pre-reads, which the controller suite reaches only through mocks.
 */
class TemplateLifecycleDialogModelTest {
    private val templates = mockk<TemplateRepository>()
    private val pipelines = mockk<PipelineRepository>()

    private val model = TemplateLifecycleDialogModel(templates, pipelines, anonymousActors(), AuthoringGuard(enabled = true))

    private fun stubExists() {
        every { templates.existsId(any(), any()) } returns true
    }

    private fun versionOf(status: co.datapipelines.pipeline.PipelineVersionStatus) =
        co.datapipelines.templates.TemplateVersionDetail(
            templateId = NAME,
            version = 2,
            status = status,
            bodyHash = "h2",
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
            createdBy = USER,
        )

    @Test
    fun `purge - an in-use draft is the template_in_use refusal with the pipelines named`() {
        stubExists()
        every { templates.findVersionDetail(any(), any(), any()) } returns versionOf(DRAFT)
        every { pipelines.findLiveVersionsPinningTemplateVersion(any(), any(), any()) } returns
            listOf(
                TemplatePin(ID, "nyc/rollup", 3, RELEASED, "extract", 2),
                TemplatePin(ID, "nyc/rollup", 4, RELEASED, "load", 2),
            )
        every { templates.listVersions(any(), any()) } returns
            listOf(TemplateVersionSummary(NAME, 2, Instant.now(), USER, DRAFT))

        val dialog = model.purge(WS, NAME, 2)
        dialog.refusal!!.code shouldBe "template.in_use"
        dialog.inUsePipelines shouldBe listOf("nyc/rollup")
    }

    @Test
    fun `discard - a pinned release lists the pinners and the dialog model says so`() {
        stubExists()
        every { templates.findVersionDetail(any(), any(), any()) } returns versionOf(RELEASED)
        every { pipelines.findLiveVersionsPinningTemplateVersion(any(), any(), any()) } returns
            listOf(TemplatePin(ID, "nyc/rollup", 3, RELEASED, "extract", 2))
        every { templates.findLatest(any(), any()) } returns
            mockk<co.datapipelines.templates.Template> { every { version } returns 2 }
        every { templates.listVersions(any(), any()) } returns
            listOf(TemplateVersionSummary(NAME, 2, Instant.now(), USER, RELEASED))

        val dialog = model.discard(WS, NAME, 2)
        dialog.pinnerPipelines shouldBe listOf("nyc/rollup")
        dialog.isCurrent shouldBe true
    }

    @Test
    fun `restore - the pointer preview moves only above-current or from null`() {
        stubExists()
        every { templates.findVersionDetail(any(), any(), any()) } returns versionOf(DISCARDED)
        every { templates.findLatest(any(), any()) } returns null
        model.restore(WS, NAME, 1).movesPointer shouldBe true

        val latest = mockk<co.datapipelines.templates.Template> {
            every { version } returns 3
        }
        every { templates.findLatest(any(), any()) } returns latest
        model.restore(WS, NAME, 2).movesPointer shouldBe false
    }

    @Test
    fun `purge entity - the sole-draft shape with no pins is the allowed branch`() {
        every { templates.listVersions(any(), any()) } returns
            listOf(TemplateVersionSummary(NAME, 1, Instant.now(), USER, DRAFT))
        every { pipelines.findAnyVersionTemplatePins(any(), any()) } returns emptyList()

        val dialog = model.purgeEntity(WS, NAME)
        dialog.refusal shouldBe null
        dialog.expected shouldBe NAME
    }

    private companion object {
        val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        const val NAME = "test/probe.sql"
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
