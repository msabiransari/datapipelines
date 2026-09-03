package co.datapipelines.web.config

import co.datapipelines.auth.AuthProperties
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * [WebCorsConfiguration] — the CORS CONTRACT, not the wiring (rest-api §13.1): the one
 * trusted origin rule, the fail-closed unset case, the exact DP- header allowlist, and
 * the pattern scope (the API and the MCP endpoint; never the UI partials).
 *
 * The security property pinned hardest: with `allowCredentials = true` the allowed origin
 * must be the configured base-url EXACTLY — a foreign origin is refused, and an unset
 * base-url allows NOTHING cross-origin. No configuration of this filter can open the API
 * to an arbitrary site.
 */
class WebCorsConfigurationTest {
    private fun sourceFor(baseUrl: String? = null) = WebCorsConfiguration(AuthProperties(baseUrl = baseUrl)).corsConfigurationSource()

    private fun requestFor(
        uri: String,
        origin: String? = "https://dp.example.com",
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", uri).apply {
            origin?.let { addHeader("Origin", it) }
        }

    @Test
    fun `the configured base-url is the one allowed origin`() {
        val config =
            sourceFor("https://dp.example.com").getCorsConfiguration(requestFor("/api/v1/pipelines"))
                ?: error("no CORS config for the API path")

        config.allowedOrigins shouldContainExactly listOf("https://dp.example.com")
        config.allowCredentials shouldBe true
        config.allowedMethods shouldContainExactly listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.maxAge shouldBe 3600L
    }

    @Test
    fun `an unset base-url allows nothing cross-origin - the fail-closed default`() {
        val config = sourceFor(null).getCorsConfiguration(requestFor("/api/v1/pipelines")) ?: error("no CORS config for the API path")

        config.allowedOrigins shouldBe emptyList()
        // And the check-level consequence: any origin is refused.
        config.checkOrigin("https://dp.example.com") shouldBe null
        config.checkOrigin("https://evil.example") shouldBe null
    }

    @Test
    fun `a foreign origin is refused even when a base-url is set`() {
        val config = sourceFor("https://dp.example.com").getCorsConfiguration(requestFor("/api/v1/pipelines")) ?: error("no CORS config")

        config.checkOrigin("https://evil.example") shouldBe null
        config.checkOrigin("https://dp.example.com") shouldBe "https://dp.example.com"
    }

    @Test
    fun `the base-url is normalized - trimmed with the trailing slash stripped`() {
        val config =
            sourceFor("  https://dp.example.com/  ")
                .getCorsConfiguration(requestFor("/api/v1/pipelines"))
                ?: error("no CORS config")

        config.allowedOrigins shouldContainExactly listOf("https://dp.example.com")
    }

    @Test
    fun `the DP- headers ride the allowlist verbatim - rest-api 13-1`() {
        val config = sourceFor("https://dp.example.com").getCorsConfiguration(requestFor("/api/v1/pipelines")) ?: error("no CORS config")

        // The constants, not literals: the contract is "these headers", and the test
        // must move with a constant rename, not silently keep asserting old strings.
        config.allowedHeaders shouldContainExactly
            listOf(
                org.springframework.http.HttpHeaders.AUTHORIZATION,
                co.datapipelines.auth.ApiKeyCredential.HEADER,
                co.datapipelines.web.api.CorrelationId.HEADER,
                co.datapipelines.auth.SecurityConfig.CSRF_HEADER,
                WebHeaders.RESULT_TTL,
                org.springframework.http.HttpHeaders.CONTENT_TYPE,
                WebHeaders.IDEMPOTENCY_KEY,
                org.springframework.http.HttpHeaders.ACCEPT,
            )
        config.exposedHeaders shouldContainExactly
            listOf(
                co.datapipelines.web.api.CorrelationId.HEADER,
                co.datapipelines.web.ratelimit.RateLimitHeaders.LIMIT,
                co.datapipelines.web.ratelimit.RateLimitHeaders.REMAINING,
                co.datapipelines.web.ratelimit.RateLimitHeaders.RESET,
                org.springframework.http.HttpHeaders.RETRY_AFTER,
            )
    }

    @Test
    fun `cors governs the api and the mcp endpoint - never the ui partials`() {
        val source = sourceFor("https://dp.example.com")

        source.getCorsConfiguration(requestFor("/api/v1/pipelines")) shouldNotBe null
        source.getCorsConfiguration(requestFor("/mcp")) shouldNotBe null
        source.getCorsConfiguration(requestFor("/partials/datasources")) shouldBe null
        source.getCorsConfiguration(requestFor("/dashboard")) shouldBe null
    }
}
