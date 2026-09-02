package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.mkammerer.argon2.Argon2Factory
import java.security.SecureRandom

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
 * - **The container blocks stay per-suite.** `@Container` on a suite's companion restarts the
 *   containers per class; hoisting them to a shared singleton would change isolation
 *   semantics every existing E2E relies on.
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
        val scopes: Array<out String>,
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
        scopes: Array<String>,
        ownerId: String? = null,
    ): SeededKey {
        val id = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        val plaintext = id + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        return SeededKey(name = name, scopes = scopes, id = id, plaintext = plaintext, hash = argon2Hash(plaintext), ownerId = ownerId)
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
