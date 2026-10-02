package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The dashboard workspace model's resolution table (#400), in [PipelineWorkspaceModel]'s
 * shape: the visibility oracle first ([DashboardService.findWorking] — absent, foreign or
 * lens-hidden is the family's 404, all three the SAME answer so none can be probed); an
 * explicit version through [DashboardService.findServedVersion] or the family's 404 NAMING
 * the version (never a clamp, never a nearby miss); the default is the served release, and
 * `null` there is NOT an error — it is the choose-a-version state (#409). The draft pointer
 * is lens metadata: the everything view's DRAFT working version names it; a narrowing lens's
 * admitted row does not.
 */
class DashboardWorkspaceModelTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()

    private val dashboards = mockk<DashboardService>()
    private val model = DashboardWorkspaceModel(dashboards)

    private fun version(
        status: PipelineVersionStatus,
        number: Int,
    ): ArtifactVersion<DashboardBody> {
        val v = mockk<ArtifactVersion<DashboardBody>>(relaxed = true)
        every { v.detail.status } returns status
        every { v.detail.version } returns number
        return v
    }

    private fun detail(
        status: PipelineVersionStatus,
        number: Int,
    ): co.datapipelines.visualization.ArtifactVersionDetail =
        co.datapipelines.visualization.ArtifactVersionDetail(
            artifactId = dashboardId,
            version = number,
            status = status,
            bodyHash = "hash",
            createdAt = java.time.Instant.parse("2026-10-01T09:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    @Test
    fun `an explicit version resolves through findServedVersion or the 404 names the version`() {
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns version(PipelineVersionStatus.DRAFT, 2)
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2) } returns
            version(PipelineVersionStatus.DRAFT, 2)
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(PipelineVersionStatus.DRAFT, 2))

        val resolved = model.resolve(workspaceId, LensedView.EVERYTHING, dashboardId, 2)

        resolved.selectedVersion shouldBe 2
        resolved.selectedIsDraft shouldBe true
        resolved.draftVersion shouldBe 2

        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 9) } returns null
        val thrown =
            assertThrows<co.datapipelines.typesystem.DatapipelinesException> {
                model.resolve(workspaceId, LensedView.EVERYTHING, dashboardId, 9)
            }
        thrown.code shouldBe "dashboard.not_found"
        thrown.details["version"] shouldBe "9"
        thrown.message shouldBe "Dashboard '$dashboardId' version 9 not found."
    }

    @Test
    fun `the default is the served release and a hidden current never resolves`() {
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns version(PipelineVersionStatus.RELEASED, 1)
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns version(PipelineVersionStatus.RELEASED, 1)
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(PipelineVersionStatus.RELEASED, 1), detail(PipelineVersionStatus.DRAFT, 2))

        val resolved = model.resolve(workspaceId, LensedView.EVERYTHING, dashboardId, null)

        resolved.selectedVersion shouldBe 1
        resolved.served?.version shouldBe 1
        resolved.selectedIsCurrent shouldBe true
        resolved.viewedLabel shouldBe "v1 · released · current"
    }

    @Test
    fun `#409 - no served release is the choose-a-version state, never an error`() {
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns version(PipelineVersionStatus.DRAFT, 1)
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(PipelineVersionStatus.DRAFT, 1))

        val resolved = model.resolve(workspaceId, LensedView.EVERYTHING, dashboardId, null)

        resolved.hasSelected shouldBe false
        resolved.selectedVersion shouldBe null
        resolved.draftVersion shouldBe 1
        resolved.versions.size shouldBe 1
    }

    @Test
    fun `an absent, foreign or lens-hidden dashboard is the family 404 - one answer for all three`() {
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns null
        val thrown =
            assertThrows<co.datapipelines.typesystem.DatapipelinesException> {
                model.resolve(workspaceId, LensedView.EVERYTHING, dashboardId, null)
            }
        thrown.code shouldBe "dashboard.not_found"
        thrown.message shouldBe "Dashboard '$dashboardId' not found."
        thrown.details["dashboard_id"] shouldBe dashboardId.toString()
        // The named-version probe runs BEFORE the record is read: the same 404 shape.
        verify(exactly = 0) { dashboards.findServed(any(), any(), any()) }
    }

    @Test
    fun `parseRequestedVersion admits a positive integer and refuses everything else`() {
        DashboardWorkspaceModel.parseRequestedVersion(null) shouldBe null
        DashboardWorkspaceModel.parseRequestedVersion("") shouldBe null
        DashboardWorkspaceModel.parseRequestedVersion("3") shouldBe 3
        assertThrows<ResponseStatusException> { DashboardWorkspaceModel.parseRequestedVersion("two") }
        assertThrows<ResponseStatusException> { DashboardWorkspaceModel.parseRequestedVersion("0") }
        assertThrows<ResponseStatusException> { DashboardWorkspaceModel.parseRequestedVersion("-1") }
        assertThrows<ResponseStatusException> { DashboardWorkspaceModel.parseRequestedVersion("1e3") }
    }
}
