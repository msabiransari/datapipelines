package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The datasources screen (ui-screens.md §4.5) under the workspaces model: the listing is
 * scoped to the ACTIVE workspace's bound datasources plus all global ones — the same
 * `listVisible` predicate the REST §9.2 listing applies, never a post-filter.
 *
 * A principal with zero memberships (possible under `closed`) sees the empty state, not an
 * error page (workspaces design §7) — `workspace` resolves null and the listing is empty.
 */
@Controller
class DatasourceUiController(
    private val datasources: DatasourceRegistry,
    private val workspacesProperties: WorkspacesProperties,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/datasources")
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
        model.addAttribute("isAdmin", isAdmin())
        model.addAttribute("memberDatasourcesEnabled", workspacesProperties.memberDatasourcesEnabled)
        model.addAttribute("canRegister", isAdmin() || workspacesProperties.memberDatasourcesEnabled)
        model.addAttribute("activeWorkspace", principal()?.workspace?.name)
        model.addAttribute(
            "bindingHint",
            principal()?.workspace?.name?.let { name -> "Bound to your active workspace: $name" } ?: "",
        )
        val page = maxOf(0, offset ?: 0)
        val size = PAGE_SIZE
        val dialectFilter =
            dialect?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
                Dialect.entries.firstOrNull { it.wire.equals(d, ignoreCase = true) }
            }
        val all = filter(visible(dialectFilter), q?.trim()?.takeIf { it.isNotEmpty() })
        val items = all.drop(page).take(size)
        model.addAttribute("datasources", items)
        model.addAttribute("q", q ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", all.size > page + size)
        model.addAttribute("total", all.size)
        return "datasources/list"
    }

    /** §5.3: the workspace-scoped visible set; empty when no active workspace resolved. */
    internal fun visible(dialectFilter: Dialect?): List<Datasource> {
        val workspaceId = principal()?.workspace?.id ?: return emptyList()
        return datasources.listVisible(dialectFilter, workspaceId)
    }

    private fun filter(
        list: List<Datasource>,
        query: String?,
    ): List<Datasource> {
        if (query == null) return list
        val lower = query.lowercase()
        return list.filter { d ->
            d.name.lowercase().contains(lower) ||
                d.displayName.lowercase().contains(lower) ||
                (d.description?.lowercase()?.contains(lower) == true)
        }
    }

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

    private fun isAdmin(): Boolean = principal()?.isAdmin == true

    private fun scopes(): Set<String> = principal()?.scopes?.map { it.name }?.toSet() ?: emptySet()

    private companion object {
        const val PAGE_SIZE = 25
    }
}
