package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

/**
 * The two provider protocols at registration time (#655, auth.md §5.2). GitHub is OAuth 2.0
 * without OpenID Connect: built as an `issuer-uri` entry — the shape the shipped
 * application.yml had until #655 — the registration cannot be built at all, because discovery
 * finds no metadata (F1, base side). Built as `protocol: oauth2`, it is assembled from the
 * configured endpoints with no HTTP at startup (F1, tip side).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcConfigProtocolTest {
    /** Answers 404 to every `/.well-known` path and counts the attempts — GitHub's shape. */
    private val provider = OAuth2ProviderStub()

    private val config = OidcConfig()

    @BeforeEach
    fun clearHits() = provider.recorded.clear()

    @AfterAll
    fun close() = provider.close()

    private fun oauth2Entry() =
        AuthProperties.Provider(
            name = "github",
            protocol = "oauth2",
            clientId = "gh-client",
            clientSecret = "gh-secret",
            authorizationUri = "${provider.base}${OAuth2ProviderStub.AUTHORIZE}",
            tokenUri = "${provider.base}${OAuth2ProviderStub.TOKEN}",
            userInfoUri = "${provider.base}${OAuth2ProviderStub.USER}",
            userNameAttribute = "id",
            scopes = listOf("read:user", "user:email"),
            emailsUri = "${provider.base}${OAuth2ProviderStub.EMAILS}",
            displayName = "Sign in with GitHub",
        )

    private fun repository(entry: AuthProperties.Provider) =
        config.clientRegistrationRepository(AuthProperties(baseUrl = "https://dp.example.com", oidc = AuthProperties.Oidc(listOf(entry))))

    @Test
    fun `F1 base - GitHub as an issuer-uri entry cannot be built - discovery finds no metadata`() {
        val failure =
            shouldThrow<IllegalArgumentException> {
                repository(
                    AuthProperties.Provider(name = "github", clientId = "gh-client", clientSecret = "gh-secret", issuerUri = provider.base),
                )
            }

        provider.wellKnownHits shouldBeGreaterThan 0
        println("EVIDENCE-655 F1 base: threw ${failure.javaClass.simpleName}: ${failure.message}")
        println("EVIDENCE-655 F1 base: discovery hits=${provider.wellKnownHits} paths=${provider.recorded.map { it.path }}")
    }

    @Test
    fun `F1 tip - an oauth2 entry is built from its configured endpoints with no HTTP at startup`() {
        val registration = repository(oauth2Entry()).findByRegistrationId("github")

        provider.recorded.size shouldBe 0
        registration.clientName shouldBe "Sign in with GitHub"
        registration.authorizationGrantType shouldBe AuthorizationGrantType.AUTHORIZATION_CODE
        // The client authentication Spring's own CommonOAuth2Provider.GITHUB template uses.
        registration.clientAuthenticationMethod shouldBe ClientAuthenticationMethod.CLIENT_SECRET_BASIC
        registration.scopes shouldContainExactlyInAnyOrder listOf("read:user", "user:email")
        registration.redirectUri shouldBe "https://dp.example.com/login/oauth2/code/github"
        registration.providerDetails.authorizationUri shouldBe "${provider.base}${OAuth2ProviderStub.AUTHORIZE}"
        registration.providerDetails.tokenUri shouldBe "${provider.base}${OAuth2ProviderStub.TOKEN}"
        registration.providerDetails.userInfoEndpoint.uri shouldBe "${provider.base}${OAuth2ProviderStub.USER}"
        registration.providerDetails.userInfoEndpoint.userNameAttributeName shouldBe "id"
        println("EVIDENCE-655 F1 tip: built registration '${registration.registrationId}', stub requests=${provider.recorded.size}")
    }

    @Test
    fun `an oauth2 entry missing an endpoint is refused by name at construction`() {
        val failure = shouldThrow<IllegalArgumentException> { repository(oauth2Entry().copy(tokenUri = "")) }

        failure.message.orEmpty() shouldContain "'github'"
        failure.message.orEmpty() shouldContain "token-uri"
    }

    @Test
    fun `an oauth2 entry asking for the openid scope is refused - that would route it to the OIDC user service`() {
        val failure = shouldThrow<IllegalArgumentException> { repository(oauth2Entry().copy(scopes = listOf("openid", "read:user"))) }

        failure.message.orEmpty() shouldContain "openid"
    }

    @Test
    fun `an unknown protocol is refused by name at construction`() {
        val failure = shouldThrow<IllegalArgumentException> { repository(oauth2Entry().copy(protocol = "saml")) }

        failure.message.orEmpty() shouldContain "protocol 'saml'"
    }
}
