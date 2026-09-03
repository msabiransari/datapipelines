package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.api.visibleTo
import co.datapipelines.web.executions.ResultCursor
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Controller
@RequestMapping("/partials/executions")
class ExecutionDetailPartialController(
    private val executions: ExecutionRepository,
    private val resultStore: ResultStore,
    private val cursor: ResultCursor,
    private val cancellation: ExecutionCancellationService,
) {
    @GetMapping("/{id}/result")
    @RequiredScope(ScopeMatrix.RestOperation.RETRIEVE_RESULT)
    fun result(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "0") offset: Long,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        val record =
            try {
                cursor.readable(id, principal)
            } catch (e: ApiException) {
                // 057/T85: a FAILED execution's result area shows the failure record — the
                // same structured rendering the detail page and the live editor got — instead
                // of one bare code string. The other rows (expired, not found) have no record
                // and keep the bare card.
                if (renderExecutionFailure(id, principal, model, e.code)) return "partials/execution-result-error"
                model.addAttribute("error", e.code)
                return "partials/execution-result-error"
            }

        val resultKey = resultStore.keyFor(record.executionId)
        val pageSize = DEFAULT_PAGE
        val page =
            resultStore.page(resultKey, offset.coerceAtLeast(0), pageSize)
                ?: run {
                    model.addAttribute("error", "result.expired")
                    return "partials/execution-result-error"
                }

        model.addAttribute("executionId", record.executionId)
        model.addAttribute("schema", page.schema)
        model.addAttribute("rows", page.rows)
        model.addAttribute("offset", offset)
        model.addAttribute("hasMore", page.hasMore)
        model.addAttribute("nextOffset", if (page.hasMore) offset + page.rows.size else null)
        model.addAttribute("prevOffset", if (offset > 0) (offset - pageSize).coerceAtLeast(0) else null)
        model.addAttribute("totalRows", page.totalRows)
        return "partials/execution-result"
    }

    /**
     * The 057 failure branch of [result]: renders the execution's full failure record (the
     * shared `partials/execution-error` model) when this refusal is a FAILED execution with a
     * stored record. True when it rendered; false when the caller should fall back to the bare
     * code card (expired, not found, or no record stored).
     */
    private fun renderExecutionFailure(
        id: UUID,
        principal: AuthenticatedPrincipal,
        model: Model,
        code: String,
    ): Boolean {
        if (code != PipelineErrorCodes.Result.EXECUTION_FAILED) return false
        val failed = executions.findById(principal.requireWorkspace().id, id)?.takeIf { it.visibleTo(principal) } ?: return false
        val errorJson = failed.errorJson ?: return false
        ExecutionErrorView.attributes(ExecutorJson.mapper.readTree(errorJson)).forEach { (k, v) -> model.addAttribute(k, v) }
        model.addAttribute("failedNodeId", failed.failedNodeId)
        return true
    }

    @Suppress("ThrowsCount")
    @DeleteMapping("/{id}/cancel")
    @RequiredScope(ScopeMatrix.RestOperation.CANCEL_EXECUTION)
    fun cancel(
        @PathVariable id: UUID,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        if (!Scope.satisfies(principal.scopes, Scope.EXECUTE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Execute scope required")
        }
        val record =
            executions.findById(principal.requireWorkspace().id, id)?.takeIf { it.visibleTo(principal) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found")
        if (record.status != ExecutionStatus.RUNNING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Execution is not running")
        }
        cancellation.cancel(id, AbortReason.CANCELLED)
        model.addAttribute("cancelled", true)
        model.addAttribute("executionId", id)
        return "partials/execution-cancelled"
    }

    private companion object {
        const val DEFAULT_PAGE = 20
    }
}
