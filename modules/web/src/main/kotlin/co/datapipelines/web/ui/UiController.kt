package co.datapipelines.web.ui

import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class UiController(
    private val themeResolver: ThemeResolver,
    private val oidcRegistrations: OidcRegistrations,
    private val authProperties: AuthProperties,
) {
    /**
     * The login screen (auth.md §5A).
     *
     * 090 §C — a signed-in visitor is bounced to `/dashboard` instead. Opening `/login`
     * in a second tab of a live session used to render the form (owner's walk,
     * 2026-09-07): `/login` is `permitAll`, so the JWT filter had already authenticated
     * the `dp_session` cookie and `UiWorkspaceAdvice` had already answered
     * `authenticated = true` — the shell rendered around a form asking the visitor to
     * become someone they already were. Submitting it would have re-minted a session on
     * top of the live one; abandoning it left them looking at a dead end with a working
     * menu around it.
     *
     * The check reads the SecurityContext, not the cookie: the cookie is a claim and the
     * filter chain is what turns it into a principal, so an expired or forged `dp_session`
     * leaves the context empty and still gets the form — which is exactly the case
     * `/login?error=expired` exists for. The bounce is therefore "a VALID session lands on
     * the dashboard", never "a cookie is present".
     */
    @GetMapping("/login")
    fun login(
        model: Model,
        request: HttpServletRequest,
    ): String {
        if (signedIn()) return "redirect:/dashboard"
        model.addAttribute("providers", oidcRegistrations.providers())
        // auth.md §5A — the template renders the password form (and the divider)
        // only when local accounts are enabled; OIDC-only renders exactly as before.
        model.addAttribute("localEnabled", authProperties.local.enabled)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        val error = request.getParameter("error")
        if (error != null) {
            model.addAttribute("error", error)
        }
        return "login"
    }

    // 033: `/` is the public marketing site (SiteController); the signed-in
    // dashboard lives here. No auto-redirect from `/` — owner decision, 033 §E.
    @GetMapping("/dashboard")
    fun dashboard(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "dashboard"
    }

    /**
     * Whether THIS request already carries an authenticated principal. The same question
     * [UiWorkspaceAdvice] answers for the layout's `authenticated` attribute — asked here
     * from the controller because a redirect has to happen before the model is built.
     */
    private fun signedIn(): Boolean = SecurityContextHolder.getContext().authentication?.principal is AuthenticatedPrincipal
}
