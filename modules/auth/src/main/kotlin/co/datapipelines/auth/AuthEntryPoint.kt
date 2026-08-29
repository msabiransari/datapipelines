package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * Emits the §13.7 error envelope when an unauthenticated request hits a protected
 * path. If a filter ([ApiKeyFilter] or [JwtAuthenticationFilter]) recorded a specific
 * rejection on the request ([AuthAttributes.AUTH_ERROR]), that exact code/status is
 * used — so an expired session surfaces as `auth.session.expired`, not as the generic
 * `auth.api_key.missing` (AU-TEST-3). With no recorded rejection the default is
 * `auth.api_key.missing` (401) — "no credentials provided" (auth.md §9).
 *
 * ## Browsers are redirected, API clients get JSON (T31, auth.md §8.3)
 *
 * A request whose `Accept` includes `text/html` — a human navigating `/`, `/ui` screens,
 * any full page — is a browser hitting a logged-out URL, and a 401 JSON body is a dead
 * end for it. Such requests get a `302` to `/login` instead. The split is by **shape,
 * then path**: `/api/**` and `/mcp` NEVER redirect — a browser-navigated API URL is a
 * misconfiguration to surface, not a login flow to paper over, and their 401 JSON is
 * byte-pinned by tests. Everything else redirects only when the client actually asks
 * for HTML; `curl` (`Accept: */*`) and JSON clients keep the exact current envelope.
 *
 * The `Location` is set as a RELATIVE header (`/login`), never via `sendRedirect`: the
 * servlet container builds `sendRedirect`'s absolute URL from the `Host` header, and a
 * poisoned `Host` would then aim the redirect at an attacker origin. RFC 7231 relative
 * `Location` resolves against the request's own origin.
 */
class AuthEntryPoint(
    private val errorWriter: AuthErrorWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (redirectsToLogin(request)) {
            response.status = HttpServletResponse.SC_FOUND
            response.setHeader("Location", LOGIN_PATH)
            return
        }
        val recorded = request.getAttribute(AuthAttributes.AUTH_ERROR) as? AuthException
        errorWriter.write(request, response, recorded ?: ApiKeyMissingException())
    }

    /**
     * The T31 redirect predicate: an HTML-accepting request outside the API/MCP surfaces.
     * `/login` itself is `permitAll` and never reaches the entry point, so no loop exists.
     */
    private fun redirectsToLogin(request: HttpServletRequest): Boolean {
        val path = request.requestURI.substring(request.contextPath.length)
        if (path.startsWith("/api/") || path.startsWith("/mcp")) return false
        return request.getHeader("Accept")?.contains("text/html") == true
    }

    private companion object {
        const val LOGIN_PATH = "/login"
    }
}
