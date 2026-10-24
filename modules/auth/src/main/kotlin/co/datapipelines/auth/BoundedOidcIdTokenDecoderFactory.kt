package co.datapipelines.auth

import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoderFactory
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.util.StringUtils
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

/**
 * Spring's ID-token decoder, re-created so the JWKS fetch carries the login provider
 * read bound (#686): [OidcConfig] exposes this class as the
 * `JwtDecoderFactory<ClientRegistration>` bean, which Spring's login config resolves
 * and hands to its OIDC authentication provider in place of a plain
 * [OidcIdTokenDecoderFactory]. Everything else mirrors Spring's factory (Spring
 * Security 6.5.11, `OidcIdTokenDecoderFactory`) branch for branch:
 *
 * - **Algorithm resolution** — the default resolver answers `SignatureAlgorithm.RS256`
 *   for every registration, as Spring's does; a signature algorithm builds through
 *   `withJwkSetUri`, a MAC algorithm through `withSecretKey` over the client secret
 *   (the JCA names from Spring's `JCA_ALGORITHM_MAPPINGS`), anything else refuses with
 *   the `missing_signature_verifier` error code and Spring's message texts.
 * - **Validators** — `JwtValidators.createDefaultWithValidators(OidcIdTokenValidator(registration))`,
 *   exactly what Spring's package-private default validator factory installs: the
 *   default stack (certificate thumbprint + timestamp) plus the issuer/audience
 *   validation against this registration.
 * - **Claim-type conversion** — one [ClaimTypeConverter] from
 *   [OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter], shared across the
 *   decoders this factory builds, as Spring's shared static is.
 * - **Memoisation** — one decoder per registration id, created on first use, in a
 *   [ConcurrentHashMap]; each decoder is a distinct `NimbusJwtDecoder` per registration.
 *
 * The ONE difference is the `restOperations` on the JWK-set decoder: Spring's builder
 * default is an unbounded `RestTemplate`; here it is [OidcConfig.boundedRestTemplate] —
 * the same connect/read bound every other login-thread provider read carries
 * (`datapipelines.auth.oidc.provider-read-timeout-seconds`, #682). No JWK-set cache is
 * added: Spring's builder defaults to a `NoOpCache` and only wraps a caching source when
 * a real cache is configured, so every decode fetches the JWKS through the bounded
 * client, exactly as it previously fetched through Spring's client.
 */
internal class BoundedOidcIdTokenDecoderFactory(
    private val boundedRestTemplate: RestTemplate,
    /** Spring's default resolver answers `SignatureAlgorithm.RS256` unconditionally. */
    private val jwsAlgorithmResolver: (ClientRegistration) -> JwsAlgorithm = { SignatureAlgorithm.RS256 },
) : JwtDecoderFactory<ClientRegistration> {
    private val jwtDecoders: MutableMap<String, JwtDecoder> = ConcurrentHashMap()

    private val defaultClaimTypeConverter: ClaimTypeConverter =
        OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter()

    override fun createDecoder(registration: ClientRegistration): JwtDecoder =
        jwtDecoders.computeIfAbsent(registration.registrationId) {
            buildDecoder(registration).apply {
                setJwtValidator(JwtValidators.createDefaultWithValidators(OidcIdTokenValidator(registration)))
                setClaimSetConverter(defaultClaimTypeConverter)
            }
        }

    /**
     * Spring's `buildDecoder` (6.5.11) branch for branch, with the bounded client on the
     * JWK-set branch. A MAC decoder talks to no provider, so it carries no HTTP client.
     */
    private fun buildDecoder(registration: ClientRegistration): NimbusJwtDecoder {
        val jwsAlgorithm = jwsAlgorithmResolver(registration)
        return when (jwsAlgorithm) {
            is SignatureAlgorithm -> {
                val jwkSetUri = registration.providerDetails.jwkSetUri
                if (!StringUtils.hasText(jwkSetUri)) {
                    missingSignatureVerifier(
                        "Failed to find a Signature Verifier for Client Registration: " +
                            "'${registration.registrationId}'. Check to ensure you have configured the JwkSet URI.",
                    )
                }
                NimbusJwtDecoder
                    .withJwkSetUri(jwkSetUri)
                    .jwsAlgorithm(jwsAlgorithm)
                    .restOperations(boundedRestTemplate)
                    .build()
            }

            is MacAlgorithm -> {
                val clientSecret = registration.clientSecret
                if (!StringUtils.hasText(clientSecret)) {
                    missingSignatureVerifier(
                        "Failed to find a Signature Verifier for Client Registration: " +
                            "'${registration.registrationId}'. Check to ensure you have configured the client secret.",
                    )
                }
                val secretKey =
                    SecretKeySpec(
                        clientSecret.toByteArray(StandardCharsets.UTF_8),
                        requireNotNull(JCA_ALGORITHM_MAPPINGS[jwsAlgorithm]) {
                            "No JCA name mapped for MAC algorithm '$jwsAlgorithm'"
                        },
                    )
                NimbusJwtDecoder
                    .withSecretKey(secretKey)
                    .macAlgorithm(jwsAlgorithm)
                    .build()
            }

            else -> missingSignatureVerifier(
                "Failed to find a Signature Verifier for Client Registration: " +
                    "'${registration.registrationId}'. Check to ensure you have configured a valid JWS Algorithm: " +
                    "'$jwsAlgorithm'",
            )
        }
    }

    /**
     * Spring's error shape: `missing_signature_verifier`, thrown with the error's own
     * toString. Returns [Nothing], so call sites need no `throw` of their own.
     */
    private fun missingSignatureVerifier(description: String): Nothing {
        val oauth2Error = OAuth2Error(MISSING_SIGNATURE_VERIFIER_ERROR_CODE, description, null)
        throw OAuth2AuthenticationException(oauth2Error, oauth2Error.toString())
    }

    private companion object {
        /** The error code Spring's factory refuses with when no verifier can be built. */
        const val MISSING_SIGNATURE_VERIFIER_ERROR_CODE = "missing_signature_verifier"

        /** Spring's `JCA_ALGORITHM_MAPPINGS`: the HS* MAC algorithms' javax.crypto names. */
        val JCA_ALGORITHM_MAPPINGS: Map<MacAlgorithm, String> =
            mapOf(
                MacAlgorithm.HS256 to "HmacSHA256",
                MacAlgorithm.HS384 to "HmacSHA384",
                MacAlgorithm.HS512 to "HmacSHA512",
            )
    }
}
