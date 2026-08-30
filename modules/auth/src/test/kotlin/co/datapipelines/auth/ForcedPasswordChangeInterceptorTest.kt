package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The forced password change gate (auth.md §5A.4): a `must_change_password`
 * session principal is redirected everywhere the allowlist does not cover —
 * browsers with a 302, htmx with `HX-Redirect`, JSON clients with the
 * `auth.password.change_required` envelope — while API-key principals,
 * anonymous requests, and compliant users pass.
 */
class ForcedPasswordChangeInterceptorTest {
    private val userService = mockk<UserService>()
    private val interceptor = ForcedPasswordChangeInterceptor(userService, AuthErrorWriter(ObjectMapper()))

    private val userId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(authMethod: AuthMethod = AuthMethod.OIDC) {
        val principal =
            AuthenticatedPrincipal(
                userId = userId,
                email = "a@company.com",
                displayName = "A",
                scopes = setOf(Scope.READ),
                authMethod = authMethod,
                workspace = null,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun mustChangeUser(mustChange: Boolean): User =
        User(
            id = userId,
            email = "a@company.com",
            displayName = "A",
            provider = "local",
            providerSubject = "a@company.com",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            mustChangePassword = mustChange,
        )

    private fun call(path: String): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", path)
        val response = MockHttpServletResponse()
        val allowed = interceptor.preHandle(request, response, handler = Any())
        (allowed || response.status != 200) shouldBe true // belt and suspenders; assertions below
        return response.apply { setHeader("x-allowed", allowed.toString()) }
    }

    @Test
    fun `a must-change session is redirected to the change screen from an ordinary route`() {
        authenticate()
        every { userService.snapshot(userId) } returns mustChangeUser(true)

        val response = call("/pipelines")

        response.status shouldBe 302
        response.getHeader("Location") shouldBe "/settings/password"
        response.getHeader("x-allowed") shouldBe "false"
    }

    @Test
    fun `an htmx request gets HX-Redirect instead of a fragment-swallowing 302`() {
        authenticate()
        every { userService.snapshot(userId) } returns mustChangeUser(true)
        val request = MockHttpServletRequest("GET", "/partials/pipelines/list")
        request.addHeader("HX-Request", "true")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, handler = Any())

        allowed shouldBe false
        response.status shouldBe 200
        response.getHeader("HX-Redirect") shouldBe "/settings/password"
    }

    @Test
    fun `an api path gets the change-required envelope, not a redirect`() {
        authenticate()
        every { userService.snapshot(userId) } returns mustChangeUser(true)

        val response = call("/api/v1/pipelines")

        response.status shouldBe 403
        val body = ObjectMapper().readValue(response.contentAsString, Map::class.java)
        (body["error"] as Map<*, *>)["code"] shouldBe "auth.password.change_required"
    }

    @Test
    fun `an api-key principal is not gated - the key is a separate, deliberate credential`() {
        authenticate(AuthMethod.API_KEY)

        val response = call("/api/v1/pipelines")

        response.getHeader("x-allowed") shouldBe "true"
    }

    @Test
    fun `an anonymous request and a compliant user pass`() {
        val anonymous = call("/pipelines")
        anonymous.getHeader("x-allowed") shouldBe "true"

        authenticate()
        every { userService.snapshot(userId) } returns mustChangeUser(false)
        call("/pipelines").getHeader("x-allowed") shouldBe "true"
    }
}
