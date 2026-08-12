package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class DatasourcePartialController(
    private val datasources: DatasourceRegistry,
) {
    @GetMapping("/partials/datasources")
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
        val all = filter(datasources.list(dialectFilter), q?.trim()?.takeIf { it.isNotEmpty() })
        val items = all.drop(page).take(size)
        model.addAttribute("datasources", items)
        model.addAttribute("q", q ?: "")
        model.addAttribute("selectedDialect", dialect ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", all.size > page + size)
        model.addAttribute("total", all.size)
        model.addAttribute("scopes", scopes())
        return "partials/datasources"
    }

    @PostMapping("/partials/datasources/{name}/test")
    fun test(
        model: Model,
        @PathVariable name: String,
    ): String {
        val result = datasources.testConnection(name)
        model.addAttribute("testName", name)
        model.addAttribute("testResult", result)
        return "partials/datasource-row"
    }

    private fun filter(
        list: List<co.datapipelines.datasources.Datasource>,
        query: String?,
    ): List<co.datapipelines.datasources.Datasource> {
        if (query == null) return list
        val lower = query.lowercase()
        return list.filter { d ->
            d.name.lowercase().contains(lower) ||
                d.displayName.lowercase().contains(lower) ||
                (d.description?.lowercase()?.contains(lower) == true)
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
