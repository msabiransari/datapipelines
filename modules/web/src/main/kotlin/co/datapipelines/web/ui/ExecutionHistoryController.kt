package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The execution-history screen (ui-screens.md §4.8).
 *
 * Renders the shell **and** the first fragment (§5, 097 §B — it used to render a spinner and
 * fetch the rows on `hx-trigger="load"`); every later refresh hits
 * [ExecutionHistoryPartialController] and swaps `#execution-table` only. Both go through the
 * one [ExecutionHistoryBrowseModel], so the first paint and a filter change cannot disagree —
 * and a shared `/executions?status=FAILED` link now opens on the rows it names.
 */
@Controller
class ExecutionHistoryController(
    private val pipelines: PipelineRepository,
    private val browse: ExecutionHistoryBrowseModel,
) {
    @GetMapping("/executions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    @Suppress("LongParameterList") // the filter bar's fields, one parameter each (the §5 idiom)
    fun list(
        model: Model,
        @RequestParam(name = "pipeline_id", required = false) pipelineId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(name = "started_after", required = false) startedAfter: String?,
        @RequestParam(name = "started_before", required = false) startedBefore: String?,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
    ): String {
        val principal = currentPrincipal()
        model.addAttribute("pipelines", pipelines.findAll(principal.requireWorkspace().id))
        model.addAttribute("statuses", ExecutionStatus.entries)
        // Echoed back so the filter bar re-renders in the state its rows were fetched with.
        model.addAttribute("selectedPipelineId", pipelineId?.toString() ?: "")
        model.addAttribute("selectedStatus", status ?: "")
        model.addAttribute("selectedStartedAfter", startedAfter ?: "")
        model.addAttribute("selectedStartedBefore", startedBefore ?: "")
        browse.fillList(model, principal, pipelineId, status, startedAfter, startedBefore, offset)
        return "executions/list"
    }
}
