package co.datapipelines.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.test.util.ReflectionTestUtils
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import kotlin.time.TimeSource

/**
 * The loopback JWKS oracle for the bounded ID-token decoder factory (#686): a real HTTP
 * fetch through the production `OidcConfig.providerIdTokenDecoderFactory` bean against
 * [JwksStub], no Spring context, the key minted per run with nimbus (`RSAKey`) — the same
 * library on the module's classpath that Spring decodes with. Proves the #682 budget now
 * holds the ID-token JWKS read, and that Spring's own decoder defaults survived: a signed
 * token decodes with typed claims, the OIDC issuer/audience validator rejects a foreign
 * audience, the timestamp validator rejects an expiry past skew, and an HS256 registration
 * decodes over the client secret without touching the network.
 */
class JwksReadTimeoutTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    private val rsaJwk: RSAKey =
        RSAKey
            .Builder(keyPair.public as RSAPublicKey)
            .privateKey(keyPair.private)
            .keyID(KEY_ID)
            .build()

    /**
     * The served key set. Serialized with `JWKSet.toString()`, NOT `toJSONObject()`:
     * nimbus 9.37.4 shades minidev, so `toJSONObject()` surfaces as a plain `HashMap`
     * whose `toString()` is Map-style (`k=v`) — invalid JSON, and the decoder rejects
     * the body as `Malformed Jwk set`.
     */
    private val jwksDocument: String get() = JWKSet(rsaJwk).toString()

    private fun properties(seconds: Long = 1) = AuthProperties(oidc = AuthProperties.Oidc(providerReadTimeoutSeconds = seconds))

    @Test
    fun `a JWKS read past the budget fails within it`() {
        JwksStub().use { stub ->
            stub.jwks = jwksDocument
            stub.delayMillis = DELAY_MILLIS
            val factory = OidcConfig().providerIdTokenDecoderFactory(properties())
            val start = TimeSource.Monotonic.markNow()
            val result = runCatching { factory.createDecoder(registration(stub)).decode(idToken(stub.issuer)) }
            val elapsed = start.elapsedNow().inWholeMilliseconds
            println("jwks timeout elapsed_ms=$elapsed")
            elapsed shouldBeLessThan BUDGET_MILLIS
            result.exceptionOrNull().shouldBeInstanceOf<JwtException>()
            stub.requestsTo(JwksStub.CERTS_PATH).size shouldBe 1
        }
    }

    @Test
    fun `a correctly signed token still decodes and the claims come back typed`() {
        JwksStub().use { stub ->
            stub.jwks = jwksDocument
            val jwt = OidcConfig().providerIdTokenDecoderFactory(properties()).createDecoder(registration(stub)).decode(idToken(stub.issuer))
            jwt.subject shouldBe SUBJECT
            // The claim-type converter took. exp/aud/iss are typed by NimbusJwtDecoder's own
            // defaults too, so they do not discriminate; auth_time is converted ONLY by the
            // OIDC claim-type converter — without it the raw numeric date stays a Long.
            jwt.claims["exp"].shouldBeInstanceOf<Instant>()
            jwt.claims["aud"].shouldBeInstanceOf<Collection<*>>()
            jwt.claims[AUTH_TIME_CLAIM].shouldBeInstanceOf<Instant>()
        }
    }

    @Test
    fun `a foreign audience is rejected by the OIDC validator`() {
        JwksStub().use { stub ->
            stub.jwks = jwksDocument
            val failure =
                shouldThrow<JwtValidationException> {
                    OidcConfig().providerIdTokenDecoderFactory(properties())
                        .createDecoder(registration(stub))
                        .decode(idToken(stub.issuer, audience = "other"))
                }
            // OidcIdTokenValidator builds its error as invalid_id_token in 6.5.11 — the
            // decoder surfaces the validator's own code, not a generic invalid_token.
            failure.errors.single().errorCode shouldBe "invalid_id_token"
        }
    }

    @Test
    fun `an expired token is rejected by the timestamp validator`() {
        JwksStub().use { stub ->
            stub.jwks = jwksDocument
            val failure =
                shouldThrow<JwtValidationException> {
                    OidcConfig().providerIdTokenDecoderFactory(properties())
                        .createDecoder(registration(stub))
                        .decode(idToken(stub.issuer, expired = true))
                }
            // The timestamp validator's verdict; the OIDC validator may add its own error,
            // so assert the timestamp verdict is PRESENT rather than the only one.
            failure.errors.map { it.errorCode } shouldContain "invalid_token"
        }
    }

    @Test
    fun `the default resolver answers RS256 as Spring's does`() {
        val factory = OidcConfig().providerIdTokenDecoderFactory(properties()) as BoundedOidcIdTokenDecoderFactory
        @Suppress("UNCHECKED_CAST")
        val resolver =
            ReflectionTestUtils.getField(factory, "jwsAlgorithmResolver") as Function1<ClientRegistration, JwsAlgorithm>
        JwksStub().use { stub -> resolver(registration(stub)) shouldBe SignatureAlgorithm.RS256 }
    }

    @Test
    fun `an HS256 registration decodes over the client secret without touching the network`() {
        JwksStub().use { stub ->
            val factory = BoundedOidcIdTokenDecoderFactory(OidcConfig.boundedRestTemplate(properties())) { MacAlgorithm.HS256 }
            val jwt = factory.createDecoder(hsRegistration(stub)).decode(hsToken(stub.issuer))
            jwt.subject shouldBe SUBJECT
            stub.requestsTo(JwksStub.CERTS_PATH).size shouldBe 0
        }
    }

    /** An `oidc`-shaped registration whose `jwkSetUri` is the stub's certs endpoint. */
    private fun registration(stub: JwksStub): ClientRegistration =
        ClientRegistration
            .withRegistrationId(REGISTRATION_ID)
            .clientId(CLIENT_ID)
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT)
            .authorizationUri("${stub.issuer}/protocol/openid-connect/auth")
            .tokenUri("${stub.issuer}/protocol/openid-connect/token")
            .jwkSetUri(stub.certsUri)
            .issuerUri(stub.issuer)
            .userNameAttributeName("sub")
            .scope("openid")
            .build()

    /** An HS256-shaped registration: the client secret is the signing key, no `jwkSetUri` at all. */
    private fun hsRegistration(stub: JwksStub): ClientRegistration =
        ClientRegistration
            .withRegistrationId(REGISTRATION_ID)
            .clientId(CLIENT_ID)
            .clientSecret(HS_SECRET)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT)
            .authorizationUri("${stub.issuer}/protocol/openid-connect/auth")
            .tokenUri("${stub.issuer}/protocol/openid-connect/token")
            .issuerUri(stub.issuer)
            .userNameAttributeName("sub")
            .scope("openid")
            .build()

    /** Mints an RS256 ID token over the per-run key, with the claims the validators judge. */
    private fun idToken(
        issuer: String,
        audience: String = CLIENT_ID,
        expired: Boolean = false,
    ): String {
        val now = Instant.now()
        // Expired case: BOTH stamps in the past with iat < exp — Spring's Jwt rejects
        // exp <= iat at decode time, before any validator runs, so minting a future iat
        // with a past exp would never reach the timestamp validator.
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(SUBJECT)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(if (expired) now.minusSeconds(TTL_SECONDS * 2) else now))
                .expirationTime(Date.from(if (expired) now.minusSeconds(TTL_SECONDS) else now.plusSeconds(TTL_SECONDS)))
                .claim(AUTH_TIME_CLAIM, Date.from(now))
                .build()
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims)
        signed.sign(RSASSASigner(keyPair.private))
        return signed.serialize()
    }

    /** Mints an HS256 ID token over the registration's client secret. */
    private fun hsToken(issuer: String): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(SUBJECT)
                .issuer(issuer)
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(TTL_SECONDS)))
                .build()
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims)
        signed.sign(MACSigner(HS_SECRET))
        return signed.serialize()
    }

    private companion object {
        const val REGISTRATION_ID = "loop"
        const val CLIENT_ID = "cid"
        const val SUBJECT = "alice"
        const val REDIRECT = "https://dp.example.com/login/oauth2/code/loop"
        const val KEY_ID = "test-key"
        const val KEY_BITS = 2048
        const val TTL_SECONDS = 60L
        const val DELAY_MILLIS = 3000L
        const val BUDGET_MILLIS = 2000L

        /** 32 bytes: the minimum length nimbus accepts for an HS256 MAC key. */
        const val HS_SECRET = "0123456789abcdef0123456789abcdef"

        /** An OIDC date claim only the claim-type converter maps to [Instant]. */
        const val AUTH_TIME_CLAIM = "auth_time"
    }
}
