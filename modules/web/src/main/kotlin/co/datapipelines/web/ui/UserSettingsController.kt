package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.UserRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.File

@Controller
class UserSettingsController(
    private val userRepository: UserRepository,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/settings")
    fun settings(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        val user = userRepository.findById(principal.userId)
        model.addAttribute("user", user)
        model.addAttribute("authMethod", principal.authMethod.name)
        model.addAttribute("themes", listAvailableThemes())
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("sessionScopes", principal.scopes.map { it.wire }.sorted())
        return "settings/index"
    }

    /**
     * The change-password screen (auth.md §5A.4) — the one place the forced-change
     * gate lets a `must_change_password` user reach. The submit endpoint is the
     * `POST /partials/account/password` partial (scope-governed, §7.6).
     */
    @GetMapping("/settings/password")
    fun changePassword(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        val user = userRepository.findById(principal.userId)
        model.addAttribute("user", user)
        model.addAttribute("mustChange", user?.mustChangePassword == true)
        model.addAttribute("hasLocalPassword", user?.hasLocalPassword == true)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "settings/password"
    }

    @PatchMapping("/partials/profile/theme")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun updateTheme(
        @RequestParam theme: String,
    ): ResponseEntity<String> {
        val principal = requirePrincipal()
        val available = listAvailableThemes()
        if (theme.isNotBlank() && theme !in available) {
            return ResponseEntity.badRequest().body(
                """<span style="color:var(--accent-danger);font-size:var(--text-sm)">""" +
                    "Unknown theme: $theme</span>",
            )
        }
        userRepository.setThemePreference(principal.userId, theme.ifBlank { null })
        return ResponseEntity.ok(
            """<span style="color:var(--accent-success);font-size:var(--text-sm)" """ +
                """hx-swap-oob="true" id="theme-link" hx-swap="outerHTML" """ +
                "th:href=\"@{" +
                "/vendor/design-system/themes/{theme}.css(theme=${
                    theme.ifBlank { "saas" }
                })}\">Theme updated to ${theme.ifBlank { "default" }}</span>",
        )
    }

    companion object {
        const val THEME_ROOT = "static/vendor/design-system/themes/"

        fun listAvailableThemes(): List<String> {
            val classpathDir = UserSettingsController::class.java.classLoader.getResource(THEME_ROOT)
            if (classpathDir == null) return DEFAULT_THEMES

            val dir = File(classpathDir.toURI())
            if (!dir.isDirectory) return DEFAULT_THEMES

            return dir
                .listFiles()
                ?.filter { it.name.endsWith(".css") }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
                ?.ifEmpty { DEFAULT_THEMES }
                ?: DEFAULT_THEMES
        }

        private val DEFAULT_THEMES =
            listOf("saas", "dark", "light", "ocean", "forest", "professional", "minimal", "healthcare", "auto")
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
