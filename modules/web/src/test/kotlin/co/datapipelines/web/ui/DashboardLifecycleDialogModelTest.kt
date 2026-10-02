package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.ParameterSetFact
import co.datapipelines.visualization.ParameterSetFacts
import co.datapipelines.visualization.PinnedVisualization
import co.datapipelines.visualization.PipelineReleaseFact
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationOccurrence
import co.datapipelines.visualization.VisualizationPins
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The dashboard lifecycle dialogs' MODEL (#400) — the 094 §B discipline at the unit seam: the
 * release dialog's pin classification is the SAME evidence the service's guard reads (the
 * three ports the validator runs), so the dialog and the POST cannot disagree. The D61 rule:
 * a DRAFT VISUALIZATION pin is the one cascadable row (the consent's offer); a DRAFT set, a
 * non-RELEASED source, and anything MISSING or DISCARDED are blocking rows — the dialog
 * opens, says so, and no button exists. No draft: a refusal with no pins at all.
 */
class DashboardLifecycleDialogModelTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()

    private val dashboards = mockk<DashboardService>()
    private val pipelines = mockk<PipelineReleaseFacts>()
    private val sets = mockk<ParameterSetFacts>()
    private val pins = mockk<VisualizationPins>()
    private val model =
        DashboardLifecycleDialogModel(
            dashboards,
            pipelines,
            sets,
            pins,
            mockk<ActorNames>().also { every { it.lookup(any()) } returns emptyMap() },
            mockk<co.datapipelines.pipeline.AuthoringGuard>().also { every { it.developmentPosture } returns false },
        )

    private fun stubWorking(body: DashboardBody) {
        val v = mockk<ArtifactVersion<DashboardBody>>(relaxed = true)
        every { v.detail.status } returns PipelineVersionStatus.DRAFT
        every { v.detail.version } returns 2
        every { v.detail.updatedBy } returns null
        every { v.detail.updatedAt } returns null
        every { v.detail.createdAt } returns java.time.Instant.parse("2026-10-01T09:00:00Z")
        every { v.record.name } returns "finance/dashboards/revenue"
        every { v.body } returns body
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns v
    }

    private fun body(
        source: ArtifactRef? = null,
        set: ArtifactRef? = null,
        visualization: ArtifactRef? = null,
    ): DashboardBody {
        val b = mockk<DashboardBody>()
        every { b.sources } returns listOfNotNull(source?.let { DashboardSource("s1", it) })
        every { b.parameterSet } returns set
        every { b.visualizations } returns
            listOfNotNull(
                visualization?.let {
                    VisualizationOccurrence("v1", DashboardObjectType.VISUALIZATION, it)
                },
            )
        return b
    }

    private fun stubPins(
        source: ArtifactRef?,
        sourceStatus: PipelineVersionStatus?,
        set: ArtifactRef?,
        setStatus: PipelineVersionStatus?,
        viz: ArtifactRef?,
        vizStatus: PipelineVersionStatus?,
    ) {
        source?.let {
            every { pipelines.releaseOf(workspaceId, it) } returns
                sourceStatus?.let { status ->
                    PipelineReleaseFact(status, readOnly = true, parameters = emptyList(), outputColumns = null)
                }
        }
        set?.let {
            every { sets.setOf(workspaceId, it) } returns
                setStatus?.let { status -> ParameterSetFact(status, parameters = emptyList()) }
        }
        viz?.let {
            every { pins.pinOf(workspaceId, it) } returns
                vizStatus?.let { status -> PinnedVisualization(status, mockk<VisualizationBody>()) }
        }
    }

    @Test
    fun `a DRAFT visualization pin is the one cascadable row - the consent's offer`() {
        val source = ArtifactRef("finance/pipelines/revenue", 3)
        val set = ArtifactRef("finance/sets/filters", 2)
        val viz = ArtifactRef("finance/visualizations/cells", 1)
        stubWorking(body(source, set, viz))
        stubPins(source, PipelineVersionStatus.RELEASED, set, PipelineVersionStatus.RELEASED, viz, PipelineVersionStatus.DRAFT)

        val dialog = model.release(workspaceId, dashboardId)

        dialog.refusal shouldBe null
        dialog.blockingPins shouldBe emptyList()
        dialog.draftPins.map { it.label } shouldBe listOf("finance/visualizations/cells@1")
        dialog.otherPins.map { it.label } shouldBe listOf("finance/pipelines/revenue@3", "finance/sets/filters@2")
    }

    @Test
    fun `a non-RELEASED set or source is a blocking pin - no cascade exists for them`() {
        val source = ArtifactRef("finance/pipelines/revenue", 3)
        val set = ArtifactRef("finance/sets/filters", 2)
        val viz = ArtifactRef("finance/visualizations/cells", 1)
        stubWorking(body(source, set, viz))
        stubPins(source, PipelineVersionStatus.DRAFT, set, PipelineVersionStatus.DRAFT, viz, PipelineVersionStatus.RELEASED)

        val dialog = model.release(workspaceId, dashboardId)

        dialog.draftPins shouldBe emptyList()
        dialog.blockingPins.map { it.label } shouldBe listOf("finance/pipelines/revenue@3", "finance/sets/filters@2")
        dialog.blockingPins.forEach { it.blocksRelease shouldBe true }
    }

    @Test
    fun `a MISSING pin is blocking - named MISSING, never silently dropped`() {
        val viz = ArtifactRef("finance/visualizations/gone", 4)
        stubWorking(body(visualization = viz))
        stubPins(null, null, null, null, viz, null)

        val dialog = model.release(workspaceId, dashboardId)

        dialog.blockingPins.single().statusLabel shouldBe "MISSING"
    }

    @Test
    fun `no draft - the refusal branch, no pins, no button`() {
        val released = mockk<ArtifactVersion<DashboardBody>>(relaxed = true)
        every { released.detail.status } returns PipelineVersionStatus.RELEASED
        every { released.detail.version } returns 1
        every { released.record.name } returns "finance/dashboards/revenue"
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns released

        val dialog = model.release(workspaceId, dashboardId)

        dialog.refusal?.code shouldBe "dashboard.version.not_draft"
        dialog.pins shouldBe emptyList()
        dialog.blockingPins shouldBe emptyList()
    }
}
