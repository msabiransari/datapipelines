package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Instant

/**
 * #585 — the API home (ui-screens.md §4.18a): the page the API menu item lands on, in the shared
 * overview frame ([OverviewPage], `overview/family.html`), with the console (`/api-console`) as
 * its View all. [ApiHomePage] builds its panels and recent card (#643).
 *
 * Like [DatasourceOverviewController] it lives outside [OverviewController]: an endpoint and a
 * key are not a lifecycle family. Its two reads ([ApiHomeReads]) are the console's and the Keys
 * page's, each under that surface's own narrowing — the promoter lens on endpoints, the
 * `api_key.read` rule on keys — so the home's counts are those pages' rows, counted.
 *
 * A failed endpoints read makes the whole inventory unavailable (the frame has one such line);
 * a failed keys read alone drops the keys tile and empties the keys chart, whose caption and
 * note say so — no number is shown for what was not read, never a zero. Nothing here writes;
 * the §7.6 row is `endpoint.read`, the console's own (`ReadFloorTest`).
 */
@Controller
class ApiOverviewController(
    private val reads: ApiHomeReads,
    private val mcpConnection: McpConnectionCard,
    private val themeResolver: ThemeResolver,
) {
    private val log = LoggerFactory.getLogger(ApiOverviewController::class.java)

    @GetMapping(PATH)
    @RequiredScope(Permission.ENDPOINT_READ)
    fun overview(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        val principal = currentPrincipal()
        RoleModel.stamp(model, principal)
        mcpConnection.stamp(model)
        val now = Instant.now()
        val endpoints = read("endpoints", principal) { reads.endpoints(principal) }
        val keys = read("keys", principal) { reads.keys(principal) }
        val keysHeading = if (principal.holds(Permission.API_KEY_READ)) WORKSPACE_KEYS else YOUR_KEYS
        val lensed = reads.lensed(principal)
        model.addAttribute(
            OverviewController.MODEL,
            OverviewPage(
                family = FAMILY,
                title = "API",
                catalogHref = CONSOLE,
                lensed = lensed,
                inventory = endpoints?.let { ApiHomePage.panel(it, keys?.let { k -> KeyCounts.of(k, now) }, keysHeading, lensed) },
                unavailable = if (endpoints == null) "The endpoints could not be read." else null,
                recent = ApiHomePage.recent(endpoints, keys, now),
                guided = true,
            ),
        )
        return OverviewController.VIEW
    }

    /** One read at the boundary FamilyInventory draws: its value, or null and a log line saying which read failed. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> read(
        what: String,
        principal: AuthenticatedPrincipal,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (failure: Exception) {
            log.warn("api overview {} unavailable for workspace {}: {}", what, principal.workspace?.id, failure.toString())
            null
        }

    companion object {
        /** The home's path — under the generic `/overview/<section>` rule, so the rail lights it with no special case. */
        const val PATH = "/overview/api-console"

        /** The `data-overview` attribute and the guide's file name, `overview/guide-api-console.html`. */
        const val FAMILY = "api-console"

        /** The console: the home's View all, its endpoint bars' destination. */
        const val CONSOLE = "/api-console"

        /** The Keys page: the keys tile's and the keys chart's destination. */
        const val KEYS_PAGE = "/api-keys"

        const val YOUR_KEYS = "Your keys"
        const val WORKSPACE_KEYS = "Workspace keys"
    }
}
