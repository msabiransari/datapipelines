package co.datapipelines.application.templates

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetPin
import co.datapipelines.parameters.ParameterSetTemplatePins
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.templates.TemplateUsageService
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
 * The combined templates reverse arrow over mocked scans (the record's §8.4, #194 lane D): both
 * aggregates answer every question, the set arm is lensed like the pipeline arm (178b — a hidden
 * set never leaks, a narrowing view sees RELEASED rows only), and the counts re-derive under a
 * lens from the pinned rows. Module-level coverage of a composition the cascade E2E drives only
 * from `tests/integration-tests` (the 194d landing gate's koverVerify).
 */
class TemplateUsageTest {
    private val pipelines = mockk<TemplateUsageService>()
    private val sets = mockk<ParameterSetTemplatePins>()
    private val pipelineVersions = mockk<PipelineRepository>()
    private val visualizations = mockk<ArtifactDependents>()
    private val usage = TemplateUsage(pipelines, sets, pipelineVersions, visualizations)
    private val workspaceId = UUID.randomUUID()
    private val templateId = "acme/sales/states.sql"
    private val everything = LensedView.EVERYTHING
    private val onlyVisible =
        LensedView(ReadLens.Only(setOf("acme/p/visible")), ReadLens.Everything, parameterSets = ReadLens.Only(setOf("acme/s/visible")))

    private val visibleReleased = setPin("acme/s/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1)
    private val visibleDraft = setPin("acme/s/visible", PipelineVersionStatus.DRAFT, pinnedVersion = 2)
    private val hiddenReleased = setPin("acme/s/hidden", PipelineVersionStatus.RELEASED, pinnedVersion = 1)
    private val pipelinePin =
        TemplatePin(UUID.randomUUID(), "acme/p/visible", 3, PipelineVersionStatus.RELEASED, "n1", 1)

    private fun vizPin(
        name: String,
        status: PipelineVersionStatus,
        pinnedVersion: Int,
        artifactId: UUID = UUID.randomUUID(),
    ) = ArtifactPin(artifactId, name, 1, status, pinnedVersion)

    /** The visualization arm answers nothing — the cases that are not about it. */
    private fun noVisualizations() {
        every { visualizations.visualizationsPinningTemplate(any(), any(), any(), any()) } returns emptyList()
    }

    private fun setPin(
        name: String,
        status: PipelineVersionStatus,
        pinnedVersion: Int,
        setId: UUID = UUID.randomUUID(),
    ) = ParameterSetPin(setId, name, "state", 1, status, pinnedVersion)

    @Test
    fun `usedBy combines the pipeline answer with the sets' working-version pins - everything under the whole view`() {
        every { pipelines.usedBy(workspaceId, ReadLens.Everything, ReadLens.Everything, templateId, 1) } returns
            TemplateUsageService.UsedBy(templateId, 1, listOf(pipelinePin), 1)
        every { sets.workingVersionPins(workspaceId, templateId, 1) } returns listOf(visibleReleased, visibleDraft, hiddenReleased)
        noVisualizations()

        val combined = usage.usedBy(workspaceId, everything, templateId, 1)

        combined.templateId shouldBe templateId
        combined.version shouldBe 1
        combined.pipelineCount shouldBe 1
        combined.pipelineReferences shouldContainExactly listOf(pipelinePin)
        combined.parameterSetReferences shouldContainExactly listOf(visibleReleased, visibleDraft, hiddenReleased)
        combined.isEmpty() shouldBe false
    }

    @Test
    fun `usedBy under a narrowing view drops the hidden set and the draft - a set-only pin still counts as in use`() {
        every { pipelines.usedBy(workspaceId, onlyVisible.templates, onlyVisible.pipelines, templateId, 1) } returns
            TemplateUsageService.UsedBy(templateId, 1, emptyList(), 0)
        every { sets.workingVersionPins(workspaceId, templateId, 1) } returns listOf(visibleReleased, visibleDraft, hiddenReleased)
        noVisualizations()

        val combined = usage.usedBy(workspaceId, onlyVisible, templateId, 1)

        combined.pipelineReferences shouldBe emptyList()
        combined.parameterSetReferences shouldContainExactly listOf(visibleReleased)
        combined.isEmpty() shouldBe false
    }

    @Test
    fun `referencedAnywhere is the sets' any-version evidence, lensed the same way - and the pipeline arm is re-exposed`() {
        every { sets.anyVersionPins(workspaceId, templateId) } returns listOf(visibleReleased, visibleDraft, hiddenReleased)
        every { pipelines.referencedAnywhere(workspaceId, ReadLens.Everything, ReadLens.Everything, templateId) } returns
            listOf(pipelinePin)
        every { pipelines.referencedAnywhere(workspaceId, onlyVisible.templates, onlyVisible.pipelines, templateId) } returns emptyList()

        usage.referencedAnywhere(workspaceId, everything, templateId) shouldContainExactly
            listOf(visibleReleased, visibleDraft, hiddenReleased)
        usage.referencedAnywhere(workspaceId, onlyVisible, templateId) shouldContainExactly listOf(visibleReleased)
        usage.pipelinesReferencedAnywhere(workspaceId, everything, templateId) shouldContainExactly listOf(pipelinePin)
        usage.pipelinesReferencedAnywhere(workspaceId, onlyVisible, templateId) shouldBe emptyList()
    }

    @Test
    fun `inUseCounts adds the sets' counts to the pipelines' per pinned version under the whole view`() {
        every { pipelines.inUseCounts(workspaceId, templateId) } returns mapOf(1 to 2, 3 to 1)
        every { sets.countWorkingPinsByPinnedVersion(workspaceId, templateId) } returns mapOf(1 to 1, 2 to 4)
        noVisualizations()

        usage.inUseCounts(workspaceId, everything, templateId) shouldBe mapOf(1 to 3, 2 to 4, 3 to 1)
    }

    @Test
    fun `inUseCounts under a narrowing view re-derives the sets' counts from the admitted RELEASED pins, one per set`() {
        val setA = UUID.randomUUID()
        every { pipelines.inUseCounts(workspaceId, templateId) } returns mapOf(1 to 1)
        // #340: the pipeline arm re-derives from its rows under a narrowing view too — one admitted RELEASED pipeline.
        every { pipelineVersions.findWorkingVersionTemplatePins(workspaceId, templateId, 1) } returns listOf(pipelinePin)
        // The unlensed counts statement is still read and then discarded under a narrowing view.
        every { sets.countWorkingPinsByPinnedVersion(workspaceId, templateId) } returns mapOf(1 to 9, 2 to 9)
        noVisualizations()
        every { sets.workingVersionPins(workspaceId, templateId) } returns
            listOf(
                // two parameters of ONE set pin version 1 — one set, one count
                setPin("acme/s/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1, setId = setA),
                setPin("acme/s/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1, setId = setA),
                // a draft and a hidden set never count under the narrowing view
                setPin("acme/s/visible", PipelineVersionStatus.DRAFT, pinnedVersion = 2),
                setPin("acme/s/hidden", PipelineVersionStatus.RELEASED, pinnedVersion = 2),
            )

        usage.inUseCounts(workspaceId, onlyVisible, templateId) shouldBe mapOf(1 to 2)
    }

    @Test
    fun `inUseCounts under a narrowing view counts only the admitted RELEASED pipelines, one per pipeline - #340`() {
        val admitted = UUID.randomUUID()
        // The unlensed aggregate says 3 pipelines pin version 1 and 1 pins version 2; the lens admits one released one.
        every { pipelines.inUseCounts(workspaceId, templateId) } returns mapOf(1 to 3, 2 to 1)
        every { pipelineVersions.findWorkingVersionTemplatePins(workspaceId, templateId, 1) } returns
            listOf(
                // two nodes of ONE admitted pipeline — one pipeline, one count
                TemplatePin(admitted, "acme/p/visible", 3, PipelineVersionStatus.RELEASED, "n1", 1),
                TemplatePin(admitted, "acme/p/visible", 3, PipelineVersionStatus.RELEASED, "n2", 1),
                // a pipeline the lens hides never counts
                TemplatePin(UUID.randomUUID(), "acme/p/hidden", 1, PipelineVersionStatus.RELEASED, "n1", 1),
                // a DRAFT working version of an ADMITTED pipeline never reaches a lensed caller
                TemplatePin(UUID.randomUUID(), "acme/p/visible", 4, PipelineVersionStatus.DRAFT, "n1", 1),
            )
        every { pipelineVersions.findWorkingVersionTemplatePins(workspaceId, templateId, 2) } returns
            listOf(TemplatePin(UUID.randomUUID(), "acme/p/hidden", 1, PipelineVersionStatus.RELEASED, "n1", 2))
        every { sets.countWorkingPinsByPinnedVersion(workspaceId, templateId) } returns emptyMap()
        every { sets.workingVersionPins(workspaceId, templateId) } returns emptyList()
        noVisualizations()

        // version 2 has only a hidden pin, so it is absent (renders as zero), not a stale 1
        usage.inUseCounts(workspaceId, onlyVisible, templateId) shouldBe mapOf(1 to 1)
    }

    @Test
    fun `inUseCounts under the whole view keeps the aggregate and reads no pipeline rows - #340`() {
        every { pipelines.inUseCounts(workspaceId, templateId) } returns mapOf(1 to 2)
        every { sets.countWorkingPinsByPinnedVersion(workspaceId, templateId) } returns emptyMap()
        noVisualizations()

        usage.inUseCounts(workspaceId, everything, templateId) shouldBe mapOf(1 to 2)
        verify(exactly = 0) { pipelineVersions.findWorkingVersionTemplatePins(any(), any(), any()) }
    }

    @Test
    fun `usedBy reports the visualizations' working-version pins beside the pipelines' and the sets' - the third arm`() {
        every { pipelines.usedBy(workspaceId, ReadLens.Everything, ReadLens.Everything, templateId, 1) } returns
            TemplateUsageService.UsedBy(templateId, 1, emptyList(), 0)
        every { sets.workingVersionPins(workspaceId, templateId, 1) } returns emptyList()
        val draft = vizPin("acme/v/chart", PipelineVersionStatus.DRAFT, pinnedVersion = 1)
        every { visualizations.visualizationsPinningTemplate(workspaceId, templateId, 1, PinScope.WORKING) } returns listOf(draft)

        val combined = usage.usedBy(workspaceId, everything, templateId, 1)

        combined.visualizationReferences shouldContainExactly listOf(draft)
        combined.isEmpty() shouldBe false
    }

    @Test
    fun `usedBy under a narrowing visualization lens drops the hidden visualization and the draft`() {
        val view = LensedView(ReadLens.Everything, ReadLens.Everything, visualizations = ReadLens.Only(setOf("acme/v/visible")))
        every { pipelines.usedBy(workspaceId, view.templates, view.pipelines, templateId, 1) } returns
            TemplateUsageService.UsedBy(templateId, 1, emptyList(), 0)
        every { sets.workingVersionPins(workspaceId, templateId, 1) } returns emptyList()
        val shown = vizPin("acme/v/visible", PipelineVersionStatus.RELEASED, pinnedVersion = 1)
        every { visualizations.visualizationsPinningTemplate(workspaceId, templateId, 1, PinScope.WORKING) } returns
            listOf(
                shown,
                vizPin("acme/v/visible", PipelineVersionStatus.DRAFT, pinnedVersion = 1),
                vizPin("acme/v/hidden", PipelineVersionStatus.RELEASED, pinnedVersion = 1),
            )

        usage.usedBy(workspaceId, view, templateId, 1).visualizationReferences shouldContainExactly listOf(shown)
    }

    @Test
    fun `inUseCounts adds the visualizations' counts per pinned version, one per visualization`() {
        val chart = UUID.randomUUID()
        every { pipelines.inUseCounts(workspaceId, templateId) } returns mapOf(1 to 1)
        every { sets.countWorkingPinsByPinnedVersion(workspaceId, templateId) } returns emptyMap()
        every { visualizations.visualizationsPinningTemplate(workspaceId, templateId, null, PinScope.WORKING) } returns
            listOf(
                vizPin("acme/v/chart", PipelineVersionStatus.RELEASED, pinnedVersion = 1, artifactId = chart),
                vizPin("acme/v/chart", PipelineVersionStatus.RELEASED, pinnedVersion = 1, artifactId = chart),
                vizPin("acme/v/other", PipelineVersionStatus.DRAFT, pinnedVersion = 2),
            )

        usage.inUseCounts(workspaceId, everything, templateId) shouldBe mapOf(1 to 2, 2 to 1)
    }

    @Test
    fun `liveVersionPins asks all three aggregates for the exact pin under LIVE - unlensed`() {
        val viz = vizPin("acme/v/chart", PipelineVersionStatus.DRAFT, pinnedVersion = 3)
        every { pipelineVersions.findLiveVersionsPinningTemplateVersion(workspaceId, templateId, 3) } returns listOf(pipelinePin)
        every { sets.liveVersionPins(workspaceId, templateId, 3) } returns listOf(hiddenReleased)
        every { visualizations.visualizationsPinningTemplate(workspaceId, templateId, 3, PinScope.LIVE) } returns listOf(viz)

        val pins = usage.liveVersionPins(workspaceId, templateId, 3)

        pins.pipelines shouldContainExactly listOf(pipelinePin)
        pins.parameterSets shouldContainExactly listOf(hiddenReleased)
        pins.visualizations shouldContainExactly listOf(viz)
        pins.isEmpty() shouldBe false
    }

    @Test
    fun `everPins asks all three aggregates for ANY version under ANY - and is empty only when every arm is`() {
        every { pipelineVersions.findAnyVersionTemplatePins(workspaceId, templateId) } returns emptyList()
        every { sets.anyVersionPins(workspaceId, templateId) } returns emptyList()
        every { visualizations.visualizationsPinningTemplate(workspaceId, templateId, null, PinScope.ANY) } returns emptyList()
        usage.everPins(workspaceId, templateId).isEmpty() shouldBe true

        val viz = vizPin("acme/v/restorable", PipelineVersionStatus.DISCARDED, pinnedVersion = 1)
        every { visualizations.visualizationsPinningTemplate(workspaceId, templateId, null, PinScope.ANY) } returns listOf(viz)
        usage.everPins(workspaceId, templateId).let {
            it.visualizations shouldContainExactly listOf(viz)
            it.isEmpty() shouldBe false
        }
    }
}
