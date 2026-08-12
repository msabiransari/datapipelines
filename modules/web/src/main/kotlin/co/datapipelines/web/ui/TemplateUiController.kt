package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class TemplateUiController(
    private val templates: TemplateRepository,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/templates")
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        model.addAttribute("selectedDialect", dialect ?: "")
        model.addAttribute("scopes", scopes())
        val page = maxOf(0, offset ?: 0)
        val size = PAGE_SIZE
        val dialectFilter =
            dialect?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
                Dialect.entries.firstOrNull { it.wire.equals(d, ignoreCase = true) }
            }
        val raw = templates.list(dialect = dialectFilter, q = q?.trim()?.takeIf { it.isNotEmpty() }, offset = page, limit = size + 1)
        val items = raw.take(size)
        model.addAttribute("templates", items)
        model.addAttribute("q", q ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", raw.size > size)
        model.addAttribute("totalLabel", "Page of templates")
        return "templates/list"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }

    private companion object {
        const val PAGE_SIZE = 25
    }
}
