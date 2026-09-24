package co.datapipelines.web.ratelimit

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.RateLimitExceededException
import co.datapipelines.auth.appPath
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.config.EndpointsProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The serve path's per-key request budget (#224, configuration.md §3.22) — at most
 * `datapipelines.endpoints.key-request-budget.max-requests` requests per `window-seconds` for
 * EVERY `api_caller` key, then `429 rate_limit.exceeded` with `Retry-After`.
 *
 * A budget is a product property of a PUBLIC credential first; the demo workspace's key merely
 * needs it first (#224). The §12 limiter ([RateLimitFilter]) meters `/api/v1` and `/mcp` per
 * USER and skips the published subtree — the serve path was the one un-metered surface an
 * unaccountable credential could hold. This filter is its complement, and the two never both
 * fire on one request: §12 skips what this meters and vice versa.
 *
 * ## The shape is [LoginRateLimitFilter]'s
 * A fixed window per key, held in a **bounded** map keyed by the key id: at
 * [maxTrackedKeys] the filter sweeps windows that have already rolled over, and if the map
 * is still full it admits the request rather than growing — a flood of invented key ids
 * costs memory in no scenario (and an invented id is refused 401 long before this filter
 * could count it, so the ceiling exists for the valid-key case alone). In-memory and per
 * instance by design: the budget is a damper on a public demo key, not a distributed quota —
 * N replicas behind a load balancer admit N × `max-requests`, the same per-instance note
 * auth.md §11.5 makes for the login limiter.
 *
 * `max-requests: 0` turns the filter off entirely (the budget is inactive, and #224's lake
 * endpoints are then not published by the seeder).
 *
 * ## Why a filter after the security chain
 * The bucket key is the VALIDATED key id — the principal the auth chain resolved — never the
 * raw header, which an attacker could rotate freely. So the filter runs AFTER Spring Security
 * (order [ORDER], one past [RateLimitFilter]) and meters only requests that authenticated as
 * an endpoint key: sessions are not budgeted, and the serve path refuses them anyway.
 *
 * The response is written through [AuthErrorWriter], so the §4.2 envelope is byte-identical
 * to every other rejection; the code is the one system-wide rate-limit code
 * (pipeline-contract §13.11), the same one §12 and the login limiter answer with.
 */
class EndpointKeyBudgetFilter(
    private val endpointsProperties: EndpointsProperties,
    private val errorWriter: AuthErrorWriter,
    meterRegistry: MeterRegistry,
    // The ceiling and the clock are seams, so the saturation branch and the window boundary
    // can be REACHED by a test instead of reasoned about or slept through. Production never
    // passes them. Declared BEFORE `nowMillis` so the clock stays the trailing default.
    private val maxTrackedKeys: Int = MAX_TRACKED_KEYS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(EndpointKeyBudgetFilter::class.java)
    private val windows = ConcurrentHashMap<String, Window>()

    /** Registered eagerly: a series that only appears once it is non-zero cannot be alerted on. */
    private val saturations = meterRegistry.counter(SATURATED_METRIC)

    private class Window(
        val startedAtMillis: Long,
    ) {
        val count = AtomicInteger()
    }

    /**
     * Only the published subtree, and only when the budget is active. The subtree test repeats
     * the serve controller's category constraint (the reserved `v<n>` and `api` categories are
     * the product's own routes and are §12's business), so a `api_caller` key probing
     * `/api/v1/...` is refused 403 by the scope layer unmetered, exactly as before.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (endpointsProperties.keyRequestBudget.maxRequests <= 0) return true
        if (!isServePath(request.appPath())) return true
        val principal = currentPrincipal() ?: return true
        return principal.keyRole != KeyRole.API_CALLER
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = checkNotNull(currentPrincipal())
        val keyId = checkNotNull(principal.keyId)
        val max = endpointsProperties.keyRequestBudget.maxRequests
        if (exceeds(keyId, max)) {
            log.warn(
                "event=endpoint.key_budget_exhausted key_id={} path={} limit={}/{}s",
                keyId,
                request.appPath(),
                max,
                endpointsProperties.keyRequestBudget.windowSeconds,
            )
            response.setHeader(HttpHeaders.RETRY_AFTER, endpointsProperties.keyRequestBudget.windowSeconds.toString())
            errorWriter.write(
                request = request,
                response = response,
                status = ApiErrorCatalog.statusFor(RATE_LIMIT_CODE).value(),
                code = RATE_LIMIT_CODE,
                message =
                    "This API key's request budget of $max requests per " +
                        "${endpointsProperties.keyRequestBudget.windowSeconds} seconds is spent.",
                userMessage = "Too many requests for this API key. Wait for the window to roll over and try again.",
                details =
                    mapOf(
                        "limit" to max,
                        "window" to "${endpointsProperties.keyRequestBudget.windowSeconds}s",
                    ),
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    /** The fixed-window count for one key; a window that has rolled over is replaced. */
    private fun exceeds(
        keyId: String,
        limit: Int,
    ): Boolean {
        val now = nowMillis()
        val current = windows[keyId]
        if (current != null && now - current.startedAtMillis < windowMillis()) {
            return current.count.incrementAndGet() > limit
        }
        if (current == null && !admits(now)) return false
        val fresh = Window(now)
        windows[keyId] = fresh
        return fresh.count.incrementAndGet() > limit
    }

    private fun windowMillis(): Long = endpointsProperties.keyRequestBudget.windowSeconds * MILLIS_PER_SECOND

    /**
     * Whether a *new* key may be tracked. At the ceiling, rolled-over windows are swept; if
     * the table is still full the request is admitted unmetered rather than the map grown —
     * degrading to "allow" is the correct failure mode for a damper, and the saturation
     * counter makes the degradation a rate an operator can alert on rather than a quiet gap.
     */
    private fun admits(now: Long): Boolean {
        if (windows.size < maxTrackedKeys) return true
        windows.entries.removeIf { now - it.value.startedAtMillis >= windowMillis() }
        if (windows.size < maxTrackedKeys) return true
        saturations.increment()
        log.warn("event=endpoint.key_budget_table_saturated size={} message=\"new keys are admitted unmetered\"", windows.size)
        return false
    }

    private fun currentPrincipal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

    /** Test-only: how many keys currently hold a window — the metered/unmetered scoping's witness. */
    internal fun trackedKeyCount(): Int = windows.size

    private fun isServePath(appPath: String): Boolean {
        if (!appPath.startsWith("/api/")) return false
        val category = appPath.removePrefix("/api/").substringBefore('/')
        return CATEGORY_URL_PATTERN.matches(category)
    }

    companion object {
        /**
         * The reserved-category constraint as a filter-side regex — the same shape the serve
         * controller's mapping constrains (`EndpointPath.CATEGORY_URL_PATTERN`), restated here
         * because `modules/web`'s filter layer reads the request before any matcher runs and
         * the pattern is a published constant of the path grammar (R-EP5).
         */
        val CATEGORY_URL_PATTERN = Regex("(?!v[0-9]+$|api$)[a-z0-9][a-z0-9_.-]*")

        /** The one system-wide rate-limit code (pipeline-contract §13.11) — no serve-specific twin. */
        const val RATE_LIMIT_CODE = "rate_limit.exceeded"

        /** Observability §4.1's naming: application metrics are prefixed `datapipelines.`. */
        const val SATURATED_METRIC = "datapipelines.web.endpoint_key_budget.saturated"

        /** Just after [RateLimitFilter] (§12), which is itself just after the security chain. */
        const val ORDER: Int = RateLimitFilter.ORDER + 1

        const val MAX_TRACKED_KEYS = 10_000

        private const val MILLIS_PER_SECOND = 1000L
    }
}

/** Wiring for the serve path's per-key budget (#224). The same shape as [RateLimitConfiguration]. */
@Configuration
class EndpointKeyBudgetConfiguration {
    @Bean
    fun endpointKeyBudgetFilter(
        endpointsProperties: EndpointsProperties,
        errorWriter: AuthErrorWriter,
        meterRegistry: MeterRegistry,
    ): EndpointKeyBudgetFilter = EndpointKeyBudgetFilter(endpointsProperties, errorWriter, meterRegistry)

    @Bean
    fun endpointKeyBudgetFilterRegistration(filter: EndpointKeyBudgetFilter): FilterRegistrationBean<EndpointKeyBudgetFilter> =
        FilterRegistrationBean(filter).apply {
            order = EndpointKeyBudgetFilter.ORDER
            isAsyncSupported = true
        }
}
