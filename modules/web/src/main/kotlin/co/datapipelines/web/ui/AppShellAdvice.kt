package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * 079 §A/§B — the model the new shell chrome needs on EVERY screen, filled once here so no
 * controller can forget it (the same argument [UiWorkspaceAdvice] makes for `activeTheme`:
 * a screen that forgets one of these renders a shell with a blank breadcrumb or a headless
 * avatar, and nothing fails).
 *
 * Kept SEPARATE from [UiWorkspaceAdvice] on purpose. That advice is the workspace switcher
 * and the layout's pre-076 chrome, and it is pinned by `UiLayoutChromeAdviceTest`; folding
 * four more attributes and two repositories into it would make one class the answer to
 * "what does the layout need" and one test the answer to "did it get it". Two advices, two
 * concerns, both applied to every `@Controller`.
 *
 * The attributes:
 *
 * - **`currentUser`** — the `users` row behind the session, for the avatar (display name,
 *   email, and the OIDC `picture` claim, which IS stored: `users.profile_picture_url`,
 *   written by `OidcSuccessHandler` through `UserRepository` on every login). Settings has
 *   rendered it since 025; the avatar now does too, falling back to initials when it is null
 *   (local accounts, and OIDC providers that return no picture).
 * - **`crumbGroup` / `crumbPage`** — the top bar's breadcrumb, derived from [AppNav] so the
 *   rail's highlighted item and the breadcrumb cannot disagree. `crumbGroup` is null for the
 *   Dashboard and for off-rail screens; the template then renders the page alone.
 * - **`navCounts`** — the rail's two badges, behind [NavCounts]'s 60s TTL, as ONE attribute
 *   so one request makes one cache lookup. Either value is null when there is no workspace
 *   or the count could not be read; the template omits that badge rather than rendering a
 *   zero it does not know to be true.
 *
 * One extra `users` read per render is added by `currentUser`. That is the shape the app
 * already had — `ThemeResolver.resolve` reads the same row on every request, and most
 * controllers call it a second time on top of the advice — and the alternative (threading the
 * row out of the security filter onto the principal) is an `auth` change this round is fenced
 * out of.
 */
@ControllerAdvice(annotations = [Controller::class])
class AppShellAdvice(
    private val userRepository: UserRepository,
    private val navCounts: NavCounts,
) {
    @ModelAttribute("currentUser")
    fun currentUser(): User? = principal()?.let { userRepository.findById(it.userId) }

    @ModelAttribute("crumbGroup")
    fun crumbGroup(request: HttpServletRequest): String? = AppNav.crumbFor(request.requestURI)?.first

    @ModelAttribute("crumbPage")
    fun crumbPage(request: HttpServletRequest): String? = AppNav.crumbFor(request.requestURI)?.second

    /**
     * Both badges in one attribute, so the template reads `navCounts.pipelines` /
     * `navCounts.templates` and the request makes exactly one cache lookup. Two separate
     * `@ModelAttribute` accessors would each call the cache — harmless, but it would make the
     * cold path look like two reads to anyone tracing it.
     */
    /**
     * The palette swatches in the avatar menu: every vendored theme that is not one of the
     * three MODES the Appearance segment already offers. `VendoredThemes.names()` returns all
     * nine stylesheet names (auto, dark, forest, healthcare, light, minimal, ocean,
     * professional, saas); the six that remain here are the looks, and the three removed are
     * the modes. Derived, never listed: a tenth theme synced from the design system appears in
     * the menu by itself, and a removed one disappears — the failure the hard-coded fallback
     * in [UserSettingsController.listAvailableThemes] was deliberately written to avoid.
     */
    @ModelAttribute("themePalettes")
    fun themePalettes(): List<String> = UserSettingsController.listAvailableThemes().filterNot { it in MODES }

    @ModelAttribute("navCounts")
    fun navCounts(): NavCounts.Counts = navCounts.forWorkspace(principal()?.workspace?.id)

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

    companion object {
        /**
         * The three vendored themes that are MODES rather than palettes: `light` and `dark`
         * pin a scheme, `auto` follows the OS (`themes/auto.css` is the only one carrying a
         * `prefers-color-scheme` block). The app has ONE preference field, so the mode
         * segment and the palette swatches are two views of the same nine values — see the
         * layout's comment and ui-screens.md §4.11.
         */
        val MODES = setOf("light", "dark", "auto")
    }
}
