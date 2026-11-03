package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * The #782 logout exemption's surface: method, exact path (context-path-stripped, the
 * [ServletPathsTest] shape) and the browser's `Sec-Fetch-Site` attestation. A request without
 * the header — old browser, non-browser client — keeps the double-submit, as before.
 */
class LogoutSameOriginMatcherTest {
    private val matcher = LogoutSameOriginMatcher()

    @Test
    fun `a POST to logout with the browser's same-origin attestation matches`() {
        matcher.matches(request(path = "/logout", fetchSite = "same-origin")) shouldBe true
    }

    @Test
    fun `the attestation is case-insensitive`() {
        matcher.matches(request(path = "/logout", fetchSite = "SAME-ORIGIN")) shouldBe true
    }

    @Test
    fun `surrounding whitespace on the attestation does not break the match`() {
        matcher.matches(request(path = "/logout", fetchSite = " same-origin ")) shouldBe true
    }

    @Test
    fun `a same-site subdomain post is refused the exemption`() {
        matcher.matches(request(path = "/logout", fetchSite = "same-site")) shouldBe false
    }

    @Test
    fun `a cross-site post is refused the exemption`() {
        matcher.matches(request(path = "/logout", fetchSite = "cross-site")) shouldBe false
    }

    @Test
    fun `the none attestation is refused the exemption`() {
        matcher.matches(request(path = "/logout", fetchSite = "none")) shouldBe false
    }

    @Test
    fun `a request without the header keeps the double-submit`() {
        matcher.matches(request(path = "/logout", fetchSite = null)) shouldBe false
    }

    @Test
    fun `only POST is exempt`() {
        matcher.matches(request(method = "GET", path = "/logout", fetchSite = "same-origin")) shouldBe false
        matcher.matches(request(method = "PUT", path = "/logout", fetchSite = "same-origin")) shouldBe false
    }

    @Test
    fun `only the exact logout path is exempt`() {
        matcher.matches(request(path = "/logout/", fetchSite = "same-origin")) shouldBe false
        matcher.matches(request(path = "/logout/session", fetchSite = "same-origin")) shouldBe false
        matcher.matches(request(path = "/api/v1/logout", fetchSite = "same-origin")) shouldBe false
        matcher.matches(request(path = "/login", fetchSite = "same-origin")) shouldBe false
    }

    @Test
    fun `the context path is stripped before the path test`() {
        val behindContextPath =
            MockHttpServletRequest().apply {
                contextPath = "/dp"
                requestURI = "/dp/logout"
                method = "POST"
                addHeader("Sec-Fetch-Site", "same-origin")
            }
        matcher.matches(behindContextPath) shouldBe true
    }

    private fun request(
        method: String = "POST",
        path: String,
        fetchSite: String?,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            this.method = method
            requestURI = path
            fetchSite?.let { addHeader("Sec-Fetch-Site", it) }
        }
}
