package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.Dialect
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class PipelineUiController(
    private val pipelines: PipelineRepository,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/pipelines")
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        model.addAttribute("scopes", scopes())
        val page = maxOf(0, offset ?: 0)
        val size = PAGE_SIZE
        val all = filter(pipelines.findAll(), q?.trim()?.takeIf { it.isNotEmpty() })
        val items = all.drop(page).take(size)
        model.addAttribute("pipelines", items)
        model.addAttribute("q", q ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", all.size > page + size)
        model.addAttribute("total", all.size)
        return "pipelines/list"
    }

    private fun filter(
        records: List<PipelineRecord>,
        query: String?,
    ): List<PipelineRecord> {
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
