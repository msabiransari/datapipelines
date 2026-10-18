package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * #583 — the Datasources family overview (ui-screens.md §4.25): the page the Datasources menu
 * item lands on, in the shell with the datasources browser open.
 *
 * It lives outside [OverviewController] on purpose: the five lifecycle families read their
 * numbers through [FamilyInventory]/[FamilyRecent] under the caller's promoter lens, and a
 * datasource has no lens — its inventory is the workspace's visible set (the granted-or-global
 * predicate the REST listing applies) and its recent list is "added to this workspace",
 * grants-ordered. Both reads are the datasources module's, built into the same generalized
 * [OverviewPage] frame (one panel, many charts; one recent card, many sections).
 *
 * Nothing here writes; the §7.6 row is `datasource.read`, the lowest operation whose row admits
 * a metadata read (`ReadFloorTest`). The route declares a literal path, as the other five do.
 */
@Controller
class DatasourceOverviewController(
    private val themeResolver: ThemeResolver,
) {
    @GetMapping(PATH)
    @RequiredScope(Permission.DATASOURCE_READ)
    fun overview(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        // The frame renders before its reads land; the page's data commit fills the panel
        // (counts, connection health, by dialect) and the recent card (Recently added).
        model.addAttribute(
            OverviewController.MODEL,
            OverviewPage(
                family = "datasources",
                title = "Datasources",
                catalogHref = "/datasources",
                lensed = false,
                inventory = OverviewPage.InventoryPanel(emptyList(), emptyList()),
                unavailable = null,
                recent = null,
                guided = false,
            ),
        )
        return OverviewController.VIEW
    }

    companion object {
        /** The overview path — also the layout's path rule for opening the datasources browser. */
        const val PATH = "/overview/datasources"
    }
}
