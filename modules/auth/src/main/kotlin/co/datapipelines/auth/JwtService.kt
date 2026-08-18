package co.datapipelines.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Issues and validates the internal session JWT (auth.md §6). HS256, 8h default TTL,
 * `iss: datapipelines`. The `scopes` claim is derived at issue time per D14 / §6.1.
 *
 * ## Security posture
 * - **Secret fail-fast:** the signing secret must be **valid base64** decoding to
 *   ≥ 32 bytes; the app refuses to construct this bean otherwise (Configuration §7,
 *   auth.md §6.1 "≥ 32 bytes random, base64"). There is deliberately no raw-UTF-8
 *   fallback (AUTH-SEC-12): a 32-character ASCII passphrase silently accepted as key
 *   material carries far less than 32 bytes of entropy while passing a byte-count check.
 * - **Algorithm pinned to HS256 in code, not in config:** validation is done with a
 *   MAC verification key, so an `RS256`/`ES256` token cannot be re-interpreted as
 *   HMAC (algorithm confusion), and an unsecured `alg: none` token has no signature
 *   to verify and is rejected. The parsed header `alg` is additionally asserted to
 *   equal HS256 — the token header is never *trusted* to select the algorithm.
 *   (There is no `datapipelines.auth.jwt.algorithm` key: Configuration §3.4 does not
 *   define one, and a configurable signing algorithm is a downgrade lever, AU-API-7.)
 */
class JwtService(
    jwtProperties: JwtProperties,
    authProperties: AuthProperties,
) {
    private val secretBytes: ByteArray
    private val key: SecretKey
    private val ttlMillis: Long = authProperties.jwt.ttlHours * 3_600_000L

    init {
        secretBytes = decodeSecret(jwtProperties.secret)
        require(secretBytes.size >= MIN_SECRET_BYTES) {
            "datapipelines.jwt.secret must decode to >= $MIN_SECRET_BYTES bytes (was ${secretBytes.size})"
        }
        key = Keys.hmacShaKeyFor(secretBytes)
    }

    /**
     * A purpose-bound subkey derived from the JWT secret: `HMAC-SHA256(secret, label)`.
     *
     * This is the HKDF-expand step with a one-block output — domain separation without
     * a second configured secret. Callers that need to authenticate something other
     * than a session JWT (e.g. the `dp_oauth2_authz` cookie, AUTH-SEC-2) take a
     * subkey under their own label so a compromise of one MAC key never yields the
     * signing key of another, and neither yields the master secret.
     */
    fun deriveSubkey(label: String): ByteArray =
        Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(secretBytes, HMAC_SHA256))
            doFinal(label.toByteArray(Charsets.UTF_8))
        }

    /** Derives the session scopes from the user record (auth.md §6.1, D14). */
    fun scopesFor(user: User): Set<Scope> = if (user.isAdmin) Scope.ADMIN.expand() else Scope.AUTHOR.expand()

    fun issue(user: User): String {
        val now = System.currentTimeMillis()
        return Jwts
            .builder()
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("name", user.displayName)
            .claim("scopes", scopesFor(user).map { it.wire })
            .issuer(ISSUER)
            .issuedAt(Date(now))
            .expiration(Date(now + ttlMillis))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * Validates signature, issuer and expiry, pinning HS256. Throws
     * [SessionExpiredException] for a past-expiry token and [SessionInvalidException]
     * for anything else (bad signature, malformed, `alg: none`, wrong algorithm).
     */
    fun validate(token: String): Claims {
        if (token.isBlank()) throw SessionInvalidException("Empty session token")
        val jws = parseSigned(token)
        val alg = jws.header.algorithm
        if (alg != PINNED_ALG) throw SessionInvalidException("Unexpected JWT algorithm: $alg")
        return jws.payload
    }

    /**
     * Verifies signature, issuer and expiry with the HMAC key. Because verification uses
     * a MAC key, an `RS256`/`ES256` token cannot be re-read as HMAC (confusion) and an
     * unsecured `alg: none` token has no signature to verify — both surface as a
     * [JwtException] and are reported invalid; expiry is distinguished as expired.
     */
    private fun parseSigned(token: String): io.jsonwebtoken.Jws<Claims> =
        try {
            Jwts
                .parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
        } catch (e: ExpiredJwtException) {
            throw SessionExpiredException(e)
        } catch (e: JwtException) {
            throw SessionInvalidException(cause = e)
        }

    /**
     * Decodes the configured secret as base64. Invalid base64 is a **startup failure**
     * (AUTH-SEC-12) — never a silent fallback to the raw string as key material.
     */
    private fun decodeSecret(secret: String): ByteArray =
        try {
            Base64.getDecoder().decode(secret.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "datapipelines.jwt.secret must be valid base64 of >= $MIN_SECRET_BYTES random bytes " +
                    "(auth.md §6.1). It was not decodable as base64; a raw passphrase is not accepted.",
                e,
            )
        }

    private companion object {
        const val ISSUER = "datapipelines"
        const val PINNED_ALG = "HS256"
        const val MIN_SECRET_BYTES = 32
        const val HMAC_SHA256 = "HmacSHA256"
    }
}
