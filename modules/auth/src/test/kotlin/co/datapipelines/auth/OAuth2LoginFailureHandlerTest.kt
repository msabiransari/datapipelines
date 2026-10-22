package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error

/**
 * The provider-login failure path (#655, auth.md §5.5): the opaque redirect it always had, plus
 * the `auth.login.oidc_error` audit row it did not — reason = the OAuth 2.0 error code, and a
 * provider named only when the callback path names a CONFIGURED registration.
 */
class OAuth2LoginFailureHandlerTest {
    /**
     * Relaxed, and every case reads back what was RECORDED (rules/14): the contract is "you must
     * call me", which a strict mock would make unobservable.
     */
    private val audit = mockk<AuditLogger>(relaxed = true)

    private val rows = mutableListOf<Pair<String, Map<String, Any?>>>()

    init {
        every { audit.log(any(), any(), any(), any(), any(), any()) } answers { rows += firstArg<String>() to lastArg<Map<String, Any?>>() }
    }

    private val handler =
        OAuth2LoginFailureHandler(
            audit,
            ClientAddressResolver(emptyList()),
            AuthProperties(oidc = AuthProperties.Oidc(listOf(AuthProperties.Provider(name = "github", clientId = "id")))),
        )

    private fun fail(
        path: String,
        exception: AuthenticationException,
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        handler.onAuthenticationFailure(MockHttpServletRequest("GET", path), response, exception)
        return response
    }

    @Test
    fun `an OAuth 2_0 failure is audited with its error code and the configured provider, and redirected opaquely`() {
        val response =
            fail(
                "/login/oauth2/code/github",
                OAuth2AuthenticationException(OAuth2Error(OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE, "HTTP 500", null)),
            )

        response.redirectedUrl shouldBe "/login?error=oidc_error"
        rows shouldBe
            listOf(
                "auth.login.oidc_error" to mapOf("reason" to OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE, "provider" to "github"),
            )
    }

    @Test
    fun `a callback path naming no configured provider is audited without a provider - the segment is not written`() {
        fail("/login/oauth2/code/<script>", OAuth2AuthenticationException(OAuth2Error("authorization_request_not_found")))

        rows.single().second shouldBe mapOf("reason" to "authorization_request_not_found", "provider" to null)
    }

    @Test
    fun `a failure with no OAuth 2_0 error code is audited as unclassified`() {
        fail("/login/oauth2/code/github", InternalAuthenticationServiceException("boom"))

        rows.single().second["reason"] shouldBe OAuth2LoginFailureHandler.UNCLASSIFIED
    }
}
