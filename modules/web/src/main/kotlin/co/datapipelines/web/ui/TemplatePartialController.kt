package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.currentPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class TemplatePartialController(
    private val templates: TemplateRepository,
) {
    @GetMapping("/partials/templates")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val page = maxOf(0, offset ?: 0)
        val size = PAGE_SIZE
        val dialectFilter =
            dialect?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
                Dialect.entries.firstOrNull { it.wire.equals(d, ignoreCase = true) }
            }
        val workspaceId = currentPrincipal().requireWorkspace().id
        val raw =
            templates.list(
                workspaceId,
                dialect = dialectFilter,
                q = q?.trim()?.takeIf { it.isNotEmpty() },
                offset = page,
                limit =
                    size + 1,
            )
        val items = raw.take(size)
        model.addAttribute("templates", items)
        model.addAttribute("q", q ?: "")
        model.addAttribute("selectedDialect", dialect ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", raw.size > size)
        model.addAttribute("scopes", scopes())
        return "partials/templates"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }

    private companion object {
        const val PAGE_SIZE = 25
    }
}
