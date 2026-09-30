package co.datapipelines.web.ui

import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersionSummary
import co.datapipelines.web.anonymousActors
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The template twin of [PipelineLifecycleDialogModelTest]: the wrong-shape guard arms and
 * the in-use pre-reads, which the controller suite reaches only through mocks.
 */
class TemplateLifecycleDialogModelTest {
    private val templates = mockk<TemplateRepository>()
    private val usage = mockk<TemplateUsage>()

    private val model = TemplateLifecycleDialogModel(templates, usage, anonymousActors(), AuthoringGuard(enabled = true))

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
        // The draft is the ONLY version, so its purge takes the template with it: the entity rule (any version ever).
        every { usage.everPins(any(), any()) } returns
            pins(
                pipelines =
                    listOf(
                        TemplatePin(ID, "nyc/rollup", 3, RELEASED, "extract", 2),
                        TemplatePin(ID, "nyc/rollup", 4, RELEASED, "load", 2),
                    ),
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
        every { usage.liveVersionPins(any(), any(), any()) } returns
            pins(pipelines = listOf(TemplatePin(ID, "nyc/rollup", 3, RELEASED, "extract", 2)))
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

        val latest =
            mockk<co.datapipelines.templates.Template> {
                every { version } returns 3
            }
        every { templates.findLatest(any(), any()) } returns latest
        model.restore(WS, NAME, 2).movesPointer shouldBe false
    }

    @Test
    fun `purge entity - the sole-draft shape with no pins is the allowed branch`() {
        every { templates.listVersions(any(), any()) } returns
            listOf(TemplateVersionSummary(NAME, 1, Instant.now(), USER, DRAFT))
        every { usage.everPins(any(), any()) } returns pins()

        val dialog = model.purgeEntity(WS, NAME)
        dialog.refusal shouldBe null
        dialog.expected shouldBe NAME
    }

    private fun pins(
        pipelines: List<TemplatePin> = emptyList(),
        sets: List<co.datapipelines.parameters.ParameterSetPin> = emptyList(),
        visualizations: List<co.datapipelines.visualization.ArtifactPin> = emptyList(),
    ) = TemplateUsage.Pins(pipelines, sets, visualizations)

    private fun setPin(name: String) = co.datapipelines.parameters.ParameterSetPin(ID, name, "region", 1, RELEASED, 2)

    private fun vizPin(name: String) = co.datapipelines.visualization.ArtifactPin(ID, name, 1, DRAFT, 2)

    @Test
    fun `purge - a draft that leaves other versions asks the exact pin of its version, and a set or a visualization refuses it (320)`() {
        stubExists()
        every { templates.findVersionDetail(any(), any(), any()) } returns versionOf(DRAFT)
        every { templates.listVersions(any(), any()) } returns
            listOf(
                TemplateVersionSummary(NAME, 1, Instant.now(), USER, RELEASED),
                TemplateVersionSummary(NAME, 2, Instant.now(), USER, DRAFT),
            )
        every { usage.liveVersionPins(WS, NAME, 2) } returns
            pins(sets = listOf(setPin("acme/sales/regions")), visualizations = listOf(vizPin("acme/charts/revenue")))

        val dialog = model.purge(WS, NAME, 2)

        dialog.soleVersion shouldBe false
        dialog.inUseParameterSets shouldBe listOf("acme/sales/regions")
        dialog.inUseVisualizations shouldBe listOf("acme/charts/revenue")
        val refusal = checkNotNull(dialog.refusal)
        refusal.code shouldBe "template.in_use"
        refusal.message shouldBe
            "Version 2 is pinned by 1 parameter set(s): acme/sales/regions and 1 visualization(s): acme/charts/revenue" +
            " — repoint or discard them first."
    }

    @Test
    fun `discard - a release only a visualization or a set pins is refused, and the dialog lists them (320)`() {
        stubExists()
        every { templates.findVersionDetail(any(), any(), any()) } returns versionOf(RELEASED)
        every { usage.liveVersionPins(WS, NAME, 2) } returns
            pins(sets = listOf(setPin("acme/sales/regions")), visualizations = listOf(vizPin("acme/charts/revenue")))
        every { templates.findLatest(any(), any()) } returns mockk<co.datapipelines.templates.Template> { every { version } returns 2 }
        every { templates.listVersions(any(), any()) } returns listOf(TemplateVersionSummary(NAME, 2, Instant.now(), USER, RELEASED))

        val dialog = model.discard(WS, NAME, 2)

        dialog.pinnerPipelines shouldBe emptyList()
        dialog.pinnerParameterSets shouldBe listOf("acme/sales/regions")
        dialog.pinnerVisualizations shouldBe listOf("acme/charts/revenue")
    }

    @Test
    fun `purge entity - a template only a visualization pins is refused in the words of the guard (320)`() {
        every { templates.listVersions(any(), any()) } returns listOf(TemplateVersionSummary(NAME, 1, Instant.now(), USER, DRAFT))
        every { usage.everPins(WS, NAME) } returns pins(visualizations = listOf(vizPin("acme/charts/revenue")))

        val dialog = model.purgeEntity(WS, NAME)

        dialog.inUseVisualizations shouldBe listOf("acme/charts/revenue")
        val refusal = checkNotNull(dialog.refusal)
        refusal.code shouldBe "template.in_use"
        refusal.message shouldBe "Pinned by 1 visualization(s): acme/charts/revenue — a pinned template is never deleted."
    }

    private companion object {
        val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        const val NAME = "test/probe.sql"
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
