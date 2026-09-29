package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.CurrentArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.VisualizationOccurrence
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The dashboard and visualization arms of the promoter lens (#10 L1b, D50): a promoter sees a RELEASED dashboard only
 * when her pipeline lens admits EVERY pipeline it sources, and a visualization only when such a dashboard pins it —
 * so her dashboards can never outrun her pipelines. The fail-closed branch admits nothing on either arm.
 */
class PromotableViewDashboardLensTest {
    @Test
    fun `a dashboard is admitted only when every source pipeline is - and admits exactly the visualizations it pins`() {
        val lenses =
            PromotableView.dashboardLenses(
                listOf(
                    "ops/dashboards/visible" to
                        dashboard(listOf("ops/pipelines/a", "ops/pipelines/b"), listOf("ops/visualizations/one")),
                    "ops/dashboards/half" to
                        dashboard(listOf("ops/pipelines/a", "ops/pipelines/hidden"), listOf("ops/visualizations/two")),
                    "ops/dashboards/none" to dashboard(listOf("ops/pipelines/hidden"), listOf("ops/visualizations/one")),
                ),
                ReadLens.Only(setOf("ops/pipelines/a", "ops/pipelines/b")),
            )

        assertAll(
            { lenses.dashboards shouldBe ReadLens.Only(setOf("ops/dashboards/visible")) },
            // `one` is pinned by the admitted dashboard; `two` only by a hidden one.
            { lenses.visualizations shouldBe ReadLens.Only(setOf("ops/visualizations/one")) },
        )
    }

    @Test
    fun `a pipeline lens that admits nothing admits no dashboard and no visualization - 0 of N`() {
        val lenses =
            PromotableView.dashboardLenses(
                (1..5).map { "ops/dashboards/d$it" to dashboard(listOf("ops/pipelines/p$it"), listOf("ops/visualizations/v$it")) },
                ReadLens.NOTHING,
            )

        lenses.dashboards shouldBe ReadLens.Only(emptySet())
        lenses.visualizations shouldBe ReadLens.Only(emptySet())
    }

    @Test
    fun `the lensed view reads the current released dashboards and hands both arms to the promoter's view`() {
        val workspace = UUID.randomUUID()
        val pipelines = mockk<PipelineRepository>()
        val templates = mockk<TemplateRepository>()
        val client = mockk<PromotionTargetClient>()
        val sets = mockk<co.datapipelines.parameters.ParameterSetRepository>()
        val dashboards = mockk<DashboardService>()
        every { client.cachedInventory("ops") } returns
            PromotionTargetClient.CachedInventory.Present(PromotionWire.Inventory("uat", false, "ops"))
        every { pipelines.findCurrentVersions(workspace) } returns
            listOf(CurrentPipelineVersion(UUID.randomUUID(), "ops/pipelines/a", "A", 1, "h"))
        every { templates.findCurrentVersions(workspace) } returns emptyList()
        every { sets.findCurrentVersions(workspace) } returns emptyList()
        val shown = UUID.randomUUID()
        val hidden = UUID.randomUUID()
        every { dashboards.currentVersions(workspace) } returns
            listOf(
                CurrentArtifactVersion(shown, "ops/dashboards/shown", "Shown", 1, "h1"),
                CurrentArtifactVersion(hidden, "ops/dashboards/hidden", "Hidden", 2, "h2"),
            )
        every { dashboards.findVersion(workspace, ReadLens.Everything, shown, 1) } returns
            released(shown, "ops/dashboards/shown", dashboard(listOf("ops/pipelines/a"), listOf("ops/visualizations/shown")))
        every { dashboards.findVersion(workspace, ReadLens.Everything, hidden, 2) } returns
            released(hidden, "ops/dashboards/hidden", dashboard(listOf("ops/pipelines/elsewhere"), listOf("ops/visualizations/hidden")))

        val view = PromotableViews(pipelines, templates, client, sets, dashboards).viewFor(promoter(workspace))

        assertAll(
            { view.dashboards shouldBe ReadLens.Only(setOf("ops/dashboards/shown")) },
            { view.visualizations shouldBe ReadLens.Only(setOf("ops/visualizations/shown")) },
            { view.isLensed shouldBe true },
        )
    }

    @Test
    fun `fail closed - an unreachable target admits NOTHING on the dashboard and visualization arms too`() {
        val client = mockk<PromotionTargetClient>()
        val dashboards = mockk<DashboardService>()
        every { client.cachedInventory("ops") } returns PromotionTargetClient.CachedInventory.Unreachable("connect_refused", "x")
        every { client.targetBaseUrl } returns "https://uat.example.test"

        val view = PromotableViews(mockk(), mockk(), client, mockk(), dashboards).viewFor(promoter(UUID.randomUUID()))

        assertAll(
            { view.dashboards shouldBe ReadLens.NOTHING },
            { view.visualizations shouldBe ReadLens.NOTHING },
            { view.unavailable?.reason shouldBe "connect_refused" },
        )
        verify(exactly = 0) { dashboards.currentVersions(any()) }
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun promoter(workspace: UUID) =
        AuthenticatedPrincipal(
            UUID.randomUUID(),
            "p@e.test",
            "P",
            AuthMethod.OIDC,
            workspace = WorkspaceContext(workspace, "ops", role = WorkspaceRole.PROMOTER),
        )

    private fun dashboard(
        pipelines: List<String>,
        visualizations: List<String>,
    ) = DashboardBody(
        displayName = "D",
        sources = pipelines.mapIndexed { index, name -> DashboardSource("s$index", ArtifactRef(name, 1)) },
        visualizations =
            visualizations.mapIndexed { index, name ->
                VisualizationOccurrence("v$index", DashboardObjectType.VISUALIZATION, ArtifactRef(name, 1))
            },
        layout = DashboardLayout(),
    )

    private fun released(
        id: UUID,
        name: String,
        body: DashboardBody,
    ): ArtifactVersion<DashboardBody> {
        val at = Instant.parse("2026-09-29T00:00:00Z")
        val user = UUID.randomUUID()
        return ArtifactVersion(
            ArtifactRecord(id, UUID.randomUUID(), name, "D", "", 1, at, at, user),
            ArtifactVersionDetail(id, 1, PipelineVersionStatus.RELEASED, "h", at, user),
            body,
        )
    }
}
