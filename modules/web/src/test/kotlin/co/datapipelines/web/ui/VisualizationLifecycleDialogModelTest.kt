package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.EvidenceVerdict
import co.datapipelines.visualization.ReleaseEvidence
import co.datapipelines.visualization.TestCase
import co.datapipelines.visualization.TransformBinding
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationOccurrence
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.visualization.VisualizationTests
import co.datapipelines.web.ui.VisualizationUiFixtures.bodyOf
import co.datapipelines.web.ui.VisualizationUiFixtures.detail
import co.datapipelines.web.ui.VisualizationUiFixtures.version
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * #399 — the visualization dialogs answer the question BEFORE the button (094 §B), from the SAME reads the
 * service's guards make: the release's refusals in the service's order (cases, the transform pin, then the
 * evidence gate — which is not consulted while no case exists), the ONE consent for a DRAFT pin, the pin guard's
 * pinners narrowed to the version, the discard's pointer fallback by the repository's D60 rule (the highest other
 * eligible version, a DRAFT only under the development posture), the restore's above-current-or-NULL move and the
 * entity purge's sole-draft rule.
 */
class VisualizationLifecycleDialogModelTest {
    private val workspaceId = UUID.randomUUID()
    private val vizId = UUID.randomUUID()
    private val visualizations = mockk<VisualizationService>()
    private val dashboards = mockk<DashboardService>()
    private var templateStatus: PipelineVersionStatus? = PipelineVersionStatus.RELEASED
    private var verdict: EvidenceVerdict = EvidenceVerdict.Pass
    private var evidenceCalls = 0

    private fun model(developmentPosture: Boolean = false) =
        VisualizationLifecycleDialogModel(
            visualizations = visualizations,
            dashboards = dashboards,
            templateStatuses = TemplateVersionStatuses { _, _, _ -> templateStatus },
            evidence = ReleaseEvidence { _, _ -> verdict.also { evidenceCalls++ } },
            actors = mockk<ActorNames>().also { every { it.lookup(any()) } returns emptyMap() },
            authoring = AuthoringGuard(developmentPosture),
        )

    private fun draftWith(
        cases: Int,
        pinned: Boolean,
    ) {
        val case = TestCase("c", emptyMap(), emptyList())
        val draftBody =
            bodyOf().copy(
                tests = VisualizationTests(List(cases) { case }),
                transform = if (pinned) TransformBinding(ArtifactRef("acme/templates/t", 3), mapOf("rows" to "rows")) else null,
                config = ArtifactJson.mapper.createObjectNode(),
            )
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, vizId) } returns
            version(vizId, workspaceId, 2, PipelineVersionStatus.DRAFT, currentVersion = 1, body = draftBody)
    }

    private fun history(
        current: Int?,
        vararg rows: Pair<Int, PipelineVersionStatus>,
    ) {
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, vizId) } returns
            version(vizId, workspaceId, rows.first().first, rows.first().second, currentVersion = current)
        every { visualizations.listVersions(workspaceId, ReadLens.Everything, vizId) } returns
            rows.map { (v, s) -> detail(vizId, v, s) }
        every { dashboards.pinnedBy(workspaceId, ReadLens.Everything, VisualizationUiFixtures.NAME) } returns emptyList()
    }

    // ------------------------------------------------------------------ release

    @Test
    fun `a draft with cases, a released pin and green evidence releases - the button renders, no consent`() {
        draftWith(cases = 2, pinned = true)

        val dlg = model().release(workspaceId, vizId)

        dlg.canRelease shouldBe true
        dlg.needsConsent shouldBe false
        dlg.pin?.label shouldBe "acme/templates/t@3"
        dlg.caseCount shouldBe 2
        evidenceCalls shouldBe 1
    }

    @Test
    fun `a DRAFT transform pin is the ONE consent's row, not a refusal`() {
        draftWith(cases = 1, pinned = true)
        templateStatus = PipelineVersionStatus.DRAFT

        val dlg = model().release(workspaceId, vizId)

        dlg.canRelease shouldBe true
        dlg.needsConsent shouldBe true
    }

    @Test
    fun `no cases refuses tests_missing first, a missing pin refuses next, and the evidence gate is not consulted`() {
        draftWith(cases = 0, pinned = true)
        templateStatus = null

        val dlg = model().release(workspaceId, vizId)

        dlg.refusals.map { it.code } shouldBe
            listOf(VisualizationErrorCodes.RELEASE_TESTS_MISSING, VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED)
        dlg.refusals[1].message shouldContain "MISSING"
        evidenceCalls shouldBe 0
    }

    @Test
    fun `a DISCARDED pin refuses, and a refused evidence verdict is listed with its own code`() {
        draftWith(cases = 1, pinned = true)
        templateStatus = PipelineVersionStatus.DISCARDED
        verdict = EvidenceVerdict.Refused("visualization.release.tests_red", "The latest run is RED.", emptyMap())

        val dlg = model().release(workspaceId, vizId)

        dlg.canRelease shouldBe false
        dlg.refusals.map { it.code } shouldBe
            listOf(VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED, "visualization.release.tests_red")
    }

    @Test
    fun `a visualization with no draft opens the release dialog refused not-draft`() {
        history(1, 1 to PipelineVersionStatus.RELEASED)

        val dlg = model().release(workspaceId, vizId)

        dlg.refusals.single().code shouldBe VisualizationErrorCodes.VERSION_NOT_DRAFT
        evidenceCalls shouldBe 0
    }

    // ------------------------------------------------------------------ purge

    @Test
    fun `purge refuses a non-draft, and lists only the dashboards pinning THIS version`() {
        history(1, 2 to PipelineVersionStatus.DRAFT, 1 to PipelineVersionStatus.RELEASED)
        every { dashboards.pinnedBy(workspaceId, ReadLens.Everything, VisualizationUiFixtures.NAME) } returns
            listOf("acme/dashboards/a@1", "acme/dashboards/b@4", "acme/dashboards/gone@2")
        every { dashboards.findVersionByName(workspaceId, ReadLens.Everything, "acme/dashboards/a", 1) } returns pinning(1)
        every { dashboards.findVersionByName(workspaceId, ReadLens.Everything, "acme/dashboards/b", 4) } returns pinning(2)
        every { dashboards.findVersionByName(workspaceId, ReadLens.Everything, "acme/dashboards/gone", 2) } returns null

        val dlg = model().purge(workspaceId, vizId, 2)

        // b pins v2; an unreadable pinner is kept (over-warn, the POST decides); a pins v1 only.
        dlg.pinnedBy shouldBe listOf("acme/dashboards/b@4", "acme/dashboards/gone@2")
        dlg.canPurge shouldBe false
        dlg.expected shouldBe "v2"
        dlg.takesEntity shouldBe false

        assertThrows<DatapipelinesException> { model().purge(workspaceId, vizId, 1) }.code shouldBe
            VisualizationErrorCodes.VERSION_NOT_DRAFT
        assertThrows<DatapipelinesException> { model().purge(workspaceId, vizId, 9) }.code shouldBe VisualizationErrorCodes.NOT_FOUND
    }

    private fun pinning(version: Int): ArtifactVersion<DashboardBody> {
        val occurrence =
            mockk<VisualizationOccurrence> { every { visualization } returns ArtifactRef(VisualizationUiFixtures.NAME, version) }
        return mockk { every { body.visualizations } returns listOf(occurrence) }
    }

    // ------------------------------------------------------------------ discard / restore

    @Test
    fun `discarding the current version names the repository's fallback - the highest other eligible version`() {
        history(3, 4 to PipelineVersionStatus.DRAFT, 3 to PipelineVersionStatus.RELEASED, 2 to PipelineVersionStatus.RELEASED)

        model(developmentPosture = false).discard(workspaceId, vizId, 3).let {
            it.isCurrent shouldBe true
            it.fallback shouldBe "v2 becomes the current version."
            it.canDiscard shouldBe true
        }
        // Under the development posture a DRAFT is eligible for the pointer (D60).
        model(developmentPosture = true).discard(workspaceId, vizId, 3).fallback shouldBe "v4 becomes the current version."
        model().discard(workspaceId, vizId, 2).fallback shouldBe null

        assertThrows<DatapipelinesException> { model().discard(workspaceId, vizId, 4) }.code shouldBe
            VisualizationErrorCodes.VERSION_NOT_RELEASED
    }

    @Test
    fun `discarding the only release says no eligible version remains`() {
        history(1, 1 to PipelineVersionStatus.RELEASED)

        model().discard(workspaceId, vizId, 1).fallback shouldContain "no eligible version remains"
    }

    @Test
    fun `restore moves the pointer only above current or onto a null pointer`() {
        history(2, 3 to PipelineVersionStatus.DISCARDED, 2 to PipelineVersionStatus.RELEASED, 1 to PipelineVersionStatus.DISCARDED)

        model().restore(workspaceId, vizId, 3).movesPointer shouldBe true
        model().restore(workspaceId, vizId, 1).movesPointer shouldBe false
        assertThrows<DatapipelinesException> { model().restore(workspaceId, vizId, 2) }.code shouldBe
            VisualizationErrorCodes.VERSION_NOT_DISCARDED

        history(null, 1 to PipelineVersionStatus.DISCARDED)
        model().restore(workspaceId, vizId, 1).movesPointer shouldBe true
    }

    // ------------------------------------------------------------------ purge entity / switch

    @Test
    fun `the entity purge is admitted only for a sole draft`() {
        history(null, 1 to PipelineVersionStatus.DRAFT)
        model().purgeEntity(workspaceId, vizId).let {
            it.canPurge shouldBe true
            it.expected shouldBe VisualizationUiFixtures.NAME
        }

        history(1, 2 to PipelineVersionStatus.DRAFT, 1 to PipelineVersionStatus.RELEASED)
        model().purgeEntity(workspaceId, vizId).refusal?.code shouldBe VisualizationErrorCodes.VERSION_LAST_RELEASE
    }

    @Test
    fun `switch lists every version newest first, the draft eligible only under the development posture`() {
        history(1, 2 to PipelineVersionStatus.DRAFT, 1 to PipelineVersionStatus.RELEASED, 3 to PipelineVersionStatus.DISCARDED)

        model().switch(workspaceId, vizId).options.map { Triple(it.version, it.isCurrent, it.eligible) } shouldBe
            listOf(Triple(3, false, false), Triple(2, false, false), Triple(1, true, true))
        model(developmentPosture = true)
            .switch(workspaceId, vizId)
            .options
            .single { it.version == 2 }
            .eligible shouldBe true
    }
}
