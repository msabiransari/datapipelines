package co.datapipelines.web.pipelines

import co.datapipelines.application.lens.LensedView
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
        val shown = UUID.randomUUID()
        val hidden = UUID.randomUUID()
        // #330 — the lens reads the pins-and-sources projection (ONE statement), never the bodies;
        // and it reads it on FIRST USE of a dashboard arm, which this test's reads force.
        every { dashboards.findCurrentPinsAndSources(workspace) } returns
            listOf(
                projection(shown, "ops/dashboards/shown", 1, "h1", listOf("ops/pipelines/a"), listOf("ops/visualizations/shown")),
                projection(
                    hidden,
                    "ops/dashboards/hidden",
                    2,
                    "h2",
                    listOf("ops/pipelines/elsewhere"),
                    listOf("ops/visualizations/hidden"),
                ),
            )

        val view = PromotableViews(pipelines, templates, client, sets, dashboards, visualizations).viewFor(promoter(workspace))

        assertAll(
            { view.dashboards shouldBe ReadLens.Only(setOf("ops/dashboards/shown")) },
            { view.visualizations shouldBe ReadLens.Only(setOf("ops/visualizations/shown")) },
            { view.isLensed shouldBe true },
        )
    }

    @Test
    fun `a view that never reads a dashboard arm runs no dashboard read - the arms are lazy (#330)`() {
        val workspace = UUID.randomUUID()
        val pipelines = mockk<PipelineRepository>()
        val client = mockk<PromotionTargetClient>()
        val dashboards = mockk<DashboardService>()
        every { client.cachedInventory("ops") } returns
            PromotionTargetClient.CachedInventory.Present(PromotionWire.Inventory("uat", false, "ops"))
        every { pipelines.findCurrentVersions(workspace) } returns
            listOf(CurrentPipelineVersion(UUID.randomUUID(), "ops/pipelines/a", "A", 1, "h"))

        val view =
            PromotableViews(
                pipelines,
                mockk<TemplateRepository>().also { every { it.findCurrentVersions(workspace) } returns emptyList() },
                client,
                mockk<co.datapipelines.parameters.ParameterSetRepository>()
                    .also { every { it.findCurrentVersions(workspace) } returns emptyList() },
                dashboards,
                mockk<VisualizationService>(),
            ).viewFor(promoter(workspace))

        // The pipelines-shaped request: the pipeline lens only, so no dashboard statement may run.
        view.pipelines shouldBe ReadLens.Only(setOf("ops/pipelines/a"))
        verify(exactly = 0) { dashboards.findCurrentPinsAndSources(any()) }
        // A request that DOES touch the families derives both arms from the ONE projection read.
        every { dashboards.findCurrentPinsAndSources(workspace) } returns emptyList()
        view.dashboards shouldBe ReadLens.Only(emptySet())
        view.visualizations shouldBe ReadLens.Only(emptySet())
        verify(exactly = 1) { dashboards.findCurrentPinsAndSources(workspace) }
    }

    @Test
    fun `isLensed on a lazy-armed view asks the eager arms first - a narrowing pipeline arm never derives the dashboards`() {
        var derivations = 0
        val narrowed =
            LensedView.withLazyDashboardArms(
                pipelines = ReadLens.Only(setOf("ops/pipelines/a")),
                templates = ReadLens.Everything,
                parameterSets = ReadLens.Everything,
            ) {
                derivations++
                ReadLens.Only(emptySet<String>()) to ReadLens.Only(emptySet<String>())
            }

        narrowed.isLensed shouldBe true
        derivations shouldBe 0

        // Only a view whose three eager arms are all everything pays for the derivation — once.
        val wide =
            LensedView.withLazyDashboardArms(ReadLens.Everything, ReadLens.Everything, ReadLens.Everything) {
                derivations++
                ReadLens.Only(setOf("ops/visualizations/one")) to ReadLens.Only(setOf("ops/dashboards/one"))
            }
        wide.isLensed shouldBe true
        wide.isLensed shouldBe true
        derivations shouldBe 1
    }

    @Test
    fun `fail closed - an unreachable target admits NOTHING on the dashboard and visualization arms too`() {
        val client = mockk<PromotionTargetClient>()
        val dashboards = mockk<DashboardService>()
        every { client.cachedInventory("ops") } returns PromotionTargetClient.CachedInventory.Unreachable("connect_refused", "x")
        every { client.targetBaseUrl } returns "https://uat.example.test"

        val view = PromotableViews(mockk(), mockk(), client, mockk(), dashboards, mockk()).viewFor(promoter(UUID.randomUUID()))

        assertAll(
            { view.dashboards shouldBe ReadLens.NOTHING },
            { view.visualizations shouldBe ReadLens.NOTHING },
            { view.unavailable?.reason shouldBe "connect_refused" },
        )
        verify(exactly = 0) { dashboards.findCurrentPinsAndSources(any()) }
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    /** The view over promotable [pipelines] (no target entries → all newer) and the given dashboards. */
    private fun view(
        pipelines: List<String>,
        dashboards: List<Triple<String, List<String>, List<String>>>,
        dashboardsOnTarget: List<PromotionWire.Entry> = emptyList(),
    ): PromotableView {
        val localPipelines = pipelines.map { CurrentPipelineVersion(UUID.randomUUID(), it, "P", 1, "p-hash-$it") }
        val currentDashboards =
            dashboards.mapIndexed { index, (name, sources, pins) ->
                projection(UUID.randomUUID(), name, 3, "d-hash-$name", sources, pins)
            }
        val inventory =
            PromotionWire.Inventory(
                "uat",
                false,
                "ops",
                dashboards = dashboardsOnTarget,
            )
        return PromotableView.of(localPipelines, emptyList(), inventory, emptyList(), emptyList(), currentDashboards)
    }

    /** The #330 projection row: the identity plus the two name lists, no body. */
    private fun projection(
        id: UUID,
        name: String,
        version: Int,
        bodyHash: String,
        sources: List<String>,
        pins: List<String>,
    ) = co.datapipelines.visualization.DashboardCurrentPins(id, name, "D", version, bodyHash, sources, pins)

    private fun promoter(workspace: UUID) =
        AuthenticatedPrincipal(
            UUID.randomUUID(),
            "p@e.test",
            "P",
            AuthMethod.OIDC,
            workspace = WorkspaceContext(workspace, "ops", role = WorkspaceRole.PROMOTER),
        )
}
