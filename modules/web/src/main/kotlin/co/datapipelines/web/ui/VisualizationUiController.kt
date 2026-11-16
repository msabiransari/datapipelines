package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The visualizations family's two signed-in pages (#399, parent #396), both READS on `visualization.read`:
 *
 * - `GET /visualizations?q=&offset=` — the flat CATALOG (the `/dashboards` and `/pipelines` catalogs' shape):
 *   every visualization the caller may read, or the matches of the lensed name search, each row a link into
 *   the workspace; the folder tree lives in the sidebar's Visualizations branch.
 * - `GET /visualizations/{id}?version=&tab=` — the tabbed, version-explicit WORKSPACE: Preview (fixture mode,
 *   the default), Overview, Evidence, Used by, Versions (with the lifecycle verbs' dialogs). One URL names one
 *   visualization, one viewed version and one tab.
 *
 * Neither path is admitted by the capability preview's public pattern (`/visualizations/{id}/preview` is
 * segment-exact): both pages, and every fragment, require a signed-in session. The browser never authors a
 * visualization: no create, update or import control exists on either page (#396).
 *
 * A link that names a version is a prepared main-content navigation (`hx-boost="true"`): the shell stays and the
 * page's catalog loads the chart engine's scripts ([JsRenderedAssetConfig.scripts]) once per document.
 */
@Controller
class VisualizationUiController(
    private val browse: VisualizationBrowseModel,
    private val workspace: VisualizationWorkspaceModel,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the pages render the caller's view. */
    private val lens: PromoterLens,
) : ShellPage {
    @GetMapping("/visualizations")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) lifecycle: String? = null,
    ): String {
        val bucket = CatalogLifecycle.parse(lifecycle, CatalogLifecycle.VISUALIZATIONS)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        browse.fillWrapper(model, principal.requireWorkspace().id, lens.viewFor(principal), q, offset ?: 0, bucket)
        return LIST_VIEW
    }

    @GetMapping("/visualizations/{id}")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun workspace(
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) tab: String?,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        val requested = VisualizationWorkspaceModel.parseRequestedVersion(version)
        val resolved = workspace.resolve(workspaceId, view, id, requested)

        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        stampAffordances(model, principal)
        model.addAttribute("visualizationId", id.toString())
        // ALWAYS the name in the h1 — the choose-a-version state too.
        model.addAttribute("visualizationName", resolved.name)
        model.addAttribute("displayName", resolved.selected?.body?.displayName ?: resolved.displayName)
        // #350 — the sidebar tree's current-leaf hook reads the FULL path.
        model.addAttribute("navCurrentPath", resolved.name)
        model.addAttribute("activeTab", VisualizationWorkspaceModel.Tab.fromWire(tab).wire)
        model.addAttribute("hasSelected", resolved.hasSelected)
        model.addAttribute("viewedVersion", resolved.selectedVersion)
        model.addAttribute("viewedLabel", resolved.viewedLabel ?: NO_VERSION_SELECTED)
        model.addAttribute("viewedIsDraft", resolved.selectedIsDraft)
        model.addAttribute("currentVersion", resolved.currentVersion)
        model.addAttribute("draftVersion", resolved.draftVersion)
        model.addAttribute(
            "versions",
            resolved.versions.map {
                VersionRow(
                    version = it.version,
                    status = it.status.name,
                    isCurrent = it.version == resolved.currentVersion,
                    isSelected = it.version == resolved.selectedVersion,
                )
            },
        )
        // The chart engine's script list — only when a version resolved (the template loads the chart scripts with it).
        if (resolved.selected != null) model.addAttribute("chartEngineScripts", JsRenderedAssetConfig.scripts)
        return WORKSPACE_VIEW
    }

    /** One row of the version selector and the choose-a-version links. */
    data class VersionRow(
        val version: Int,
        val status: String,
        val isCurrent: Boolean,
        val isSelected: Boolean,
    )

    companion object {
        const val LIST_VIEW = "visualizations/list"
        const val WORKSPACE_VIEW = "visualizations/workspace"

        /** The choose-a-version state's chip word — no version, no invented metadata. */
        const val NO_VERSION_SELECTED = "no version selected"

        /**
         * The verbs' affordances, stamped for the PAGE and the lazy Versions partial (a fresh htmx model): each
         * a PERMISSION check — `holds` is the predicate the routes' @RequiredScope rows judge, so the markup
         * cannot render a verb the route refuses. No RoleModel flag exists for visualizations; none is added.
         */
        fun stampAffordances(
            model: Model,
            principal: AuthenticatedPrincipal,
        ) {
            model.addAttribute("canRelease", principal.holds(Permission.VISUALIZATION_RELEASE))
            model.addAttribute("canManageVersions", principal.holds(Permission.VISUALIZATION_VERSION_MANAGE))
            model.addAttribute("canSwitch", principal.holds(Permission.VISUALIZATION_SWITCH_VERSION))
            model.addAttribute("canDelete", principal.holds(Permission.VISUALIZATION_DELETE))
        }
    }
}
