package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.api.currentPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class PipelinePartialController(
    private val pipelines: PipelineService,
) {
    @GetMapping("/partials/pipelines")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val offsetRows = maxOf(0, offset ?: 0)
        val workspaceId = currentPrincipal().requireWorkspace().id
        // The same PipelineService.page the full screen renders from: the HTMX partial and the
        // page it replaces cannot show different totals or different badges (D2).
        val page = pipelines.page(workspaceId, q, offsetRows, PAGE_SIZE)
        model.addAttribute("pipelines", page.items)
        // versioning §7: the "drafts pending release" badge — unreleased (often agent)
        // work must be visible on the list screen. Released metadata stays what the row
        // shows until lock (§3.5); the badge is the marker that a draft exists.
        model.addAttribute("drafts", page.drafts)
        model.addAttribute("q", q ?: "")
        model.addAttribute("offset", offsetRows)
        model.addAttribute("hasMore", page.hasMore)
        model.addAttribute("total", page.total)
        model.addAttribute("scopes", scopes())
        return "partials/pipelines"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }

    private companion object {
        const val PAGE_SIZE = 25
    }
}
