package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.UserRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Resolves the active theme for the current request:
 * 1. Authenticated user's `theme_preference` from `users` table, if set
 * 2. Fall back to `datapipelines.ui.theme` deployment default
 *
 * The bean name stays `uiThemeResolver` (pinned on the declaring `@Bean`, 015) —
 * that is the name the scanned stereotype carried.
 */
class ThemeResolver(
    private val userRepository: UserRepository,
    private val uiProperties: UiProperties,
) {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(request: HttpServletRequest): String {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        if (principal != null) {
            val themePref = userRepository.findById(principal.userId)?.themePreference
            if (themePref != null) return themePref
        }
        return uiProperties.theme
    }
}
