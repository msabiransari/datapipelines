package co.datapipelines.auth

import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.jna.Argon2Library
import de.mkammerer.argon2.jna.Size_t
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * #847 — a native Argon2 failure is not a wrong password.
 *
 * The library's `Argon2.verify` answers `callLibraryVerify(...) == 0`, so every non-zero native code
 * read as "wrong password". [Argon2SecretHasher.verify] now calls the library's own binding and maps
 * the code: OK → true, `ARGON2_VERIFY_MISMATCH` → false, anything else →
 * [SecretVerificationUnavailableException].
 *
 * Two kinds of evidence: PARITY through the real native library (hashes made three ways — this
 * hasher, the library's `String` overload and its `char[]` overload — verify exactly as before, for
 * ASCII, non-ASCII, empty and 1,024-char passwords; a real garbage hash throws), and FAULTS through
 * the injected [NativeVerify] seam (an allocation failure is never produced by real memory pressure).
 */
class Argon2SecretHasherNativeFailureTest {
    private val library = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    /** The production binding, counted: the non-vacuity floor that the real native side was reached. */
    private val nativeCalls = AtomicInteger()
    private val realNative =
        NativeVerify { encoded, password ->
            nativeCalls.incrementAndGet()
            Argon2Library.INSTANCE.argon2id_verify(encoded, password, Size_t(password.size.toLong()))
        }
    private val hasher = Argon2SecretHasher(realNative)

    @Test
    fun `hashes from this hasher and both library overloads verify exactly as the library does`() {
        PASSWORDS shouldHaveAtLeastSize MIN_PASSWORDS
        PASSWORDS.forEach { password ->
            val made =
                mapOf(
                    "Argon2SecretHasher.hash" to Argon2SecretHasher().hash(password),
                    "library String overload" to libraryStringHash(password),
                    "library char[] overload" to
                        library.hash(Argon2SecretHasher.ITERATIONS, Argon2SecretHasher.MEMORY_KIB, Argon2SecretHasher.PARALLELISM, password.toCharArray()),
                )
            made.forEach { (how, encoded) ->
                withClue("$how, password of ${password.length} chars") {
                    hasher.verify(encoded, password) shouldBe true
                    hasher.verify(encoded, password + "x") shouldBe false
                    // The library's own answer, for the parity claim itself.
                    library.verify(encoded, password.toCharArray()) shouldBe true
                }
            }
        }
        nativeCalls.get() shouldBeGreaterThanOrEqual PASSWORDS.size * 3 * 2
    }

    @Test
    fun `the public constructor is the production binding`() {
        val encoded = Argon2SecretHasher().hash("pässwörd✓")

        Argon2SecretHasher().verify(encoded, "pässwörd✓") shouldBe true
        Argon2SecretHasher().verify(encoded, "passwort") shouldBe false
    }

    @Test
    fun `a native allocation failure is not a wrong password`() {
        val faulty = Argon2SecretHasher { _, _ -> Argon2SecretHasher.MEMORY_ALLOCATION_ERROR }

        val thrown = shouldThrow<SecretVerificationUnavailableException> { faulty.verify(Argon2SecretHasher().hash("pw"), "pw") }

        thrown.nativeCode shouldBe Argon2SecretHasher.MEMORY_ALLOCATION_ERROR
        thrown.status shouldBe 503
        thrown.code shouldBe AuthErrorCodes.SECRET_VERIFICATION_UNAVAILABLE
        // The fixed message reaches the envelope; the library text rides only on the cause.
        thrown.message shouldBe "Secret verification is temporarily unavailable"
        thrown.message.orEmpty() shouldNotContain "emory"
        thrown.cause?.message.orEmpty().contains("Memory allocation error") shouldBe true
    }

    @Test
    fun `a stored hash that does not decode throws through the real library`() {
        listOf("\$argon2id\$fixture", "not-a-hash", "").forEach { garbage ->
            withClue(garbage) {
                val thrown = shouldThrow<SecretVerificationUnavailableException> { hasher.verify(garbage, "pw") }
                thrown.nativeCode shouldBe Argon2SecretHasher.DECODING_FAILURE
            }
        }
        nativeCalls.get() shouldBe 3
    }

    @Test
    fun `only the mismatch code is false, only OK is true, every other code throws`() {
        Argon2SecretHasher { _, _ -> Argon2SecretHasher.ARGON2_OK }.verify("h", "p") shouldBe true
        Argon2SecretHasher { _, _ -> Argon2SecretHasher.ARGON2_VERIFY_MISMATCH }.verify("h", "p") shouldBe false
        listOf(Argon2SecretHasher.DECODING_FAILURE, Argon2SecretHasher.MEMORY_ALLOCATION_ERROR, -1, 1).forEach { code ->
            withClue(code) {
                shouldThrow<SecretVerificationUnavailableException> { Argon2SecretHasher { _, _ -> code }.verify("h", "p") }
                    .nativeCode shouldBe code
            }
        }
    }

    @Test
    fun `the bytes handed to the native side are the library's bytes and are wiped after the call`() {
        val seen = mutableListOf<ByteArray>()
        val snapshot = mutableListOf<ByteArray>()
        val recording =
            Argon2SecretHasher { encoded, password ->
                seen += encoded
                seen += password
                snapshot += encoded.copyOf()
                snapshot += password.copyOf()
                Argon2SecretHasher.ARGON2_VERIFY_MISMATCH
            }

        recording.verify("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA", "pässwörd✓") shouldBe false

        // What the library sends: NUL-terminated ASCII of the hash, UTF-8 of the password.
        snapshot[0].toList() shouldBe ("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA".toByteArray(Charsets.US_ASCII) + 0).toList()
        snapshot[1].toList() shouldBe "pässwörd✓".toByteArray(Charsets.UTF_8).toList()
        seen.forEach { bytes -> bytes.all { it == 0.toByte() } shouldBe true }
    }

    @Test
    fun `the bytes are wiped when the native side fails too`() {
        val seen = mutableListOf<ByteArray>()
        val faulty =
            Argon2SecretHasher { encoded, password ->
                seen += encoded
                seen += password
                Argon2SecretHasher.MEMORY_ALLOCATION_ERROR
            }

        shouldThrow<SecretVerificationUnavailableException> { faulty.verify("hash", "secret") }

        seen.size shouldBe 2
        seen.forEach { bytes -> bytes.all { it == 0.toByte() } shouldBe true }
    }

    @Suppress("DEPRECATION") // the String overload is deprecated upstream; parity with hashes it made is the point
    private fun libraryStringHash(password: String): String =
        library.hash(Argon2SecretHasher.ITERATIONS, Argon2SecretHasher.MEMORY_KIB, Argon2SecretHasher.PARALLELISM, password)

    private companion object {
        const val MIN_PASSWORDS = 4
        const val LONG_PASSWORD_CHARS = 1_024
        val PASSWORDS = listOf("correct horse battery", "pässwörd✓", "", "a".repeat(LONG_PASSWORD_CHARS))
    }
}
