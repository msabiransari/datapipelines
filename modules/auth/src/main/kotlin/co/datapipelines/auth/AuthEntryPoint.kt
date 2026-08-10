package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * Emits the §13.7 error envelope when an unauthenticated request hits a protected
 * path. If a filter ([ApiKeyFilter] or [JwtAuthenticationFilter]) recorded a specific
 * rejection on the request ([AuthAttributes.AUTH_ERROR]), that exact code/status is
 * used — so an expired session surfaces as `auth.session.expired`, not as the generic
 * `auth.api_key.missing` (AU-TEST-3). With no recorded rejection the default is
 * `auth.api_key.missing` (401) — "no credentials provided" (auth.md §9).
 */
@Component
class AuthEntryPoint(
    private val errorWriter: AuthErrorWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val recorded = request.getAttribute(AuthAttributes.AUTH_ERROR) as? AuthException
        errorWriter.write(request, response, recorded ?: ApiKeyMissingException())
    }
}
