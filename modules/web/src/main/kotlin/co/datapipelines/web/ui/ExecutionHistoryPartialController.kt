package co.datapipelines.web.ui

import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

@Controller
@RequestMapping("/partials")
class ExecutionHistoryPartialController(
    private val executions: ExecutionRepository,
) {
    @GetMapping("/executions")
    fun listPartial(
        @RequestParam(name = "pipeline_id", required = false) pipelineId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(name = "started_after", required = false) startedAfter: String?,
        @RequestParam(name = "started_before", required = false) startedBefore: String?,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val isAdmin = Scope.satisfies(principal.scopes, Scope.ADMIN)
        val wanted = status?.let { runCatching { ExecutionStatus.valueOf(it.trim().uppercase()) }.getOrNull() }
        val after = startedAfter?.let { Instant.parse(it) }
        val before = startedBefore?.let { Instant.parse(it) }

        val pageSize = DEFAULT_PAGE
        val raw =
            if (isAdmin) {
                executions.findAll(workspaceId, pipelineId, wanted, after, before, limit = pageSize + 1, offset = offset)
            } else {
                executions.findByUser(
                    workspaceId,
                    principal.userId,
                    pipelineId,
                    wanted,
                    after,
                    before,
                    limit = pageSize + 1,
                    offset = offset,
                )
            }

        val items = raw.take(pageSize)
        val hasMore = raw.size > pageSize

        model.addAttribute("executions", items)
        model.addAttribute("offset", offset)
        model.addAttribute("pageSize", pageSize)
        model.addAttribute("hasMore", hasMore)
        model.addAttribute("nextOffset", if (hasMore) offset + pageSize else null)
        return "partials/executions"
    }

    private companion object {
        const val DEFAULT_PAGE = 20
    }
}
