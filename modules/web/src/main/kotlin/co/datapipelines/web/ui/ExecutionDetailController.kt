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
        val record =
            executions.findById(id)?.takeIf { it.visibleTo(principal) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found")

        val pipeline = pipelines.findById(record.pipelineId)
        val resultKey = resultStore.keyFor(record.executionId)
        val resultView = resultStore.describe(resultKey)

        val resultState = resultStateOf(record, resultView)

        model.addAttribute("record", record)
        model.addAttribute("pipeline", pipeline)
        model.addAttribute("resultState", resultState)
        model.addAttribute("resultUrl", resultUrlFactory.urlFor(record.executionId))
        model.addAttribute("resultView", resultView)
        model.addAttribute("correlationId", record.correlationId?.toString() ?: CorrelationId.current())
        model.addAttribute("canCancel", record.status == ExecutionStatus.RUNNING && Scope.satisfies(principal.scopes, Scope.EXECUTE))
        model.addAttribute("isAdmin", Scope.satisfies(principal.scopes, Scope.ADMIN))

        val nodeStats = record.nodeStatsJson?.let { ExecutorJson.mapper.readTree(it) }
        model.addAttribute("nodeStats", nodeStats)

        val errorJson = record.errorJson?.let { ExecutorJson.mapper.readTree(it) }
        model.addAttribute("errorJson", errorJson)
        model.addAttribute("failedNodeId", record.failedNodeId)

        return "executions/detail"
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
