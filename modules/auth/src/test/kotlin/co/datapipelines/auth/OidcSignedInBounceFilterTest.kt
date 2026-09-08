package co.datapipelines.auth

import io.jsonwebtoken.Claims
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * 090 §C — [OidcSignedInBounceFilter]: a live session cannot start a new OIDC ceremony.
 *
 * The four cases below are the whole contract, and three of them are the ones that must NOT
 * bounce: an expired cookie, an invalid cookie and a deactivated owner are all visitors who
 * genuinely need to sign in, and a filter that turned any of them into a redirect would lock
 * them out of the only route back in. So the test spends most of its length proving the
 * filter is inert, which is the property a security filter is most often wrong about.
 *
 * [JwtService] and [UserService] are mocked because the question here is the DECISION, not
 * the crypto — `JwtServiceTest` owns validation. What is real is the servlet contract: the
 * status, the `Location` header, and whether the chain was called, all read off Spring's
 * mock request/response rather than asserted through the filter's own return value.
 */
class OidcSignedInBounceFilterTest {
    private val jwtService = mockk<JwtService>()
    private val userService = mockk<UserService>()
    private val filter = OidcSignedInBounceFilter(jwtService, userService)
    private val chain = mockk<FilterChain>(relaxed = true)

    private val userId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun request(
        uri: String = "/oauth2/authorization/google",
        cookie: String? = null,
    ) = MockHttpServletRequest("GET", uri).apply {
        requestURI = uri
        if (cookie != null) setCookies(Cookie(OidcSuccessHandler.SESSION_COOKIE, cookie))
    }

    private fun claimsFor(subject: String): Claims = mockk<Claims>().also { every { it.subject } returns subject }

    @Test
    fun `a valid session on the authorization endpoint is bounced to the dashboard`() {
        every { jwtService.validate("live") } returns claimsFor(userId.toString())
        every { userService.isActive(userId) } returns true
        val response = MockHttpServletResponse()

        filter.doFilter(request(cookie = "live"), response, chain)

        response.status shouldBe 302
        response.getHeader("Location") shouldBe "/dashboard"
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `an already-authenticated SecurityContext bounces without touching the cookie`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId = userId,
                    email = "a@b.test",
                    displayName = "A",
                    scopes = emptySet(),
                    authMethod = AuthMethod.OIDC,
                ),
                null,
                emptyList(),
            )
        val response = MockHttpServletResponse()

        filter.doFilter(request(), response, chain)

        response.status shouldBe 302
        verify(exactly = 0) { jwtService.validate(any()) }
    }

    @Test
    fun `no cookie means the login proceeds`() {
        val response = MockHttpServletResponse()

        filter.doFilter(request(), response, chain)

        response.status shouldBe 200
        response.getHeader("Location") shouldBe null
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `an expired session proceeds to the provider rather than bouncing`() {
        every { jwtService.validate("stale") } throws SessionExpiredException()
        val response = MockHttpServletResponse()

        filter.doFilter(request(cookie = "stale"), response, chain)

        response.status shouldBe 200
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `an invalid session proceeds to the provider rather than bouncing`() {
        every { jwtService.validate("forged") } throws SessionInvalidException("bad signature")
        val response = MockHttpServletResponse()

        filter.doFilter(request(cookie = "forged"), response, chain)

        response.status shouldBe 200
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `a deactivated owner proceeds to the provider rather than bouncing`() {
        every { jwtService.validate("live") } returns claimsFor(userId.toString())
        every { userService.isActive(userId) } returns false
        val response = MockHttpServletResponse()

        filter.doFilter(request(cookie = "live"), response, chain)

        response.status shouldBe 200
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    /**
     * The filter is INERT off its one route. Asserted with a live session present, because
     * the failure this rules out — a signed-in user redirected to /dashboard from every
     * page they open — would be indistinguishable from "the app works" on /dashboard itself.
     */
    @Test
    fun `every route but the authorization endpoint passes straight through`() {
        every { jwtService.validate(any()) } returns claimsFor(userId.toString())
        every { userService.isActive(userId) } returns true

        for (uri in listOf("/login", "/dashboard", "/oauth2/authorization", "/login/oauth2/code/google", "/templates")) {
            val response = MockHttpServletResponse()
            filter.doFilter(request(uri, cookie = "live"), response, chain)
            response.status shouldBe 200
        }
        verify(exactly = 5) { chain.doFilter(any(), any()) }
    }
}
