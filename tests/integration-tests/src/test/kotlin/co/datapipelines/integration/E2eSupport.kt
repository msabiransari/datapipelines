package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.mkammerer.argon2.Argon2Factory
import io.restassured.specification.RequestSpecification
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The E2E suites' shared auth-seeding vocabulary (020 F8, extracted at the fourth near-verbatim
 * copy): the `dpk_<id>.<secret>` API-key shape, its Argon2id hash with auth's exact parameters,
 * and the SSE event parser.
 *
 * ## What is deliberately NOT here (the NOT-list)
 *
 * - **The SQL seeding blocks stay per-suite.** Their variation is fixture identity, not
 *   boilerplate: fixed user vs per-key owner, fixed workspace vs per-key workspace, and
 *   `ON CONFLICT DO NOTHING` present in some suites only. Forcing them through one
 *   parameterized INSERT would trade seven readable blocks for one four-knob helper.
 * - **The containers are NOT here either — they live in [SharedE2e].** Since round 060 the
 *   Postgres and Redis every suite boots against are one per test JVM (data isolation via
 *   suite-unique seeds and [E2eClean]), and since round 141 so is the lake suites' MinIO
 *   (one bucket per suite); only suites whose SUBJECT is a separate instance — a private
 *   Redis whose pub/sub is killed or counted, Keycloak and GreenMail, the two-deployment
 *   promotion pair, the fresh-deployment walkthrough, the compose stack's exact engines —
 *   still declare a per-class `@Container`.
 */
object E2eAuth {
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val random = SecureRandom()

    // Argon2id with auth's exact parameters (SecretHasher.kt: 2 / 19 456 / 1) — one shared
    // copy instead of one per suite. The char[] wipe mirrors auth's AUTH-SEC-13 handling.
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    /** A generated `dpk_<id>.<secret>` key and its stored Argon2id hash (auth.md §7.1/§7.2). */
    class SeededKey(
        val name: String,
        val id: String,
        val plaintext: String,
        val hash: String,
        /** The owning user for per-user key suites; null where the suite inserts a single fixed user. */
        val ownerId: String? = null,
    )

    /**
     * Generates a key pair in the exact wire shape auth issues: `dpk_` + 12 BASE32 chars,
     * secret `.` + 48 BASE32 chars. Suites seeding per-user keys pass [ownerId]; the key row
     * itself is inserted by each suite's own SQL (see the class NOT-list).
     */
    fun generateKey(
        name: String,
        ownerId: String? = null,
    ): SeededKey {
        val id = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        val plaintext = id + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        return SeededKey(name = name, id = id, plaintext = plaintext, hash = argon2Hash(plaintext), ownerId = ownerId)
    }

    /** Argon2id, auth's parameters (2 / 19 456 / 1), char[] wiped after hashing. */
    fun argon2Hash(raw: String): String {
        val chars = raw.toCharArray()
        return try {
            argon2.hash(2, 19_456, 1, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }
}

/**
 * A signed SESSION for a seeded person — how an E2E suite drives REST and the UI since #215 B2
 * (owner ruling 2026-09-24, "MCP key should be only MCP"): the MCP (`user`) key reaches `/mcp` and
 * nothing else, so a suite that walks the `/api/v1` routes or a page walks it the way a person does — a
 * `dp_session` JWT signed with the suite's own per-run secret, plus the CSRF double-submit every
 * cookie-authenticated state change needs (auth.md §8.4). An agent's path stays `/mcp` with a key.
 *
 * Extracted here, like [E2eAuth], because it is the same vocabulary in every suite: the claim
 * shape [co.datapipelines.auth.JwtService] validates (HS256, issuer `datapipelines`, the Base64
 * secret decoded to the HMAC key) and the three cookie/header names. A suite registers
 * [newSecret]'s value as `datapipelines.jwt.secret` and signs with the same value.
 */
object E2eSession {
    const val COOKIE = "dp_session"
    const val CSRF_COOKIE = "dp_csrf"
    const val CSRF_HEADER = "DP-CSRF-Token"

    /** The double-submit value: any token works as long as the cookie and the header carry the same one. */
    const val CSRF_TOKEN = "e2e-session-csrf"

    private const val SECRET_BYTES = 32
    private const val TOKEN_TTL_SECONDS = 3600L
    private val random = SecureRandom()

    /** A per-run JWT signing secret, Base64 of 32 random bytes — no literal secret in any fixture (HIGH-2). */
    fun newSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

    /** A session JWT for [userId] with [workspace] as its active workspace, signed with [secret]. */
    fun jwt(
        secret: String,
        userId: String,
        email: String,
        workspace: String? = "default",
    ): String {
        val now = Instant.now()
        val header = b64("""{"alg":"HS256","typ":"JWT"}""")
        val active = workspace?.let { ""","active_workspace":"$it"""" }.orEmpty()
        val payload =
            b64(
                """{"sub":"$userId","email":"$email","name":"E2E User","iss":"datapipelines",""" +
                    """"iat":${now.epochSecond},"exp":${now.plusSeconds(TOKEN_TTL_SECONDS).epochSecond}$active}""",
            )
        val signature =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"))
                b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
            }
        return "$header.$payload.$signature"
    }

    /** The session cookie and the CSRF double-submit on a RestAssured request. */
    fun RequestSpecification.asSession(jwt: String): RequestSpecification =
        cookie(COOKIE, jwt).cookie(CSRF_COOKIE, CSRF_TOKEN).header(CSRF_HEADER, CSRF_TOKEN)

    /** The `Cookie` header value for a hand-built (java.net.http) request; pair it with [CSRF_HEADER] = [CSRF_TOKEN]. */
    fun cookieHeader(jwt: String): String = "$COOKIE=$jwt; $CSRF_COOKIE=$CSRF_TOKEN"

    private fun b64(value: String): String = b64(value.toByteArray(Charsets.UTF_8))

    private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}

object E2eSse {
    /**
     * Reads an SSE response body to its end, returning (event name, payload) pairs.
     *
     * Deliberately a line reader, not a general SSE parser: `ExecutionStream` writes one
     * single-line JSON `data:` per `event:` (rest-api §6.3), heartbeats arrive as
     * `: heartbeat` comments this skips, and `id:` lines are gap-detection metadata the
     * ordering assertions do not need.
     *
     * The stream is consumed to EOF, not to `data_ready`: the emitter sends an event before
     * its bookkeeping lands, and the launcher closes the stream only after the execution row
     * is fully recorded (`result_row_count` included) — so end-of-stream, not any single
     * event, is the point where every assertion the suites make about a finished execution
     * holds.
     */
    fun parseEvents(
        body: String,
        mapper: ObjectMapper,
    ): List<Pair<String, JsonNode>> {
        val events = mutableListOf<Pair<String, JsonNode>>()
        var currentEvent: String? = null
        for (line in body.lines()) {
            if (line.startsWith("event:")) {
                currentEvent = line.removePrefix("event:").trim()
            } else if (line.startsWith("data:")) {
                events += (currentEvent ?: "unknown") to mapper.readTree(line.removePrefix("data:").trim())
            }
        }
        return events
    }
}
