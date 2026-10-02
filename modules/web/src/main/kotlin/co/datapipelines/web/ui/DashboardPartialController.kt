package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.api.currentPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Controller
@RequestMapping("/partials")
class DashboardPartialController(
    private val executions: ExecutionRepository,
    private val pipelines: PipelineService,
    private val pipelineNames: PipelineNames,
    /** 178 — the promoter lens: the pipelines tile counts the caller's view. */
    private val lens: PromoterLens,
    // #10 L3b — the dashboards screens' model: the tree level and the events pane.
    private val browse: DashboardBrowseModel,
    // #400 — the family's service: the Overview/Versions/Keys tabs' lensed reads.
    private val dashboards: co.datapipelines.visualization.DashboardService,
) {
    @GetMapping("/dashboard-stats")
    @RequiredScope(Permission.PIPELINE_READ)
    fun stats(model: Model): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val totalPipelines = pipelines.count(workspaceId, lens.viewFor(principal).pipelines)
        val todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)

        // D11 (2026-09-20) + #9 R3 (#250) + #293: the execution figures follow `visibleTo`'s
        // rule, decided in SQL — every run with `execution.read_all`; own runs plus the
        // workspace's SCHEDULED runs with `execution.read`; and a role the row refuses (the
        // promoter, whose route here is `pipeline.read`) its OWN runs — the answer the explorers'
        // Runs tabs and the search palette already gave the same promoter. Before #293 the tiles
        // read zero for a promoter who could see their runs one click away.
        val recentBatch = executions.listVisibleTo(principal, workspaceId, pipelineId = null, status = null, limit = STATS_SAMPLE_SIZE)

        val executionsToday = recentBatch.count { it.startedAt >= todayStart }
        val successCount = recentBatch.count { it.status == ExecutionStatus.SUCCESS }
        val successRate = if (recentBatch.isNotEmpty()) (successCount * PERCENT / recentBatch.size) else 0

        model.addAttribute("totalPipelines", totalPipelines)
        model.addAttribute("executionsToday", executionsToday)
        model.addAttribute("successRate", successRate)
        return "partials/dashboard-stats"
    }

    @GetMapping("/recent-executions")
    @RequiredScope(Permission.EXECUTION_READ)
    fun recentExecutions(model: Model): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val isAdmin = principal.holds(Permission.EXECUTION_READ_ALL)

        val executions =
            if (isAdmin) {
                executions.findAll(workspaceId, limit = RECENT_COUNT, offset = 0)
            } else {
                // #9 R3 (#250): the dashboard's recent executions follow the executions screen —
                // own runs plus every scheduled run of the workspace, one `findVisible` read.
                executions.findVisible(workspaceId, principal.userId, limit = RECENT_COUNT, offset = 0)
            }

        model.addAttribute("executions", executions)
        model.addAttribute("pipelineNames", pipelineNames.lookup(workspaceId, executions.map { it.pipelineId }))
        return "partials/recent-executions"
    }

    /**
     * #10 L3b, #400 — the SIDEBAR's tree level, or the branch's search results: the
     * dispatcher ([DashboardBrowseModel.fillWrapper]) answers an empty `q` with the root
     * level and a non-empty one with the flat list, so clearing the box returns to the tree
     * by construction. #400: the L3b page instance is retired — this route serves the NAV
     * scope alone (`scope` is still read for wire compatibility but only `nav` selects the
     * nav root; the catalog fragment lives under `/partials/dashboards`).
     */
    @GetMapping("/dashboards/tree")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardTree(
        model: Model,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) scope: String?,
        // The pager's offset (the level template's prev/next links); page one when absent —
        // PipelinePartialController's shape. Dropped at delivery, so every pager link re-rendered
        // page one (the L3b merge's security pass found it); DashboardPartialControllerTest pins it.
        @RequestParam(required = false) offset: Int?,
    ): String {
        val principal = currentPrincipal()
        return if (q.isNullOrEmpty() && scope == DashboardListScope.NAV.wire) {
            browse.fillLevel(
                model,
                principal.requireWorkspace().id,
                lens.viewFor(principal),
                prefix,
                offset = offset ?: 0,
            )
        } else {
            browse.fillWrapper(
                model,
                principal.requireWorkspace().id,
                lens.viewFor(principal),
                q,
                offset = offset ?: 0,
                scope = DashboardListScope.fromWire(scope),
            )
        }
    }

    /**
     * #400 — the `/dashboards` CATALOG's swap root: the flat list under `#dash-list-wrapper`,
     * every dashboard the caller may read or the matches of `q`, in the pipelines catalog
     * partial's shape. The catalog has no tree to return to: it is always the flat branch.
     */
    @GetMapping("/dashboards")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardCatalog(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val principal = currentPrincipal()
        return browse.fillWrapper(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            q,
            offset = offset ?: 0,
            scope = DashboardListScope.PAGE,
        )
    }

    /**
     * #400 — the workspace's Overview tab, lazy-loaded: the viewed version's definition,
     * read-only, on `dashboard.read` (the page's own row — the partial carries no verb).
     * `version` names the version the PAGE resolved; the same admission re-runs here, so a
     * stale tab open is refused the family's 404 naming the version, never another body.
     */
    @GetMapping("/dashboards/{id}/overview")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardOverview(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        val principal = currentPrincipal()
        if (version < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'version' must be a positive integer.")
        }
        return browse.fillOverview(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            id,
            version,
        )
    }

    /**
     * #400 — the workspace's Versions tab, lazy-loaded: the admitted history with the
     * served/draft/discarded markers, on `dashboard.read`. Version-independent by design: the
     * history is the dashboard's own, not the viewed version's.
     */
    @GetMapping("/dashboards/{id}/versions")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardVersions(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        val servedVersion = dashboards.findServed(workspaceId, view.dashboards, id)?.detail?.version
        // The verbs' affordances ride the PARTIAL's model too: the htmx fetch carries a fresh
        // model, and an unstamped one renders no verb for anyone (the browser suite caught it).
        DashboardWorkspaceController.stampAffordances(model, principal)
        return browse.fillVersions(model, workspaceId, view, id, servedVersion)
    }

    /**
     * #400 — the workspace's Keys tab, lazy-loaded: the `dashboard` keys bound to this
     * dashboard, read-only. It floors at `dashboard.key.bind` — the LOWEST row whose cells
     * match the tab's visibility (the brief's roles table) — so the tab is a workspace
     * admin's surface and the page never fetches it for anyone lower.
     */
    @GetMapping("/dashboards/{id}/keys")
    @RequiredScope(Permission.DASHBOARD_KEY_BIND)
    fun dashboardKeys(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard not found")
        return browse.fillKeys(model, workspaceId, id, working.record.name)
    }

    /**
     * #10 L3b — the board page's events pane, newest first, for the pane's bounded poll. Its
     * data is the caller's refreshes — the REST `GET /api/v1/dashboards/{id}/refreshes`
     * route's own rows — so it floors at the SAME permission that route declares
     * (`dashboard.execute`, D50's delegated act; the page that embeds it stays on
     * `dashboard.read`), and the read goes through [DashboardRuntime], so the lens and the
     * own/`execution.read_all` visibility are the route's, never a second copy.
     */
    @GetMapping("/dashboards/{id}/refreshes")
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun dashboardRefreshes(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        return browse.fillRefreshes(model, principal, id)
    }

    private companion object {
        const val STATS_SAMPLE_SIZE = 100
        const val RECENT_COUNT = 10
        const val PERCENT = 100
    }
}
