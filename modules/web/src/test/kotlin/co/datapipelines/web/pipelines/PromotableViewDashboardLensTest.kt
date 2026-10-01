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
import co.datapipelines.visualization.VisualizationService
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
 * #10 L1c adds the arm the wire could not carry before: a dashboard the TARGET already holds at the same version and
 * hash is HIDDEN (§10.2's "newer than the target", finally load-bearing on the read).
 */
class PromotableViewDashboardLensTest {
    @Test
    fun `a dashboard is admitted only when every source pipeline is - and admits exactly the visualizations it pins`() {
        val view =
            view(
                pipelines = listOf("ops/pipelines/a", "ops/pipelines/b"),
                dashboards =
                    listOf(
                        Triple("ops/dashboards/visible", listOf("ops/pipelines/a", "ops/pipelines/b"), listOf("ops/visualizations/one")),
                        Triple("ops/dashboards/half", listOf("ops/pipelines/a", "ops/pipelines/hidden"), listOf("ops/visualizations/two")),
                        Triple("ops/dashboards/none", listOf("ops/pipelines/hidden"), listOf("ops/visualizations/one")),
                    ),
            )

        assertAll(
            { view.dashboardsLens shouldBe ReadLens.Only(setOf("ops/dashboards/visible")) },
            // `one` is pinned by the admitted dashboard; `two` only by a hidden one.
            { view.visualizationsLens shouldBe ReadLens.Only(setOf("ops/visualizations/one")) },
        )
    }

    @Test
    fun `a pipeline lens that admits nothing admits no dashboard and no visualization - 0 of N`() {
        val view =
            view(
                pipelines = emptyList(),
                dashboards = (1..5).map { Triple("ops/dashboards/d$it", listOf("ops/pipelines/p$it"), listOf("ops/visualizations/v$it")) },
            )

        view.dashboardsLens shouldBe ReadLens.Only(emptySet())
        view.visualizationsLens shouldBe ReadLens.Only(emptySet())
    }

    @Test
    fun `a dashboard the target holds at the same version and hash is HIDDEN - the newer-than-target arm`() {
        val name = "ops/dashboards/already_there"
        val view =
            view(
                pipelines = listOf("ops/pipelines/a"),
                dashboards = listOf(Triple(name, listOf("ops/pipelines/a"), listOf("ops/visualizations/one"))),
                dashboardsOnTarget = listOf(PromotionWire.Entry(name, 3, "d-hash-$name")),
            )

        assertAll(
            { view.dashboardsLens shouldBe ReadLens.Only(emptySet()) },
            // The pin of a hidden dashboard is not admitted either — the 404 rule holds through the arm.
            { view.visualizationsLens shouldBe ReadLens.Only(emptySet()) },
        )
    }

    @Test
    fun `a dashboard the target holds at a LOWER version stays admitted - the promotion is real`() {
        val name = "ops/dashboards/ahead"
        val view =
            view(
                pipelines = listOf("ops/pipelines/a"),
                dashboards = listOf(Triple(name, listOf("ops/pipelines/a"), listOf("ops/visualizations/one"))),
                dashboardsOnTarget = listOf(PromotionWire.Entry(name, 2, "older-$name")),
            )

        assertAll(
            { view.dashboardsLens shouldBe ReadLens.Only(setOf(name)) },
            { view.dashboards.single().targetVersion shouldBe 2 },
        )
    }

    @Test
    fun `the lensed view reads the current released dashboards and hands both arms to the promoter's view`() {
        val workspace = UUID.randomUUID()
        val pipelines = mockk<PipelineRepository>()
        val templates = mockk<TemplateRepository>()
        val client = mockk<PromotionTargetClient>()
        val sets = mockk<co.datapipelines.parameters.ParameterSetRepository>()
        val visualizations = mockk<VisualizationService>()
        val dashboards = mockk<DashboardService>()
        every { client.cachedInventory("ops") } returns
            PromotionTargetClient.CachedInventory.Present(PromotionWire.Inventory("uat", false, "ops"))
        every { pipelines.findCurrentVersions(workspace) } returns
            listOf(CurrentPipelineVersion(UUID.randomUUID(), "ops/pipelines/a", "A", 1, "h"))
        every { templates.findCurrentVersions(workspace) } returns emptyList()
        every { sets.findCurrentVersions(workspace) } returns emptyList()
        every { visualizations.currentVersions(workspace) } returns emptyList()
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

        val view =
            PromotableViews(pipelines, templates, client, sets, dashboards, visualizations, mockk(relaxed = true))
                .viewFor(promoter(workspace))

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

        val view =
            PromotableViews(mockk(), mockk(), client, mockk(), dashboards, mockk(), mockk(relaxed = true))
                .viewFor(promoter(UUID.randomUUID()))

        assertAll(
            { view.dashboards shouldBe ReadLens.NOTHING },
            { view.visualizations shouldBe ReadLens.NOTHING },
            { view.unavailable?.reason shouldBe "connect_refused" },
        )
        verify(exactly = 0) { dashboards.currentVersions(any()) }
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    /** The view over promotable [pipelines] (no target entries → all newer) and the given dashboards. */
    private fun view(
        pipelines: List<String>,
        dashboards: List<Triple<String, List<String>, List<String>>>,
        dashboardsOnTarget: List<PromotionWire.Entry> = emptyList(),
    ): PromotableView {
        val localPipelines = pipelines.map { CurrentPipelineVersion(UUID.randomUUID(), it, "P", 1, "p-hash-$it") }
        val localDashboards =
            dashboards.mapIndexed { index, (name, _, _) -> CurrentArtifactVersion(UUID.randomUUID(), name, "D", 3, "d-hash-$name") }
        val bodies = dashboards.associate { (name, sources, pins) -> name to dashboard(sources, pins) }
        val inventory =
            PromotionWire.Inventory(
                "uat",
                false,
                "ops",
                dashboards = dashboardsOnTarget,
            )
        return PromotableView.of(localPipelines, emptyList(), inventory, emptyList(), emptyList(), localDashboards, bodies)
    }

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
