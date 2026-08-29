package co.datapipelines.auth

import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Spring Security wiring (auth.md §8). Generic OIDC login → internal JWT, plus the
 * API-key path; both resolve to [AuthenticatedPrincipal] and are gated by the §7.6
 * scope matrix through [ScopeInterceptor].
 *
 * Filter order, as actually assembled: Spring's `CsrfFilter` (registered at its own
 * position in the chain, ahead of everything added below) → [LoginRateLimitFilter] →
 * [ApiKeyFilter] → [JwtAuthenticationFilter] → [WorkspaceResolutionFilter] → OAuth2
 * login → authorization, and then
 * [ScopeInterceptor] on the MVC pipeline once a handler has been resolved. The three
 * `addFilterBefore` calls below read in the reverse of the resulting order, which is
 * why this list is spelled out rather than inferred. The server is STATELESS; the
 * OIDC authorization request is carried in a signed cookie
 * ([CookieOAuth2AuthorizationRequestRepository]) rather than a server session.
 *
 * ## CSRF follows the credential, not the path (§8.4, v2.4)
 * [ApiKeyCredentialMatcher] is the only exemption: a request is exempt when it
 * carries `DP-API-Key`, or when it targets `/mcp` where cookies never authenticate.
 * Cookie-authenticated state-changing requests need the `dp_csrf` double-submit token
 * **everywhere**, the `/api/v1` prefix included — `SameSite=Strict` is defence-in-depth, not
 * the control, because it does not stop a same-site subdomain attacker.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties::class, AuthProperties::class)
class SecurityConfig(
    private val filters: AuthFilters,
    private val oidcSuccessHandler: OidcSuccessHandler,
    private val scopeInterceptor: ScopeInterceptor,
    private val authEntryPoint: AuthEntryPoint,
    private val authAccessDeniedHandler: AuthAccessDeniedHandler,
    private val auditLogoutHandler: AuditLogoutHandler,
    private val authorizationRequestRepository: CookieOAuth2AuthorizationRequestRepository,
    private val authorizationRequestResolver: OAuth2AuthorizationRequestResolver,
    private val authProperties: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                // dp_csrf cookie (readable by JS), DP-CSRF-Token header (D10). The plain
                // (non-XOR) request handler keeps the cookie value equal to the header the
                // SPA sends back.
                csrf.csrfTokenRepository(csrfTokenRepository())
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher())
            }.authorizeHttpRequests { auth ->
                auth
                    // Async re-dispatches (SSE completion, rest-api §6) and error dispatches
                    // re-enter this chain with no SecurityContext: the auth filters are
                    // OncePerRequestFilter and do not re-run on them. The REQUEST dispatch
                    // already authenticated and authorized the request, so these dispatch
                    // types are permitted — without this, every completed SSE stream dies
                    // as Access Denied on the completion dispatch and the container aborts
                    // the (already committed) response mid-chunk.
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/health",
                        "/ready",
                        "/info",
                        "/login",
                        "/login/**",
                        "/oauth2/**",
                        "/vendor/**",
                        "/css/**",
                        "/js/**",
                        "/favicon.ico",
                        "/error",
                        "/webjars/**",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2Login { oauth ->
                oauth.successHandler(oidcSuccessHandler)
                oauth.failureHandler { request, response, exception ->
                    // The user gets an opaque `oidc_error` (never provider internals), but the
                    // failure itself is NOT swallowed (rules/02): without this line every
                    // authorization-request-not-found, invalid_grant or PKCE mismatch is
                    // indistinguishable from a §4.2 rejection at the success handler.
                    log.warn(
                        "OIDC login failed at {}: {}",
                        request.requestURI,
                        (exception as? OAuth2AuthenticationException)?.error?.let { "${it.errorCode}: ${it.description}" }
                            ?: exception.toString(),
                        exception,
                    )
                    response.sendRedirect("${request.contextPath}/login?error=oidc_error")
                }
                oauth.authorizationEndpoint {
                    it.authorizationRequestRepository(authorizationRequestRepository)
                    // PKCE (RFC 7636) is applied by this resolver — see OidcConfig.
                    it.authorizationRequestResolver(authorizationRequestResolver)
                }
            }.addFilterBefore(filters.jwt, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(filters.apiKey, JwtAuthenticationFilter::class.java)
            .addFilterBefore(filters.loginRateLimit, ApiKeyFilter::class.java)
            // Workspace resolution (design §5) runs once a credential has authenticated:
            // after the JWT filter, before OAuth2 login / authorization.
            .addFilterAfter(filters.workspaceResolution, JwtAuthenticationFilter::class.java)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(authEntryPoint)
                it.accessDeniedHandler(authAccessDeniedHandler)
            }.logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .addLogoutHandler(auditLogoutHandler)
                    .deleteCookies(OidcSuccessHandler.SESSION_COOKIE)
                    .logoutSuccessUrl("/login")
            }

        return http.build()
    }

    /**
     * CSRF token in a JS-readable `dp_csrf` cookie; SPA echoes it in `DP-CSRF-Token`.
     * `Secure` follows the base-url scheme (T33) — see [AuthProperties.secureCookies].
     */
    @Suppress("DEPRECATION") // setSecure is the API this Spring Security version ships; the replacement ctor does not exist yet
    private fun csrfTokenRepository(): CookieCsrfTokenRepository =
        CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
            setCookieName(CSRF_COOKIE)
            setHeaderName(CSRF_HEADER)
            setSecure(authProperties.secureCookies())
        }

    /** Registers the scope interceptor on the MVC pipeline (auth.md §8.1). */
    @Bean
    fun scopeInterceptorConfigurer(): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(scopeInterceptor)
            }
        }

    companion object {
        /** D10 — the CSRF cookie and header names (auth.md §8.4, rest-api §3.6). */
        const val CSRF_COOKIE = "dp_csrf"
        const val CSRF_HEADER = "DP-CSRF-Token"
    }
}
