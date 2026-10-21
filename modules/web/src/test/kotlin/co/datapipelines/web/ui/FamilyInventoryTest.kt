package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Instant
import java.util.UUID

class FamilyInventoryTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val dashboards = mockk<DashboardRepository>()
    private val visualizations = mockk<VisualizationRepository>()
    private val sets = mockk<ParameterSetRepository>()
    private val service = FamilyInventory(pipelines, templates, dashboards, visualizations, sets)
    private val workspace = UUID.randomUUID()

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `repository read failures are unavailable and never zeros`(family: NavigationFamily) {
        val failure = DataAccessResourceFailureException("read failed")
        every { pipelines.inventory(workspace, any()) } throws failure
        every { templates.inventory(workspace, any()) } throws failure
        every { dashboards.inventory(workspace, any()) } throws failure
        every { visualizations.inventory(workspace, any()) } throws failure
        every { sets.inventory(workspace, any()) } throws failure
        service.forFamily(family, workspace, LensedView.EVERYTHING) shouldBe InventoryResult.Unavailable("Inventory could not be read.")
    }

    @Test
    fun `each adapter receives its own lens with the supplied workspace`() {
        val p = ReadLens.Only(setOf("pipeline"))
        val t = ReadLens.Only(setOf("template"))
        val d = ReadLens.Only(setOf("dashboard"))
        val v = ReadLens.Only(setOf("visualization"))
        val s = ReadLens.Only(setOf("set"))
        val counts = Inventory(0, 1, 0, 0)
        every { pipelines.inventory(workspace, p) } returns counts
        every { templates.inventory(workspace, t) } returns counts
        every { dashboards.inventory(workspace, d) } returns counts
        every { visualizations.inventory(workspace, v) } returns counts
        every { sets.inventory(workspace, s) } returns counts
        val view = LensedView(p, t, parameterSets = s, visualizations = v, dashboards = d)
        NavigationFamily.entries.forEach { service.forFamily(it, workspace, view) shouldBe InventoryResult.Available(counts) }
        verify(exactly = 1) {
            pipelines.inventory(workspace, p)
            templates.inventory(workspace, t)
            dashboards.inventory(workspace, d)
            visualizations.inventory(workspace, v)
            sets.inventory(workspace, s)
        }
    }

    @Test
    fun `unavailable view short circuits every repository`() {
        val view = LensedView(ReadLens.Everything, ReadLens.Everything, LensedView.Unavailable("target", "unreachable"))
        NavigationFamily.entries.forEach { service.forFamily(it, workspace, view) shouldBe InventoryResult.Unavailable("unreachable") }
        verify { listOf(pipelines, templates, dashboards, visualizations, sets) wasNot Called }
    }

    @Test
    fun `lazy lens failures are unavailable and unrelated families do not derive dashboard arms`() {
        val view =
            LensedView.withLazyDashboardArms(
                ReadLens.Everything,
                ReadLens.Everything,
                ReadLens.Everything,
            ) { error("lens read failed") }
        every { pipelines.inventory(workspace, ReadLens.Everything) } returns Inventory(0, 0, 0, 0)
        service.forFamily(NavigationFamily.PIPELINES, workspace, view) shouldBe InventoryResult.Available(Inventory(0, 0, 0, 0))
        service.forFamily(NavigationFamily.DASHBOARDS, workspace, view) shouldBe InventoryResult.Unavailable("Inventory could not be read.")
        service.forFamily(NavigationFamily.VISUALIZATIONS, workspace, view) shouldBe
            InventoryResult.Unavailable("Inventory could not be read.")
        verify { listOf(dashboards, visualizations) wasNot Called }
    }

    /**
     * #598a — the chart group keeps forFamily's rules: the caller's own lens, fail closed, never zeros.
     * #598b — the request's clock and the configured window reach the repository unchanged.
     * #599 — every family reads ITS repository through ITS arm of the view: five distinct lenses, so a
     * family answered through another family's arm (or another family's repository) misses the stub and fails.
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `each family's charts read its own repository with its own lens, once`(family: NavigationFamily) {
        val empty = ChartInventory.empty(NOW, ACTIVE_DAYS)
        every { pipelines.charts(workspace, P, NOW, ACTIVE_DAYS) } returns empty
        every { templates.charts(workspace, T, NOW, ACTIVE_DAYS) } returns empty
        every { dashboards.charts(workspace, D, NOW, ACTIVE_DAYS) } returns empty
        every { visualizations.charts(workspace, V, NOW, ACTIVE_DAYS) } returns empty
        every { sets.charts(workspace, S, NOW, ACTIVE_DAYS) } returns empty

        service.chartsFor(family, workspace, FIVE_LENSES, NOW, ACTIVE_DAYS) shouldBe ChartsResult.Available(empty)

        val (read, lens) = repositoryAndLens(family)
        when (read) {
            is PipelineRepository -> verify(exactly = 1) { read.charts(workspace, lens, NOW, ACTIVE_DAYS) }
            is TemplateRepository -> verify(exactly = 1) { read.charts(workspace, lens, NOW, ACTIVE_DAYS) }
            is DashboardRepository -> verify(exactly = 1) { read.charts(workspace, lens, NOW, ACTIVE_DAYS) }
            is VisualizationRepository -> verify(exactly = 1) { read.charts(workspace, lens, NOW, ACTIVE_DAYS) }
            is ParameterSetRepository -> verify(exactly = 1) { read.charts(workspace, lens, NOW, ACTIVE_DAYS) }
        }
        verify { listOf(pipelines, templates, dashboards, visualizations, sets).filter { it !== read } wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `a chart read failure is unavailable and never zeros`(family: NavigationFamily) {
        val failure = DataAccessResourceFailureException("read failed")
        every { pipelines.charts(workspace, any(), any(), any()) } throws failure
        every { templates.charts(workspace, any(), any(), any()) } throws failure
        every { dashboards.charts(workspace, any(), any(), any()) } throws failure
        every { visualizations.charts(workspace, any(), any(), any()) } throws failure
        every { sets.charts(workspace, any(), any(), any()) } throws failure

        service.chartsFor(family, workspace, LensedView.EVERYTHING, NOW, ACTIVE_DAYS) shouldBe
            ChartsResult.Unavailable("Inventory could not be read.")
    }

    @Test
    fun `an unavailable view reads no family's charts`() {
        val unavailable = LensedView(ReadLens.Everything, ReadLens.Everything, LensedView.Unavailable("target", "unreachable"))
        NavigationFamily.entries.forEach {
            service.chartsFor(it, workspace, unavailable, NOW, ACTIVE_DAYS) shouldBe ChartsResult.Unavailable("unreachable")
        }
        verify { listOf(pipelines, templates, dashboards, visualizations, sets) wasNot Called }
    }

    /** #330's lazy dashboard arms: a failed derivation is unavailable for the two artifact families, and Templates never derives them. */
    @Test
    fun `a lazy lens failure makes the artifact charts unavailable and other families do not derive the arms`() {
        var derived = 0
        val view =
            LensedView.withLazyDashboardArms(ReadLens.Everything, ReadLens.Everything, ReadLens.Everything) {
                derived++
                error("lens read failed")
            }
        val empty = ChartInventory.empty(NOW, ACTIVE_DAYS)
        every { templates.charts(workspace, ReadLens.Everything, NOW, ACTIVE_DAYS) } returns empty

        service.chartsFor(NavigationFamily.TEMPLATES, workspace, view, NOW, ACTIVE_DAYS) shouldBe ChartsResult.Available(empty)
        derived shouldBe 0
        listOf(NavigationFamily.DASHBOARDS, NavigationFamily.VISUALIZATIONS).forEach {
            service.chartsFor(it, workspace, view, NOW, ACTIVE_DAYS) shouldBe ChartsResult.Unavailable("Inventory could not be read.")
        }
        verify { listOf(dashboards, visualizations) wasNot Called }
    }

    private fun repositoryAndLens(family: NavigationFamily): Pair<Any, ReadLens> =
        when (family) {
            NavigationFamily.PIPELINES -> pipelines to P
            NavigationFamily.TEMPLATES -> templates to T
            NavigationFamily.DASHBOARDS -> dashboards to D
            NavigationFamily.VISUALIZATIONS -> visualizations to V
            NavigationFamily.PARAMETER_SETS -> sets to S
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-14T12:00:00Z")
        const val ACTIVE_DAYS = 7
        val P = ReadLens.Only(setOf("pipeline"))
        val T = ReadLens.Only(setOf("template"))
        val D = ReadLens.Only(setOf("dashboard"))
        val V = ReadLens.Only(setOf("visualization"))
        val S = ReadLens.Only(setOf("set"))

        /** Five distinct arms, one per family: a family read through another's arm misses its stub. */
        val FIVE_LENSES = LensedView(P, T, parameterSets = S, visualizations = V, dashboards = D)
    }
}
