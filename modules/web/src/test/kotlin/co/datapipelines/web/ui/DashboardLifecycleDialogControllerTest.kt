package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardReleased
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PointerMove
import co.datapipelines.visualization.Purged
import co.datapipelines.visualization.Switched
import co.datapipelines.visualization.VersionMoved
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * The dashboard lifecycle dialogs' controller (#400), at the unit seam the pipeline dialogs'
 * controller test established: every POST calls the SAME service the REST routes wire (the
 * hash and the guard re-run there — the dialog only decides what to SAY), every golden path
 * answers `HX-Redirect` onto the workspace's Versions tab with the flash code (or the catalog
 * when the row is gone), the release is audited exactly as the REST route audits it (#332:
 * each cascaded visualization's own event first, then the dashboard's own), and the typed
 * confirm is checked BEFORE the service — a mismatched dialog can never purge anything.
 * Session-only: an API key is refused `auth.session.required` before anything is read.
 */
class DashboardLifecycleDialogControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()

    private val dashboards = mockk<DashboardService>()
    private val audit = mockk<AuditEventSink>(relaxed = true)
    private val dialogs = mockk<DashboardLifecycleDialogModel>(relaxed = true)
    private val controller = DashboardLifecycleDialogController(dashboards, dialogs, audit)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(method: AuthMethod = AuthMethod.OIDC) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "u@d.p",
                "User",
                method,
                workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun working(
        version: Int = 2,
        status: PipelineVersionStatus = PipelineVersionStatus.DRAFT,
        hash: String = "hash-2",
    ): ArtifactVersion<DashboardBody> {
        val v = mockk<ArtifactVersion<DashboardBody>>(relaxed = true)
        every { v.detail.status } returns status
        every { v.detail.version } returns version
        every { v.detail.bodyHash } returns hash
        every { v.record.name } returns "finance/dashboards/revenue_overview"
        return v
    }

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ): co.datapipelines.visualization.ArtifactVersionDetail =
        co.datapipelines.visualization.ArtifactVersionDetail(
            artifactId = dashboardId,
            version = version,
            status = status,
            bodyHash = "hash",
            createdAt = java.time.Instant.parse("2026-10-01T09:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    @Test
    fun `the release calls the service with the draft's hash and the consent, and answers the versions tab`() {
        authenticate()
        val draft = working()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns draft
        val released =
            DashboardReleased(
                version = working(version = 2, status = PipelineVersionStatus.RELEASED),
                visualizationsReleased = listOf(ArtifactRef("finance/visualizations/cells", 1)),
            )
        every {
            dashboards.release(workspaceId, dashboardId, "hash-2", any(), releasePinnedVisualizations = true)
        } returns released

        val answer = controller.release(dashboardId, releasePinnedVisualizations = true)

        answer.headers["HX-Redirect"] shouldBe listOf("/dashboards/$dashboardId?tab=versions&ok=released_with_visualizations")
        // #332 — one cascaded visualization event FIRST, then the dashboard's own.
        verifySequence {
            audit.log("visualization.version.released", any(), any(), any(), any(), any())
            audit.log("dashboard.version.released", any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a release without a cascade carries the plain released flash`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns working()
        every {
            dashboards.release(workspaceId, dashboardId, "hash-2", any(), releasePinnedVisualizations = false)
        } returns DashboardReleased(working(version = 2, status = PipelineVersionStatus.RELEASED), emptyList())

        val answer = controller.release(dashboardId, releasePinnedVisualizations = false)

        answer.headers["HX-Redirect"] shouldBe listOf("/dashboards/$dashboardId?tab=versions&ok=released")
    }

    @Test
    fun `a dashboard with no draft refuses before the service runs`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns
            working(status = PipelineVersionStatus.RELEASED)

        val thrown =
            assertThrows<DatapipelinesException> {
                controller.release(dashboardId, releasePinnedVisualizations = false)
            }
        thrown.code shouldBe "dashboard.version.not_draft"
        verify(exactly = 0) { dashboards.release(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an API key is refused before anything is read - the verbs are human verbs`() {
        authenticate(method = AuthMethod.API_KEY)
        val thrown =
            assertThrows<DatapipelinesException> {
                controller.release(dashboardId, releasePinnedVisualizations = false)
            }
        thrown.code shouldBe "auth.session.required"
        verify(exactly = 0) { dashboards.findWorking(any(), any(), any()) }
    }

    @Test
    fun `the purge draft's typed confirm is checked BEFORE the service`() {
        authenticate()
        val thrown =
            assertThrows<DatapipelinesException> {
                controller.purge(dashboardId, version = 2, confirm = "v9")
            }
        thrown.code shouldBe "dashboard.version.conflict"
        thrown.details["expected"] shouldBe "v2"
        verify(exactly = 0) { dashboards.purgeDraft(any(), any(), any()) }
    }

    @Test
    fun `the purge draft calls the service with the fresh hash and answers the versions tab`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns working(hash = "fresh-hash")
        every { dashboards.purgeDraft(workspaceId, dashboardId, "fresh-hash") } returns Purged.Version

        val answer = controller.purge(dashboardId, version = 2, confirm = "v2")

        answer.headers["HX-Redirect"] shouldBe listOf("/dashboards/$dashboardId?tab=versions&ok=dashboard_draft_purged")
        verify(exactly = 1) { audit.log(event = "dashboard.version.purged", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a sole-draft purge answers the CATALOG - the row is gone`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns working()
        every { dashboards.purgeDraft(workspaceId, dashboardId, "hash-2") } returns Purged.Entity

        val answer = controller.purge(dashboardId, version = 2, confirm = "v2")

        answer.headers["HX-Redirect"] shouldBe listOf("/dashboards?ok=dashboard_purged")
    }

    @Test
    fun `discard, restore and switch answer the versions tab with their own flash codes and audit the move`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns working()
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(1, PipelineVersionStatus.RELEASED))
        every { dashboards.discardVersion(workspaceId, dashboardId, 1, any()) } returns
            VersionMoved(detail(1, PipelineVersionStatus.DISCARDED), PointerMove(1, null))

        controller
            .discard(dashboardId, version = 1)
            .headers["HX-Redirect"] shouldBe listOf("/dashboards/$dashboardId?tab=versions&ok=discarded")

        every { dashboards.restoreVersion(workspaceId, dashboardId, 1) } returns
            VersionMoved(detail(1, PipelineVersionStatus.RELEASED), PointerMove(null, 1))
        controller
            .restore(dashboardId, version = 1)
            .headers["HX-Redirect"] shouldBe listOf("/dashboards/$dashboardId?tab=versions&ok=restored")

        every { dashboards.switchCurrent(workspaceId, dashboardId, 1) } returns
            Switched("finance/dashboards/revenue_overview", PointerMove(2, 1))
        controller
            .switchCurrent(dashboardId, version = 1)
            .headers["HX-Redirect"] shouldBe listOf("/dashboards/$dashboardId?tab=versions&ok=switched")

        verify(exactly = 1) { audit.log(event = "dashboard.version.discarded", any(), any(), any(), any(), any()) }
        verify(exactly = 1) { audit.log(event = "dashboard.version.restored", any(), any(), any(), any(), any()) }
        verify(exactly = 1) { audit.log(event = "dashboard.current_switched", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the entity purge's typed confirm names the dashboard and the answer loses the leaf`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns working()

        val mismatch =
            assertThrows<DatapipelinesException> {
                controller.purgeEntity(dashboardId, confirm = "finance/dashboards/other")
            }
        mismatch.details["expected"] shouldBe "finance/dashboards/revenue_overview"
        verify(exactly = 0) { dashboards.purgeEntity(any(), any()) }

        every { dashboards.purgeEntity(workspaceId, dashboardId) } returns Purged.Entity
        val answer: ResponseEntity<String> = controller.purgeEntity(dashboardId, confirm = "finance/dashboards/revenue_overview")
        answer.headers["HX-Redirect"] shouldBe listOf("/dashboards?ok=dashboard_purged")
        verify(exactly = 1) { audit.log(event = "dashboard.purged", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the GET dialogs render their partials`() {
        authenticate()

        val model = ExtendedModelMap()
        controller.releaseDialog(model, dashboardId) shouldBe "partials/dashboard-lifecycle-release"

        val m2 = ExtendedModelMap()
        controller.discardDialog(m2, dashboardId, version = 1) shouldBe "partials/dashboard-lifecycle-discard"
        val m3 = ExtendedModelMap()
        controller.switchDialog(m3, dashboardId, version = null) shouldBe "partials/dashboard-lifecycle-switch"
        val m4 = ExtendedModelMap()
        controller.purgeDialog(m4, dashboardId, version = 2) shouldBe "partials/dashboard-lifecycle-purge"
        val m5 = ExtendedModelMap()
        controller.purgeVersionDialog(m5, dashboardId, version = 2) shouldBe "partials/dashboard-lifecycle-purge-version"
        val m6 = ExtendedModelMap()
        controller.restoreDialog(m6, dashboardId, version = 1) shouldBe "partials/dashboard-lifecycle-restore"
        val m7 = ExtendedModelMap()
        controller.purgeEntityDialog(m7, dashboardId) shouldBe "partials/dashboard-lifecycle-purge-entity"
    }
}
