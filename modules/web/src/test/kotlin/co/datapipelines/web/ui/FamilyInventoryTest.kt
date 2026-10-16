package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
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
}
