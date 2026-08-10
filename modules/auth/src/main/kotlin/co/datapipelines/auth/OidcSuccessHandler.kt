package co.datapipelines.auth

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component

/**
 * OIDC login callback (auth.md §5.5). Fully provider-agnostic — reads
 * `authorizedClientRegistrationId` (whatever the deployment named the provider) and
 * the ID-token claims, provisions/links the user, issues the internal JWT, and sets
 * the `dp_session` cookie (HttpOnly, Secure, SameSite=Strict, §5.5/§8).
 *
 * ## Rejection paths (§4.2/§4.3) — each audited, each issuing NO session cookie
 * | Condition | Audit event | Redirect |
 * |---|---|---|
 * | no `email` claim | `auth.login.oidc_error` | `/login?error=oidc_error` |
 * | `email_verified: false` | `auth.login.oidc_error` | `/login?error=oidc_error` |
 * | domain not allowlisted | `auth.login.domain_not_allowed` | `/login?error=domain_not_allowed` |
 * | `is_active = false` | `auth.login.user_inactive` | `/login?error=inactive` |
 *
 * The `email_verified` gate (§4.2) is the one that is easy to miss and expensive to
 * get wrong: provisioning is keyed on email, so an unverified self-registered account
 * at the provider would **take over** the existing row for that address. A provider
 * that omits the claim entirely is treated as vouching for the address.
 */
@Component
class OidcSuccessHandler(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val auditLogger: AuditLogger,
    private val authProperties: AuthProperties,
) : SimpleUrlAuthenticationSuccessHandler() {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val oidcUser = authentication.principal as OidcUser
        val claims = oidcUser.idToken.claims
        val registrationId = (authentication as OAuth2AuthenticationToken).authorizedClientRegistrationId

        // §4.2: lowercase at the boundary, so allowlist, audit and provisioning all
        // see the one canonical form of the address.
        val email = (claims["email"] as String?)?.trim()?.lowercase()
        if (email.isNullOrEmpty()) {
            rejectOidc(request, response, registrationId, reason = "missing_email")
            return
        }
        if (isEmailUnverified(claims["email_verified"])) {
            rejectOidc(request, response, registrationId, reason = "email_not_verified", email = email)
            return
        }
        val displayName = claims["name"] as String? ?: email
        val pictureUrl = claims["picture"] as String?
        val providerSubject = claims["sub"] as String

        if (!authProperties.isDomainAllowed(email)) {
            auditLogger.log("auth.login.domain_not_allowed", sourceIp = request.remoteAddr, details = mapOf("email" to email))
            redirectStrategy.sendRedirect(request, response, "/login?error=domain_not_allowed")
            return
        }

        val user =
            userService.findOrCreateByEmail(
                email = email,
                displayName = displayName,
                pictureUrl = pictureUrl,
                provider = registrationId,
                providerSubject = providerSubject,
            )

        if (!user.isActive) {
            auditLogger.log("auth.login.user_inactive", userId = user.id, sourceIp = request.remoteAddr)
            redirectStrategy.sendRedirect(request, response, "/login?error=inactive")
            return
        }

        response.addCookie(sessionCookie(jwtService.issue(user)))
        userService.updateLastLogin(user.id)
        auditLogger.log(
            event = "auth.login.success",
            userId = user.id,
            sourceIp = request.remoteAddr,
            userAgent = request.getHeader("User-Agent"),
            details = mapOf("email" to email, "provider" to registrationId),
        )
        redirectStrategy.sendRedirect(request, response, "/")
    }

    /**
     * True only when the provider explicitly asserts the address is NOT verified.
     * Absent → accepted (§4.2). The claim arrives as a JSON boolean from most
     * providers and as the string `"false"` from a few, so both are honored.
     */
    private fun isEmailUnverified(claim: Any?): Boolean =
        when (claim) {
            null -> false
            is Boolean -> !claim
            is String -> claim.equals("false", ignoreCase = true)
            else -> false
        }

    private fun rejectOidc(
        request: HttpServletRequest,
        response: HttpServletResponse,
        registrationId: String,
        reason: String,
        email: String? = null,
    ) {
        auditLogger.log(
            event = "auth.login.oidc_error",
            sourceIp = request.remoteAddr,
            details = mapOf("reason" to reason, "provider" to registrationId, "email" to email),
        )
        redirectStrategy.sendRedirect(request, response, "/login?error=oidc_error")
    }

    private fun sessionCookie(jwt: String): Cookie =
        Cookie(SESSION_COOKIE, jwt).apply {
            isHttpOnly = true
            secure = true
            path = "/"
            maxAge = (authProperties.jwt.ttlHours * SECONDS_PER_HOUR).toInt()
            setAttribute("SameSite", "Strict")
        }

    companion object {
        const val SESSION_COOKIE = "dp_session"
        private const val SECONDS_PER_HOUR = 3600L
    }
}
