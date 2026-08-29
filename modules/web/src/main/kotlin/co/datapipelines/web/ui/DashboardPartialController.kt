package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.time.LocalDate
import java.time.ZoneOffset

@Controller
@RequestMapping("/partials")
class DashboardPartialController(
    private val executions: ExecutionRepository,
    private val pipelines: PipelineRepository,
) {
    @GetMapping("/dashboard-stats")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun stats(model: Model): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val isAdmin = Scope.satisfies(principal.scopes, Scope.ADMIN)

        val totalPipelines = pipelines.countAll(workspaceId)
        val todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)

        val recentBatch =
            if (isAdmin) {
                executions.findAll(workspaceId, limit = STATS_SAMPLE_SIZE, offset = 0)
            } else {
                executions.findByUser(workspaceId, principal.userId, limit = STATS_SAMPLE_SIZE, offset = 0)
            }

        val executionsToday = recentBatch.count { it.startedAt >= todayStart }
        val successCount = recentBatch.count { it.status == ExecutionStatus.SUCCESS }
        val successRate = if (recentBatch.isNotEmpty()) (successCount * PERCENT / recentBatch.size) else 0

        model.addAttribute("totalPipelines", totalPipelines)
        model.addAttribute("executionsToday", executionsToday)
        model.addAttribute("successRate", successRate)
        return "partials/dashboard-stats"
    }

    @GetMapping("/recent-executions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun recentExecutions(model: Model): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val isAdmin = Scope.satisfies(principal.scopes, Scope.ADMIN)

        val executions =
            if (isAdmin) {
                executions.findAll(workspaceId, limit = RECENT_COUNT, offset = 0)
            } else {
                executions.findByUser(workspaceId, principal.userId, limit = RECENT_COUNT, offset = 0)
            }

        model.addAttribute("executions", executions)
        return "partials/recent-executions"
    }

    private companion object {
        const val STATS_SAMPLE_SIZE = 100
        const val RECENT_COUNT = 10
        const val PERCENT = 100
    }
}
