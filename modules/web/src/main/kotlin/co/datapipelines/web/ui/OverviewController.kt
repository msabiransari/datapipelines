package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant

/**
 * #556 — the five family overviews (ui-screens.md §4.25; shell design §8): the page a family's
 * menu item lands on, in the shell with that family's resource browser open (the layout's path
 * rule reads `/overview/<family>` as the family, `AppNav.matches`).
 *
 * Five literal mappings rather than one path variable: each family's read permission is its own
 * [RequiredScope], and a literal leaves no value a variable would have to refuse. One template,
 * `overview/family`, renders all five from an [OverviewPage].
 *
 * One request renders the whole page: the inventory is read ONCE ([FamilyInventory.forFamily], one
 * SQL snapshot) and so is Recently updated, both under the caller's [PromoterLens] view — a
 * promoter's numbers and list are the admitted ones, never the workspace's. Nothing here writes;
 * the page's one interaction, copying the starter prompt, is clipboard-only (`overview.mjs`).
 */
@Controller
class OverviewController(
    private val inventory: FamilyInventory,
    private val recent: FamilyRecent,
    private val themeResolver: ThemeResolver,
    private val lens: PromoterLens,
) {
    @GetMapping("/overview/pipelines")
    @RequiredScope(Permission.PIPELINE_READ)
    fun pipelines(
        model: Model,
        request: HttpServletRequest,
    ): String = render(OverviewPage.Family.PIPELINES, model, request)

    @GetMapping("/overview/templates")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun templates(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) lifecycle: String?,
    ): String {
        // #579: this URL IS the templates catalog, so it carries the catalog's contract — a
        // lifecycle value its family does not offer is the house 400 before any SQL. The other
        // overviews are pages, not catalogs; their filters live on the catalogs' own routes.
        CatalogLifecycle.parse(lifecycle, OverviewPage.Family.TEMPLATES.offered)
        return render(OverviewPage.Family.TEMPLATES, model, request)
    }

    @GetMapping("/overview/dashboards")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboards(
        model: Model,
        request: HttpServletRequest,
    ): String = render(OverviewPage.Family.DASHBOARDS, model, request)

    @GetMapping("/overview/visualizations")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun visualizations(
        model: Model,
        request: HttpServletRequest,
    ): String = render(OverviewPage.Family.VISUALIZATIONS, model, request)

    @GetMapping("/overview/parameter-sets")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun parameterSets(
        model: Model,
        request: HttpServletRequest,
    ): String = render(OverviewPage.Family.PARAMETER_SETS, model, request)

    private fun render(
        family: OverviewPage.Family,
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        val counted = inventory.forFamily(family.navigation, workspaceId, view)
        val latest = recent.forFamily(family.navigation, workspaceId, view)
        // A lazy dashboard arm whose derivation failed has already made both reads Unavailable;
        // asking it again must not turn the page into a 500, and an unknown arm reads as lensed.
        val lensed = runCatching { !family.lens(view).isEverything }.getOrDefault(true)
        model.addAttribute(MODEL, OverviewPage.of(family, lensed, counted, latest, Instant.now()))
        return VIEW
    }

    companion object {
        /** The one model attribute the template reads every number from. */
        const val MODEL = "overview"
        const val VIEW = "overview/family"
    }
}
