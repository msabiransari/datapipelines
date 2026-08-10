package co.datapipelines.datasources.crypto

import co.datapipelines.datasources.test32ByteKeyBase64
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * [CredentialEncryptor] — the AES-256-GCM credential store (datasources.md §7.1, §13.2).
 *
 * Covers the properties the spec pins: a decrypt/encrypt round-trip, a fresh nonce per encryption
 * (the one catastrophic GCM misuse if broken), authentication-tag tamper detection, the required
 * fail-fast key with no fallback, and the datasource-name binding that makes a ciphertext
 * non-transferable between rows.
 */
class CredentialEncryptorTest {
    private val encryptor = CredentialEncryptor.fromBase64Key(test32ByteKeyBase64())

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val secret = "s3cr3t-p@ss word with unicode ✓ and spaces"

        encryptor.decrypt(encryptor.encrypt(secret, DS), DS) shouldBe secret
    }

    @Test
    fun `each encryption uses a fresh nonce, so identical plaintext yields different ciphertext`() {
        val first = encryptor.encrypt("same-password", DS)
        val second = encryptor.encrypt("same-password", DS)

        // The stored value is nonce ‖ ciphertext ‖ tag; the first 12 bytes are the nonce.
        first.copyOfRange(0, CredentialEncryptor.NONCE_BYTES) shouldNotBe
            second.copyOfRange(0, CredentialEncryptor.NONCE_BYTES)
        first shouldNotBe second
        // Both still decrypt to the same plaintext.
        encryptor.decrypt(first, DS) shouldBe "same-password"
        encryptor.decrypt(second, DS) shouldBe "same-password"
    }

    @Test
    fun `the stored layout is nonce followed by ciphertext and a 128-bit tag`() {
        val stored = encryptor.encrypt("x", DS)

        // 12-byte nonce + (1 byte ciphertext + 16-byte tag) for a 1-char plaintext.
        stored.size shouldBe CredentialEncryptor.NONCE_BYTES + 1 + CredentialEncryptor.TAG_BYTES
    }

    @Test
    fun `a tampered ciphertext fails the authentication tag`() {
        val stored = encryptor.encrypt("original", DS)
        // Flip one bit in the final tag byte.
        stored[stored.lastIndex] = (stored[stored.lastIndex].toInt() xor 0x01).toByte()

        shouldThrow<CredentialDecryptionException> { encryptor.decrypt(stored, DS) }
    }

    @Test
    fun `a ciphertext moved to a different datasource no longer decrypts`() {
        // §5.6 / DS-SEC-9: the datasource name is GCM associated data, and the name is immutable
        // (§11.1), so a `password_encrypted` value copied from one row into another — the exact
        // move a DB-write attacker or a bad migration makes — fails the tag instead of silently
        // pointing pg-prod's credential at a datasource the attacker controls.
        val sealedForProd = encryptor.encrypt("prod-secret", "pg_prod")

        encryptor.decrypt(sealedForProd, "pg_prod") shouldBe "prod-secret"
        shouldThrow<CredentialDecryptionException> { encryptor.decrypt(sealedForProd, "pg_staging") }
    }

    @Test
    fun `the same plaintext under two datasource names produces unrelated ciphertext`() {
        val prod = encryptor.encrypt("shared-password", "pg_prod")
        val staging = encryptor.encrypt("shared-password", "pg_staging")

        prod shouldNotBe staging
        encryptor.decrypt(staging, "pg_staging") shouldBe "shared-password"
    }

    @Test
    fun `a value shorter than nonce plus tag is rejected, not read out of bounds`() {
        shouldThrow<CredentialDecryptionException> { encryptor.decrypt(ByteArray(4), DS) }
    }

    @Test
    fun `a value encrypted under one key does not decrypt under another`() {
        val other = CredentialEncryptor.fromRawKey(ByteArray(CredentialEncryptor.KEY_BYTES) { 0x7F })

        shouldThrow<CredentialDecryptionException> { other.decrypt(encryptor.encrypt("secret", DS), DS) }
    }

    @Test
    fun `a missing key fails fast so the application cannot start`() {
        shouldThrow<IllegalStateException> { CredentialEncryptor.fromBase64Key(null) }
        shouldThrow<IllegalStateException> { CredentialEncryptor.fromBase64Key("   ") }
    }

    @Test
    fun `a malformed base64 key fails fast`() {
        shouldThrow<IllegalStateException> { CredentialEncryptor.fromBase64Key("not valid base64 @@@@") }
    }

    @Test
    fun `a key of the wrong length fails fast`() {
        val sixteenBytes = Base64.getEncoder().encodeToString(ByteArray(16))
        val fortyBytes = Base64.getEncoder().encodeToString(ByteArray(40))

        shouldThrow<IllegalStateException> { CredentialEncryptor.fromBase64Key(sixteenBytes) }
        shouldThrow<IllegalStateException> { CredentialEncryptor.fromBase64Key(fortyBytes) }
    }

    private companion object {
        const val DS = "test_ds"
    }
}
