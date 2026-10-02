package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.dashboards.runtime.DashboardRuntime
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The canonical dashboard workspace READ page (#400, the parent ruling #396): `GET
 * /dashboards/{id}?version=N&tab=…`. One URL names one dashboard, one viewed version and one
 * tab — the tabbed, version-explicit workspace that replaces the L3b list-plus-viewer pair.
 * The old `/dashboards/{id}/preview` route is a compatibility redirect into it
 * ([DashboardUiController.preview], SEE_OTHER), so the deep links #369 shipped keep working.
 *
 * The floor is [Permission.DASHBOARD_READ] — the page is a read surface for every admitted
 * reader, the promoter through its lens; Execute (the board's runtime and the refreshes pane)
 * and the lifecycle verbs stay their own permission-gated calls, role-hidden in the markup
 * (RoleVisibilityRenderTest; the brief's roles table). The browser never authors a dashboard:
 * there is no create or update control here — agents author over MCP, and a person releases
 * from the Versions tab (#396's sentence).
 *
 * ## The version model
 * [DashboardWorkspaceModel.resolve] decides what the page shows. The default is the current
 * RELEASED version; `?version=N` is #369's named-version rule (a DRAFT only to the
 * everything-lens, DISCARDED to nobody, else the family's 404 naming the version). No served
 * release and no named version is NOT an error — it is the choose-a-version state (#409):
 * the record exists in the caller's tree, the `h1` shows its name, and the draft (when the
 * caller's lens shows one) is offered with its preview link and the "no release yet"
 * sentence. The runtime configuration is read ONCE here for the selected version — the ONE
 * Plotly bundle the page declares (`renderer.bundle`, the implementation spec's §10.4) — and
 * a board that cannot run is the refusal state IN PLACE, the name still in the `h1`.
 *
 * ## Full navigations, deliberately
 * Every version switch and every entry into this page is a FULL navigation (`hx-boost="false"`
 * on every link that names a version or a board): the two Plotly bundles must never travel
 * between documents (dashboards.md §6.4's one-bundle rule; #369 review F1). The tabs
 * themselves are in-page state ([co.datapipelines.web.ui.DashboardWorkspaceModel] resolves the
 * server-side default; `static/js/dashboards/workspace.js` owns the switches) — a tab change
 * swaps no version and cancels no poll.
 */
@Controller
class DashboardWorkspaceController(
    private val workspace: DashboardWorkspaceModel,
    private val runtime: DashboardRuntime,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the page resolves and renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/dashboards/{id}")
    @RequiredScope(Permission.DASHBOARD_READ)
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
        val requested = DashboardWorkspaceModel.parseRequestedVersion(version)
        val resolved = workspace.resolve(workspaceId, view, id, requested)

        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        model.addAttribute("dashboardId", id.toString())
        // ALWAYS: the choose-a-version state (#409) and the refusal state keep the name in the
        // h1 — a dashboard the tree just linked to never renders an empty heading again.
        model.addAttribute("dashboardName", resolved.name)
        // #350 — the sidebar tree's current-leaf hook reads the FULL path, so the rail's
        // Dashboards tree can mark this leaf and reveal its folders (editor.html:11's shape).
        model.addAttribute("navCurrentPath", resolved.name)

        // Affordances by PERMISSION check, not a RoleModel flag — RoleModel has no dashboard
        // axis (the brief: derive, never add one). `holds` is the same predicate the routes'
        // @RequiredScope rows judge, so the markup cannot render a verb the route refuses.
        val canBindKeys = principal.holds(Permission.DASHBOARD_KEY_BIND)
        stampAffordances(model, principal)

        // The tab set is closed server-side; requesting Keys without the binding permission
        // resolves to Board BEFORE any keys read is attempted (the page never fetches what
        // the role cannot read — PipelineWorkspaceTab's rule).
        val activeTab = DashboardWorkspaceTab.fromWire(tab, canBindKeys)
        model.addAttribute("activeTab", activeTab.wire)

        // The version state the chip, the selector and the choose-a-version state read.
        model.addAttribute("hasSelected", resolved.hasSelected)
        model.addAttribute("viewedVersion", resolved.selectedVersion)
        model.addAttribute("viewedLabel", resolved.viewedLabel ?: NO_VERSION_SELECTED)
        model.addAttribute("viewedIsDraft", resolved.selectedIsDraft)
        model.addAttribute("viewedIsCurrent", resolved.selectedIsCurrent)
        model.addAttribute("servedVersion", resolved.served?.version)
        model.addAttribute("hasDraft", resolved.draftVersion != null)
        model.addAttribute("draftVersion", resolved.draftVersion)
        // The board's version channel: the named version rides data-dp-dashboard-version (the
        // #369 attribute, an integer); the default resolution writes NOTHING, so the glue's
        // init stays "released" — the first-party page's own value (dashboards.md §6.1).
        model.addAttribute("boardVersion", if (requested != null) resolved.selectedVersion else null)
        model.addAttribute(
            "versions",
            resolved.versions.map {
                VersionRow(
                    version = it.version,
                    status = it.status.name,
                    isServed = it.version == resolved.served?.version,
                    isSelected = it.version == resolved.selectedVersion,
                )
            },
        )

        // A version selected: the runtime configuration read — the ONE bundle the page
        // declares, and the release/dependency oracle. A board that cannot run is the
        // refusal state IN PLACE (never a blank pane, never an error page): the name stays
        // in the h1, the code and sentence fill the board pane's refusal region, and no
        // bundle loads (the template renders the script block only with a bundle).
        if (resolved.hasSelected) {
            try {
                val config = runtime.config(principal, id, resolved.selectedVersion)
                model.addAttribute("bundle", config.get("renderer").get("bundle").asText())
            } catch (e: DatapipelinesException) {
                model.addAttribute("refusalCode", e.code)
                model.addAttribute("refusalMessage", e.message)
            }
        }
        return VIEW
    }

    /** One row of the version selector and the choose-a-version links: the facts the markup reads. */
    data class VersionRow(
        val version: Int,
        val status: String,
        val isServed: Boolean,
        val isSelected: Boolean,
    )

    /** The tab set the page's query contract admits (#400's floor: five, Board the default). */
    enum class DashboardWorkspaceTab(
        val wire: String,
    ) {
        BOARD("board"),
        OVERVIEW("overview"),
        REFRESHES("refreshes"),
        VERSIONS("versions"),
        KEYS("keys"),
        ;

        companion object {
            /**
             * Unknown or missing resolves to Board; requesting Keys without the key-binding
             * permission ALSO resolves to Board — before any keys read is attempted.
             */
            fun fromWire(
                raw: String?,
                canBindKeys: Boolean,
            ): DashboardWorkspaceTab =
                entries
                    .firstOrNull { it.wire == raw }
                    ?.takeIf { it != KEYS || canBindKeys }
                    ?: BOARD
        }
    }

    companion object {
        /**
         * The affordance booleans, stamped ONCE for every surface that renders the verbs —
         * the workspace PAGE and the lazy Versions partial (whose htmx fetch carries a fresh
         * model: unstamped there, the buttons silently vanish, which is how the browser
         * suite's first run caught it). The booleans are PERMISSION checks, not a RoleModel
         * flag — no dashboard axis exists there and none was added (the brief's rule).
         */
        fun stampAffordances(
            model: Model,
            principal: AuthenticatedPrincipal,
        ) {
            model.addAttribute("canRelease", principal.holds(Permission.DASHBOARD_RELEASE))
            model.addAttribute("canManageVersions", principal.holds(Permission.DASHBOARD_VERSION_MANAGE))
            model.addAttribute("canSwitch", principal.holds(Permission.DASHBOARD_SWITCH_VERSION))
            model.addAttribute("canDelete", principal.holds(Permission.DASHBOARD_DELETE))
            model.addAttribute("canBindKeys", principal.holds(Permission.DASHBOARD_KEY_BIND))
        }

        /** Compatibility anchor: the template file name the render tests consume. */
        const val VIEW = "dashboards/board"

        /** The choose-a-version state's chip word — no version, no invented metadata. */
        const val NO_VERSION_SELECTED = "no release yet"
    }
}
