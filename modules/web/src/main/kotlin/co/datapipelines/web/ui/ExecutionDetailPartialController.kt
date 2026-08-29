package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ResultStore
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
