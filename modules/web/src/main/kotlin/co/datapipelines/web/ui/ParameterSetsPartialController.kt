package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The Parameter Sets catalog fragment (#374) — in its OWN controller so no other family's partial
 * controller grows a method.
 *
 * ONE route, ONE presentation ([ParameterSetsBrowseModel]): the `/parameter-sets` catalog's flat list —
 * every set the lens admits, or the matches of `q` (#415). The route is `parameter_set.read`, the
 * floor the page routes declare; every read goes through the caller's lens, so a set the lens hides
 * is not rendered, not counted, not paged — and not a search hit.
 *
 * The path keeps its historical `/tree` name because the catalog page requests it (#466 kept the
 * route and removed its sidebar presentations). The sidebar's tree levels (`prefix`) and nav search
 * (`scope=nav`) are gone with #466 — the sidebar has read `/api/v1/parameter-sets/tree` since #460. A
 * stale `scope` or `prefix` is not a bound parameter, so it is ignored like any other unknown query
 * value and the request answers the catalog list: degrading to the page's own list costs a stale
 * client nothing, where a 400 would add a refusal path for a parameter no client sends.
 */
@Controller
class ParameterSetsPartialController(
    private val browse: ParameterSetsBrowseModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/partials/parameter-sets/tree")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun catalog(
        model: Model,
        // The pager's offset (the catalog template's prev/next links); page one when absent.
        @RequestParam(required = false) offset: Int?,
        // #415 — the catalog's name search.
        @RequestParam(required = false) q: String?,
    ): String {
        val principal = currentPrincipal()
        return browse.fillWrapper(model, principal.requireWorkspace().id, lens.viewFor(principal), q, offset ?: 0)
    }
}
