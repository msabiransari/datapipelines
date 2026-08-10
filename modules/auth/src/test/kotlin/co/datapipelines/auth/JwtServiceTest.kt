package co.datapipelines.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Internal session JWT (auth.md §6): issue/validate, HS256 pinning, tamper + expiry. */
class JwtServiceTest {
    private val secretBytes = ByteArray(32) { (it + 1).toByte() }
    private val secret = Base64.getEncoder().encodeToString(secretBytes)
    private val service = JwtService(JwtProperties(secret), AuthProperties())

    private fun user(isAdmin: Boolean = false) =
        User(
            id = UUID.randomUUID(),
            email = "alice@company.com",
            displayName = "Alice",
            profilePictureUrl = null,
            provider = "keycloak",
            providerSubject = "sub-1",
            isActive = true,
            isAdmin = isAdmin,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            lastLoginAt = null,
        )

    @Test
    fun `issue then validate round-trips subject, email and scopes`() {
        val u = user(isAdmin = false)
        val claims = service.validate(service.issue(u))

        claims.subject shouldBe u.id.toString()
        claims["email"] shouldBe u.email
        @Suppress("UNCHECKED_CAST")
        val scopes = claims["scopes"] as List<String>
        scopes shouldContain "author"
        scopes shouldContain "read"
        scopes shouldNotContain "admin"
    }

    @Test
    fun `admin user gets the admin scope at issue (D14)`() {
        val claims = service.validate(service.issue(user(isAdmin = true)))
        @Suppress("UNCHECKED_CAST")
        (claims["scopes"] as List<String>) shouldContain "admin"
    }

    @Test
    fun `alg none tokens are rejected (algorithm confusion guard)`() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"${UUID.randomUUID()}","iss":"datapipelines"}""".toByteArray())
        val unsecured = "$header.$payload."

        shouldThrow<SessionInvalidException> { service.validate(unsecured) }
    }

    @Test
    fun `a tampered payload fails signature validation`() {
        val token = service.issue(user())
        val parts = token.split(".")
        // Flip a character in the payload segment — signature no longer matches.
        val mangledPayload = parts[1].dropLast(1) + if (parts[1].last() == 'A') 'B' else 'A'
        val tampered = "${parts[0]}.$mangledPayload.${parts[2]}"

        shouldThrow<SessionInvalidException> { service.validate(tampered) }
    }

    @Test
    fun `an expired token maps to session_expired, not session_invalid`() {
        val key = Keys.hmacShaKeyFor(secretBytes)
        val past = Instant.now().minusSeconds(3600)
        val expired =
            Jwts
                .builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "a@b.com")
                .claim("name", "A")
                .claim("scopes", listOf("read"))
                .issuer("datapipelines")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact()

        shouldThrow<SessionExpiredException> { service.validate(expired) }
    }

    @Test
    fun `a secret shorter than 32 bytes fails fast at construction`() {
        val shortSecret = Base64.getEncoder().encodeToString(ByteArray(16))
        shouldThrow<IllegalArgumentException> { JwtService(JwtProperties(shortSecret), AuthProperties()) }
    }

    @Test
    fun `a secret that is not valid base64 fails fast - never a silent raw-UTF8 fallback`() {
        // 40 ASCII characters: long enough to pass a naive byte-count check, and exactly
        // the passphrase an operator would paste in. AUTH-SEC-12 requires this to fail.
        val passphrase = "this-is-a-long-passphrase-not-base64!!!!"

        val failure = shouldThrow<IllegalArgumentException> { JwtService(JwtProperties(passphrase), AuthProperties()) }

        failure.message.orEmpty() shouldContain "base64"
    }

    @Test
    fun `a derived subkey is deterministic, label-bound and not the signing secret`() {
        val forCookie = service.deriveSubkey("dp_oauth2_authz")
        val forCookieAgain = service.deriveSubkey("dp_oauth2_authz")
        val forSomethingElse = service.deriveSubkey("other-purpose")

        forCookie.toList() shouldBe forCookieAgain.toList()
        (forCookie.contentEquals(forSomethingElse)) shouldBe false
        (forCookie.contentEquals(secretBytes)) shouldBe false
        forCookie.size shouldBe 32
    }
}
