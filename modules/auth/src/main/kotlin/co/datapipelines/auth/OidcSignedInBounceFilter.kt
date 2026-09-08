package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 090 §C — a signed-in visitor never starts a NEW authorization ceremony.
 *
 * `UiController` bounces `GET /login` for a live session, and this filter closes the other
 * door into the same dead end: `/oauth2/authorization/{provider}`, which a bookmark, a stale
 * tab or a typed URL reaches directly. Left alone it sends a live session out to the identity
 * provider and back through the success handler, which re-mints a session on top of the one
 * already in the cookie — a login the visitor did not ask for, and, if the IdP prompts, a
 * screen that cannot explain why it is asking.
 *
 * ## Why it validates the cookie itself
 *
 * [JwtAuthenticationFilter] is installed before `UsernamePasswordAuthenticationFilter`, which
 * Spring Security orders AFTER `OAuth2AuthorizationRequestRedirectFilter` — so at the moment
 * the authorization redirect is decided, no principal has been resolved and the
 * `SecurityContext` is empty. The context is still consulted first (it is free, and it is the
 * right answer whenever some earlier credential filter did run); the cookie check is the
 * fallback that makes the filter correct in its actual position. Both questions are answered
 * with the SAME two collaborator calls the JWT filter makes — `validate` then `isActive` — so
 * there is no second copy of the session policy here, only a second caller of it.
 *
 * ## What does NOT bounce
 *
 * An expired, tampered or unparseable cookie, and a cookie whose user has been deactivated:
 * every one of those is a visitor who genuinely needs to sign in, and every one of them lands
 * on the provider exactly as before. The failure is swallowed to `false` rather than logged as
 * an error (rules/02: this is an expected input, not a fault) — the JWT filter downstream is
 * the component that owns reporting and clearing a bad cookie, and it still will.
 */
class OidcSignedInBounceFilter(
    private val jwtService: JwtService,
    private val userService: UserService,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(OidcSignedInBounceFilter::class.java)

    /** Inert everywhere but the authorization entry point. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.appPath().startsWith(AUTHORIZATION_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (signedIn(request)) {
            log.debug("Authorization request at {} refused: the session is already signed in", request.requestURI)
            // A RELATIVE Location, for [AuthEntryPoint]'s reason: the absolute form would be
            // built from request headers this deployment does not control.
            response.setHeader("Location", DASHBOARD_PATH)
            response.status = HttpServletResponse.SC_FOUND
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun signedIn(request: HttpServletRequest): Boolean {
        if (SecurityContextHolder.getContext().authentication?.principal is AuthenticatedPrincipal) return true
        val jwt = request.cookies?.firstOrNull { it.name == OidcSuccessHandler.SESSION_COOKIE }?.value
        if (jwt.isNullOrBlank()) return false
        return try {
            userService.isActive(java.util.UUID.fromString(jwtService.validate(jwt).subject))
        } catch (e: SessionExpiredException) {
            log.debug("Authorization request carries an expired session cookie; letting the login proceed", e)
            false
        } catch (e: SessionInvalidException) {
            log.debug("Authorization request carries an invalid session cookie; letting the login proceed", e)
            false
        } catch (e: IllegalArgumentException) {
            log.debug("Authorization request carries a session cookie with a non-UUID subject", e)
            false
        }
    }

    companion object {
        const val AUTHORIZATION_PREFIX = "/oauth2/authorization/"
        const val DASHBOARD_PATH = "/dashboard"
    }
}
