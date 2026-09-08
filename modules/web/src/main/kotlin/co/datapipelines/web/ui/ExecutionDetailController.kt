package co.datapipelines.web.ui

import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.api.visibleTo
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Controller
class ExecutionDetailController(
    private val executions: ExecutionRepository,
    private val pipelines: PipelineRepository,
    private val resultStore: ResultStore,
    private val resultUrlFactory: ResultUrlFactory,
) {
    @GetMapping("/executions/{id}")
    fun detail(
        @PathVariable id: UUID,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val record =
            executions.findById(workspaceId, id)?.takeIf { it.visibleTo(principal) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found")

        val pipeline = pipelines.findById(workspaceId, record.pipelineId)
        val resultKey = resultStore.keyFor(record.executionId)
        val resultView = resultStore.describe(resultKey)

        val resultState = resultStateOf(record, resultView)

        model.addAttribute("record", record)
        model.addAttribute("pipeline", pipeline)
        // versioning §8, and now the everyday case (D55/D56): the version this ran may have been
        // a DRAFT, so the screen says so instead of leaving "v1" to mean two different things.
        // Same derivation as the REST `draft_run` field — no released_at, or started before it.
        model.addAttribute("draftRun", draftRun(workspaceId, record))
        model.addAttribute("resultState", resultState)
        model.addAttribute("resultUrl", resultUrlFactory.urlFor(record.executionId))
        model.addAttribute("resultView", resultView)
        model.addAttribute("correlationId", record.correlationId?.toString() ?: CorrelationId.current())
        model.addAttribute("canCancel", record.status == ExecutionStatus.RUNNING && Scope.satisfies(principal.scopes, Scope.EXECUTE))
        model.addAttribute("isAdmin", Scope.satisfies(principal.scopes, Scope.ADMIN))

        val nodeStats = record.nodeStatsJson?.let { ExecutorJson.mapper.readTree(it) }
        model.addAttribute("nodeStats", nodeStats)

        // Design D6: children are ordinary execution rows, linked by the lineage columns — the
        // detail page shows the whole family (root + descendants) via the root's index.
        val family = executions.findByRoot(workspaceId, record.rootExecutionId ?: record.executionId)
        model.addAttribute("family", family)

        val errorJson = record.errorJson?.let { ExecutorJson.mapper.readTree(it) }
        model.addAttribute("errorJson", errorJson)
        model.addAttribute("failedNodeId", record.failedNodeId)
        // 057: the fragment's parsed model — code, message, correlation id, node line, sql,
        // root-first chain. Plain values keep the template markup, not string surgery.
        ExecutionErrorView.attributes(errorJson).forEach { (k, v) -> model.addAttribute(k, v) }

        return "executions/detail"
    }

    /** §8's draft-run derivation for one execution: `started_at < released_at`, or no release. */
    private fun draftRun(
        workspaceId: UUID,
        record: ExecutionRecord,
    ): Boolean {
        val releasedAt =
            pipelines
                .releasedAtFor(workspaceId, listOf(record.pipelineId to record.pipelineVersion))[
                record.pipelineId to record.pipelineVersion,
            ]
        return releasedAt == null || record.startedAt.isBefore(releasedAt)
    }

    private fun resultStateOf(
        record: ExecutionRecord,
        resultView: co.datapipelines.executor.StoredResultView?,
    ): String =
        when {
            record.status != ExecutionStatus.SUCCESS -> "not-applicable"
            record.resultRowCount == null || record.resultRowCount == 0L -> "no-caller-result"
            resultView != null -> "available"
            else -> "expired"
        }
}
