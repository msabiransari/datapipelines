package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.dashboards.runtime.DashboardRuntime
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The dashboards screens (ui-screens.md §4.x; the implementation spec's §6.3) — the tree page,
 * the board page and its draft-preview twin, [PipelineUiController]'s shape: a `@Controller` for
 * the screen family, every handler session-authenticated and scope-declared, the model work in
 * [DashboardBrowseModel].
 *
 * ## The board page's refusal state, and why it is in-page
 * A dashboard the caller cannot see (absent, foreign, lens-hidden) is the family's 404 — the
 * [ResponseStatusException] the execution detail page throws for an absent record. A dashboard the
 * caller CAN see but whose board cannot run (no release yet, a purged pin, a source that stopped
 * passing the read-only rule) is a REFUSAL the page renders in place — the record exists for this
 * caller (it is in their tree), so an error page would be the wrong instrument and a blank pane
 * the wrong answer (the implementation spec's §6.3: "never a blank page"). The state names the
 * family's own code and message through `th:text`; no server value reaches a script body.
 */
@Controller
class DashboardUiController(
    private val browse: DashboardBrowseModel,
    private val dashboards: DashboardService,
    private val runtime: DashboardRuntime,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the pages render the caller's view. */
    private val lens: PromoterLens,
) {
    /** The tree page — the explorer chrome over the same one-level-per-request partial the sidebar's lazy tree loads. */
    @GetMapping("/dashboards")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun list(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        browse.fillLevel(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            prefix = null,
            offset = 0,
            scope = DashboardBrowseModel.SCOPE_PAGE,
        )
        return "dashboards/list"
    }

    /**
     * The board page — the released view. The server resolves the runtime configuration ONCE here
     * to learn the ONE Plotly bundle the page must declare (`renderer.bundle`, the implementation
     * spec's §10.4: the server chooses, the page loads exactly one, the script tag DECLARES it) —
     * the same read the client runtime's bootstrap performs, so the declaration on the tag and the
     * pair the runtime judges at bootstrap cannot disagree. No permission is consulted twice: the
     * page sits on `dashboard.read`, and D50 makes every reader an executor — the runtime routes
     * the board then drives are gated server-side, unchanged.
     */
    @GetMapping("/dashboards/{id}")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun board(
        @PathVariable id: UUID,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        // Absent, foreign or lens-hidden: the family's 404, the execution detail page's rule.
        // The working-version read through the lens is the visibility oracle; the runtime's
        // config read below is the release/dependency oracle.
        dashboards.findWorking(workspaceId, view.dashboards, id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard not found")
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dashboardId", id.toString())
        RoleModel.stamp(model)
        val config =
            try {
                runtime.config(principal, id)
            } catch (e: DatapipelinesException) {
                // The board cannot run (no release yet, a dependency missing or no longer
                // admissible): the refusal state, in place — never a blank pane, never a raw
                // JSON envelope on a person's page.
                model.addAttribute("refusalCode", e.code)
                model.addAttribute("refusalMessage", e.message)
                return "dashboards/board"
            }
        model.addAttribute("dashboardName", config.get("dashboard").get("name").asText())
        model.addAttribute("bundle", config.get("renderer").get("bundle").asText())
        browse.fillRefreshes(model, principal, id)
        return "dashboards/board"
    }

    /**
     * The DRAFT PREVIEW page (#369, the implementation spec's §6.3) — the board handler's twin, rendering the SAME
     * template for a NAMED version (`?version=N`, a positive integer): the draft the engineer is perfecting, or a
     * released one (R2). The declared permission stays `dashboard.read` — the record's §7 sentence, "a
     * `dashboard.read` holder with `dashboard.execute`", reads as the page on read and the RUNTIME it mounts on
     * execute; the runtime's config read below already carries the version through to the resolver, whose
     * RELEASED-only pin rule (R1) is the draft's admission. Visibility first, exactly as [board]: absent, foreign or
     * lens-hidden is the family's 404 (a promoter is never told a draft exists); a version that does not resolve, or
     * a pin that does not hold, is the refusal state IN PLACE — this dashboard exists in the caller's tree, so the
     * refusal names the code where the person is looking. The page differs from [board] in what it tells the
     * template: the previewed version rides a data attribute (an integer, never a script body) and the banner names
     * it with the way back to the released view. No audit event: the preview is a read.
     */
    @GetMapping("/dashboards/{id}/preview")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun preview(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        model: Model,
        request: HttpServletRequest,
    ): String {
        if (version < 1) {
            // The runtime routes' rule for a named version, at the page: a bounded positive integer, else the 400.
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'version' must be a positive integer.")
        }
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        dashboards.findWorking(workspaceId, view.dashboards, id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard not found")
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dashboardId", id.toString())
        model.addAttribute("previewVersion", version)
        RoleModel.stamp(model)
        val config =
            try {
                runtime.config(principal, id, version)
            } catch (e: DatapipelinesException) {
                // The named version does not resolve (absent, discarded, hidden) or the board cannot run: the
                // refusal state, in place — the preview URL stays in the address bar where the person is.
                model.addAttribute("refusalCode", e.code)
                model.addAttribute("refusalMessage", e.message)
                return "dashboards/board"
            }
        model.addAttribute("dashboardName", config.get("dashboard").get("name").asText())
        model.addAttribute("previewStatus", config.get("dashboard").get("status").asText())
        model.addAttribute("bundle", config.get("renderer").get("bundle").asText())
        browse.fillRefreshes(model, principal, id)
        return "dashboards/board"
    }
}
