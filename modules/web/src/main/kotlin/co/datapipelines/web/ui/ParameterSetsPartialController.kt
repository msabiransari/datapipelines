package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The Parameter Sets tree fragment (#374) — [DashboardPartialController]'s `/dashboards/tree` for the
 * third artifact family, in its OWN controller so no other family's partial controller grows a method.
 *
 * ONE route, two presentations chosen by `scope` ([ParameterSetsBrowseModel]): the sidebar's lazy tree
 * (`scope=nav`, or any `prefix` request — a level is always a sidebar level) and the `/parameter-sets`
 * catalog's flat, server-paged list (any other value, absent included). The route is
 * `parameter_set.read`, the floor the page routes declare; every read goes through the caller's lens, so
 * a set the lens hides is not rendered, not counted and not paged. Every sidebar response carries
 * [ParameterSetsBrowseModel.NAV_STAMP_HEADER] so the rail refuses a level rendered under another
 * workspace or lens.
 */
@Controller
class ParameterSetsPartialController(
    private val browse: ParameterSetsBrowseModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/partials/parameter-sets/tree")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun tree(
        model: Model,
        response: HttpServletResponse,
        @RequestParam(required = false) prefix: String?,
        // The pager's offset (the level and catalog templates' prev/next links); page one when absent.
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) scope: String?,
    ): String {
        val principal = currentPrincipal()
        val workspace = principal.requireWorkspace()
        val view = lens.viewFor(principal)
        val nav = prefix != null || scope == ParameterSetsBrowseModel.SCOPE_NAV
        return if (nav) {
            response.setHeader(ParameterSetsBrowseModel.NAV_STAMP_HEADER, ParameterSetsBrowseModel.navStamp(workspace.name, view))
            browse.fillLevel(model, workspace.id, view, prefix, offset ?: 0)
        } else {
            browse.fillCatalog(model, workspace.id, view, offset ?: 0)
        }
    }
}
