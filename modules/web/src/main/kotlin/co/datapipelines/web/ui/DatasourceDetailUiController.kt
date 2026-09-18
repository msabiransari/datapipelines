package co.datapipelines.web.ui

import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.visibleDialectProperties
import co.datapipelines.typesystem.Dialect
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/**
 * The datasource detail page — a read-only Tables view for every dialect (162, #156). A LAKE
 * datasource shows its dp-lake catalog as a namespace tree of the tables REGISTERED there
 * (round 089 §A, metadata-db §4.15); every other dialect shows its LIVE schemas → tables →
 * columns through [SchemaIntrospector] ([DatasourceSchemaTreeBrowseModel]). Both trees follow
 * the 058/067 explorer pattern — one level per request, the shared `tpl-*` classes so the
 * existing explorer CSS and `template-explorer.js` apply unchanged — and neither offers a
 * form: LAKE registration stays REST/MCP-only (R10), and introspection has nothing to write.
 *
 * An unknown or invisible (other-workspace) name redirects to the listing, on every dialect:
 * the detail reveals nothing the listing would not.
 */
@Controller
class DatasourceDetailUiController(
    private val datasources: DatasourceRegistry,
    private val lakeTables: LakeTableRegistryService,
    private val browse: LakeTableBrowseModel,
    private val schemaTree: DatasourceSchemaTreeBrowseModel,
    private val themeResolver: ThemeResolver,
    // 118 §7.3 — the learned facts rendered inline under the catalog tree, read-only.
    private val semantics: co.datapipelines.application.semantics.SemanticsService,
) {
    /** The page: the LAKE registry for a LAKE datasource, the schema tree for every other dialect. */
    @GetMapping("/datasources/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun detail(
        model: Model,
        request: HttpServletRequest,
        @PathVariable name: String,
    ): String {
        val datasource = visible(name) ?: return "redirect:/datasources"
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("datasource", datasource)
        // 109 §B — the non-secret dialect properties, the same §5.6-classified projection the
        // REST detail returns: the operator sees region/unsigned/catalog.kind the row actually
        // runs with, and never a secret-valued key, on either surface.
        model.addAttribute("dialectProperties", visibleDialectProperties(datasource.dialect, datasource.properties.dialect))
        if (datasource.dialect == Dialect.LAKE) {
            browse.fillLevel(model, lakeTables.list(datasource), prefix = null, offset = 0)
        } else {
            schemaTree.fillRoot(model, datasource)
        }
        // 118 §7.3 — the same rows and the same fragment as the list's Facts dialog. The page
        // is behind READ_RESOURCES, so a principal is always present here; the null branch is
        // the "no facts" render, never a refusal.
        val facts =
            principal()?.let {
                semantics.list(
                    it,
                    datasource,
                    co.datapipelines.application.semantics.SemanticsService
                        .ListQuery(),
                )
            }
                ?: emptyList()
        DatasourceFactsModel.fill(model, datasource, facts)
        RoleModel.stamp(model, principal())
        return "datasources/detail"
    }

    /**
     * One LAKE tree level, for a folder's lazy expansion (the `/partials/templates` rule:
     * server-side prefix derivation, one level per request, at any size). Under `/partials`,
     * which the ScopeInterceptor governs as default-deny — hence the explicit floor, the same
     * read scope the listing itself sits on.
     */
    @GetMapping("/partials/datasources/{name}/lake-tables")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun level(
        model: Model,
        @PathVariable name: String,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val datasource = visibleLake(name) ?: return "redirect:/datasources"
        model.addAttribute("datasource", datasource)
        return browse.fillLevel(model, lakeTables.list(datasource), prefix, offset ?: 0)
    }

    /**
     * A schema's tables, paged — a discovered-schema dialect's tree level (162, #156), the same
     * lazy-per-request contract as [level] above. `schema` is the dotted namespace path a
     * folder's own link carries; absent/blank is the flat root (no schema tier).
     */
    @GetMapping("/partials/datasources/{name}/tables")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun tables(
        model: Model,
        @PathVariable name: String,
        @RequestParam(required = false) schema: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val datasource = visible(name) ?: return "redirect:/datasources"
        return schemaTree.fillTables(model, datasource, schema, offset ?: 0)
    }

    /** One table's columns — the tree's terminal level, unpaged (162, #156). */
    @GetMapping("/partials/datasources/{name}/tables/{table}/columns")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun columns(
        model: Model,
        @PathVariable name: String,
        @PathVariable table: String,
        @RequestParam(required = false) schema: String?,
    ): String {
        val datasource = visible(name) ?: return "redirect:/datasources"
        return schemaTree.fillColumns(model, datasource, schema, table)
    }

    /** The datasource when it is visible to the caller, whatever its dialect. */
    private fun visible(name: String): Datasource? {
        val workspaceId = principal()?.workspace?.id ?: return null
        return datasources.getVisible(name, workspaceId)
    }

    /** The datasource when it is visible to the caller AND lake-dialect, else null. */
    private fun visibleLake(name: String): Datasource? = visible(name)?.takeIf { it.dialect == Dialect.LAKE }

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
}
