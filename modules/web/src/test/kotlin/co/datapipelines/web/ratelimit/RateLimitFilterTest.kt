package co.datapipelines.web.ratelimit

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * The shared per-user limiter filter (rest-api §12): headers on every response, 429 with
 * `Retry-After` and `rate_limit.exceeded` when the budget is gone, and — the row mcp-server §13
 * explicitly leaves to this module — coverage of `/mcp` as well as `/api/v1`.
 */
class RateLimitFilterTest {
    private val principal =
        AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", setOf(Scope.READ), AuthMethod.API_KEY, "dpk_x")

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun filter(decision: RateLimitDecision) =
        RateLimitFilter(
            limiter = { decision },
            errorWriter = AuthErrorWriter(JsonMapper.builder().build()),
        )

    private fun allowed(remaining: Long = 87) =
        RateLimitDecision(
            allowed = true,
            limit = 100,
            remaining = remaining,
            resetEpochSeconds = 1_691_234_567,
            retryAfterSeconds = 1,
            window = "s",
        )

    @Test
    fun `an allowed request carries the RateLimit headers and proceeds`() {
        authenticate()
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter(allowed()).doFilter(request, response, chain)

        response.getHeader(RateLimitHeaders.LIMIT) shouldBe "100"
        response.getHeader(RateLimitHeaders.REMAINING) shouldBe "87"
        response.getHeader(RateLimitHeaders.RESET) shouldBe "1691234567"
        verify(exactly = 1) { chain.doFilter(request, response) }
    }

    @Test
    fun `an exhausted budget is a 429 with Retry-After and the single system-wide code`() {
        authenticate()
        val request = MockHttpServletRequest("GET", "/api/v1/executions")
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter(allowed(remaining = 0).copy(allowed = false, retryAfterSeconds = 42)).doFilter(request, response, chain)

        response.status shouldBe 429
        response.getHeader("Retry-After") shouldBe "42"
        response.contentAsString shouldContain "\"code\":\"rate_limit.exceeded\""
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `the mcp endpoint is metered too`() {
        authenticate()
        val request = MockHttpServletRequest("POST", "/mcp")
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        filter(allowed()).doFilter(request, response, chain)

        response.getHeader(RateLimitHeaders.LIMIT) shouldBe "100"
        verify(exactly = 1) { chain.doFilter(request, response) }
    }

    @Test
    fun `unauthenticated requests and unmetered paths pass through unmetered`() {
        val limiter = mockk<RateLimiter>()
        val filter = RateLimitFilter(limiter, AuthErrorWriter(JsonMapper.builder().build()))

        // No principal: nothing to meter against.
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/pipelines"), MockHttpServletResponse(), MockFilterChain())
        // Outside the metered surfaces entirely.
        authenticate()
        filter.doFilter(MockHttpServletRequest("GET", "/health"), MockHttpServletResponse(), MockFilterChain())

        verify(exactly = 0) { limiter.consume(any()) }
    }

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }
}
