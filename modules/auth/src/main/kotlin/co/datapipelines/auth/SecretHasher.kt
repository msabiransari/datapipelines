package co.datapipelines.auth

import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Semaphore

/**
 * Password-grade hashing of API-key secrets (auth.md §7.2). Behind an interface so
 * the cost of a verification is observable in tests (the A3 cache proof counts
 * verifications) without running Argon2id thousands of times.
 */
interface SecretHasher {
    /** Hashes [raw], returning the self-describing encoded hash stored in `api_keys.key_hash`. */
    fun hash(raw: String): String

    /** Constant-time-ish verification of [raw] against an [encodedHash] produced by [hash]. */
    fun verify(
        encodedHash: String,
        raw: String,
    ): Boolean
}

/**
 * Argon2id implementation (auth.md §7.2, §12.2 — `de.mkammerer:argon2-jvm`).
 *
 * The `char[]` handed to the native binding is wiped in a `finally` block
 * (AUTH-SEC-13): argon2-jvm copies it into native memory, and leaving the plaintext
 * key in a live array keeps it recoverable from a heap dump for as long as the array
 * is reachable.
 *
 * Not a bean on its own — it is wrapped by [BoundedSecretHasher] in
 * [SecretHasherConfig], so nothing can reach the unbounded native path by injecting
 * `SecretHasher`.
 */
class Argon2SecretHasher : SecretHasher {
    private val argon2: Argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    override fun hash(raw: String): String {
        val chars = raw.toCharArray()
        return try {
            argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    override fun verify(
        encodedHash: String,
        raw: String,
    ): Boolean {
        val chars = raw.toCharArray()
        return try {
            argon2.verify(encodedHash, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    private companion object {
        // OWASP-aligned Argon2id parameters; API keys are high-entropy so this is ample.
        const val ITERATIONS = 2
        const val MEMORY_KIB = 19_456
        const val PARALLELISM = 1
    }
}

/**
 * Caps how many Argon2 hashes may run **at the same time** (security NEW-1).
 *
 * ## Why a bound is required
 * Each Argon2id call allocates [Argon2SecretHasher] `MEMORY_KIB` — 19 MiB of *native*,
 * off-heap memory — for its whole duration. The verification-outcome cache
 * ([AuthCache.verifiedSecret]) stores only *successes*, so it does not bound this:
 * anyone holding a **valid key id** (a value its owner sees in the UI and `audit_log`
 * records) can present a wrong secret on the unmetered `/api` surface and force a full
 * Argon2 run per request. ~200 concurrent requests ≈ 3.8 GB of RSS the JVM heap
 * settings do not govern — the container is OOM-killed, so this is an availability
 * bug, not a slow path.
 *
 * With a semaphore the same flood queues instead of allocating: waiters block on a
 * permit holding no native memory, throughput degrades gracefully, and peak native
 * usage is `permits × 19 MiB` no matter how many requests arrive.
 *
 * This also subsumes the deferred `AuthCache` single-flight (AUTH-SEC-14): the
 * thundering herd at TTL rollover contends for the same native path, and bounding the
 * path bounds the herd's cost. Requests still each run a verification — they simply
 * cannot all run one *simultaneously*.
 *
 * Both [hash] and [verify] are bounded: issuance is authenticated and rare, but it
 * allocates the identical 19 MiB and there is no reason to leave it outside the cap.
 */
class BoundedSecretHasher(
    private val delegate: SecretHasher,
    permits: Int,
) : SecretHasher {
    init {
        require(permits > 0) { "SecretHasher permit count must be positive, was $permits" }
    }

    /** Fair, so a sustained flood cannot starve a legitimate caller indefinitely. */
    private val permitted = Semaphore(permits, true)

    /** Permits currently available — the bound this class promises, observable in tests. */
    fun availablePermits(): Int = permitted.availablePermits()

    override fun hash(raw: String): String = withPermit { delegate.hash(raw) }

    override fun verify(
        encodedHash: String,
        raw: String,
    ): Boolean = withPermit { delegate.verify(encodedHash, raw) }

    /**
     * Runs [block] holding one permit. `acquire` sits OUTSIDE the `try` on purpose: a
     * throw from `acquire` means no permit was taken, and releasing one that was never
     * acquired would silently widen the bound on every interrupted request.
     */
    private fun <T> withPermit(block: () -> T): T {
        permitted.acquire()
        return try {
            block()
        } finally {
            permitted.release()
        }
    }
}

/**
 * The single `SecretHasher` bean: Argon2id, wrapped in its concurrency bound.
 *
 * The permit count is a code constant rather than a config key on purpose —
 * [Configuration §3.4] defines the `datapipelines.auth.*` keys and carries none for
 * this, and inventing an undocumented key would be spec drift (the same reasoning as
 * [AuthCache]'s `MAX_ENTRIES`). One permit per CPU keeps every core usable for hashing
 * while capping native memory at roughly `cores × 19 MiB`.
 */
@Configuration
class SecretHasherConfig {
    @Bean
    fun secretHasher(): SecretHasher = BoundedSecretHasher(Argon2SecretHasher(), defaultPermits())

    private fun defaultPermits(): Int = Runtime.getRuntime().availableProcessors()
}
