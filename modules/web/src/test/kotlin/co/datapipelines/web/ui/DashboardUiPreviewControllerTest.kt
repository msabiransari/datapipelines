package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.dashboards.runtime.DashboardRuntime
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
 * The draft preview page's controller states (#369, the implementation spec's §6.3): visibility first — absent,
 * foreign or lens-hidden is the family's 404, a promoter's among them; then the runtime's config read WITH the
 * named version — a version that does not resolve, or a pin that does not hold, is the refusal state IN PLACE
 * (the board handler's `:95–:101` shape); a resolved draft fills the name, the bundle, the version and its status.
 * The permission stays `dashboard.read` — the role walk and the floor test own that half.
 */
class DashboardUiPreviewControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()

    private val dashboards = mockk<DashboardService>()
    private val runtime = mockk<DashboardRuntime>()
    private val controller =
        DashboardUiController(
            browse = mockk(relaxed = true),
            dashboards = dashboards,
            runtime = runtime,
            themeResolver = mockk<ThemeResolver> { every { resolve(any<HttpServletRequest>()) } returns "light" },
            lens = PromoterLens { LensedView.EVERYTHING },
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(role: WorkspaceRole = WorkspaceRole.VIEWER) {
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

    private fun configJson(status: String = "DRAFT"): com.fasterxml.jackson.databind.node.ObjectNode =
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
                            put("status", status)
                        },
                )
                putObject("renderer").put("bundle", "2d")
            }

    private fun preview(version: Int): String {
        val model = ExtendedModelMap()
        val request = MockHttpServletRequest()
        return controller.preview(dashboardId, version, model, request)
    }

    @Test
    fun `a resolved draft fills the name, bundle, version and status beside the id`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns mockk()
        every { runtime.config(any(), dashboardId, 2) } returns configJson()

        val view = preview(2)

        view shouldBe "dashboards/board"
        val model = captureModel(2)
        model["dashboardId"] shouldBe dashboardId.toString()
        model["previewVersion"] shouldBe 2
        model["previewStatus"] shouldBe "DRAFT"
        model["dashboardName"] shouldBe "finance/dashboards/board"
        model["bundle"] shouldBe "2d"
        model.containsKey("refusalCode") shouldBe false
    }

    @Test
    fun `an absent, foreign or lens-hidden dashboard is the family's 404`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns null
        val thrown = assertThrows<ResponseStatusException> { preview(2) }
        thrown.statusCode.value() shouldBe 404
    }

    @Test
    fun `a version that does not resolve, or a pin that does not hold, is the refusal state in place`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns mockk()
        every { runtime.config(any(), dashboardId, 9) } throws
            ApiException(
                "dashboard.not_found",
                "The dashboard has no version 9.",
                mapOf("id" to dashboardId.toString(), "version" to 9),
            )

        val view = preview(9)

        view shouldBe "dashboards/board"
        val model = captureModel(9)
        model["refusalCode"] shouldBe "dashboard.not_found"
        model["refusalMessage"] shouldBe "The dashboard has no version 9."
        model.containsKey("bundle") shouldBe false
    }

    @Test
    fun `a dependency refusal rides the same in-place state, so the author sees the release hint`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns mockk()
        every { runtime.config(any(), dashboardId, 2) } throws
            ApiException(
                "dashboard.runtime.dependency_missing",
                "A visualization this dashboard pins is not released — release it first, then try this board again.",
                mapOf("dependency" to "visualization", "name" to "v1", "reason" to "not_released"),
            )

        preview(2)

        val model = captureModel(2)
        model["refusalCode"] shouldBe "dashboard.runtime.dependency_missing"
        model["refusalMessage"] shouldBe
            "A visualization this dashboard pins is not released — release it first, then try this board again."
    }

    @Test
    fun `a version below one is the 400 before anything is looked up`() {
        authenticate()
        val thrown = assertThrows<ResponseStatusException> { preview(0) }
        thrown.statusCode.value() shouldBe 400
    }

    private fun captureModel(version: Int): ExtendedModelMap {
        val model = ExtendedModelMap()
        val request = MockHttpServletRequest()
        controller.preview(dashboardId, version, model, request)
        return model
    }
}
