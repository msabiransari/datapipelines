package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
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
 * One request renders the whole page: the inventory is read ONCE ([FamilyInventory.forFamily], or
 * [FamilyInventory.chartsFor] for a family carrying the #598 charts — one SQL snapshot either way)
 * and so is Recently updated, both under the caller's [PromoterLens] view — a
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
    ): String = render(OverviewPage.Family.TEMPLATES, model, request)

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
        val latest = recent.forFamily(family.navigation, workspaceId, view)
        // A lazy dashboard arm whose derivation failed has already made both reads Unavailable;
        // asking it again must not turn the page into a 500, and an unknown arm reads as lensed.
        val lensed = runCatching { !family.lens(view).isEverything }.getOrDefault(true)
        // #598a: still ONE inventory read per request — the chart group for a family that carries
        // the stacked charts, the four-bucket inventory for every other.
        val page =
            when (family.charts) {
                OverviewStacked.ChartSet.LIFECYCLE -> {
                    OverviewPage.of(family, lensed, inventory.chartsFor(family.navigation, workspaceId, view), latest, Instant.now())
                }

                OverviewStacked.ChartSet.STANDARD -> {
                    OverviewPage.of(family, lensed, inventory.forFamily(family.navigation, workspaceId, view), latest, Instant.now())
                }
            }
        model.addAttribute(MODEL, page)
        return VIEW
    }

    companion object {
        /** The one model attribute the template reads every number from. */
        const val MODEL = "overview"
        const val VIEW = "overview/family"
    }
}
