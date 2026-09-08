package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
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
    private val pipelineNames: PipelineNames,
    /**
     * Only for §8's draft-run markers, read in ONE batched query for the page — the same
     * dependency and the same derivation `ExecutionsController` uses for the REST `draft_run`
     * field. Since D55 a v1 run is routinely a draft run, so the column has to say which.
     */
    private val pipelines: co.datapipelines.pipeline.PipelineRepository,
) {
    @GetMapping("/executions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
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

        val releasedAt = pipelines.releasedAtFor(workspaceId, items.map { it.pipelineId to it.pipelineVersion })
        model.addAttribute("executions", items)
        model.addAttribute(
            "draftRuns",
            items.associate { row ->
                val at = releasedAt[row.pipelineId to row.pipelineVersion]
                row.executionId to (at == null || row.startedAt.isBefore(at))
            },
        )
        model.addAttribute("pipelineNames", pipelineNames.lookup(workspaceId, items.map { it.pipelineId }))
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
