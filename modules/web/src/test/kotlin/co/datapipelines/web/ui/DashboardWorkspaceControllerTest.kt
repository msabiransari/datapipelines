package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.dashboards.runtime.DashboardRuntime
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The dashboard workspace controller's states (#400): the NAME is stamped for EVERY render —
 * the choose-a-version state (#409: no served release and none named → the empty state over
 * the admitted history, the runtime config NEVER called, and no "not found" for a dashboard
 * the tree just linked) and the refusal state alike; a version that resolves fills the ONE
 * bundle the server chose; the tab set is closed (unknown → Board, Keys without the binding
 * permission → Board); the affordance booleans are the PERMISSION checks (no RoleModel flag
 * exists for dashboards — the brief's rule).
 */
class DashboardWorkspaceControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()

    private val dashboards = mockk<DashboardService>()
    private val runtime = mockk<DashboardRuntime>()
    private val model = mockk<co.datapipelines.visualization.ArtifactVersion<DashboardBody>>(relaxed = true)
    private val controller =
        DashboardWorkspaceController(
            workspace = DashboardWorkspaceModel(dashboards),
            runtime = runtime,
            themeResolver = mockk<ThemeResolver> { every { resolve(any<HttpServletRequest>()) } returns "light" },
            lens = PromoterLens { LensedView.EVERYTHING },
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(role: WorkspaceRole = WorkspaceRole.AUTHOR) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "u@d.p",
                "User",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme", role),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun workingVersion(
        status: PipelineVersionStatus = PipelineVersionStatus.DRAFT,
        version: Int = 2,
    ): ArtifactVersion<DashboardBody> {
        every { model.detail.status } returns status
        every { model.detail.version } returns version
        every { model.record.name } returns "finance/dashboards/board"
        return model
    }

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ): co.datapipelines.visualization.ArtifactVersionDetail =
        co.datapipelines.visualization.ArtifactVersionDetail(
            artifactId = dashboardId,
            version = version,
            status = status,
            bodyHash = "hash-$version",
            createdAt = java.time.Instant.parse("2026-10-01T09:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    private fun configJson(bundle: String = "2d"): com.fasterxml.jackson.databind.node.ObjectNode =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
            .objectNode()
            .apply {
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "dashboard",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .apply {
                            put("id", dashboardId.toString())
                            put("name", "finance/dashboards/board")
                            put("version", 2)
                            put("status", "DRAFT")
                        },
                )
                putObject("renderer").put("bundle", bundle)
            }

    private fun render(
        version: String? = null,
        tab: String? = null,
    ): ExtendedModelMap {
        val m = ExtendedModelMap()
        controller.workspace(dashboardId, version, tab, m, MockHttpServletRequest())
        return m
    }

    @Test
    fun `a resolved version fills the name, the served chip facts and the ONE bundle`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2) } returns workingVersion()
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(2, PipelineVersionStatus.DRAFT))
        every { runtime.config(any(), dashboardId, 2) } returns configJson()

        val m = render(version = "2")

        m["dashboardName"] shouldBe "finance/dashboards/board"
        m["viewedVersion"] shouldBe 2
        m["viewedIsDraft"] shouldBe true
        m["boardVersion"] shouldBe 2
        m["bundle"] shouldBe "2d"
        m["activeTab"] shouldBe "board"
        m.containsKey("refusalCode") shouldBe false
    }

    @Test
    fun `the default resolution is the served release, and its version attribute is ABSENT (the glue stays on released)`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns
            workingVersion(status = PipelineVersionStatus.RELEASED, version = 1)
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns
            workingVersion(status = PipelineVersionStatus.RELEASED, version = 1)
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(1, PipelineVersionStatus.RELEASED))
        every { runtime.config(any(), dashboardId, 1) } returns configJson()

        val m = render()

        m["viewedVersion"] shouldBe 1
        m["viewedIsDraft"] shouldBe false
        m["boardVersion"] shouldBe null
        m["bundle"] shouldBe "2d"
    }

    @Test
    fun `#409 - a draft-only dashboard renders the choose-a-version state with the NAME and never calls the runtime`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(2, PipelineVersionStatus.DRAFT))

        val m = render()

        m["dashboardName"] shouldBe "finance/dashboards/board"
        m["hasSelected"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        val versions = m["versions"] as List<DashboardWorkspaceController.VersionRow>
        versions.size shouldBe 1
        verify(exactly = 0) { runtime.config(any(), any(), any()) }
        m.containsKey("bundle") shouldBe false
    }

    @Test
    fun `an absent, foreign or lens-hidden dashboard is the family's 404 naming the id`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns null

        val thrown =
            org.junit.jupiter.api.assertThrows<co.datapipelines.typesystem.DatapipelinesException> {
                render()
            }
        thrown.code shouldBe "dashboard.not_found"
    }

    @Test
    fun `a named version that does not resolve is the family's 404 NAMING the version`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 9) } returns null

        val thrown =
            org.junit.jupiter.api.assertThrows<co.datapipelines.typesystem.DatapipelinesException> {
                render(version = "9")
            }
        thrown.code shouldBe "dashboard.not_found"
        thrown.details["version"] shouldBe "9"
    }

    @Test
    fun `a board that cannot run is the refusal state IN PLACE - the name stays, no bundle loads`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2) } returns workingVersion()
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(2, PipelineVersionStatus.DRAFT))
        every { runtime.config(any(), dashboardId, 2) } throws
            ApiException(
                "dashboard.runtime.dependency_missing",
                "A visualization this dashboard pins is not released — release it first, then try this board again.",
                mapOf("dependency" to "visualization", "name" to "v1", "reason" to "not_released"),
            )

        val m = render(version = "2")

        m["dashboardName"] shouldBe "finance/dashboards/board"
        m["refusalCode"] shouldBe "dashboard.runtime.dependency_missing"
        m["refusalMessage"] shouldBe
            "A visualization this dashboard pins is not released — release it first, then try this board again."
        m.containsKey("bundle") shouldBe false
    }

    @Test
    fun `the tab set is closed - unknown is Board, Keys without the binding permission is Board`() {
        authenticate(role = WorkspaceRole.VIEWER)
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns
            listOf(detail(2, PipelineVersionStatus.DRAFT))

        render(tab = "nonsense")["activeTab"] shouldBe "board"
        render(tab = "keys")["activeTab"] shouldBe "board"
    }

    @Test
    fun `the affordances are the PERMISSION checks - an author releases and manages, a viewer reads`() {
        authenticate(role = WorkspaceRole.VIEWER)
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns emptyList()

        val viewer = render()
        viewer["canRelease"] shouldBe false
        viewer["canManageVersions"] shouldBe false
        viewer["canSwitch"] shouldBe false
        viewer["canDelete"] shouldBe false
        viewer["canBindKeys"] shouldBe false

        authenticate(role = WorkspaceRole.AUTHOR)
        val author = render()
        author["canRelease"] shouldBe true
        author["canManageVersions"] shouldBe true
        author["canSwitch"] shouldBe true
        author["canDelete"] shouldBe true
        author["canBindKeys"] shouldBe false
    }

    @Test
    fun `the sidebar's current-leaf hook rides the full path (#350)`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        every { dashboards.listVersions(workspaceId, ReadLens.Everything, dashboardId) } returns emptyList()

        render()["navCurrentPath"] shouldBe "finance/dashboards/board"
    }

    @Test
    fun `a promoter asking for a DRAFT version is the family 404 - the lens, never the everything view`() {
        // A NARROWING lens: the workspace resolves through `view.dashboards`, so a draft named
        // by number is the same 404 an unknown number is — a status cannot be probed.
        val narrow =
            co.datapipelines.application.lens.PromoterLens {
                co.datapipelines.application.lens.LensedView(
                    pipelines = co.datapipelines.pipeline.ReadLens.Everything,
                    templates = co.datapipelines.pipeline.ReadLens.Everything,
                    dashboards =
                        co.datapipelines.pipeline.ReadLens
                            .Only(emptySet()),
                )
            }
        val narrowed =
            DashboardWorkspaceController(
                workspace = DashboardWorkspaceModel(dashboards),
                runtime = runtime,
                themeResolver = mockk<ThemeResolver> { every { resolve(any<HttpServletRequest>()) } returns "light" },
                lens = narrow,
            )
        authenticate(role = WorkspaceRole.PROMOTER)
        every {
            dashboards.findWorking(
                workspaceId,
                co.datapipelines.pipeline.ReadLens
                    .Only(emptySet()),
                dashboardId,
            )
        } returns
            workingVersion()
        every {
            dashboards.findServed(
                workspaceId,
                co.datapipelines.pipeline.ReadLens
                    .Only(emptySet()),
                dashboardId,
            )
        } returns null
        every {
            dashboards.findServedVersion(
                workspaceId,
                co.datapipelines.pipeline.ReadLens
                    .Only(emptySet()),
                dashboardId,
                2,
            )
        } returns
            null

        val thrown =
            assertThrows<co.datapipelines.typesystem.DatapipelinesException> {
                narrowed.workspace(dashboardId, "2", null, ExtendedModelMap(), MockHttpServletRequest())
            }
        thrown.code shouldBe "dashboard.not_found"
        thrown.details["version"] shouldBe "2"
        verify(exactly = 0) { runtime.config(any(), any(), any()) }
    }

    @Test
    fun `a malformed version parameter is the 400, never a clamp`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns workingVersion()
        val thrown = assertThrows<ResponseStatusException> { render(version = "two") }
        thrown.statusCode.value() shouldBe 400
    }
}
