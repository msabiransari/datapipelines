package co.datapipelines.auth

import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy
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
 * position in the chain, ahead of everything added below) → [PromotionServerKeyFilter] →
 * [LoginRateLimitFilter] →
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
 * **everywhere**, the `/api/v1` prefix included — `SameSite=Lax` (§5.5: Strict breaks the
 * cross-site login redirect) is defence-in-depth, not the control, because it stops neither a
 * same-site subdomain attacker nor a cross-site top-level GET.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties::class, AuthProperties::class)
@Suppress("LongParameterList") // the wiring class — every parameter is an @Bean reference (019 precedent)
class SecurityConfig(
    private val filters: AuthFilters,
    private val oidcSuccessHandler: OidcSuccessHandler,
    private val scopeInterceptor: ScopeInterceptor,
    private val forcedPasswordChangeInterceptor: ForcedPasswordChangeInterceptor,
    private val authEntryPoint: AuthEntryPoint,
    private val authAccessDeniedHandler: AuthAccessDeniedHandler,
    private val auditLogoutHandler: AuditLogoutHandler,
    private val authorizationRequestRepository: CookieOAuth2AuthorizationRequestRepository,
    private val authorizationRequestResolver: OAuth2AuthorizationRequestResolver,
    private val authProperties: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    // One line over detekt's LongMethod cap after the 027 CSRF-stability block landed
    // inline — same call as PipelineExecutor's suppress: the filter chain reads as one
    // vertical narrative (csrf → authz → session → logout); splitting a customizer out
    // would hide the CSRF config's coupling to the repository/handler lines above it.
    @Suppress("LongMethod")
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                // dp_csrf cookie (readable by JS), DP-CSRF-Token header (D10). The plain
                // (non-XOR) request handler keeps the cookie value equal to the header the
                // SPA sends back.
                csrf.csrfTokenRepository(csrfTokenRepository())
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                // The promotion route joins the exemption on the SAME grounds (§10.6): its
                // credential is a request header a hostile browser context cannot forge, and
                // no cookie authenticates there — PromotionServerKeyFilter refuses the route
                // outright without a valid server key, session or no session.
                csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher(), PromotionRouteMatcher())
                // 027 (024 T41's browser family): Spring's default composite includes
                // CsrfAuthenticationStrategy, written for server-side session
                // repositories — it ROTATES the token, and DELETES the cookie when
                // loadToken() finds none. It fires whenever the security context changes
                // during a request, and with per-request JWT authentication (the 60s
                // AuthCache re-check) that is EVERY authenticated request: observed live,
                // any htmx partial response (the dashboard auto-loads two) wiped dp_csrf,
                // so every subsequent browser mutation 403'd auth.csrf.invalid against the
                // stale token its page had rendered. Double-submit needs the cookie
                // STABLE; this chain has no server session to protect, so the strategy
                // is neutered. The cookie is minted by the first render that materializes
                // the deferred token (the login page) and never rewritten afterwards.
                csrf.sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy())
            }.authorizeHttpRequests { auth ->
                // Async re-dispatches (SSE completion, rest-api §6) and error dispatches
                // re-enter this chain with no SecurityContext: the auth filters are
                // OncePerRequestFilter and do not re-run on them. The REQUEST dispatch
                // already authenticated and authorized the request, so these dispatch
                // types are permitted — without this, every completed SSE stream dies
                // as Access Denied on the completion dispatch and the container aborts
                // the (already committed) response mid-chunk.
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // The permitAll allowlist lives in [PublicPaths] (096 §A, review F1): one row
                // per pattern WITH the sentence that justifies it, frozen by `PublicPathsTest`,
                // walked against every request mapping by `PublicRouteWalkerTest`, and rendered
                // into auth.md §8.3. Nothing else may be permitted here — an inline pattern
                // would be invisible to all three. Registered one at a time, in ENTRIES order:
                // a spread of the whole list into the vararg copies the array on every call and
                // detekt refuses it, and the resulting rules are identical either way.
                PublicPaths.PATTERNS.forEach { pattern -> auth.requestMatchers(pattern).permitAll() }
                auth.anyRequest().authenticated()
            }

        // OIDC login is wired ONLY when providers are configured (auth.md §5A): a
        // local-accounts-only deployment has no ClientRegistrations, no discovery,
        // and no /oauth2 endpoints — the filter chain below is identical either way.
        if (authProperties.oidc.providers.any { it.clientId.isNotBlank() }) {
            configureOidcLogin(http)
        }

        http
            .addFilterBefore(filters.jwt, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(filters.apiKey, JwtAuthenticationFilter::class.java)
            .addFilterBefore(filters.loginRateLimit, ApiKeyFilter::class.java)
            // versioning §10.6 — the promotion peer's gate, ahead of every credential filter:
            // on its own route it is the ONLY way through (an API key or a session cookie does
            // not open a deployment-to-deployment channel), and off that route it is inert, so
            // the server key authenticates nothing anywhere else.
            .addFilterBefore(filters.promotionServerKey, LoginRateLimitFilter::class.java)
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
     * The OIDC login wiring (auth.md §8), applied only when at least one provider is
     * configured — see the call site. The opaque `oidc_error` redirect never leaks
     * provider internals, but the failure itself is NOT swallowed (rules/02): without
     * the warn line every authorization-request-not-found, invalid_grant or PKCE
     * mismatch is indistinguishable from a §4.2 rejection at the success handler.
     */
    private fun configureOidcLogin(http: HttpSecurity) {
        // 090 §C — ahead of the authorization redirect, which Spring Security orders BEFORE
        // `UsernamePasswordAuthenticationFilter` and therefore before this chain's own
        // credential filters. Installed only here, with the rest of the OIDC wiring: a
        // deployment with no providers has no authorization endpoint to guard.
        http.addFilterBefore(filters.oidcSignedInBounce, OAuth2AuthorizationRequestRedirectFilter::class.java)
        http.oauth2Login { oauth ->
            oauth.successHandler(oidcSuccessHandler)
            oauth.failureHandler { request, response, exception ->
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
        }
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

    /**
     * Registers both MVC interceptors, IN ORDER (auth.md §8.1, §5A.4).
     *
     * They were two configurer beans until RBAC round 1, which made the order load-bearing:
     * `ScopeInterceptor` now refuses a principal with no reachable workspace before it looks
     * at anything else (D-R5), and a user who must change their password may well have no
     * workspace — so with the scope gate first they got `workspace.not_found` instead of
     * "change your password", and the one instruction that could unstick them was unreachable.
     *
     * The forced-change gate goes first on the merits, not just to fix a test: it is about
     * whether the CREDENTIAL may be used at all, which is upstream of what its holder may do.
     * It is ahead of EVERY handler except [ForcedPasswordChangeInterceptor.EXCLUDE_PATTERNS],
     * so a future controller is gated by default and cannot forget it.
     */
    @Bean
    fun mvcInterceptorConfigurer(): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry
                    .addInterceptor(forcedPasswordChangeInterceptor)
                    .excludePathPatterns(ForcedPasswordChangeInterceptor.EXCLUDE_PATTERNS)
                registry.addInterceptor(scopeInterceptor)
            }
        }

    companion object {
        /** D10 — the CSRF cookie and header names (auth.md §8.4, rest-api §3.6). */
        const val CSRF_COOKIE = "dp_csrf"
        const val CSRF_HEADER = "DP-CSRF-Token"
    }
}
