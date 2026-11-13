package co.datapipelines.auth

import com.sun.jna.Native
import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.jna.Argon2Library
import de.mkammerer.argon2.jna.Size_t
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore

/**
 * Password-grade hashing of API-key secrets (auth.md §7.2). Behind an interface so
 * the cost of a verification is observable in tests (the A3 cache proof counts
 * verifications) without running Argon2id thousands of times.
 */
interface SecretHasher {
    /** Hashes [raw], returning the self-describing encoded hash stored in `api_keys.key_hash`. */
    fun hash(raw: String): String

    /**
     * Constant-time-ish verification of [raw] against an [encodedHash] produced by [hash].
     *
     * `false` means exactly one thing: the hash decoded and [raw] does not match it — a wrong
     * password or key secret (#847). Any other outcome of the native verification (an allocation
     * failure, a stored hash that does not decode, any other non-mismatch code) is not an answer
     * about the credential, so it THROWS [SecretVerificationUnavailableException] (503
     * `auth.secret_verification.unavailable`) instead of reading as a wrong password. Callers let
     * it propagate before any failure counter, audit row or cache admit: fail closed, and count
     * no attempt.
     */
    fun verify(
        encodedHash: String,
        raw: String,
    ): Boolean
}

/**
 * The native verification seam of [Argon2SecretHasher] (#847): the encoded hash's NUL-terminated
 * ASCII bytes and the password's UTF-8 bytes in, argon2's own return code out. Production binds it
 * to `Argon2Library.INSTANCE.argon2id_verify`; auth tests inject a stand-in to produce a native
 * fault without real memory pressure.
 */
internal fun interface NativeVerify {
    fun verify(
        encoded: ByteArray,
        password: ByteArray,
    ): Int
}

/**
 * Argon2id implementation (auth.md §7.2, §12.2 — `de.mkammerer:argon2-jvm`).
 *
 * The `char[]` handed to the native binding is wiped in a `finally` block
 * (AUTH-SEC-13): argon2-jvm copies it into native memory, and leaving the plaintext
 * key in a live array keeps it recoverable from a heap dump for as long as the array
 * is reachable. [verify] also wipes the two byte arrays it builds for the native call.
 *
 * ## Why [verify] calls the binding itself (#847)
 * The library's `Argon2.verify` returns `callLibraryVerify(...) == 0`, so EVERY non-zero native
 * code — the genuine mismatch, but also a memory-allocation failure and a stored hash that does
 * not decode — comes back as `false`, a "wrong password". No public overload exposes the code.
 * [verify] therefore builds the bytes exactly as the library does (UTF-8 of the `char[]`,
 * `Native.toByteArray(encodedHash, "ASCII")`), calls the library's own public binding, and maps
 * `0` → true, [ARGON2_VERIFY_MISMATCH] → false, anything else → [SecretVerificationUnavailableException].
 * JVM `Error`s (an `UnsatisfiedLinkError`, an `OutOfMemoryError`) are not wrapped. [hash] is
 * unchanged: the library already throws `IllegalStateException` on any native hashing failure.
 *
 * Not a bean on its own — it is wrapped by [BoundedSecretHasher] in
 * [SecretHasherConfig], so nothing can reach the unbounded native path by injecting
 * `SecretHasher`.
 */
class Argon2SecretHasher internal constructor(
    private val nativeVerify: NativeVerify,
) : SecretHasher {
    /** The production binding: the library's own public `argon2id_verify`, the call its `verify` makes. */
    constructor() : this(
        NativeVerify { encoded, password ->
            Argon2Library.INSTANCE.argon2id_verify(encoded, password, Size_t(password.size.toLong()))
        },
    )

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
        var password = EMPTY_BYTES
        var encoded = EMPTY_BYTES
        try {
            password = utf8Bytes(chars)
            encoded = Native.toByteArray(encodedHash, ASCII)
            return when (val code = nativeVerify.verify(encoded, password)) {
                ARGON2_OK -> true
                ARGON2_VERIFY_MISMATCH -> false
                else -> throw SecretVerificationUnavailableException(code, nativeFailure(code))
            }
        } finally {
            password.fill(0)
            encoded.fill(0)
            chars.fill('\u0000')
        }
    }

    /** The library's text for [code], carried only on the exception's cause (logs, never the envelope). */
    private fun nativeFailure(code: Int): IllegalStateException =
        IllegalStateException("argon2id_verify failed: ${Argon2Library.INSTANCE.argon2_error_message(code)} ($code)")

    /**
     * The library's `toByteArray(char[], Charset)`: encode, copy the encoded range out, wipe the
     * encoder's buffer — so the bytes the native side sees are the bytes the library would have sent.
     */
    private fun utf8Bytes(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return try {
            buffer.array().copyOfRange(buffer.position(), buffer.limit())
        } finally {
            buffer.array().fill(0)
        }
    }

    internal companion object {
        // OWASP-aligned Argon2id parameters; API keys are high-entropy so this is ample.
        const val ITERATIONS = 2
        const val MEMORY_KIB = 19_456
        const val PARALLELISM = 1

        /** argon2.h `ARGON2_OK`: the password matches the hash. */
        const val ARGON2_OK = 0

        /**
         * argon2.h `ARGON2_VERIFY_MISMATCH` — the ONLY code that means "wrong password"; the native
         * `argon2_error_message(-35)` reads "The password does not match the supplied hash" (A.0 probe).
         */
        const val ARGON2_VERIFY_MISMATCH = -35

        /**
         * argon2.h `ARGON2_DECODING_FAIL` — the stored encoded hash does not decode;
         * `argon2_error_message(-32)` reads "Decoding failed". Metric tag `encoding`.
         */
        const val DECODING_FAILURE = -32

        /**
         * argon2.h `ARGON2_MEMORY_ALLOCATION_ERROR` — the native side could not allocate its memory
         * block; `argon2_error_message(-22)` reads "Memory allocation error". Metric tag `memory`.
         */
        const val MEMORY_ALLOCATION_ERROR = -22

        private const val ASCII = "ASCII"
        private val EMPTY_BYTES = ByteArray(0)
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
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) : SecretHasher {
    init {
        require(permits > 0) { "SecretHasher permit count must be positive, was $permits" }
    }

    /**
     * `datapipelines.auth.secret_verification.failed` by `cause` (#847, observability §4.1): every
     * verification that ended in [SecretVerificationUnavailableException]. All three series are
     * registered eagerly so a healthy deployment reads `0`, not an absent series; an unknown native
     * code counts under `other` — the raw code never becomes a tag (a bounded set, §4.3).
     */
    private val verificationFailures: Map<String, Counter> =
        listOf(CAUSE_MEMORY, CAUSE_ENCODING, CAUSE_OTHER).associateWith { cause ->
            Counter
                .builder(SECRET_VERIFICATION_FAILED_METRIC)
                .description("Secret verifications that failed natively (not a wrong secret), by cause")
                .tag("cause", cause)
                .register(meterRegistry)
        }

    /** Fair, so a sustained flood cannot starve a legitimate caller indefinitely. */
    private val permitted = Semaphore(permits, true)

    /** Permits currently available — the bound this class promises, observable in tests. */
    fun availablePermits(): Int = permitted.availablePermits()

    override fun hash(raw: String): String = withPermit { delegate.hash(raw) }

    override fun verify(
        encodedHash: String,
        raw: String,
    ): Boolean =
        withPermit {
            try {
                delegate.verify(encodedHash, raw)
            } catch (e: SecretVerificationUnavailableException) {
                verificationFailures.getValue(causeOf(e.nativeCode)).increment()
                throw e
            }
        }

    private fun causeOf(nativeCode: Int): String =
        when (nativeCode) {
            Argon2SecretHasher.MEMORY_ALLOCATION_ERROR -> CAUSE_MEMORY
            Argon2SecretHasher.DECODING_FAILURE -> CAUSE_ENCODING
            else -> CAUSE_OTHER
        }

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

    companion object {
        /** observability §4.1 — a `const val` so the meter-catalogue sweep resolves it. */
        const val SECRET_VERIFICATION_FAILED_METRIC = "datapipelines.auth.secret_verification.failed"
        const val CAUSE_MEMORY = "memory"
        const val CAUSE_ENCODING = "encoding"
        const val CAUSE_OTHER = "other"
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
    /**
     * [meterRegistry] is an [ObjectProvider], as in `AuthConfiguration.authFilters`: test contexts
     * without actuator hold no `MeterRegistry`, and the fallback is a real in-memory one.
     */
    @Bean
    fun secretHasher(meterRegistry: ObjectProvider<MeterRegistry>): SecretHasher =
        BoundedSecretHasher(Argon2SecretHasher(), defaultPermits(), meterRegistry.getIfAvailable { SimpleMeterRegistry() })

    private fun defaultPermits(): Int = Runtime.getRuntime().availableProcessors()
}
