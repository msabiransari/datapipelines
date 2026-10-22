package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler

/**
 * A provider login that failed BEFORE [OidcSuccessHandler] could judge it (auth.md §5.5):
 * a missing or replayed authorization request, an `invalid_grant` or PKCE mismatch at the
 * token exchange, a provider error on the callback, an unreadable user-info answer, or — for
 * an OAuth 2.0 entry (#655) — a failed emails read
 * ([OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE]).
 *
 * The browser gets the opaque `/login?error=oidc_error` the success handler's own refusals
 * use, so provider internals never reach the page. The failure is NOT swallowed (rules/02):
 * a WARN line with the error code and description, and since #655 an
 * `auth.login.oidc_error` audit row whose `reason` is the OAuth 2.0 error code — the same
 * event the success handler writes, so an audit reader sees every refused provider login in
 * one place. `provider` is named only when the callback path names a CONFIGURED registration:
 * the route is unauthenticated, and an arbitrary path segment is not written into the audit
 * log. The route is metered by [LoginRateLimitFilter] (`/login` prefix), which bounds how many
 * rows one client can cause.
 */
class OAuth2LoginFailureHandler(
    private val auditLogger: AuditLogger,
    private val clientAddressResolver: ClientAddressResolver,
    private val authProperties: AuthProperties,
) : AuthenticationFailureHandler {
    private val log = LoggerFactory.getLogger(OAuth2LoginFailureHandler::class.java)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val error = (exception as? OAuth2AuthenticationException)?.error
        log.warn(
            "OIDC login failed at {}: {}",
            request.requestURI,
            error?.let { "${it.errorCode}: ${it.description}" } ?: exception.toString(),
            exception,
        )
        auditLogger.log(
            event = "auth.login.oidc_error",
            sourceIp = clientAddressResolver.clientAddressOf(request),
            details = mapOf("reason" to (error?.errorCode ?: UNCLASSIFIED), "provider" to configuredProvider(request)),
        )
        response.sendRedirect("${request.contextPath}/login?error=oidc_error")
    }

    /** The registration id of a `/login/oauth2/code/{id}` callback, when it names a configured provider. */
    private fun configuredProvider(request: HttpServletRequest): String? {
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        if (!path.startsWith(CALLBACK_PREFIX)) return null
        val id = path.removePrefix(CALLBACK_PREFIX).substringBefore('/')
        return id.takeIf { authProperties.oidcProvider(it) != null }
    }

    companion object {
        /** Spring Security's default login-processing prefix, which this deployment keeps (auth.md §5.4). */
        const val CALLBACK_PREFIX = "/login/oauth2/code/"

        /** The `reason` of a failure that carries no OAuth 2.0 error code. */
        const val UNCLASSIFIED = "authentication_failed"
    }
}
