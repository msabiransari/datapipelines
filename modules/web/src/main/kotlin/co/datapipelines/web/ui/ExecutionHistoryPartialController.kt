package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The execution-history screen's list fragment (§5): the same rows the page paints first,
 * projected by the same [ExecutionHistoryBrowseModel] — this controller decides nothing of
 * its own.
 */
@Controller
@RequestMapping("/partials")
class ExecutionHistoryPartialController(
    private val browse: ExecutionHistoryBrowseModel,
) {
    @GetMapping("/executions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    @Suppress("LongParameterList") // the filter bar's fields, one parameter each (the §5 idiom)
    fun listPartial(
        @RequestParam(name = "pipeline_id", required = false) pipelineId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(name = "started_after", required = false) startedAfter: String?,
        @RequestParam(name = "started_before", required = false) startedBefore: String?,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
        model: Model,
    ): String = browse.fillList(model, currentPrincipal(), pipelineId, status, startedAfter, startedBefore, offset)
}
