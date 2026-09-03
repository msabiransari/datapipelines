package co.datapipelines.auth

/**
 * The servlet filters the auth chain installs (auth.md §8.2), grouped so
 * [SecurityConfig] takes one collaborator instead of four.
 *
 * These are PLAIN objects, constructed inside the `authFilters` `@Bean`
 * ([AuthConfiguration]) and never top-level beans themselves. A `Filter` that is
 * not a bean is never auto-registered with the servlet container, so the AU-API-10
 * double-registration hazard (each filter running twice per request — two Argon2
 * verifications for one API key, two rate-limit increments for one login, and a
 * container-level copy running on `permitAll` paths the security chain
 * deliberately does not authenticate) is structurally impossible. The
 * `FilterRegistrationBean(isEnabled = false)` workarounds that used to suppress
 * it were deleted with the scanning that made them necessary (015, spec D4);
 * exact-once execution is proven behaviorally by `AuthHttpBoundaryTest`.
 *
 * The flip side of non-bean filters (D9, 034 F4): the `GenericFilterBean`
 * lifecycle is dropped with the registration. Every filter here extends
 * `OncePerRequestFilter`, whose `afterPropertiesSet`/`init(FilterConfig)`/
 * `destroy` callbacks are driven by the container or the bean factory — and
 * these objects see NEITHER, so none of those callbacks ever fires. Any future
 * setup must live in the constructor; state placed in an `init` override would
 * silently never happen.
 */
data class AuthFilters(
    val apiKey: ApiKeyFilter,
    val jwt: JwtAuthenticationFilter,
    val loginRateLimit: LoginRateLimitFilter,
    val workspaceResolution: WorkspaceResolutionFilter,
    /** versioning §10.6 — the promotion peer's credential gate, scoped to its own route. */
    val promotionServerKey: PromotionServerKeyFilter,
)
