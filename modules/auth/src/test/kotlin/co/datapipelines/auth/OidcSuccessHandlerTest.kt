package co.datapipelines.auth

import io.kotest.matchers.collections.shouldBeEmpty
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

    private val notices = RecordingNotices()

    private fun handler(props: AuthProperties = AuthProperties()) =
        OidcSuccessHandler(userService, jwtService, auditLogger, props, workspaceService, ClientAddressResolver(emptyList()), notices)

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

    private fun authPropertiesWithProvider(provider: AuthProperties.Provider) =
        AuthProperties(oidc = AuthProperties.Oidc(providers = listOf(provider)))

    @Test
    fun `a verified login issues dp_session with the documented cookie attributes`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns
            UserService.Provisioned(user(), created = false)
        every { jwtService.issue(any(), any(), any()) } returns "the.jwt.token"

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
    fun `an absent email_verified claim is refused by default - the provider has not vouched (#187)`() {
        // Fail closed: provisioning is keyed on email, so a provider that stays silent about
        // verification must not hand the account to whoever asserts the address.
        val response = run(baseClaims())

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        response.redirectedUrl shouldBe "/login?error=oidc_error"
        verify(exactly = 0) { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an absent claim is accepted under the provider's trust knob, and the assumption is audited (#187)`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns
            UserService.Provisioned(user(), created = false)
        every { jwtService.issue(any(), any(), any()) } returns "jwt"
        val props =
            authPropertiesWithProvider(
                AuthProperties.Provider(
                    name = "keycloak",
                    clientId = "dp-client",
                    clientSecret = "dp-secret",
                    issuerUri = "https://sso.test",
                    trustEmailWithoutVerifiedClaim = true,
                ),
            )

        val response = run(baseClaims(), props)

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldNotBeNull()
        verify(exactly = 1) { auditLogger.log("auth.login.email_verified_assumed", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a claim that is PRESENT and false is refused whatever the knob says (#187)`() {
        val props =
            authPropertiesWithProvider(
                AuthProperties.Provider(
                    name = "keycloak",
                    clientId = "dp-client",
                    clientSecret = "dp-secret",
                    issuerUri = "https://sso.test",
                    trustEmailWithoutVerifiedClaim = true,
                ),
            )

        val response = run(baseClaims(extra = mapOf("email_verified" to false)), props)

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        response.redirectedUrl shouldBe "/login?error=oidc_error"
    }

    @Test
    fun `an email linked to a different sign-in identity is refused - nothing is re-linked (#187)`() {
        every {
            userService.findOrCreateByEmail(any(), any(), any(), any(), any())
        } throws IdentityMismatchException(userId, storedProvider = "google", incomingProvider = "keycloak")

        val response = run(baseClaims(extra = mapOf("email_verified" to true)))

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        response.redirectedUrl shouldBe "/login?error=identity_mismatch"
        verify {
            auditLogger.log(
                "auth.login.identity_mismatch",
                any(),
                any(),
                any(),
                any(),
                match {
                    it["stored_provider"] == "google" &&
                        it["incoming_provider"] == "keycloak" &&
                        // The subject values never appear in the audit.
                        it.keys.none { key -> key.contains("subject") }
                },
            )
        }
        verify(exactly = 0) { jwtService.issue(any(), any(), any()) }
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
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns
            UserService.Provisioned(user(), created = false)
        every { jwtService.issue(any(), any(), any()) } returns "jwt"

        run(baseClaims(email = "Alice@Company.COM", extra = mapOf("email_verified" to true)))

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

        val response = run(baseClaims(email = "eve@evil.com", extra = mapOf("email_verified" to true)), props)

        response.redirectedUrl shouldBe "/login?error=domain_not_allowed"
        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        verify { auditLogger.log("auth.login.domain_not_allowed", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an inactive user is rejected and audited, with no session cookie`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns
            UserService.Provisioned(user(active = false), created = false)

        val response = run(baseClaims(extra = mapOf("email_verified" to true)))

        response.redirectedUrl shouldBe "/login?error=inactive"
        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        verify { auditLogger.log("auth.login.user_inactive", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { jwtService.issue(any(), any(), any()) }
    }

    @Test
    fun `a FIRST social login sends the sys-ops new-user notice naming the provider and the workspace`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns UserService.Provisioned(user(), created = true)
        every { jwtService.issue(any(), any(), any()) } returns "jwt"
        every { workspaceService.workspaceForLogin(any(), any()) } returns
            WorkspaceContext(UUID.randomUUID(), "demo")

        run(baseClaims(extra = mapOf("email_verified" to true)))

        val (user, createdBy, workspace) = notices.newUsers.single()
        user.email shouldBe "alice@company.com"
        createdBy shouldBe "self-service via keycloak"
        workspace shouldBe "demo"
        notices.welcomes.shouldBeEmpty()
    }

    @Test
    fun `a returning user's login sends no notice`() {
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns
            UserService.Provisioned(user(), created = false)
        every { jwtService.issue(any(), any(), any()) } returns "jwt"

        run(baseClaims(extra = mapOf("email_verified" to true)))

        notices.newUsers.shouldBeEmpty()
    }
}
