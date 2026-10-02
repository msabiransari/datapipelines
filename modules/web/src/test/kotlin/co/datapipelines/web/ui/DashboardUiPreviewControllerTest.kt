package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.view.RedirectView
import java.util.UUID

/**
 * The draft-preview route's REDIRECT states (#400): `GET /dashboards/{id}/preview?version=N`
 * answers **303** to the canonical workspace URL `/dashboards/{id}?version=N&tab=board` — the
 * deep links #369 shipped land in the workspace's Board tab viewing exactly that version —
 * after the handler's ORIGINAL guards: a non-positive version is the 400 before anything is
 * looked up, and the working-version read through the lens first (absent, foreign or
 * lens-hidden is the family's 404, a promoter's among them). The redirect target is built
 * HERE from the validated path id and the validated integer, never from a request parameter.
 * The permission stays `dashboard.read` — the role walk and the floor test own that half.
 */
class DashboardUiPreviewControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()

    private val dashboards = mockk<DashboardService>()
    private val controller =
        DashboardUiController(
            browse = mockk(relaxed = true),
            dashboards = dashboards,
            themeResolver = mockk<ThemeResolver> { every { resolve(any<jakarta.servlet.http.HttpServletRequest>()) } returns "light" },
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

    private fun preview(version: Int): RedirectView = controller.preview(dashboardId, version)

    @Test
    fun `a visible dashboard answers 303 onto the workspace's Board tab at the named version`() {
        authenticate()
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns
            mockk<ArtifactVersion<co.datapipelines.visualization.DashboardBody>>()

        val redirect = preview(2)

        redirect.url shouldBe "/dashboards/$dashboardId?version=2&tab=board"
        // The STATUS is pinned at the HTTP layer (the E2E's no-follow case asserts the 303);
        // a view object carries no public status to read back.
    }

    @Test
    fun `an absent, foreign or lens-hidden dashboard is the family's 404 - the redirect never fires`() {
        authenticate(WorkspaceRole.PROMOTER)
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, dashboardId) } returns null
        val thrown = assertThrows<ResponseStatusException> { preview(2) }
        thrown.statusCode.value() shouldBe 404
    }

    @Test
    fun `a version below one is the 400 before anything is looked up`() {
        authenticate()
        val thrown = assertThrows<ResponseStatusException> { preview(0) }
        thrown.statusCode.value() shouldBe 400
    }
}
