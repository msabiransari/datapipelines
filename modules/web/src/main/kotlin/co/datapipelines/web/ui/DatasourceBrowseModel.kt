package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import org.springframework.ui.Model

/**
 * The datasources screen's model, in one place for the page controller and the partial
 * controller (097 §A; ui-screens.md §5's BrowseModel rule, and the shape
 * [PipelineBrowseModel]/[TemplateBrowseModel] already had).
 *
 * It exists because the two controllers had built it twice. Both resolved the dialect wire,
 * paged, computed `hasMore`/`total` and searched — and the two searches had diverged: the
 * page matched three fields, the partial ten. `GET /datasources?q=postgres` (a reload, a
 * shared link, a boosted navigation) came back EMPTY while typing the same word into the same
 * box returned rows. A projection written twice is a projection that will disagree; the fix
 * is not a third copy of the ten-field filter but one object that both surfaces render
 * through, pinned by `DatasourceBrowseParityTest`.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code with no allowlist (015 / module-structure §8.4), and
 * `ArchitectureGuardTest` enforces it.
 */
class DatasourceBrowseModel(
    private val datasources: DatasourceRegistry,
) {
    /**
     * Fills [model] with the workspace-scoped listing [principal] may see, filtered by [q] and
     * [dialect] and paged from [offset] — every attribute the `partials/datasources` fragment
     * reads, and nothing a particular surface has to add for itself.
     *
     * §5.3: the visible set is the repository's own predicate, never a post-filter, and a
     * principal with no active workspace sees the empty state rather than an error.
     */
    fun fillList(
        model: Model,
        principal: AuthenticatedPrincipal?,
        q: String?,
        dialect: String?,
        offset: Int?,
    ) {
        val page = maxOf(0, offset ?: 0)
        val query = q?.trim()?.takeIf { it.isNotEmpty() }
        val workspaceId = principal?.workspace?.id
        val visible = if (workspaceId == null) emptyList() else datasources.listVisible(dialectOf(dialect), workspaceId)
        val all = filter(visible, query)
        model.addAttribute("datasources", all.drop(page).take(PAGE_SIZE))
        model.addAttribute("q", q ?: "")
        model.addAttribute("selectedDialect", dialect ?: "")
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", all.size > page + PAGE_SIZE)
        model.addAttribute("total", all.size)
        model.addAttribute("scopes", principal?.scopes?.map { it.name }?.toSet() ?: emptySet<String>())
    }

    /** An unrecognised wire value filters nothing — the behaviour both surfaces already had. */
    private fun dialectOf(dialect: String?): Dialect? =
        dialect?.trim()?.takeIf { it.isNotEmpty() }?.let { wire ->
            Dialect.entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
        }

    /**
     * The screen's search covers EVERY column the table renders (§4.5): name +
     * readonly, dialect, workspace, URL, username, credential kind, last-test state — plus
     * description, which is searchable though only the modal shows it. A search that silently
     * ignores a visible column reads as "no results" to the user (029). The workspace column
     * renders the bound workspace's name or the literal `global`, so both match; the last-test
     * column renders `ok`, `failed` or `never tested`, so all three do — which is what makes
     * "show me the broken datasources" a search rather than a manual scan (061/T84).
     *
     * This is the partial's ten-field filter, which is the §4.5 contract; the page's
     * three-field copy is what 097 deleted.
     */
    private fun filter(
        list: List<Datasource>,
        query: String?,
    ): List<Datasource> {
        if (query == null) return list
        val lower = query.lowercase()
        return list.filter { d ->
            d.name.lowercase().contains(lower) ||
                d.displayName.lowercase().contains(lower) ||
                d.dialect.wire
                    .lowercase()
                    .contains(lower) ||
                d.jdbcUrl.lowercase().contains(lower) ||
                (d.username?.lowercase()?.contains(lower) == true) ||
                d.credentialKind.wire
                    .contains(lower) ||
                (d.workspaceName ?: "global").lowercase().contains(lower) ||
                (d.isReadonly && "readonly".contains(lower)) ||
                lastTestLabel(d).contains(lower) ||
                (d.description?.lowercase()?.contains(lower) == true)
        }
    }

    /** Exactly the words the §8.1B column renders, so the search and the screen agree. */
    private fun lastTestLabel(datasource: Datasource): String =
        when (datasource.lastTest?.ok) {
            null -> "never tested"
            true -> "ok"
            false -> "failed"
        }

    companion object {
        /** The datasources screen's page size — the value both surfaces have always used. */
        const val PAGE_SIZE = 25

        /** The fragment both the page's first paint and every later refresh render. */
        const val LIST_VIEW = "partials/datasources"
    }
}
