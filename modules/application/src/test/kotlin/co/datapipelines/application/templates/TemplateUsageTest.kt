package co.datapipelines.application.templates

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetPin
import co.datapipelines.parameters.ParameterSetTemplatePins
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.templates.TemplateUsageService
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
    private val usage = TemplateUsage(pipelines, sets)
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

        usage.inUseCounts(workspaceId, everything, templateId) shouldBe mapOf(1 to 3, 2 to 4, 3 to 1)
    }

    @Test
    fun `inUseCounts under a narrowing view re-derives the sets' counts from the admitted RELEASED pins, one per set`() {
        val setA = UUID.randomUUID()
        every { pipelines.inUseCounts(workspaceId, templateId) } returns mapOf(1 to 1)
        // The unlensed counts statement is still read and then discarded under a narrowing view.
        every { sets.countWorkingPinsByPinnedVersion(workspaceId, templateId) } returns mapOf(1 to 9, 2 to 9)
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
}
