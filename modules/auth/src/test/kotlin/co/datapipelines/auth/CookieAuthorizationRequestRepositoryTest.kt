package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AUTH-SEC-2 / AUTH-SEC-8: the `dp_oauth2_authz` cookie is authenticated JSON, never
 * Java serialization. Round-trip, tamper, forgery, oversize, and clear-on-use.
 */
class CookieAuthorizationRequestRepositoryTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })
    private val jwtService = JwtService(JwtProperties(secret), AuthProperties())
    private val repository = CookieOAuth2AuthorizationRequestRepository(jwtService, ObjectMapper())

    private fun authorizationRequest(): OAuth2AuthorizationRequest =
        OAuth2AuthorizationRequest
            .authorizationCode()
            .authorizationUri("https://idp.example.com/authorize")
            .clientId("dp-client")
            .redirectUri("https://dp.example.com/login/oauth2/code/keycloak")
            .scopes(setOf("openid", "profile", "email"))
            .state("state-123")
            .additionalParameters(mapOf("code_challenge" to "chal", "code_challenge_method" to "S256", "nonce" to "hashed"))
            .attributes(mapOf("registration_id" to "keycloak", "code_verifier" to "verifier-abc", "nonce" to "raw"))
            .build()

    private fun save(request: OAuth2AuthorizationRequest): Cookie {
        val response = MockHttpServletResponse()
        repository.saveAuthorizationRequest(request, MockHttpServletRequest(), response)
        return checkNotNull(response.getCookie(COOKIE)) { "no $COOKIE cookie written" }
    }

    private fun load(value: String): OAuth2AuthorizationRequest? {
        val request = MockHttpServletRequest("GET", "/login/oauth2/code/keycloak")
        request.setCookies(Cookie(COOKIE, value))
        return repository.loadAuthorizationRequest(request)
    }

    @Test
    fun `an authorization request round-trips through the cookie, PKCE attributes included`() {
        val original = authorizationRequest()

        val loaded = load(save(original).value)

        loaded.shouldNotBeNull()
        loaded.authorizationUri shouldBe original.authorizationUri
        loaded.clientId shouldBe original.clientId
        loaded.redirectUri shouldBe original.redirectUri
        loaded.state shouldBe original.state
        loaded.scopes shouldBe original.scopes
        loaded.authorizationRequestUri shouldBe original.authorizationRequestUri
        loaded.additionalParameters["code_challenge"] shouldBe "chal"
        // The code_verifier lives in ATTRIBUTES; dropping it would break the token exchange.
        loaded.attributes["code_verifier"] shouldBe "verifier-abc"
        loaded.attributes["registration_id"] shouldBe "keycloak"
    }

    @Test
    fun `the cookie payload is JSON, not a Java-serialized object stream`() {
        val value = save(authorizationRequest()).value
        val payload = String(Base64.getUrlDecoder().decode(value.substringBeforeLast('.')))

        payload shouldContain "\"client_id\""
        // "rO0" is the base64 prefix of the Java serialization stream magic (0xACED).
        value shouldNotContain "rO0"
    }

    @Test
    fun `cookie attributes are HttpOnly, Secure, SameSite=Lax and short-lived`() {
        val cookie = save(authorizationRequest())

        cookie.isHttpOnly shouldBe true
        cookie.secure shouldBe true
        cookie.getAttribute("SameSite") shouldBe "Lax"
        cookie.maxAge shouldBe 180
    }

    /**
     * The tamper lands on the payload's **first** base64url character, deliberately.
     *
     * base64url packs 6 bits per character, so a payload whose byte length is not a
     * multiple of 3 ends in a character carrying only 4 or 2 significant bits — its
     * remaining bits are padding, and 4 (or 16) different characters decode to the
     * SAME bytes. This MAC is computed over the DECODED bytes, so flipping the LAST
     * character can leave the payload byte-identical and the cookie perfectly valid.
     * Today's payload happens to escape that band, but adding one field to
     * `AuthorizationRequestPayload` moves the length and the test starts failing
     * roughly one run in four, for reasons that have nothing to do with the MAC.
     * The first character always carries all 6 of its bits. (Same trap as the JWT
     * signature in `AuthHttpBoundaryTest`.)
     */
    @Test
    fun `a tampered payload is rejected before parsing`() {
        val value = save(authorizationRequest()).value
        val payload = value.substringBeforeLast('.')
        val mac = value.substringAfterLast('.')
        val flipped = (if (payload.first() == 'A') 'B' else 'A') + payload.drop(1)

        load("$flipped.$mac").shouldBeNull()
    }

    @Test
    fun `a cookie forged with the wrong key has no valid MAC and is discarded`() {
        val payload =
            """{"authorization_uri":"https://evil","client_id":"x","scopes":[],""" +
                """"additional_parameters":{},"attributes":{},"authorization_request_uri":"https://evil"}"""
        val bytes = payload.toByteArray()
        val forgedMac =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(ByteArray(32) { 42 }, "HmacSHA256"))
                doFinal(bytes)
            }
        val encoder = Base64.getUrlEncoder().withoutPadding()

        load("${encoder.encodeToString(bytes)}.${encoder.encodeToString(forgedMac)}").shouldBeNull()
    }

    /**
     * The oversized cookie is one this repository **itself minted**, so its MAC is
     * genuinely valid and the length cap is the ONLY thing that can reject it.
     *
     * The previous version passed `"A".repeat(9000)` — a string with no `.` at all,
     * which died at the `payload.mac` separator check. It therefore stayed green with
     * `require(value.length <= MAX_COOKIE_LENGTH)` deleted, i.e. it never tested the
     * cap. Any string built by hand has the same problem: it fails the MAC check
     * anyway. Only a legitimately-signed, over-long cookie isolates the cap.
     */
    @Test
    fun `an oversized cookie is discarded before it is decoded`() {
        val oversized =
            OAuth2AuthorizationRequest
                .authorizationCode()
                .authorizationUri("https://idp.example.com/authorize")
                .clientId("dp-client")
                .redirectUri("https://dp.example.com/login/oauth2/code/keycloak")
                .scopes(setOf("openid"))
                // Well past MAX_COOKIE_LENGTH (4096) once JSON-wrapped and base64'd.
                .state("s".repeat(5000))
                .attributes(mapOf("registration_id" to "keycloak"))
                .build()
        val value = save(oversized).value

        // Precondition: the cookie really is over the cap, and really is well-formed.
        value.length shouldBeGreaterThan MAX_COOKIE_LENGTH
        load(value).shouldBeNull()
    }

    @Test
    fun `garbage that is not payload-dot-mac is discarded`() {
        load("not-base64-at-all").shouldBeNull()
    }

    @Test
    fun `the cookie is expired on use so an authorization request is single-shot`() {
        val stored = save(authorizationRequest())
        val request = MockHttpServletRequest("GET", "/login/oauth2/code/keycloak")
        request.setCookies(Cookie(COOKIE, stored.value))
        val response = MockHttpServletResponse()

        val removed = repository.removeAuthorizationRequest(request, response)

        removed.shouldNotBeNull()
        checkNotNull(response.getCookie(COOKIE)).maxAge shouldBe 0
    }

    private companion object {
        const val COOKIE = "dp_oauth2_authz"

        /** Mirrors `CookieOAuth2AuthorizationRequestRepository.MAX_COOKIE_LENGTH`. */
        const val MAX_COOKIE_LENGTH = 4096
    }
}
