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
 * The LAKE datasource detail page (round 089 §A): a read-only namespace tree of the tables
 * REGISTERED in the dp-lake catalog (metadata-db §4.15), following the 058/067 explorer
 * pattern — one level per request, the shared `tpl-*` tree classes so the existing explorer
 * CSS and `template-explorer.js` apply unchanged.
 *
 * Registration stays REST/MCP-only (R10, agent-first authoring): this screen renders the
 * registry and says so, and offers no form. Non-LAKE datasources have no registry, so they get
 * no detail page — the route redirects to the listing, which is also what an invisible
 * (other-workspace) or unknown name does: the detail reveals nothing the listing would not.
 */
@Controller
class DatasourceDetailUiController(
    private val datasources: DatasourceRegistry,
    private val lakeTables: LakeTableRegistryService,
    private val browse: LakeTableBrowseModel,
    private val themeResolver: ThemeResolver,
    // 118 §7.3 — the learned facts rendered inline under the catalog tree, read-only.
    private val semantics: co.datapipelines.application.semantics.SemanticsService,
) {
    /**
     * The page. Only a LAKE datasource HAS a catalog to show; anything else (unknown,
     * invisible, or a dialect whose tables are discovered rather than registered) redirects to
     * the datasources listing.
     */
    @GetMapping("/datasources/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun detail(
        model: Model,
        request: HttpServletRequest,
        @PathVariable name: String,
    ): String {
        val datasource = visibleLake(name) ?: return "redirect:/datasources"
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("datasource", datasource)
        // 109 §B — the non-secret dialect properties, the same §5.6-classified projection the
        // REST detail returns: the operator sees region/unsigned/catalog.kind the row actually
        // runs with, and never a secret-valued key, on either surface.
        model.addAttribute("dialectProperties", visibleDialectProperties(datasource.dialect, datasource.properties.dialect))
        browse.fillLevel(model, lakeTables.list(datasource), prefix = null, offset = 0)
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
     * One tree level, for a folder's lazy expansion (the `/partials/templates` rule:
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

    /** The datasource when it is visible to the caller AND lake-dialect, else null. */
    private fun visibleLake(name: String): Datasource? {
        val workspaceId = principal()?.workspace?.id ?: return null
        return datasources.getVisible(name, workspaceId)?.takeIf { it.dialect == Dialect.LAKE }
    }

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
}
