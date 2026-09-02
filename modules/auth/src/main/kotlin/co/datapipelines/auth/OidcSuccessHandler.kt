package co.datapipelines.auth

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler

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
class OidcSuccessHandler(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val auditLogger: AuditLogger,
    private val authProperties: AuthProperties,
    private val workspaceService: WorkspaceService,
    private val clientAddressResolver: ClientAddressResolver,
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
            auditLogger.log(
                "auth.login.domain_not_allowed",
                sourceIp = clientAddressResolver.clientAddressOf(request),
                details = mapOf("email" to email),
            )
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
            auditLogger.log("auth.login.user_inactive", userId = user.id, sourceIp = clientAddressResolver.clientAddressOf(request))
            redirectStrategy.sendRedirect(request, response, "/login?error=inactive")
            return
        }

        // §4.2 step 4 (design §5.1/§7): resolve the workspace the JWT stamps — last-used,
        // else first membership, else the freshly provisioned personal workspace
        // (auto-per-user only; the hook is a no-op in the other modes).
        val activeWorkspace = workspaceService.workspaceForLogin(user, email)

        response.addCookie(sessionCookie(jwtService.issue(user, activeWorkspace?.name)))
        userService.updateLastLogin(user.id)
        auditLogger.log(
            event = "auth.login.success",
            userId = user.id,
            sourceIp = clientAddressResolver.clientAddressOf(request),
            userAgent = request.getHeader("User-Agent"),
            details =
                mapOf(
                    "email" to email,
                    "provider" to registrationId,
                    "active_workspace" to activeWorkspace?.name,
                ),
        )
        // 033: the signed-in landing page moved to /dashboard (`/` is the public site).
        redirectStrategy.sendRedirect(request, response, "/dashboard")
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
            sourceIp = clientAddressResolver.clientAddressOf(request),
            details = mapOf("reason" to reason, "provider" to registrationId, "email" to email),
        )
        redirectStrategy.sendRedirect(request, response, "/login?error=oidc_error")
    }

    private fun sessionCookie(jwt: String): Cookie = sessionCookie(jwt, authProperties)

    companion object {
        const val SESSION_COOKIE = "dp_session"
    }
}

/**
 * The `dp_session` cookie, built once for every minter (login and the UI workspace
 * switcher re-stamp the same cookie): HttpOnly, path `/`, TTL the JWT's, SameSite=Lax
 * — and `Secure` keyed off [AuthProperties.secureCookies] (T33): `https` base-url (or
 * none) keeps the flag, an explicit `http://` base-url drops it so local login works.
 *
 * SameSite stays **Lax, not Strict**: the post-login landing arrives over the cross-site
 * redirect chain from the IdP, where Strict withholds the cookie — and a browser reload
 * of that landing re-uses the cross-site initiator, so the user stays logged out forever
 * (observed live 2026-08-28, T33). Lax still withholds the cookie on cross-site POSTs;
 * CSRF covers state changes (§8.4).
 */
private const val SECONDS_PER_HOUR = 3600L

fun sessionCookie(
    jwt: String,
    authProperties: AuthProperties,
): Cookie =
    Cookie(OidcSuccessHandler.SESSION_COOKIE, jwt).apply {
        isHttpOnly = true
        secure = authProperties.secureCookies()
        path = "/"
        maxAge = (authProperties.jwt.ttlHours * SECONDS_PER_HOUR).toInt()
        setAttribute("SameSite", "Lax")
    }
