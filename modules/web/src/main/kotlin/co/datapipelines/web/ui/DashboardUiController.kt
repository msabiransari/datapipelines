package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.view.RedirectView
import java.util.UUID

/**
 * The dashboards screens (#400): the flat CATALOG and the draft-preview compatibility
 * redirect, [PipelineUiController]'s shape — a `@Controller` for the screen family's
 * non-workspace routes, every handler session-authenticated and scope-declared, the model
 * work in [DashboardBrowseModel]. The board is the workspace now
 * ([DashboardWorkspaceController]) — one URL naming one dashboard, one viewed version and
 * one tab, the parent ruling #396's tabbed, version-explicit workspace.
 */
@Controller
class DashboardUiController(
    private val browse: DashboardBrowseModel,
    private val dashboards: DashboardService,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the pages render the caller's view. */
    private val lens: PromoterLens,
) {
    /**
     * The flat catalog (#400, the `/pipelines` catalog's shape): every dashboard the caller
     * may read — or the matches of `q` — one row each, a row linking the workspace, paged at
     * the explorers' page size. The L3b tree page is retired: the folder tree is the
     * sidebar's navigating-tree branch, and a dashboard opens in its workspace — there is no
     * second tree and no detail pane here.
     */
    @GetMapping("/dashboards")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        model.addAttribute("q", q ?: "")
        val principal = currentPrincipal()
        browse.fillList(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            q?.trim()?.takeIf { it.isNotEmpty() },
            maxOf(0, offset ?: 0),
            DashboardListScope.PAGE,
        )
        return "dashboards/list"
    }

    /**
     * The #369 draft-preview route, kept as a COMPATIBILITY deep link: it answers **303** to
     * the canonical workspace URL `/dashboards/{id}?version=N&tab=board`, so every deep link
     * shipped with #369 lands in the workspace's Board tab viewing exactly that version. The
     * permission, the visibility oracle and the version bound are the handler's originals:
     * `dashboard.read`, the working-version read through the lens first (absent, foreign or
     * lens-hidden is the family's 404 — a promoter is never told a draft exists), and a
     * non-positive `version` is the 400 before anything is looked up. The redirect target is
     * built HERE from the validated path id and the validated integer — never from a request
     * parameter — so the route carries no open-redirect surface.
     */
    @GetMapping("/dashboards/{id}/preview")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun preview(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): RedirectView {
        if (version < 1) {
            // The runtime routes' rule for a named version, at the page: a bounded positive
            // integer, else the 400.
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'version' must be a positive integer.")
        }
        val principal = currentPrincipal()
        dashboards.findWorking(principal.requireWorkspace().id, lens.viewFor(principal).dashboards, id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard not found")
        return RedirectView("/dashboards/$id?version=$version&tab=board").apply { setStatusCode(HttpStatus.SEE_OTHER) }
    }
}
