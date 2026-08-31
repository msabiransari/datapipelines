package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Fills the layout's workspace switcher for every [Controller] screen (ui-screens.md §3):
 * the caller's memberships and the ACTIVE workspace's name.
 *
 * Also supplies the global layout chrome attributes so no controller can forget them:
 * `activeTheme` (a missing value renders a `themes/null.css` URL, which 404s and leaves
 * every design token unresolved — no borders, no surfaces), `authenticated` (the layout
 * hides the nav links and logout for anonymous requests), and `currentPath` (nav active
 * state). Controllers that still set `activeTheme` themselves simply override the
 * advice's value with the same one.
 *
 * Reads go through [WorkspaceService]'s 60s liveness cache, so one membership read is
 * shared by the advice and any controller that asks again in the same request.
 *
 * Screens without an authenticated principal (the login page) get nothing: the advice
 * silently omits the workspace attributes rather than erroring, and `layouts/default`
 * hides the switcher when `workspaceOptions` is absent.
 */
@ControllerAdvice(annotations = [Controller::class])
class UiWorkspaceAdvice(
    private val workspaceService: WorkspaceService,
    private val themeResolver: ThemeResolver,
) {
    @ModelAttribute("workspaceOptions")
    fun workspaceOptions(): List<WorkspaceMembership>? = principal()?.let { workspaceService.memberships(it.userId) }

    @ModelAttribute("activeWorkspace")
    fun activeWorkspace(): String? = principal()?.workspace?.name

    @ModelAttribute("activeTheme")
    fun activeTheme(request: HttpServletRequest): String = themeResolver.resolve(request)

    @ModelAttribute("authenticated")
    fun authenticated(): Boolean = principal() != null

    @ModelAttribute("currentPath")
    fun currentPath(request: HttpServletRequest): String = request.requestURI

    /**
     * The `DP-Workspace` entry spliced into the layout's `hx-headers` JSON (ui-screens.md
     * §3): present exactly when an active workspace resolved, so every htmx partial call
     * rides the switched workspace even before the re-stamped session claim takes over on
     * the next full-page load.
     *
     * The fragment carries RAW quotes: `th:attr` HTML-escapes the whole attribute exactly
     * once, and the browser decodes it back to valid JSON. Baking `&quot;` entities in
     * here double-escapes (htmx then sends no headers and every mutation 403s) —
     * `LayoutHxHeadersTest` pins the rendered attribute.
     */
    @ModelAttribute("workspaceHeaderFragment")
    fun workspaceHeaderFragment(): String =
        principal()
            ?.workspace
            ?.name
            ?.let { name -> ",\"DP-Workspace\":\"${name.replace("\"", "")}\"" }
            ?: ""

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
}
