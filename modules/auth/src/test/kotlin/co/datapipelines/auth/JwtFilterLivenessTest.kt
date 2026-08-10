package co.datapipelines.auth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
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
 * D13 liveness re-check on the JWT path (auth.md §6.3): even a cryptographically valid
 * session is rejected when the owner is no longer active, and the defined failure
 * boundary clears the cookie rather than swallowing the outcome silently.
 */
class JwtFilterLivenessTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
    private val jwtService = JwtService(JwtProperties(secret), AuthProperties())
    private val userService = mockk<UserService>()
    private val filter = JwtAuthenticationFilter(jwtService, userService)

    private val userId = UUID.randomUUID()
    private val token =
        jwtService.issue(
            User(userId, "u@c.com", "U", null, "kc", "s", true, false, Instant.now(), Instant.now(), null),
        )

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun run(cookieValue: String?): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        if (cookieValue != null) request.setCookies(Cookie(OidcSuccessHandler.SESSION_COOKIE, cookieValue))
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Test
    fun `an active user's valid session authenticates with the token scopes`() {
        every { userService.isActive(userId) } returns true

        run(token)

        val auth = SecurityContextHolder.getContext().authentication
        auth.shouldNotBeNull()
        (auth.principal as AuthenticatedPrincipal).userId shouldBe userId
        auth.authorities.map { it.authority }.contains("SCOPE_read") shouldBe true
    }

    @Test
    fun `a deactivated user's valid session is rejected and the cookie is cleared`() {
        every { userService.isActive(userId) } returns false

        val response = run(token)

        SecurityContextHolder.getContext().authentication.shouldBeNull()
        val cleared = response.getCookie(OidcSuccessHandler.SESSION_COOKIE)
        cleared.shouldNotBeNull()
        cleared.maxAge shouldBe 0
    }

    @Test
    fun `a malformed token authenticates nobody and clears the cookie`() {
        val response = run("not-a-jwt")

        SecurityContextHolder.getContext().authentication.shouldBeNull()
        response.getCookie(OidcSuccessHandler.SESSION_COOKIE)?.maxAge shouldBe 0
    }
}
