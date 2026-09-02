package co.datapipelines.auth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import java.time.Instant
import java.util.UUID

/**
 * The OIDC callback (auth.md §5.5) — success cookie attributes and every rejection
 * path (AU-TEST-7), plus the amended §4.2 rules: `email_verified: false` is rejected
 * (AUTH-SEC-6) and the address is lowercase-normalized before it reaches provisioning.
 */
class OidcSuccessHandlerTest {
    private val userService = mockk<UserService>(relaxed = true)
    private val jwtService = mockk<JwtService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    private val userId = UUID.randomUUID()

    private val workspaceService =
        mockk<WorkspaceService> {
            every { workspaceForLogin(any(), any()) } returns null
        }

    private fun handler(props: AuthProperties = AuthProperties()) =
        OidcSuccessHandler(userService, jwtService, auditLogger, props, workspaceService, ClientAddressResolver(emptyList()))

    private fun user(
        email: String = "alice@company.com",
        active: Boolean = true,
    ) = User(userId, email, "Alice", null, "keycloak", "sub-1", active, false, Instant.now(), Instant.now(), null)

    private fun authentication(claims: Map<String, Any>): OAuth2AuthenticationToken {
        val idToken = OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), claims)
        val authorities = listOf(SimpleGrantedAuthority("ROLE_USER"))
        return OAuth2AuthenticationToken(DefaultOidcUser(authorities, idToken), authorities, "keycloak")
    }

    private fun run(
        claims: Map<String, Any>,
        props: AuthProperties = AuthProperties(),
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/login/oauth2/code/keycloak")
        val response = MockHttpServletResponse()
        handler(props).onAuthenticationSuccess(request, response, authentication(claims))
        return response
    }

    private fun baseClaims(
        email: String = "alice@company.com",
        extra: Map<String, Any> = emptyMap(),
    ): Map<String, Any> = mapOf("sub" to "sub-1", "email" to email, "name" to "Alice") + extra

    @Test
    fun `a verified login issues dp_session with the documented cookie attributes`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns user()
        every { jwtService.issue(any()) } returns "the.jwt.token"

        val response = run(baseClaims(extra = mapOf("email_verified" to true)))

        val cookie = response.getCookie(OidcSuccessHandler.SESSION_COOKIE)
        cookie.shouldNotBeNull()
        cookie.value shouldBe "the.jwt.token"
        cookie.isHttpOnly shouldBe true
        cookie.secure shouldBe true
        // Lax, never Strict: Strict breaks the post-IdP landing + its reloads (T33)
        cookie.getAttribute("SameSite") shouldBe "Lax"
        cookie.path shouldBe "/"
        cookie.maxAge shouldBe 8 * 3600
        response.redirectedUrl shouldBe "/dashboard"
    }

    @Test
    fun `an absent email_verified claim is treated as the provider vouching for the address`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns user()
        every { jwtService.issue(any()) } returns "jwt"

        run(baseClaims()).getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldNotBeNull()
    }

    @Test
    fun `email_verified false is rejected as an oidc_error with no session cookie`() {
        val response = run(baseClaims(extra = mapOf("email_verified" to false)))

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        response.redirectedUrl shouldBe "/login?error=oidc_error"
        verify { auditLogger.log("auth.login.oidc_error", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `email_verified false as a string is rejected too`() {
        run(baseClaims(extra = mapOf("email_verified" to "false")))
            .getCookie(OidcSuccessHandler.SESSION_COOKIE)
            .shouldBeNull()
    }

    @Test
    fun `the email is lowercased before provisioning so provider case cannot fork a row`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns user()
        every { jwtService.issue(any()) } returns "jwt"

        run(baseClaims(email = "Alice@Company.COM"))

        verify { userService.findOrCreateByEmail("alice@company.com", "Alice", null, "keycloak", "sub-1") }
    }

    @Test
    fun `a missing email claim is an oidc_error, not a crash`() {
        val response = run(mapOf("sub" to "sub-1", "name" to "Alice"))

        response.redirectedUrl shouldBe "/login?error=oidc_error"
        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
    }

    @Test
    fun `a domain outside the allowlist is rejected and audited, with no session cookie`() {
        val props = AuthProperties(allowlist = AuthProperties.Allowlist(domains = listOf("company.com")))

        val response = run(baseClaims(email = "eve@evil.com"), props)

        response.redirectedUrl shouldBe "/login?error=domain_not_allowed"
        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        verify { auditLogger.log("auth.login.domain_not_allowed", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an inactive user is rejected and audited, with no session cookie`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns user(active = false)

        val response = run(baseClaims())

        response.redirectedUrl shouldBe "/login?error=inactive"
        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        verify { auditLogger.log("auth.login.user_inactive", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { jwtService.issue(any()) }
    }
}
