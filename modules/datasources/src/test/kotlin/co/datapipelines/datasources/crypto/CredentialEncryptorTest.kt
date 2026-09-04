package co.datapipelines.datasources.crypto

import co.datapipelines.datasources.test32ByteKeyBase64
import co.datapipelines.datasources.testEncryptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * [CredentialEncryptor] — the AES-256-GCM credential store (datasources.md §7.1, §13.2).
 *
 * Covers the properties the spec pins: a decrypt/encrypt round-trip, a fresh nonce per encryption
 * (the one catastrophic GCM misuse if broken), authentication-tag tamper detection, the
 * datasource-name binding that makes a ciphertext non-transferable between rows — and, since the
 * key-provider seam, the VERSION byte: what it is, that an unknown version is refused rather than
 * guessed, that a v1 row still decrypts after `current` moves to v2, and that the pre-versioning
 * layout is refused rather than heuristically read.
 *
 * The config-shaped failures (missing key, bad base64, wrong length) moved with the code that
 * owns them — see [EnvKeyProviderTest].
 */
class CredentialEncryptorTest {
    private val encryptor = testEncryptor()

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val secret = "s3cr3t-p@ss word with unicode ✓ and spaces"

        encryptor.decrypt(encryptor.encrypt(secret, DS), DS) shouldBe secret
    }

    @Test
    fun `each encryption uses a fresh nonce, so identical plaintext yields different ciphertext`() {
        val first = encryptor.encrypt("same-password", DS)
        val second = encryptor.encrypt("same-password", DS)

        // The stored value is version ‖ nonce ‖ ciphertext ‖ tag; bytes 1..12 are the nonce.
        first.copyOfRange(
            CredentialEncryptor.VERSION_BYTES,
            CredentialEncryptor.VERSION_BYTES + CredentialEncryptor.NONCE_BYTES,
        ) shouldNotBe
            second.copyOfRange(CredentialEncryptor.VERSION_BYTES, CredentialEncryptor.VERSION_BYTES + CredentialEncryptor.NONCE_BYTES)
        first shouldNotBe second
        // Both still decrypt to the same plaintext.
        encryptor.decrypt(first, DS) shouldBe "same-password"
        encryptor.decrypt(second, DS) shouldBe "same-password"
    }

    @Test
    fun `the stored layout is a version byte, a nonce, the ciphertext and a 128-bit tag`() {
        val stored = encryptor.encrypt("x", DS)

        // 1-byte version + 12-byte nonce + (1 byte ciphertext + 16-byte tag) for a 1-char plaintext.
        stored.size shouldBe
            CredentialEncryptor.VERSION_BYTES + CredentialEncryptor.NONCE_BYTES + 1 + CredentialEncryptor.TAG_BYTES
    }

    @Test
    fun `a round-trip under the default single-key deployment carries version 1 in its first byte`() {
        // The migration wrote 0x01 onto every pre-round row on exactly this claim: a deployment
        // that never rotated encrypts under `datapipelines.db.encryption-key`, which IS version 1.
        encryptor.encrypt("secret", DS)[0] shouldBe 0x01.toByte()
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
    fun `a value shorter than a version plus nonce plus tag is rejected, not read out of bounds`() {
        shouldThrow<CredentialDecryptionException> { encryptor.decrypt(ByteArray(4), DS) }
    }

    @Test
    fun `a value encrypted under one key does not decrypt under another`() {
        val other = CredentialEncryptor(EnvKeyProvider.fromConfig(otherKeyBase64()))

        shouldThrow<CredentialDecryptionException> { other.decrypt(encryptor.encrypt("secret", DS), DS) }
    }

    @Test
    fun `a version byte the provider does not know is refused, never guessed`() {
        val stored = encryptor.encrypt("secret", DS)
        stored[0] = 0x09

        val thrown = shouldThrow<CredentialDecryptionException> { encryptor.decrypt(stored, DS) }
        // The refusal names the version — the "wrong deployment / retired key" operator signal —
        // and nothing else about the blob.
        requireNotNull(thrown.message).contains("version 9") shouldBe true
    }

    @Test
    fun `flipping the version byte of a valid blob to another CONFIGURED version still fails`() {
        // The version byte selects a key; it is not itself authenticated. It does not need to be:
        // the wrong key fails the GCM tag, so a flip is a tamper signal either way.
        val twoKeys =
            CredentialEncryptor(EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to otherKeyBase64()), currentVersion = 1))
        val stored = twoKeys.encrypt("secret", DS)
        stored[0] = 0x02

        shouldThrow<CredentialDecryptionException> { twoKeys.decrypt(stored, DS) }
    }

    @Test
    fun `a row written under key v1 still decrypts after current moves to v2`() {
        // The whole point of the version byte: rotation is LAZY. The v1 row is untouched and
        // keeps working; only new writes carry v2.
        val beforeRotation = CredentialEncryptor(EnvKeyProvider.fromConfig(test32ByteKeyBase64()))
        val legacyRow = beforeRotation.encrypt("old-secret", DS)

        val afterRotation =
            CredentialEncryptor(
                EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to otherKeyBase64()), currentVersion = 2),
            )

        afterRotation.decrypt(legacyRow, DS) shouldBe "old-secret"
        val freshRow = afterRotation.encrypt("new-secret", DS)
        freshRow[0] shouldBe 0x02.toByte()
        afterRotation.decrypt(freshRow, DS) shouldBe "new-secret"
    }

    @Test
    fun `the pre-versioning layout is REFUSED, not guessed`() {
        // What a row looked like before this round: nonce ‖ ciphertext ‖ tag, no version byte.
        // Migration V11 prefixed 0x01 onto every one of them, so a blob still in the old layout
        // reaching this code is a defect — reading it heuristically would only ever hide one.
        val versioned = encryptor.encrypt("secret", DS)
        val legacyLayout = versioned.copyOfRange(CredentialEncryptor.VERSION_BYTES, versioned.size)

        shouldThrow<CredentialDecryptionException> { encryptor.decrypt(legacyLayout, DS) }
    }

    private fun otherKeyBase64(): String = Base64.getEncoder().encodeToString(ByteArray(DataKey.KEY_BYTES) { 0x7F })

    private companion object {
        const val DS = "test_ds"
    }
}
