package co.datapipelines.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persists the in-flight [OAuth2AuthorizationRequest] in a short-lived cookie instead
 * of the HttpSession, so the server stays **stateless** (auth.md §8.1 sets
 * `SessionCreationPolicy.STATELESS`; the default session-backed repository would
 * force a server session and lose the authorization request on the callback).
 *
 * The cookie is `HttpOnly`, `Secure` and `SameSite=Lax` — Lax (not Strict) because
 * the provider's callback is a top-level cross-site navigation that Strict would drop.
 * It carries only our own authorization request and is cleared on use.
 *
 * ## Wire format (AUTH-SEC-2 / AUTH-SEC-8)
 * `base64url(payload) + "." + base64url(HMAC-SHA256(payload))`, where `payload` is
 * **JSON** of the minimal fields.
 *
 * Java serialization is deliberately absent. An `ObjectInputStream` over an
 * attacker-supplied cookie is a deserialization sink even with an
 * `ObjectInputFilter` allowlist: the filter constrains *which* classes may be
 * instantiated, not what the stream can do on the way there (unbounded arrays,
 * deep nesting → `StackOverflowError`/`OutOfMemoryError` before any class is
 * resolved). JSON of six known fields has no such surface.
 *
 * The MAC is verified **before** the payload is parsed, with a key derived from the
 * JWT secret under this cookie's own label ([JwtService.deriveSubkey]) — so a forged
 * or tampered cookie is discarded without the JSON parser ever seeing it, and this
 * MAC key is not the session-signing key. Length is capped before base64 decoding,
 * and every failure path — including `Error`s such as `StackOverflowError` raised by
 * hostile input — resolves to `null` (no authorization request) rather than a 500.
 */
class CookieOAuth2AuthorizationRequestRepository(
    jwtService: JwtService,
    private val objectMapper: ObjectMapper,
    /** T33: `Secure` follows the base-url scheme ([AuthProperties.secureCookies]); default secure. */
    private val secureCookies: Boolean = true,
) : AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private val log = LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository::class.java)
    private val macKey = SecretKeySpec(jwtService.deriveSubkey(COOKIE), HMAC_ALGORITHM)

    override fun loadAuthorizationRequest(request: HttpServletRequest): OAuth2AuthorizationRequest? = readCookie(request)?.let(::decode)

    override fun saveAuthorizationRequest(
        authorizationRequest: OAuth2AuthorizationRequest?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (authorizationRequest == null) {
            expire(response)
            return
        }
        response.addCookie(
            Cookie(COOKIE, encode(authorizationRequest)).apply {
                isHttpOnly = true
                secure = secureCookies
                path = "/"
                maxAge = MAX_AGE_SECONDS
                setAttribute("SameSite", "Lax")
            },
        )
    }

    override fun removeAuthorizationRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): OAuth2AuthorizationRequest? {
        val loaded = loadAuthorizationRequest(request)
        if (loaded != null) expire(response)
        return loaded
    }

    private fun readCookie(request: HttpServletRequest): String? =
        request.cookies
            ?.firstOrNull { it.name == COOKIE }
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun expire(response: HttpServletResponse) {
        response.addCookie(
            Cookie(COOKIE, "").apply {
                isHttpOnly = true
                secure = secureCookies
                path = "/"
                maxAge = 0
            },
        )
    }

    private fun encode(authorizationRequest: OAuth2AuthorizationRequest): String {
        val payload = objectMapper.writeValueAsBytes(AuthorizationRequestPayload.of(authorizationRequest))
        return "${ENCODER.encodeToString(payload)}.${ENCODER.encodeToString(mac(payload))}"
    }

    /**
     * Verifies the MAC, then parses. Returns `null` for anything that is not a cookie
     * this server minted and still trusts.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun decode(value: String): OAuth2AuthorizationRequest? =
        try {
            // Cap FIRST: a multi-megabyte cookie is discarded before it is decoded.
            require(value.length <= MAX_COOKIE_LENGTH) { "authorization-request cookie exceeds $MAX_COOKIE_LENGTH chars" }
            val separator = value.lastIndexOf('.')
            require(separator > 0 && separator < value.length - 1) { "authorization-request cookie is not payload.mac" }
            val payload = DECODER.decode(value.substring(0, separator))
            val presented = DECODER.decode(value.substring(separator + 1))
            require(MessageDigest.isEqual(mac(payload), presented)) { "authorization-request cookie MAC mismatch" }
            objectMapper.readValue(payload, AuthorizationRequestPayload::class.java).toAuthorizationRequest()
        } catch (e: Throwable) {
            // Includes Errors: hostile input must never become a 500. A malformed,
            // forged, oversized or expired cookie simply means "no authorization
            // request in flight" — logged, never swallowed silently (rules/02).
            log.debug("Discarding unusable {} cookie: {}", COOKIE, e.toString())
            null
        }

    private fun mac(payload: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(macKey)
            doFinal(payload)
        }

    /**
     * The minimal set of fields Spring needs to complete the code exchange.
     *
     * `attributes` is **not** optional: it carries `registration_id` (used by
     * `OAuth2LoginAuthenticationFilter` to resolve the client), the OIDC `nonce`, and
     * — since PKCE is enabled ([OidcConfig]) — the `code_verifier` the token request
     * must present. Dropping it would silently break login.
     *
     * Every property carries `@param:` **and** `@get:` `@JsonProperty` (rules/09 Kotlin
     * trap): with only the `@param:` target, Jackson reads the wire name on the way IN
     * and the getter's camelCase name on the way OUT — the cookie then never
     * round-trips, and the failure is silent (every load resolves to "no authorization
     * request in flight").
     */
    internal class AuthorizationRequestPayload
        @JsonCreator
        constructor(
            @param:JsonProperty("authorization_uri")
            @get:JsonProperty("authorization_uri")
            val authorizationUri: String,
            @param:JsonProperty("client_id")
            @get:JsonProperty("client_id")
            val clientId: String,
            @param:JsonProperty("redirect_uri")
            @get:JsonProperty("redirect_uri")
            val redirectUri: String?,
            @param:JsonProperty("scopes")
            @get:JsonProperty("scopes")
            val scopes: Set<String>,
            @param:JsonProperty("state")
            @get:JsonProperty("state")
            val state: String?,
            @param:JsonProperty("additional_parameters")
            @get:JsonProperty("additional_parameters")
            val additionalParameters: Map<String, String>,
            @param:JsonProperty("attributes")
            @get:JsonProperty("attributes")
            val attributes: Map<String, String>,
            @param:JsonProperty("authorization_request_uri")
            @get:JsonProperty("authorization_request_uri")
            val authorizationRequestUri: String,
        ) {
            fun toAuthorizationRequest(): OAuth2AuthorizationRequest =
                OAuth2AuthorizationRequest
                    .authorizationCode()
                    .authorizationUri(authorizationUri)
                    .clientId(clientId)
                    .redirectUri(redirectUri)
                    .scopes(scopes)
                    .state(state)
                    .additionalParameters(LinkedHashMap<String, Any>(additionalParameters))
                    .attributes(LinkedHashMap<String, Any>(attributes))
                    .authorizationRequestUri(authorizationRequestUri)
                    .build()

            companion object {
                fun of(request: OAuth2AuthorizationRequest): AuthorizationRequestPayload {
                    require(request.grantType == AuthorizationGrantType.AUTHORIZATION_CODE) {
                        "Only the authorization_code grant is stored in this cookie (auth.md §5.4)"
                    }
                    return AuthorizationRequestPayload(
                        authorizationUri = request.authorizationUri,
                        clientId = request.clientId,
                        redirectUri = request.redirectUri,
                        scopes = request.scopes,
                        state = request.state,
                        additionalParameters = request.additionalParameters.mapValues { it.value.toString() },
                        attributes = request.attributes.mapValues { it.value.toString() },
                        authorizationRequestUri = request.authorizationRequestUri,
                    )
                }
            }
        }

    private companion object {
        const val COOKIE = "dp_oauth2_authz"
        const val MAX_AGE_SECONDS = 180
        const val HMAC_ALGORITHM = "HmacSHA256"

        /**
         * Hard cap on the cookie value before any decoding. Real payloads are ~600-900
         * chars (authorization URI + scopes + PKCE + nonce); 4 KB is the practical
         * per-cookie browser limit and far above anything we mint.
         */
        const val MAX_COOKIE_LENGTH = 4096

        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
