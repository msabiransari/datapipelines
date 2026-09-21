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
 * the `dp_session` cookie (HttpOnly, Secure, **SameSite=Lax**, §5.5/§8).
 *
 * Lax, not Strict, and deliberately so (T33, observed live 2026-08-28): the post-login
 * landing arrives over the cross-site redirect chain from the IdP, where Strict withholds
 * the cookie — and a reload of that landing re-uses the cross-site initiator, so the user
 * stays logged out forever. The full reasoning is at [sessionCookie]. This KDoc and
 * auth.md §8.4 both claimed Strict until 096 §F while the code had set Lax since T33;
 * two texts leaning on a defence the code does not provide is worse than the weaker
 * defence, because the CSRF argument in §8.4 was written on top of them.
 *
 * ## Rejection paths (§4.2/§4.3) — each audited, each issuing NO session cookie
 * | Condition | Audit event | Redirect |
 * |---|---|---|
 * | no `email` claim | `auth.login.oidc_error` | `/login?error=oidc_error` |
 * | `email_verified: false` | `auth.login.oidc_error` | `/login?error=oidc_error` |
 * | `email_verified` MISSING (unless the provider's trust knob, §5.1) | `auth.login.oidc_error` | `/login?error=oidc_error` |
 * | stored identity differs from the incoming one (#187) | `auth.login.identity_mismatch` | `/login?error=identity_mismatch` |
 * | domain not allowlisted | `auth.login.domain_not_allowed` | `/login?error=domain_not_allowed` |
 * | `is_active = false` | `auth.login.user_inactive` | `/login?error=inactive` |
 *
 * The `email_verified` gate (§4.2) is the one that is easy to miss and expensive to
 * get wrong: provisioning is keyed on email, so an unverified self-registered account
 * at the provider would **take over** the existing row for that address. Since #187
 * a MISSING claim counts as UNVERIFIED (fail closed); an IdP that never emits the
 * claim must be trusted explicitly, per provider, with
 * `trust-email-without-verified-claim` (§5.1) — and each such acceptance is audited
 * (`auth.login.email_verified_assumed`). The link-once rule (#187) lives in
 * [UserService]: a login whose identity does not match the stored row refuses — an
 * identity is re-linked only by the super admin's explicit reset, never by a login.
 */
class OidcSuccessHandler(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val auditLogger: AuditLogger,
    private val authProperties: AuthProperties,
    private val workspaceService: WorkspaceService,
    private val clientAddressResolver: ClientAddressResolver,
    /** The §5A.8 new-user notice on the CREATE branch only; [MailNotices.NONE] is for test slices. */
    private val mailNotices: MailNotices = MailNotices.NONE,
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
        if (claimsUnverifiedOrRedirect(claims, registrationId, email, request, response)) return
        if (domainRefused(email, request, response)) return

        val displayName = claims["name"] as String? ?: email
        val pictureUrl = claims["picture"] as String?
        val providerSubject = claims["sub"] as String

        val (user, created) =
            provisionOrRedirect(email, displayName, pictureUrl, registrationId, providerSubject, request, response)
                ?: return
        completeLogin(user, created, email, registrationId, request, response)
    }

    /** The §4.3 allowlist gate — false when the login may proceed; redirects on refusal. */
    private fun domainRefused(
        email: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean {
        if (authProperties.isDomainAllowed(email)) return false
        auditLogger.log(
            "auth.login.domain_not_allowed",
            sourceIp = clientAddressResolver.clientAddressOf(request),
            details = mapOf("email" to email),
        )
        redirectStrategy.sendRedirect(request, response, "/login?error=domain_not_allowed")
        return true
    }

    /**
     * The §4.2 `email_verified` gate (#187) — false when the login may proceed. Default is
     * to treat a MISSING claim as unverified; a claim PRESENT and false is refused whatever
     * the provider's trust knob says. An acceptance UNDER THE KNOB is audited here, once per
     * login, so an audit can tell "vouched" from "assumed".
     */
    private fun claimsUnverifiedOrRedirect(
        claims: Map<String, Any>,
        registrationId: String,
        email: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean {
        val trustWithoutClaim = authProperties.oidcProvider(registrationId)?.trustEmailWithoutVerifiedClaim ?: false
        if (isEmailUnverified(claims["email_verified"], trustWithoutClaim)) {
            rejectOidc(request, response, registrationId, reason = "email_not_verified", email = email)
            return true
        }
        if (trustWithoutClaim && !isVerifiedClaim(claims["email_verified"])) {
            // The knob carried the decision — absent claim OR a shape this code does not
            // recognise. Both are "the provider did not vouch", and both are audited.
            auditLogger.log(
                event = "auth.login.email_verified_assumed",
                sourceIp = clientAddressResolver.clientAddressOf(request),
                details =
                    mapOf(
                        "provider" to registrationId,
                        "email" to email,
                        "claim" to if (claims["email_verified"] == null) "absent" else "unrecognized",
                    ),
            )
        }
        return false
    }

    /**
     * Provision-or-refuse (#187): the stored identity must be the bootstrap placeholder or
     * the SAME `(provider, provider_subject)`. Anything else is the mismatch refusal —
     * nothing was changed; the audit names the two PROVIDERS and never the subject values.
     * Null means the redirect has been sent.
     */
    private fun provisionOrRedirect(
        email: String,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): UserService.Provisioned? =
        try {
            userService.findOrCreateByEmail(
                email = email,
                displayName = displayName,
                pictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
            )
        } catch (e: IdentityMismatchException) {
            auditLogger.log(
                event = "auth.login.identity_mismatch",
                userId = e.userId,
                sourceIp = clientAddressResolver.clientAddressOf(request),
                details =
                    mapOf(
                        "stored_provider" to e.storedProvider,
                        "incoming_provider" to e.incomingProvider,
                        "email" to email,
                    ),
            )
            redirectStrategy.sendRedirect(request, response, "/login?error=identity_mismatch")
            null
        }

    /** The tail of a successful login: workspace, notice, cookie, last-login stamp, audit. */
    private fun completeLogin(
        user: User,
        created: Boolean,
        email: String,
        registrationId: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (!user.isActive) {
            auditLogger.log("auth.login.user_inactive", userId = user.id, sourceIp = clientAddressResolver.clientAddressOf(request))
            redirectStrategy.sendRedirect(request, response, "/login?error=inactive")
            return
        }

        // §4.2 step 4 (design §5.1/§7): resolve the workspace the JWT stamps — last-used,
        // else first membership, else the freshly provisioned personal workspace
        // (auto-per-user only; the hook is a no-op in the other modes).
        val activeWorkspace = workspaceService.workspaceForLogin(user, email)
        // §5A.8: sys-ops hears about a FIRST social login — the branch that inserted the row,
        // which `findOrCreateByEmail` reports as `created`. A returning user sends nothing.
        // After the workspace resolution, so the notice can name where the account landed.
        if (created) {
            mailNotices.newUser(user, createdBy = "self-service via $registrationId", workspace = activeWorkspace?.name)
        }

        response.addCookie(sessionCookie(jwtService.issue(user, activeWorkspace?.name, LoginMethod.OIDC)))
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
     * True when the login must NOT proceed on the strength of this claim (#187).
     * Absent → UNVERIFIED by default — the fail-closed reading of §4.2: provisioning is
     * keyed on email, and a provider that stays silent has not vouched for the address.
     * An IdP that never emits the claim is trusted only through its explicit
     * `trust-email-without-verified-claim` knob (§5.1), which this function takes as
     * [trustWithoutVerifiedClaim]. A claim that is PRESENT and false is refused whatever
     * the knob says. The claim arrives as a JSON boolean from most providers and as the
     * string `"true"`/`"false"` from a few, so both are honored; any other shape — a
     * number, an object, a string such as `"yes"` — is treated as absent (knob-aware),
     * never as verified.
     */
    private fun isEmailUnverified(
        claim: Any?,
        trustWithoutVerifiedClaim: Boolean,
    ): Boolean =
        when (claim) {
            null -> !trustWithoutVerifiedClaim
            is Boolean -> !claim
            is String -> stringClaimUnverified(claim, trustWithoutVerifiedClaim)
            else -> !trustWithoutVerifiedClaim
        }

    /**
     * `"true"` verifies, `"false"` refuses; any other string (`"yes"`, `"0"`, `""`) is not a
     * verification — knob-aware like an absent claim, never read as verified (#187 review).
     */
    private fun stringClaimUnverified(
        claim: String,
        trustWithoutVerifiedClaim: Boolean,
    ): Boolean =
        if (claim.equals("true", ignoreCase = true)) {
            false
        } else if (claim.equals("false", ignoreCase = true)) {
            true
        } else {
            !trustWithoutVerifiedClaim
        }

    /** The two shapes that mean "verified": JSON `true` or the string `"true"`. */
    private fun isVerifiedClaim(claim: Any?): Boolean = claim == true || (claim is String && claim.equals("true", ignoreCase = true))

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
