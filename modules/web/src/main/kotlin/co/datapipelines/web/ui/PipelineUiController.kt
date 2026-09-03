package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class PipelineUiController(
    private val pipelines: PipelineService,
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
        val offsetRows = maxOf(0, offset ?: 0)
        val workspaceId = currentPrincipal().requireWorkspace().id
        // D2: the paging, the truthful total and the draft badges are PipelineService.page's —
        // this screen, its HTMX partial, the REST listing and `pipelines_list` had four copies.
        val page = pipelines.page(workspaceId, q, offsetRows, PAGE_SIZE)
        model.addAttribute("pipelines", page.items)
        model.addAttribute("drafts", page.drafts)
        model.addAttribute("q", q ?: "")
        model.addAttribute("offset", offsetRows)
        model.addAttribute("hasMore", page.hasMore)
        model.addAttribute("total", page.total)
        return "pipelines/list"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }

    private companion object {
        const val PAGE_SIZE = 25
    }
}
