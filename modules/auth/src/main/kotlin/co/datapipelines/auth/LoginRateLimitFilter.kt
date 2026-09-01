package co.datapipelines.auth

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
 */
class LoginRateLimitFilter(
    private val authProperties: AuthProperties,
    private val errorWriter: AuthErrorWriter,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(LoginRateLimitFilter::class.java)
    private val windows = ConcurrentHashMap<String, Window>()

    private class Window(
        val startedAtMillis: Long,
    ) {
        val count = AtomicInteger()
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = METERED_PREFIXES.none { request.requestURI.startsWith(it) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val limit = authProperties.rateLimit.loginPerMinute
        if (limit > 0 && exceeds(request.remoteAddr.orEmpty(), limit)) {
            log.warn("Login rate limit hit remote={} path={} limit={}/min", request.remoteAddr, request.requestURI, limit)
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
        if (windows.size < MAX_TRACKED_CLIENTS) return true
        windows.entries.removeIf { now - it.value.startedAtMillis >= WINDOW_MILLIS }
        if (windows.size < MAX_TRACKED_CLIENTS) return true
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
        const val WINDOW_SECONDS = 60L
        const val WINDOW_MILLIS = WINDOW_SECONDS * 1000
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
