package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import java.time.Instant

/**
 * [OAuth2IdentityUserService] against the GitHub-shaped stub over real HTTP (#655): the
 * OIDC-shaped attributes it hands [OidcSuccessHandler], the verified-PRIMARY rule, and every
 * malformed emails answer as the named [OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuth2IdentityUserServiceTest {
    private val stub = OAuth2ProviderStub()

    @BeforeEach
    fun reset() {
        stub.recorded.clear()
        stub.user = OAuth2ProviderStub.GITHUB_USER
        stub.emails = OAuth2ProviderStub.VERIFIED_PRIMARY
        stub.emailsStatus = OK
    }

    @AfterAll
    fun close() = stub.close()

    private fun service(emailsUri: String? = "${stub.base}${OAuth2ProviderStub.EMAILS}") =
        OAuth2IdentityUserService(
            AuthProperties(
                oidc = AuthProperties.Oidc(listOf(AuthProperties.Provider(name = "github", protocol = "oauth2", emailsUri = emailsUri))),
            ),
        )

    private fun request(nameAttribute: String = "id"): OAuth2UserRequest {
        val registration =
            ClientRegistration
                .withRegistrationId("github")
                .clientId("gh-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://dp.example.com/login/oauth2/code/github")
                .authorizationUri("${stub.base}${OAuth2ProviderStub.AUTHORIZE}")
                .tokenUri("${stub.base}${OAuth2ProviderStub.TOKEN}")
                .userInfoUri("${stub.base}${OAuth2ProviderStub.USER}")
                .userNameAttributeName(nameAttribute)
                .build()
        val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, OAuth2ProviderStub.ACCESS_TOKEN, Instant.now(), null)
        return OAuth2UserRequest(registration, token)
    }

    private fun failureOf(block: () -> Unit): String = shouldThrow<OAuth2AuthenticationException>(block).error.errorCode

    @Test
    fun `the principal carries the OIDC-shaped keys - sub as a string, the verified primary, name and picture`() {
        val user = service().loadUser(request())

        user.attributes["sub"] shouldBe "4242"
        user.name shouldBe "4242"
        user.attributes["email"] shouldBe "Octo-Alice@Datapipelines.CO" // lowercased by the handler, not here
        user.attributes["email_verified"] shouldBe true
        user.attributes["name"] shouldBe "Alice Octo"
        user.attributes["picture"] shouldBe "https://avatars.githubusercontent.com/u/4242?v=4"
    }

    @Test
    fun `with an emails-uri the public user email is discarded even when no primary row exists`() {
        stub.emails = """[{"email":"other@datapipelines.co","primary":false,"verified":true}]"""

        val user = service().loadUser(request())

        user.attributes shouldNotContainKey "email"
        user.attributes shouldNotContainKey "email_verified"
    }

    @Test
    fun `an unverified primary is passed on as email_verified false - and a non-boolean verified flag fails closed`() {
        stub.emails = """[{"email":"a@datapipelines.co","primary":true,"verified":false}]"""
        service().loadUser(request()).attributes["email_verified"] shouldBe false

        stub.emails = """[{"email":"a@datapipelines.co","primary":true,"verified":"true"}]"""
        service().loadUser(request()).attributes["email_verified"] shouldBe false
    }

    @Test
    fun `without an emails-uri the user-info attributes are passed through and nothing else is read`() {
        val user = service(emailsUri = null).loadUser(request())

        user.attributes["email"] shouldBe "public-alias@elsewhere.example"
        stub.requestsTo(OAuth2ProviderStub.EMAILS).size shouldBe 0
    }

    @Test
    fun `a non-2xx emails answer is the named error and its description never carries the token`() {
        stub.emailsStatus = SERVER_ERROR
        stub.emails = """{"message":"boom"}"""

        val failure = shouldThrow<OAuth2AuthenticationException> { service().loadUser(request()) }

        failure.error.errorCode shouldBe OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE
        failure.error.description shouldContain "HTTP 500"
        failure.error.description shouldNotContain OAuth2ProviderStub.ACCESS_TOKEN
    }

    @Test
    fun `malformed emails answers are the named error - an object, a non-object row, two primaries, a primary with no address`() {
        val malformed =
            listOf(
                """{"email":"a@b.co","primary":true,"verified":true}""",
                """["a@b.co"]""",
                """[{"email":"a@b.co","primary":true,"verified":true},{"email":"c@b.co","primary":true,"verified":true}]""",
                """[{"primary":true,"verified":true}]""",
            )
        malformed.forEach { body ->
            stub.emails = body
            failureOf { service().loadUser(request()) } shouldBe OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE
        }
    }

    @Test
    fun `a user-info answer without the configured subject attribute is a named authentication failure, not a 500`() {
        failureOf { service().loadUser(request(nameAttribute = "uuid")) } shouldBe OAuth2IdentityUserService.INVALID_USER_INFO_RESPONSE
    }

    private companion object {
        const val OK = 200
        const val SERVER_ERROR = 500
    }
}
