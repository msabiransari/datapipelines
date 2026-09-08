package co.datapipelines.auth

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-IP rate limit on the login surface (AUTH-SEC-5), honoring
 * `datapipelines.auth.rate-limit.login-per-minute` ([Configuration §3.4], default 10).
 *
 * The key is the CLIENT address from [ClientAddressResolver], not the raw peer: behind the
 * documented load balancer (deployment.md §6.2) every peer is the LB, and keying on it
 * makes the per-IP budget one deployment-wide bucket (R8/T46). With
 * `datapipelines.auth.trusted-proxies` empty — the shipped default — the resolver returns
 * the peer and the header is ignored, so a bare deployment behaves exactly as before.
 *
 * Only the `/oauth2` and `/login` prefixes are metered: these are the sole unauthenticated,
 * state-creating endpoints, and each one costs a discovery-backed redirect or a
 * server-side token exchange with the IdP. Everything else is rate-limited per *user*
 * at the web layer ([Configuration §3.7]) — a different budget with a different key.
 *
 * Over the limit the response is `429 rate_limit.exceeded` — the single system-wide
 * code ([Pipeline Contract §13.11]; auth.md §9 is explicit that there is no
 * auth-layer rate-limit code) in the full [REST API §4.2] envelope, plus the standard
 * `Retry-After` header.
 *
 * ## Counters
 * A fixed one-minute window per client IP, held in a **bounded** map: at
 * [MAX_TRACKED_CLIENTS] the filter sweeps windows that have already rolled over, and
 * if the map is still full it admits the request rather than growing — a spoofed-IP
 * flood costs memory in no scenario. In-memory and per instance by design: this is a
 * brute-force damper, not a distributed quota.
 *
 * ## Saturation is VISIBLE, not silent (096 §F, review finding F8)
 * Admitting unmetered at the ceiling is the right failure mode and it stays. What was
 * wrong is that the only signal was a WARN line — and a WARN that fires on the request
 * path fires thousands of times a minute during exactly the flood it reports, so it is
 * indistinguishable from noise in a log and invisible to a dashboard. Every saturated
 * admission now increments `datapipelines.auth.login_rate_limit.saturated`
 * ([Observability §4.1](../../../../../../../docs/observability.md)), which is a rate an
 * operator can alert on. The counter is created eagerly at construction, so it reads zero
 * on a healthy deployment rather than being absent — an absent series and a quiet one look
 * the same to a scrape, and only one of them means "the limiter is working".
 *
 * The window is per INSTANCE: N replicas behind a load balancer means an effective limit of
 * N × `login-per-minute` for a client that lands on all of them (auth.md §11.5). Deliberate
 * — a distributed quota needs a shared store on the login path, which is a cost this damper
 * does not justify.
 */
class LoginRateLimitFilter(
    private val clientAddressResolver: ClientAddressResolver,
    private val authProperties: AuthProperties,
    private val errorWriter: AuthErrorWriter,
    meterRegistry: MeterRegistry,
    // The ceiling as a seam, so the saturation branch can be REACHED by a test instead of
    // being reasoned about — the same reason `nowMillis` is one. Production never passes it.
    // Declared BEFORE `nowMillis` so the clock stays the trailing lambda every caller uses.
    private val maxTrackedClients: Int = MAX_TRACKED_CLIENTS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(LoginRateLimitFilter::class.java)
    private val windows = ConcurrentHashMap<String, Window>()

    /** Registered eagerly: a series that only appears once it is non-zero cannot be alerted on. */
    private val saturations = meterRegistry.counter(SATURATED_METRIC)

    private class Window(
        val startedAtMillis: Long,
    ) {
        val count = AtomicInteger()
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = METERED_PREFIXES.none { request.appPath().startsWith(it) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val limit = authProperties.rateLimit.loginPerMinute
        val client = clientAddressResolver.clientAddressOf(request)
        if (limit > 0 && exceeds(client, limit)) {
            log.warn("Login rate limit hit client={} path={} limit={}/min", client, request.requestURI, limit)
            response.setHeader("Retry-After", WINDOW_SECONDS.toString())
            errorWriter.write(request, response, RateLimitExceededException(limit))
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun exceeds(
        clientIp: String,
        limit: Int,
    ): Boolean {
        val now = nowMillis()
        val current = windows[clientIp]
        if (current != null && now - current.startedAtMillis < WINDOW_MILLIS) {
            return current.count.incrementAndGet() > limit
        }
        if (current == null && !admits(now)) return false
        val fresh = Window(now)
        windows[clientIp] = fresh
        return fresh.count.incrementAndGet() > limit
    }

    /**
     * Whether a *new* client may be tracked. At the ceiling, rolled-over windows are
     * swept; if the table is still full the request is admitted unmetered rather than
     * the map grown — degrading to "allow" is the correct failure mode for a damper,
     * and it means a spoofed-source-IP flood cannot exhaust the heap.
     */
    private fun admits(now: Long): Boolean {
        if (windows.size < maxTrackedClients) return true
        windows.entries.removeIf { now - it.value.startedAtMillis >= WINDOW_MILLIS }
        if (windows.size < maxTrackedClients) return true
        saturations.increment()
        log.warn("Login rate-limit table saturated at {} clients; new clients are not metered", windows.size)
        return false
    }

    /**
     * Test-only reset of the in-memory windows. The exact-once metering test
     * (`AuthHttpBoundaryTest`) owns the shared per-IP budget for its assertions; any
     * SECOND consumer of a metered path (`/login`, `/oauth2/`) in the same application
     * context would otherwise pre-consume that budget and make the test's early-429
     * mimic the double-execution bug it exists to detect (D9, 034 F4). The windows are
     * per-instance state, so clearing them changes no runtime behavior.
     */
    internal fun resetWindowsForTest() {
        windows.clear()
    }

    private companion object {
        val METERED_PREFIXES = listOf("/oauth2/", "/login")

        /**
         * Observability §4.1's naming: application metrics are prefixed `datapipelines.`
         * (the 096 prompt named the suffix; the prefix is the spec's, not optional).
         * No tags — a saturated table is one closed condition with nothing to slice by,
         * and §4.3 forbids inventing a dimension that is not a bounded set.
         */
        const val SATURATED_METRIC = "datapipelines.auth.login_rate_limit.saturated"
        const val WINDOW_SECONDS = 60L
        const val WINDOW_MILLIS = WINDOW_SECONDS * 1000
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
