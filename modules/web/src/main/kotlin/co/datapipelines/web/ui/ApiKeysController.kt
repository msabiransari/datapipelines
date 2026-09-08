package co.datapipelines.web.ui

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * `/settings/api-keys` — 091 reduced this screen to a LINK (ui-screens.md §4.10).
 *
 * Issuing and revoking keys moved to the API screen (`/api-console`, §4.18), where the
 * endpoints those keys call and the MCP connection they authenticate live. Keeping a second
 * key surface here would mean two tables of the same rows, and the settings one had no way to
 * show what an endpoint key is bound to.
 *
 * The ROUTE stays: the avatar menu, `AppNav`'s off-rail breadcrumb table and every bookmark
 * point at it, and answering them with a 404 to save one template would be a worse deal than
 * rendering a sentence and a link. It reads no keys at all now — the page has nothing to hide,
 * so it needs nothing from the repository.
 */
@Controller
class ApiKeysController(
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/settings/api-keys")
    fun apiKeys(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "settings/api-keys"
    }
}
