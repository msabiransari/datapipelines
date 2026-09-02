package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutHandler

/**
 * Writes the `auth.logout` audit row (auth.md §10.1) as the session cookie is
 * cleared. Without it, `audit_log` shows every login and no logout — an incident
 * timeline with only half the story.
 *
 * The principal is read from the `SecurityContext` Spring hands the logout chain, so
 * the row carries the real `user_id` (and `key_id`, when a key-authenticated client
 * calls `/logout`) rather than an anonymous marker.
 */
class AuditLogoutHandler(
    private val auditLogger: AuditLogger,
    private val clientAddressResolver: ClientAddressResolver,
) : LogoutHandler {
    override fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication?,
    ) {
        val principal = authentication?.principal as? AuthenticatedPrincipal
        auditLogger.log(
            event = "auth.logout",
            userId = principal?.userId,
            keyId = principal?.keyId,
            sourceIp = clientAddressResolver.clientAddressOf(request),
            userAgent = request.getHeader("User-Agent"),
        )
    }
}
