package co.datapipelines.auth

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType

/**
 * AUTH-SEC-8: PKCE (RFC 7636) is applied to every authorization request. The
 * `code_challenge` goes on the redirect to the IdP and the `code_verifier` is carried
 * in the request's attributes — which is exactly why
 * [CookieOAuth2AuthorizationRequestRepository] must round-trip `attributes`.
 */
class PkceAuthorizationRequestTest {
    private val registration: ClientRegistration =
        ClientRegistration
            .withRegistrationId("keycloak")
            .clientId("dp-client")
            .clientSecret("dp-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://dp.example.com/login/oauth2/code/keycloak")
            .authorizationUri("https://idp.example.com/authorize")
            .tokenUri("https://idp.example.com/token")
            .scope("openid", "profile", "email")
            .build()

    private val resolver =
        OidcConfig().authorizationRequestResolver(InMemoryClientRegistrationRepository(registration))

    @Test
    fun `the authorization request carries a PKCE challenge and keeps the verifier in attributes`() {
        val request = MockHttpServletRequest("GET", "/oauth2/authorization/keycloak")
        request.servletPath = "/oauth2/authorization/keycloak"

        val resolved = resolver.resolve(request)

        resolved.shouldNotBeNull()
        resolved.additionalParameters["code_challenge_method"] shouldBe "S256"
        (resolved.additionalParameters["code_challenge"] as String).shouldNotBeBlank()
        (resolved.attributes["code_verifier"] as String).shouldNotBeBlank()
        resolved.attributes["registration_id"] shouldBe "keycloak"
        // The challenge is on the wire; the verifier is not.
        resolved.authorizationRequestUri.contains("code_challenge=") shouldBe true
        resolved.authorizationRequestUri.contains("code_verifier=") shouldBe false
    }
}
