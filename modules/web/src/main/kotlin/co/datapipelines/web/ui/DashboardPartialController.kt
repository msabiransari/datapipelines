package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    private val dashboards: DashboardBrowseModel,
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
     * #10 L3b — ONE level of the dashboard tree, for the folder expansion in EITHER instance
     * that renders it: the tree page's pane or the sidebar's lazy branch, named by [scope]
     * (the two coexist in one document, so their level ids are per-scope). The read is
     * [DashboardBrowseModel.fillLevel]'s, through the dashboards service's lensed reads —
     * `dashboard.read`, the same row the tree page declares (the brief's roles table).
     */
    @GetMapping("/dashboards/tree")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardTree(
        model: Model,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) scope: String?,
    ): String {
        val principal = currentPrincipal()
        return dashboards.fillLevel(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            prefix,
            offset = 0,
            scope = if (scope == DashboardBrowseModel.SCOPE_NAV) DashboardBrowseModel.SCOPE_NAV else DashboardBrowseModel.SCOPE_PAGE,
        )
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
        return dashboards.fillRefreshes(model, principal, id)
    }

    private companion object {
        const val STATS_SAMPLE_SIZE = 100
        const val RECENT_COUNT = 10
        const val PERCENT = 100
    }
}
