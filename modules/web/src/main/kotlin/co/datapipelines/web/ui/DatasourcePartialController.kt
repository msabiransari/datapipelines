package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.datasources.DatasourceWorkspaceRules
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The datasources screen's htmx partials (ui-screens.md §4.5/§5): the workspace-scoped
 * list fragment, the connection-test row fragment, and the REGISTER action of the §4.5
 * modal — which applies the SAME [DatasourceWorkspaceRules] the REST §9.1 endpoint applies,
 * then crosses the SAME `registry.save` boundary (validation, encryption, pool eviction).
 * Two write paths, one rule set — that is the point of the extracted component.
 */
@Controller
class DatasourcePartialController(
    private val datasources: DatasourceRegistry,
    private val rules: DatasourceWorkspaceRules,
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
        val workspaceId = principal()?.workspace?.id
        val visible =
            if (workspaceId == null) {
                emptyList()
            } else {
                datasources.listVisible(dialectFilter, workspaceId)
            }
        val all = filter(visible, q?.trim()?.takeIf { it.isNotEmpty() })
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
        // §5.3: an invisible datasource behaves as not-found — the row fragment renders the
        // "not found" state for it, same as the REST §9.6 probe's 404.
        val workspaceId = principal()?.workspace?.id
        val visible = workspaceId != null && datasources.getVisible(name, workspaceId) != null
        val result = if (visible) datasources.testConnection(name) else null
        model.addAttribute("testName", name)
        model.addAttribute("testResult", result)
        return "partials/datasource-row"
    }

    /**
     * The register modal's action. Form-encoded fields (the §5 idiom), bound here into a
     * [Datasource] and gated by the shared rules — `global` (admin-only; the checkbox is
     * visible-disabled for members) and `readonly` ride along as booleans. The active
     * workspace is the default binding; a member with the gate off gets the refusal HTML.
     */
    @Suppress("LongParameterList") // the register form's fields, one parameter each (the §5 form-encoding idiom)
    @PostMapping("/partials/datasources")
    fun register(
        @RequestParam name: String,
        @RequestParam dialect: String,
        @RequestParam jdbcUrl: String,
        @RequestParam username: String,
        @RequestParam password: String,
        @RequestParam(required = false) displayName: String?,
        @RequestParam(required = false) description: String?,
        @RequestParam(required = false, defaultValue = "false") global: Boolean,
        @RequestParam(required = false, defaultValue = "false") readonly: Boolean,
    ): Any {
        val principal = principal() ?: error("No authenticated principal")
        return try {
            val resolvedDialect =
                Dialect.entries.firstOrNull { it.wire.equals(dialect.trim(), ignoreCase = true) }
                    ?: return refused("Unknown dialect '$dialect'.")
            val workspaceId = rules.resolveCreateBinding(principal, global, null)
            val datasource =
                Datasource(
                    name = name.trim(),
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim(),
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    dialect = resolvedDialect,
                    jdbcUrl = jdbcUrl.trim(),
                    username = username.trim(),
                    password = password,
                    isReadonly = readonly,
                    workspaceId = workspaceId,
                )
            if (datasources.exists(datasource.name)) {
                return refused("A datasource named '${datasource.name}' already exists.")
            }
            datasources.save(datasource, principal.userId)
            // htmx honors HX-Redirect with a full-page navigation — the screen re-renders
            // with the new row (the api-keys screen's post-create choreography).
            ResponseEntity.ok().header("HX-Redirect", "/datasources").body("")
        } catch (e: co.datapipelines.typesystem.DatapipelinesException) {
            refused(e.message ?: "The connection details were rejected.")
        }
    }

    /** The refusal the modal renders inline — never an error page for an expected 4xx. */
    private fun refused(why: String): ResponseEntity<String> =
        ResponseEntity.badRequest().body(
            """<div class="ds-surface" style="border:1px solid var(--accent-danger);border-radius:var(--radius-base);""" +
                """padding:var(--gap-sm);color:var(--text-primary);font-size:var(--text-sm);max-width:480px">""" +
                why.replace("&", "&amp;").replace("<", "&lt;") +
                "</div>",
        )

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

    private fun scopes(): Set<String> = principal()?.scopes?.map { it.name }?.toSet() ?: emptySet()

    private companion object {
        const val PAGE_SIZE = 25
    }
}
