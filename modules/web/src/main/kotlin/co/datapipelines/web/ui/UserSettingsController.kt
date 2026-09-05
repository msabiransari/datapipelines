package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.SessionRequiredException
import co.datapipelines.auth.UserRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class UserSettingsController(
    private val userRepository: UserRepository,
    private val themeResolver: ThemeResolver,
    private val uiProperties: UiProperties,
    private val localPasswordService: LocalPasswordService,
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
            // Shape C (§5.1): the refusal keeps its real 400 and is retargeted at the
            // stack — toast.js's bridgeErrors admits it (htmx never swaps 4xx alone).
            return ResponseEntity
                .badRequest()
                .header("HX-Retarget", "#toast")
                .header("HX-Reswap", "beforeend")
                .body(
                    ToastHtml.oob(
                        "danger",
                        "Unknown theme",
                        "${ToastHtml.esc(theme)} is not a vendored theme — the preference was not changed.",
                    ),
                )
        }
        userRepository.setThemePreference(principal.userId, theme.ifBlank { null })
        model.addAttribute("theme", theme.ifBlank { uiProperties.theme })
        return "partials/theme-swap"
    }

    /**
     * Self-service password change (auth.md §5A.4), scope-governed as
     * `CHANGE_OWN_PASSWORD` (§7.6 — any authenticated principal, own account by
     * construction). Answers htmx fragments, never redirects: success is a toast,
     * failures stay inline in `#password-change-result` (§5.1 — validation is not
     * a toast; the screen owns its 4xx delivery).
     *
     * SESSION-ONLY. The scope floor is "any authenticated", and API keys are CSRF-exempt
     * and authenticate on every path, so without this gate a leaked READ-scoped `dpk_` key
     * could guess its owner's password here — and on a hit, rotate it and take the
     * interactive account. Rotating the credential that backs a browser session is never
     * something a non-interactive key legitimately does; the key's own lifecycle is
     * `/api/v1/auth/keys`. The rate-limit and lockout hardening in
     * [LocalPasswordService.changeOwn] is the second layer, for a hijacked session.
     */
    @PostMapping("/partials/account/password")
    @RequiredScope(ScopeMatrix.RestOperation.CHANGE_OWN_PASSWORD)
    fun changeOwnPassword(
        @RequestParam currentPassword: String,
        @RequestParam newPassword: String,
        @RequestParam confirmPassword: String,
    ): ResponseEntity<String> {
        val principal = requirePrincipal()
        if (principal.authMethod != AuthMethod.OIDC) {
            throw SessionRequiredException("change-own-password")
        }
        if (newPassword != confirmPassword) {
            return ResponseEntity.badRequest().body(errorSpan("The new passwords do not match"))
        }
        // Read the gate's key BEFORE the change: a successful change clears it, and the
        // response shape depends on whether this screen was reached through the forced-change
        // gate (auth.md §5A.4) or voluntarily from Settings.
        val wasForced = userRepository.findById(principal.userId)?.mustChangePassword == true
        return when (val result = localPasswordService.changeOwn(principal.userId, currentPassword, newPassword)) {
            is LocalPasswordService.ChangeResult.Success -> {
                if (wasForced) {
                    // The forced flow: the user came here because every other screen was
                    // refused. A toast that says "you can continue" while the same form stays
                    // on screen is a dead end (found on the owner's first local login,
                    // 2026-09-05). htmx follows HX-Redirect with a full navigation; the gate's
                    // liveness cache is evicted by the password write, so the dashboard renders.
                    ResponseEntity
                        .ok()
                        .header("HX-Redirect", "/dashboard")
                        .body("")
                } else {
                    // Voluntary change from Settings — toast-only (§5.1 Shape B): htmx extracts
                    // the OOB toast and clears #password-change-result with the empty remainder.
                    // The FAILURES below stay inline spans — field-level/credential validation
                    // is never a toast.
                    ResponseEntity.ok(
                        ToastHtml.oob(
                            "success",
                            "Password changed",
                            "You can continue to the app.",
                        ),
                    )
                }
            }

            is LocalPasswordService.ChangeResult.WrongCurrentPassword -> {
                ResponseEntity.badRequest().body(errorSpan("The current password is incorrect"))
            }

            is LocalPasswordService.ChangeResult.PolicyViolation -> {
                ResponseEntity.badRequest().body(errorSpan(result.reason))
            }

            is LocalPasswordService.ChangeResult.NoLocalAccount -> {
                ResponseEntity.badRequest().body(errorSpan("This account has no local password"))
            }

            is LocalPasswordService.ChangeResult.AccountLocked -> {
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    errorSpan("Too many failed attempts — this account is temporarily locked. Try again later."),
                )
            }
        }
    }

    private fun errorSpan(message: String): String = """<span style="color:var(--accent-danger);font-size:var(--text-sm)">$message</span>"""

    companion object {
        /**
         * The vendored theme names for the settings screen and the theme write's validation
         * (025 B1, 027): [VendoredThemes.names] enumerates the design-system CSS jar-safe.
         * NO hard-coded fallback list — a third copy of what the sync script already guards
         * in both directions is a drift risk, and it would LIE: a classpath with no vendored
         * assets cannot serve any theme it names. Null (assets absent) surfaces as an empty
         * listing; the blank theme value still clears a preference back to the deployment
         * default, and any non-blank write against an empty listing is rejected as unknown.
         */
        fun listAvailableThemes(): List<String> = VendoredThemes.names().orEmpty()
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
