package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class ExecutionHistoryController(
    private val pipelines: PipelineRepository,
) {
    @GetMapping("/executions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(model: Model): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        model.addAttribute("pipelines", pipelines.findAll(workspaceId))
        model.addAttribute("statuses", ExecutionStatus.entries)
        return "executions/list"
    }
}
