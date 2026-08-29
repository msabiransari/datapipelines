package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.currentPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class PipelinePartialController(
    private val pipelines: PipelineRepository,
) {
    @GetMapping("/partials/pipelines")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val page = maxOf(0, offset ?: 0)
        val size = PAGE_SIZE
        val query = q?.trim()?.takeIf { it.isNotEmpty() }
        val workspaceId = currentPrincipal().requireWorkspace().id
        val items: List<co.datapipelines.pipeline.PipelineRecord>
        val total: Int
        val hasMore: Boolean
        if (query == null) {
            val pageRows = pipelines.findAll(workspaceId, null, size + 1, page)
            items = pageRows.take(size)
            total = page + items.size + (if (pageRows.size > size) 1 else 0)
            hasMore = pageRows.size > size
        } else {
            val all = filter(pipelines.findAll(workspaceId), query)
            items = all.drop(page).take(size)
            total = all.size
            hasMore = all.size > page + size
        }
        model.addAttribute("pipelines", items)
        model.addAttribute("q", q ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", hasMore)
        model.addAttribute("total", total)
        model.addAttribute("scopes", scopes())
        return "partials/pipelines"
    }

    private fun filter(
        records: List<co.datapipelines.pipeline.PipelineRecord>,
        query: String?,
    ): List<co.datapipelines.pipeline.PipelineRecord> {
        if (query == null) return records
        val lower = query.lowercase()
        return records.filter { r ->
            r.name.lowercase().contains(lower) ||
                r.displayName.lowercase().contains(lower) ||
                r.description.lowercase().contains(lower)
        }
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }

    private companion object {
        const val PAGE_SIZE = 25
    }
}
