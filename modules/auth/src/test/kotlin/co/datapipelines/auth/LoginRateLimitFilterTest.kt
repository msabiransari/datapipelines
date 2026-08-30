package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * AUTH-SEC-5 / AU-API-8: per-IP login rate limit over the `/oauth2` and `/login`
 * prefixes, honoring `datapipelines.auth.rate-limit.login-per-minute`, answering
 * `429 rate_limit.exceeded` in the [Pipeline Contract §13.11] envelope.
 */
class LoginRateLimitFilterTest {
    private val mapper = ObjectMapper()
    private var nowMillis = 0L
    private val limit = 3
    private val filter =
        LoginRateLimitFilter(
            AuthProperties(rateLimit = AuthProperties.RateLimit(loginPerMinute = limit)),
            AuthErrorWriter(mapper),
        ) { nowMillis }

    private fun call(
        path: String,
        ip: String = "10.0.0.1",
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", path)
        request.remoteAddr = ip
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Test
    fun `requests up to the limit pass and the next one is 429 rate_limit exceeded`() {
        repeat(limit) { call("/oauth2/authorization/keycloak").status shouldBe 200 }

        val blocked = call("/oauth2/authorization/keycloak")

        blocked.status shouldBe 429
        blocked.getHeader("Retry-After") shouldBe "60"
        val body = mapper.readValue(blocked.contentAsString, Map::class.java)
        body["schema_version"] shouldBe 1
        val error = body["error"] as Map<*, *>
        error["code"] shouldBe "rate_limit.exceeded"
        (error["details"] as Map<*, *>)["limit"] shouldBe limit
    }

    @Test
    fun `the window rolls over after a minute`() {
        repeat(limit + 1) { call("/login/oauth2/code/keycloak") }
        call("/login/oauth2/code/keycloak").status shouldBe 429

        nowMillis += 60_001

        call("/login/oauth2/code/keycloak").status shouldBe 200
    }

    @Test
    fun `the budget is per client IP, not global`() {
        repeat(limit + 1) { call("/oauth2/authorization/keycloak", ip = "10.0.0.1") }

        call("/oauth2/authorization/keycloak", ip = "10.0.0.1").status shouldBe 429
        call("/oauth2/authorization/keycloak", ip = "10.0.0.2").status shouldBe 200
    }

    @Test
    fun `non-login paths are not metered at all`() {
        repeat(limit * 5) { call("/api/v1/pipelines").status shouldBe 200 }
        repeat(limit * 5) { call("/mcp").status shouldBe 200 }
    }

    @Test
    fun `the local password POST to the login path is metered like the OIDC paths`() {
        // auth.md §5A: the POST /login form endpoint inherits the same per-IP damper
        // as the OIDC paths (the `/login` prefix), so a fast spray is capped per IP
        // and the per-account lockout only needs to stop the slow one.
        fun postLogin(): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", "/login")
            request.remoteAddr = "10.0.0.7"
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            return response
        }

        repeat(limit) { postLogin().status shouldBe 200 }
        postLogin().status shouldBe 429
    }

    @Test
    fun `a zero or negative configured limit disables the filter`() {
        val disabled =
            LoginRateLimitFilter(
                AuthProperties(rateLimit = AuthProperties.RateLimit(loginPerMinute = 0)),
                AuthErrorWriter(mapper),
            ) { nowMillis }
        val request = MockHttpServletRequest("GET", "/oauth2/authorization/keycloak")
        request.remoteAddr = "10.0.0.9"

        repeat(50) {
            val response = MockHttpServletResponse()
            disabled.doFilter(request, response, MockFilterChain())
            response.status shouldBe 200
        }
    }

    /**
     * The documented fail-open at saturation (AUTH-SEC-4/-5), which nothing asserted
     * before: deleting `MAX_TRACKED_CLIENTS` from `admits` left every other test green
     * while a spoofed-source-IP flood could grow the window table without limit.
     *
     * "Fail open" is the deliberate choice for a brute-force damper — an unbounded map
     * is a worse outcome than an unmetered client — so the assertion is that the
     * untracked client is *admitted repeatedly*, well past the limit. The clock is held
     * still so the rolled-over-window sweep can free nothing.
     */
    @Test
    fun `at the client ceiling a new IP is admitted unmetered rather than tracked`() {
        repeat(MAX_TRACKED_CLIENTS) { i -> call("/oauth2/authorization/keycloak", ip = "10.1.${i / 256}.${i % 256}").status shouldBe 200 }

        // A brand-new IP cannot be tracked now. It must sail through — many times over
        // the limit — instead of being metered or growing the table.
        repeat(limit * 3) {
            call("/oauth2/authorization/keycloak", ip = "203.0.113.7").status shouldBe 200
        }
    }

    /** An IP already being tracked keeps its budget even once the table is full. */
    @Test
    fun `saturation does not disarm the limit for clients already tracked`() {
        val known = "198.51.100.4"
        call("/oauth2/authorization/keycloak", ip = known).status shouldBe 200
        repeat(MAX_TRACKED_CLIENTS) { i -> call("/oauth2/authorization/keycloak", ip = "10.2.${i / 256}.${i % 256}") }

        repeat(limit) { call("/oauth2/authorization/keycloak", ip = known) }

        call("/oauth2/authorization/keycloak", ip = known).status shouldBe 429
    }

    private companion object {
        /** Mirrors `LoginRateLimitFilter.MAX_TRACKED_CLIENTS`. */
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
