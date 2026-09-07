package co.datapipelines.web.ratelimit

import co.datapipelines.auth.ApiKeyCredential
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RateLimitExceededException
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiErrorCatalog
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The shared per-user request limiter (rest-api.md §12), covering **both** the `/api/v1` prefix
 * and the `/mcp` endpoint.
 *
 * (Path globs are spelled out in prose here on purpose: a literal star-slash-star sequence inside
 * a Kotlin comment opens a nested comment that never closes.)
 *
 * ## Why one filter for both surfaces
 * §12.1's limits are per *user*, not per surface: "an API key draws from its owner's budget, so
 * minting more keys does not raise any limit". Two limiters — one per surface — would give an
 * agent double the documented budget simply by alternating between REST and MCP. `mcp-server`
 * deliberately shipped no limiter of its own and left the row to this filter (mcp-server.md §13),
 * so this is the only place the limit exists.
 *
 * ## Ordering
 * Registered **after** the Spring Security chain (`SecurityProperties.DEFAULT_FILTER_ORDER` is
 * -100; this is +1). Two consequences, both intended: the principal has been resolved, so the
 * limit is per user rather than per IP; and an unauthenticated request is 401'd before it consumes
 * anyone's budget, so an anonymous flood cannot exhaust a real user's quota. It is also ordered
 * ahead of `mcp-server`'s `McpAuthFilter` (`DEFAULT_FILTER_ORDER + 10`), so `/mcp` is metered
 * before the transport sees the request.
 *
 * ## Headers and rejection
 * Every response carries the §12.2 `RateLimit-*` headers. A rejection is `429` with `Retry-After`,
 * written through `auth`'s [AuthErrorWriter] so the envelope is byte-identical to every other
 * rejection — and it carries one of TWO codes (§12.2, pipeline-contract §13.11):
 *
 *  - `rate_limit.exceeded` — the caller's budget is gone. Their doing; back off and retry.
 *  - `rate_limit.unavailable` — the limiter could not decide and failed CLOSED (083, owner ruling
 *    2026-09-06). Not the caller's doing, and an operator's page rather than a client's.
 *
 * Both are 429 and both surfaces (`/api/v1` and `/mcp`) see the same code, because this one
 * filter meters both.
 */
class RateLimitFilter(
    private val limiter: RateLimiter,
    private val errorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        if (principal == null) {
            // Unauthenticated: the security chain rejects it moments from now, and metering an
            // anonymous request against nobody's budget would be a no-op at best and a way to
            // burn another user's quota at worst.
            filterChain.doFilter(request, response)
            return
        }

        val decision = limiter.consume(principal.userId)
        response.setHeader(RateLimitHeaders.LIMIT, decision.limit.toString())
        response.setHeader(RateLimitHeaders.REMAINING, decision.remaining.toString())
        response.setHeader(RateLimitHeaders.RESET, decision.resetEpochSeconds.toString())

        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }

        response.setHeader(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds.toString())
        if (decision.unavailable) refuseUnavailable(request, response, decision) else refuseThrottled(request, response, decision)
    }

    /** The caller spent its budget — `rate_limit.exceeded`, the code every layer shares. */
    private fun refuseThrottled(
        request: HttpServletRequest,
        response: HttpServletResponse,
        decision: RateLimitDecision,
    ) {
        val error = RateLimitExceededException(decision.limit.toInt())
        errorWriter.write(
            request = request,
            response = response,
            status = ApiErrorCatalog.statusFor(error.code).value(),
            code = error.code,
            message = "Per-user rate limit of ${decision.limit} requests per ${decision.window} exceeded.",
            userMessage = error.userMessage,
            details =
                mapOf(
                    "limit" to decision.limit,
                    "window" to decision.window,
                    "retry_after_seconds" to decision.retryAfterSeconds,
                ),
        )
    }

    /**
     * The limiter could not decide — `rate_limit.unavailable`, the fail-closed refusal.
     *
     * `details` deliberately carries no `limit` and no reason: the caller has spent nothing, so a
     * limit would be a lie, and *which* dependency fell over is topology an unauthenticated
     * caller does not get told (observability §9.2). The reason is in the WARN the limiter
     * already logged, once, for the whole outage.
     */
    private fun refuseUnavailable(
        request: HttpServletRequest,
        response: HttpServletResponse,
        decision: RateLimitDecision,
    ) {
        val code = PipelineErrorCodes.Limits.RATE_LIMIT_UNAVAILABLE
        errorWriter.write(
            request = request,
            response = response,
            status = ApiErrorCatalog.statusFor(code).value(),
            code = code,
            message = "The rate limiter could not evaluate this request and refused it.",
            userMessage = ApiErrorCatalog.userMessageFor(code),
            details = mapOf("retry_after_seconds" to decision.retryAfterSeconds),
        )
    }

    /** Only the two metered surfaces (rest-api §12.1). Probes and static assets are not metered. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return !(uri.startsWith(API_PREFIX) || uri == ApiKeyCredential.MCP_PATH)
    }

    companion object {
        const val API_PREFIX: String = "/api/v1/"

        /** Just after the Spring Security chain, ahead of `mcp-server`'s own filter (+10). */
        const val ORDER: Int = SecurityProperties.DEFAULT_FILTER_ORDER + 1
    }
}

/** Wiring for the shared limiter (rest-api §12, module-structure §5.9). */
@Configuration
class RateLimitConfiguration {
    @Bean
    fun rateLimitFilter(
        limiter: RateLimiter,
        errorWriter: AuthErrorWriter,
    ): RateLimitFilter = RateLimitFilter(limiter, errorWriter)

    @Bean
    fun rateLimitFilterRegistration(filter: RateLimitFilter): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(filter).apply {
            order = RateLimitFilter.ORDER
            isAsyncSupported = true
        }
}
