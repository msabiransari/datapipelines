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
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import java.time.Instant
import java.util.UUID

/**
 * [OidcSuccessHandler] with an OAuth 2.0 principal (#655): a `DefaultOAuth2User` — attributes,
 * no ID token — goes down the SAME provisioning path as an OIDC login, and the per-provider trust
 * knob is consulted only when the entry has no `emails-uri` (F5).
 */
class OidcSuccessHandlerOAuth2PrincipalTest {
    private val userService = mockk<UserService>(relaxed = true)
    private val jwtService = mockk<JwtService> { every { issue(any(), any(), any()) } returns "jwt" }
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService> { every { workspaceForLogin(any(), any(), any()) } returns null }

    private fun user() =
        User(UUID.randomUUID(), "alice@company.com", "Alice", null, "github", "4242", true, false, Instant.now(), Instant.now(), null)

    private fun provider(
        emailsUri: String?,
        trust: Boolean,
    ) = AuthProperties.Provider(
        name = "github",
        protocol = "oauth2",
        clientId = "id",
        emailsUri = emailsUri,
        trustEmailWithoutVerifiedClaim = trust,
    )

    private fun run(
        attributes: Map<String, Any>,
        provider: AuthProperties.Provider,
    ): MockHttpServletResponse {
        val authorities = listOf(SimpleGrantedAuthority("OAUTH2_USER"))
        val principal = DefaultOAuth2User(authorities, attributes, "id")
        val response = MockHttpServletResponse()
        OidcSuccessHandler(
            userService,
            jwtService,
            auditLogger,
            AuthProperties(oidc = AuthProperties.Oidc(listOf(provider))),
            workspaceService,
            ClientAddressResolver(emptyList()),
        ).onAuthenticationSuccess(
            MockHttpServletRequest("GET", "/login/oauth2/code/github"),
            response,
            OAuth2AuthenticationToken(principal, authorities, "github"),
        )
        return response
    }

    private fun attributes(extra: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf(
            "id" to 4242,
            "sub" to "4242",
            "email" to "Alice@Company.COM",
            "name" to "Alice",
            "picture" to "https://avatars.example/a",
        ) + extra

    private fun provisions() =
        every { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) } returns
            UserService.Provisioned(user(), created = false)

    @Test
    fun `an OAuth2User principal provisions from its attributes - one code path with OIDC`() {
        provisions()

        val response = run(attributes(mapOf("email_verified" to true)), provider(emailsUri = EMAILS_URI, trust = false))

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldNotBeNull()
        verify {
            userService.findOrCreateByEmail(
                email = "alice@company.com",
                displayName = "Alice",
                pictureUrl = "https://avatars.example/a",
                provider = "github",
                providerSubject = "4242",
            )
        }
    }

    @Test
    fun `F5 - with an emails-uri the trust knob does not stand in for a missing verification`() {
        val response = run(attributes(), provider(emailsUri = EMAILS_URI, trust = true))

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldBeNull()
        response.redirectedUrl shouldBe "/login?error=oidc_error"
        verify(exactly = 0) { userService.findOrCreateByEmail(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { auditLogger.log("auth.login.email_verified_assumed", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `without an emails-uri the knob applies to an oauth2 entry as it does to an OIDC one - audited`() {
        provisions()

        val response = run(attributes(), provider(emailsUri = null, trust = true))

        response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldNotBeNull()
        verify(exactly = 1) { auditLogger.log("auth.login.email_verified_assumed", any(), any(), any(), any(), any()) }
    }

    private companion object {
        const val EMAILS_URI = "https://api.github.com/user/emails"
    }
}
