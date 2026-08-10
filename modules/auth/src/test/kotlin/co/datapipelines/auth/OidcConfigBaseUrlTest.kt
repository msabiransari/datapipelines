package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * AUTH-SEC-11 / auth.md §5.2: the OIDC redirect URI is built **absolutely** from
 * `datapipelines.auth.base-url`, and startup fails when that key is unset while any
 * provider is configured.
 *
 * The failure case is the whole point: the alternative to a configured origin is
 * Spring's request-derived `{baseUrl}` placeholder, which lets a hostile
 * `Host` / `X-Forwarded-Host` header choose the `redirect_uri` sent to the IdP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcConfigBaseUrlTest {
    private val config = OidcConfig()

    private fun provider() =
        AuthProperties.Provider(
            name = "keycloak",
            clientId = "dp-client",
            clientSecret = "dp-secret",
            issuerUri = discovery.issuer,
            displayName = "Company SSO",
        )

    @Test
    fun `startup fails when base-url is unset while a provider is configured`() {
        val props = AuthProperties(baseUrl = null, oidc = AuthProperties.Oidc(providers = listOf(provider())))

        val failure = shouldThrow<IllegalStateException> { config.clientRegistrationRepository(props) }

        failure.message.orEmpty() shouldContain "datapipelines.auth.base-url"
    }

    @Test
    fun `startup fails when base-url is blank`() {
        val props = AuthProperties(baseUrl = "   ", oidc = AuthProperties.Oidc(providers = listOf(provider())))

        shouldThrow<IllegalStateException> { config.clientRegistrationRepository(props) }
    }

    @Test
    fun `the redirect uri is absolute, from base-url, with the registration id appended`() {
        val props =
            AuthProperties(
                baseUrl = "https://dp.example.com",
                oidc = AuthProperties.Oidc(providers = listOf(provider())),
            )

        val registration = config.clientRegistrationRepository(props).findByRegistrationId("keycloak")

        registration.redirectUri shouldBe "https://dp.example.com/login/oauth2/code/keycloak"
        // Never the request-derived template.
        registration.redirectUri.contains("{baseUrl}") shouldBe false
        registration.clientName shouldBe "Company SSO"
        registration.providerDetails.authorizationUri shouldBe "${discovery.issuer}/protocol/openid-connect/auth"
    }

    @Test
    fun `a trailing slash on base-url does not produce a doubled separator`() {
        val props =
            AuthProperties(
                baseUrl = "https://dp.example.com/",
                oidc = AuthProperties.Oidc(providers = listOf(provider())),
            )

        config
            .clientRegistrationRepository(props)
            .findByRegistrationId("keycloak")
            .redirectUri shouldBe "https://dp.example.com/login/oauth2/code/keycloak"
    }

    @Test
    fun `no providers configured is still a startup failure`() {
        shouldThrow<IllegalStateException> { config.clientRegistrationRepository(AuthProperties(baseUrl = "https://x")) }
    }

    @AfterAll
    fun stopStub() = discovery.close()

    private companion object {
        val discovery = OidcDiscoveryStub()
    }
}
