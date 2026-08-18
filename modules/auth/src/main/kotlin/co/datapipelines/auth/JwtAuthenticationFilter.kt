package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Cookie → JWT → principal (auth.md §6.3). Runs after [ApiKeyFilter]; if a key
 * already authenticated the request (API key wins, §8.4) it does nothing.
 *
 * ## `/mcp` accepts no cookies (auth.md §8.5, AUTH-SEC-1)
 * [shouldNotFilter] returns true for `/mcp`, so a `dp_session` cookie presented there
 * authenticates **nobody** — the MCP surface is API-key-only, which is also what makes
 * it CSRF-irrelevant ([ApiKeyCredentialMatcher]).
 *
 * ## Defined failure boundary (rules/02, auth.md §6.3)
 * The §6.3 sketch wraps validation in one broad `catch (Exception)` that silently
 * clears the cookie — this filter does NOT reproduce that. Each expected failure
 * (expired, invalid/tampered, deactivated owner) is caught by its specific type and
 * logged as a structured event before the cookie is cleared and the request
 * proceeds unauthenticated. Unexpected exceptions are left to propagate (a 500 at
 * the container boundary) rather than being swallowed as "just an invalid token".
 */
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userService: UserService,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    /** `/mcp` is API-key-only — session cookies are never accepted there (§8.5). */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI == ApiKeyCredential.MCP_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val jwt = request.cookies?.firstOrNull { it.name == OidcSuccessHandler.SESSION_COOKIE }?.value
        val alreadyAuthenticated = SecurityContextHolder.getContext().authentication != null
        if (!jwt.isNullOrBlank() && !alreadyAuthenticated) {
            authenticate(jwt, request, response)
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(
        jwt: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        try {
            val claims = jwtService.validate(jwt)
            val userId = UUID.fromString(claims.subject)
            if (!userService.isActive(userId)) throw DeactivatedUserException(userId)

            @Suppress("UNCHECKED_CAST")
            val scopeTokens = claims["scopes"] as? List<String> ?: emptyList()
            val scopes = scopeTokens.map { Scope.fromWire(it) }.toSet()
            val principal =
                AuthenticatedPrincipal(
                    userId = userId,
                    email = claims["email"] as String,
                    displayName = claims["name"] as String,
                    scopes = scopes,
                    authMethod = AuthMethod.OIDC,
                    keyId = null,
                )
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    scopes.map { SimpleGrantedAuthority("SCOPE_${it.wire}") },
                )
        } catch (e: SessionExpiredException) {
            reject("session_expired", request, response, e)
        } catch (e: SessionInvalidException) {
            reject("session_invalid", request, response, e)
        } catch (e: DeactivatedUserException) {
            reject("user_inactive", request, response, e, userId = e.userId)
        } catch (e: IllegalArgumentException) {
            // Malformed subject/scope token — a bad JWT, not an unexpected fault.
            reject("session_invalid", request, response, SessionInvalidException(cause = e))
        }
    }

    /**
     * Clears the cookie, logs the structured rejection, and records the exact
     * [AuthException] on the request so [AuthEntryPoint] answers `auth.session.expired`
     * / `auth.session.invalid` at the HTTP boundary instead of the generic
     * `auth.api_key.missing` (AU-TEST-3).
     */
    private fun reject(
        reason: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        cause: AuthException,
        userId: UUID? = null,
    ) {
        SecurityContextHolder.clearContext()
        response.addCookie(clearedSessionCookie())
        request.setAttribute(AuthAttributes.AUTH_ERROR, cause)
        log.info(
            "dp_session rejected reason={} user_id={} path={} remote={} cause={}",
            reason,
            userId,
            request.requestURI,
            request.remoteAddr,
            cause.javaClass.simpleName,
        )
    }

    private fun clearedSessionCookie(): Cookie =
        Cookie(OidcSuccessHandler.SESSION_COOKIE, "").apply {
            maxAge = 0
            path = "/"
            isHttpOnly = true
            secure = true
        }
}
