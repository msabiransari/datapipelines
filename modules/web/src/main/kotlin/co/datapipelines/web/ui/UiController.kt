package co.datapipelines.web.ui

import co.datapipelines.auth.AuthProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class UiController(
    private val themeResolver: ThemeResolver,
    private val oidcRegistrations: OidcRegistrations,
    private val authProperties: AuthProperties,
) {
    @GetMapping("/login")
    fun login(
        model: Model,
        request: HttpServletRequest,
    ): String {
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

    @GetMapping("/")
    fun dashboard(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "dashboard"
    }
}
