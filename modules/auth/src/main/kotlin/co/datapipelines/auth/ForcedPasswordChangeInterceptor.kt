package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

/**
 * The forced password change gate (auth.md §5A.4): while a user's
 * `must_change_password` is TRUE, every authenticated route redirects to the
 * change-password screen — the deployment must not run on a one-time credential
 * (config-seeded or admin-reset) a moment longer than necessary.
 *
 * ## Why an interceptor, registered once for all paths
 * This must be impossible for a future controller to forget: it runs on the MVC
 * pipeline ahead of EVERY handler (registration in `SecurityConfig`, next to the
 * ScopeInterceptor), so a route added tomorrow is gated by default — pinned by a
 * test that adds exactly such a route. The allowlist lives in ONE place (the
 * registration's exclude patterns): the change-password page and endpoint,
 * `/logout`, `/health`, `/ready`, and the public/static paths (login, the
 * vendored assets, the app CSS/JS) without which the change page itself could
 * not render.
 *
 * ## What it does to whom
 *  - **Session principals** (cookie-minted JWT, any login method): browsers get a
 *    `302` to `/settings/password`; an htmx request gets `HX-Redirect` instead
 *    (a 302 would swap a full page into a fragment target); API and MCP paths
 *    get the `403 auth.password.change_required` envelope — a redirect is
 *    meaningless to a JSON client.
 *  - **API-key principals** are NOT gated: the key is a separate credential the
 *    user created deliberately, and the forced change is about the human proving
 *    control of the interactive account (§5A.4).
 *  - The flag is read through the cached user snapshot (D13, ~60s); every
 *    password mutation evicts it immediately, so a completed change (or an admin
 *    reset) takes effect on the very next request on this instance.
 */
class ForcedPasswordChangeInterceptor(
    private val userService: UserService,
    private val authErrorWriter: AuthErrorWriter,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val principal =
            SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        val gated =
            principal != null &&
                // session principal — API keys are a separate, deliberate credential (see KDoc)
                principal.authMethod == AuthMethod.OIDC &&
                userService.snapshot(principal.userId)?.mustChangePassword == true
        if (!gated) return true

        val path = request.requestURI
        when {
            path.startsWith(API_PREFIX) || path == ApiKeyCredential.MCP_PATH -> {
                authErrorWriter.write(request, response, PasswordChangeRequiredException())
            }

            request.getHeader(HX_REQUEST_HEADER)?.equals("true", ignoreCase = true) == true -> {
                response.setHeader(HX_REDIRECT_HEADER, CHANGE_PASSWORD_PATH)
            }

            else -> {
                response.sendRedirect(CHANGE_PASSWORD_PATH)
            }
        }
        return false
    }

    companion object {
        const val CHANGE_PASSWORD_PATH = "/settings/password"
        const val CHANGE_PASSWORD_PARTIAL = "/partials/account/password"
        private const val API_PREFIX = "/api/"
        private const val HX_REQUEST_HEADER = "HX-Request"
        private const val HX_REDIRECT_HEADER = "HX-Redirect"

        /**
         * The paths that stay reachable while `must_change_password` is TRUE — the
         * change endpoints themselves, logout, health probes, and the public/static
         * paths the change page needs to render. Registered as exclude patterns in
         * `SecurityConfig.forcedChangeInterceptorConfigurer`.
         */
        val EXCLUDE_PATTERNS =
            listOf(
                CHANGE_PASSWORD_PATH,
                CHANGE_PASSWORD_PARTIAL,
                "/logout",
                "/health",
                "/ready",
                "/info",
                "/login",
                "/login/**",
                "/error",
                "/vendor/**",
                "/css/**",
                "/js/**",
                "/favicon.ico",
                "/webjars/**",
            )
    }
}
