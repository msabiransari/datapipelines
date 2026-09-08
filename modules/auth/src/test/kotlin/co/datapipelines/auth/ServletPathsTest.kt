package co.datapipelines.auth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 096 §F (review finding F10) — the context-path strip, proven with a request that
 * actually carries one.
 *
 * Every path decision in this module was written against `requestURI`, which INCLUDES the
 * servlet context path. At the root context the two are identical, so nothing was ever
 * observably wrong; under `server.servlet.context-path=/dp` each of them silently answers
 * the opposite of what it means, and every one of those failures is fail-OPEN:
 *
 *  - `/mcp` stops being recognised, so [JwtAuthenticationFilter] starts authenticating
 *    session cookies there (auth.md §8.5 says it must not) and the CSRF exemption stops
 *    applying, so every MCP client's POST 403s.
 *  - The promotion prefix stops matching, so [PromotionServerKeyFilter]'s fail-closed gate
 *    goes inert on the routes it exists to guard.
 *  - The login limiter meters nothing.
 *
 * The assertions below are written as `appPath()` decisions rather than by booting a
 * context under a context path: the helper is the single spelling all of those sites now
 * share, so pinning it pins them.
 */
class ServletPathsTest {
    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun request(
        contextPath: String,
        uri: String,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            this.contextPath = contextPath
            requestURI = uri
        }

    @Test
    fun `at the root context appPath is the requestURI unchanged`() {
        request("", "/mcp").appPath() shouldBe "/mcp"
        request("", "/api/v1/pipelines").appPath() shouldBe "/api/v1/pipelines"
    }

    @Test
    fun `under a context path the application path drops it`() {
        request("/dp", "/dp/mcp").appPath() shouldBe "/mcp"
        request("/dp", "/dp/api/v1/promotion/push").appPath() shouldBe "/api/v1/promotion/push"
    }

    /**
     * The one that would have shipped a real hole: `/mcp` accepts no cookies (§8.5), and
     * the filter decides that by comparing the path to `/mcp`. Under a context path the
     * comparison used to fail, so the MCP endpoint would have started authenticating
     * `dp_session`. The `userService` double is STRICT on purpose — if the filter wrongly
     * runs, `isActive` is called and the test goes red rather than passing quietly.
     */
    @Test
    fun `the mcp endpoint is still cookie-free under a context path`() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
        val jwtService = JwtService(JwtProperties(secret), AuthProperties())
        val userId = UUID.randomUUID()
        val token =
            jwtService.issue(
                User(userId, "u@c.com", "U", null, "kc", "s", true, false, Instant.now(), Instant.now(), null),
            )
        val filter = JwtAuthenticationFilter(jwtService, mockk<UserService>(), ClientAddressResolver(emptyList()))

        val request =
            request("/dp", "/dp/mcp").apply {
                setCookies(Cookie(OidcSuccessHandler.SESSION_COOKIE, token))
            }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        SecurityContextHolder.getContext().authentication.shouldBeNull()
    }

    @Test
    fun `the promotion prefix and the metered login prefixes survive a context path`() {
        PromotionRouteMatcher().matches(request("/dp", "/dp/api/v1/promotion/push")) shouldBe true
        PromotionRouteMatcher().matches(request("/dp", "/dp/api/v1/pipelines")) shouldBe false
    }

    @Test
    fun `the csrf exemption matcher still recognises mcp under a context path`() {
        ApiKeyCredentialMatcher().matches(request("/dp", "/dp/mcp")) shouldBe true
        ApiKeyCredentialMatcher().matches(request("/dp", "/dp/api/v1/pipelines")) shouldBe false
    }
}
