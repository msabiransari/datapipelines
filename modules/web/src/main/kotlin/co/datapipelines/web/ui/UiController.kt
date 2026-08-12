package co.datapipelines.web.ui

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class UiController(
    private val themeResolver: ThemeResolver,
    private val oidcRegistrations: OidcRegistrations,
) {
    @GetMapping("/login")
    fun login(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("providers", oidcRegistrations.providers())
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
