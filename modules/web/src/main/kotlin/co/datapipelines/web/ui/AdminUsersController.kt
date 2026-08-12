package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AdminUsersController(
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/admin/users")
    fun users(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        if (!Scope.satisfies(principal.scopes, Scope.ADMIN)) {
            log.info(
                "Scope denied: user {} accessing /admin/users with scopes {}",
                principal.userId,
                principal.scopes,
            )
            throw org.springframework.security.access
                .AccessDeniedException("Admin scope required")
        }
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "admin/users"
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")

    private companion object {
        private val log = LoggerFactory.getLogger(AdminUsersController::class.java)
    }
}
