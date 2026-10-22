package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.time.TimeSource

/** Real loopback HTTP through the production OIDC/token beans, including Spring's OAuth errors. */
class ProviderReadTimeoutTest {
    private fun properties(seconds: Long = 1) = AuthProperties(oidc = AuthProperties.Oidc(providerReadTimeoutSeconds = seconds))

    @Test
    fun `all provider factories set connect and read timeouts without millisecond overflow`() {
        for (seconds in listOf(1L, 7L, 10L, MAX_SECONDS)) {
            val factory = OidcConfig.boundedRequestFactory(properties(seconds))
            ReflectionTestUtils.getField(factory, "connectTimeout") shouldBe (seconds * MILLIS_PER_SECOND).toInt()
            ReflectionTestUtils.getField(factory, "readTimeout") shouldBe (seconds * MILLIS_PER_SECOND).toInt()
        }
        for (seconds in listOf(0L, -1L, MAX_SECONDS + 1, Long.MAX_VALUE)) {
            shouldThrow<IllegalArgumentException> { OidcConfig.boundedRequestFactory(properties(seconds)) }
        }
    }

    @Test
    fun `OIDC user-info uses the configured timeout through the production bean`() {
        OAuth2ProviderStub().use { stub ->
            stub.userDelayMillis = DELAY_MILLIS
            val start = TimeSource.Monotonic.markNow()
            val result =
                runCatching {
                    OidcConfig().providerOidcUserService(properties()).loadUser(oidcRequest(stub))
                }
            val elapsed = start.elapsedNow().inWholeMilliseconds
            println("oidc user-info timeout elapsed_ms=$elapsed")
            elapsed shouldBeLessThan BUDGET_MILLIS
            val failure = result.exceptionOrNull().shouldBeInstanceOf<OAuth2AuthenticationException>()
            failure.error.errorCode shouldBe "invalid_user_info_response"
            stub.requestsTo(OAuth2ProviderStub.USER).size shouldBe 1
        }
    }

    @Test
    fun `OIDC user-info still merges claims when the provider answers`() {
        OAuth2ProviderStub().use { stub ->
            stub.user = """{"sub":"4242","name":"OIDC Alice"}"""
            val user = OidcConfig().providerOidcUserService(properties()).loadUser(oidcRequest(stub))
            user.subject shouldBe "4242"
            user.fullName shouldBe "OIDC Alice"
            stub.requestsTo(OAuth2ProviderStub.USER).size shouldBe 1
        }
    }

    @Test
    fun `bounded token client preserves token conversion and OAuth error handling`() {
        OAuth2ProviderStub().use { stub ->
            val client = OidcConfig().providerTokenResponseClient(properties())
            val request = tokenRequest(stub)
            client.getTokenResponse(request).accessToken.tokenValue shouldBe OAuth2ProviderStub.ACCESS_TOKEN
            stub.requestsTo(OAuth2ProviderStub.TOKEN).single().method shouldBe "POST"
            stub.tokenStatus = BAD_REQUEST
            stub.tokenBody = """{"error":"invalid_grant","error_description":"code refused"}"""
            val failure = shouldThrow<OAuth2AuthorizationException> { client.getTokenResponse(request) }
            failure.error.errorCode shouldBe "invalid_grant"
        }
    }

    private fun registration(stub: OAuth2ProviderStub): ClientRegistration =
        ClientRegistration
            .withRegistrationId("oidc-stub")
            .clientId("client")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT)
            .authorizationUri("${stub.base}${OAuth2ProviderStub.AUTHORIZE}")
            .tokenUri("${stub.base}${OAuth2ProviderStub.TOKEN}")
            .userInfoUri("${stub.base}${OAuth2ProviderStub.USER}")
            .userNameAttributeName("sub")
            .scope("openid", "profile")
            .build()

    private fun oidcRequest(stub: OAuth2ProviderStub): OidcUserRequest {
        val now = Instant.now()
        val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(TOKEN_TTL), setOf("profile"))
        val idToken = OidcIdToken("id-token", now, now.plusSeconds(TOKEN_TTL), mapOf("sub" to "4242"))
        return OidcUserRequest(registration(stub), token, idToken)
    }

    private fun tokenRequest(stub: OAuth2ProviderStub): OAuth2AuthorizationCodeGrantRequest {
        val request =
            OAuth2AuthorizationRequest
                .authorizationCode()
                .authorizationUri("${stub.base}${OAuth2ProviderStub.AUTHORIZE}")
                .clientId("client")
                .redirectUri(REDIRECT)
                .state("state")
                .build()
        val response =
            OAuth2AuthorizationResponse
                .success("code")
                .redirectUri(REDIRECT)
                .state("state")
                .build()
        return OAuth2AuthorizationCodeGrantRequest(registration(stub), OAuth2AuthorizationExchange(request, response))
    }

    private companion object {
        const val REDIRECT = "https://dp.example.com/login/oauth2/code/oidc-stub"
        const val DELAY_MILLIS = 3000L
        const val BUDGET_MILLIS = 2000L
        const val TOKEN_TTL = 60L
        const val BAD_REQUEST = 400
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_SECONDS = 2147483L
    }
}
