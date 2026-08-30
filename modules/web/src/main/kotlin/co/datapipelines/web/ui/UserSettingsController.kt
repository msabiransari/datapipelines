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

@Controller
class UserSettingsController(
    private val userRepository: UserRepository,
    private val themeResolver: ThemeResolver,
    private val uiProperties: UiProperties,
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
     * The theme swap (025 C1): a RENDERED Thymeleaf fragment (`partials/theme-swap`), never
     * a hand-built string. The old raw-string response carried an unprocessed `th:href` on
     * an OOB span, which htmx swapped over the layout's real stylesheet `<link>` — the page
     * lost its theme CSS until reload. Rendering matters for a second reason: `@{...}` is
     * what prepends the context path, so a hand-built href breaks under a non-root context
     * path even when it is processed.
     *
     * A blank value CLEARS the preference and swaps to the DEPLOYMENT default
     * ([UiProperties.theme]) — not a hardcoded name.
     */
    @PatchMapping("/partials/profile/theme")
    @RequiredScope(ScopeMatrix.RestOperation.PROFILE_PREFERENCE)
    fun updateTheme(
        @RequestParam theme: String,
        model: Model,
    ): Any {
        val principal = requirePrincipal()
        val available = listAvailableThemes()
        if (theme.isNotBlank() && theme !in available) {
            return ResponseEntity.badRequest().body(
                """<span style="color:var(--accent-danger);font-size:var(--text-sm)">""" +
                    "Unknown theme: $theme</span>",
            )
        }
        userRepository.setThemePreference(principal.userId, theme.ifBlank { null })
        model.addAttribute("theme", theme.ifBlank { uiProperties.theme })
        return "partials/theme-swap"
    }

    companion object {
        /**
         * The vendored theme names for the settings screen and the theme write's validation.
         * [VendoredThemes.names] enumerates the design-system CSS jar-safe (025 B1 — the T21
         * class: this used to resolve the classpath dir through `File(...)` and 500'd /settings
         * inside a packaged deployment); the fallback list covers a classpath with no vendored
         * assets (pre-P8).
         */
        fun listAvailableThemes(): List<String> = VendoredThemes.names() ?: DEFAULT_THEMES

        private val DEFAULT_THEMES =
            listOf("saas", "dark", "light", "ocean", "forest", "professional", "minimal", "healthcare", "auto")
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
