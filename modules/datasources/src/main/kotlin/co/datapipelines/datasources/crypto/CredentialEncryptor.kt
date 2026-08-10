package co.datapipelines.datasources.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption of datasource passwords (datasources.md §7.1).
 *
 * ## Primitive
 *
 * The JDK's own SunJCE `AES/GCM/NoPadding` — **no BouncyCastle** (removed 2026-08-07,
 * security review MEDIUM-6; module-structure §5.4). A **fresh, random 96-bit nonce per
 * encryption** from [SecureRandom]; the stored value is `nonce ‖ ciphertext ‖ tag` (GCM's
 * `doFinal` already appends the 128-bit tag to the ciphertext, so `nonce ‖ doFinal(...)` is
 * exactly that layout). Nonce reuse under one key is the one catastrophic GCM misuse, so it is
 * generated per call and never derived or cached — [CredentialEncryptorTest] asserts two
 * encryptions of the same plaintext produce different nonces.
 *
 * ## Associated data — the datasource name
 *
 * Every call binds the ciphertext to the datasource it belongs to by passing the **datasource
 * name** as GCM associated data (`aad`). The name is immutable (§11.1) and is the row's primary
 * key, so it is available on both paths and can never drift from the value the ciphertext was
 * sealed under. The effect: a `password_encrypted` value copied from one datasource row to
 * another fails the authentication tag instead of silently decrypting — a stored-credential
 * swap becomes a detectable tamper, not a working impersonation of another environment's DB.
 *
 * ## Key source — required, fail-fast, no fallback
 *
 * The 256-bit master key has exactly one source: `datapipelines.db.encryption-key`
 * (`DATAPIPELINES_DB_ENCRYPTION_KEY`), a base64 encoding of exactly 32 bytes (configuration.md
 * §2). [fromBase64Key] throws when it is missing, not valid base64, or not exactly 32 bytes —
 * and because this encryptor is constructed at startup wiring, that throw **stops the
 * application from starting**. There is deliberately no KMS lookup and no generated-key-file
 * fallback: a silently generated key is how every stored password becomes undecryptable on the
 * next redeploy (§7.1 rationale). KMS as an *explicit* alternative source is a v1.1 item.
 */
class CredentialEncryptor private constructor(
    private val keyBytes: ByteArray,
) {
    /**
     * Encrypts [plaintext], returning `nonce ‖ ciphertext ‖ tag` for storage as `BYTEA`.
     *
     * @param datasourceName the owning datasource's immutable name (§11.1), bound into the
     *   ciphertext as GCM associated data — [decrypt] must be given the same value.
     */
    fun encrypt(
        plaintext: String,
        datasourceName: String,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val cipher = newCipher(Cipher.ENCRYPT_MODE, nonce, datasourceName)
        val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return nonce + ciphertextAndTag
    }

    /**
     * Decrypts a `nonce ‖ ciphertext ‖ tag` value produced by [encrypt].
     *
     * @param datasourceName the name the value was sealed under; a mismatch fails the tag.
     * @throws CredentialDecryptionException when the value is too short to contain a nonce and
     *   tag, or when the GCM authentication tag fails — the tamper signal (wrong key, mutated
     *   bytes, or a ciphertext lifted from a different datasource). The message never contains
     *   the ciphertext or any derived material.
     */
    fun decrypt(
        stored: ByteArray,
        datasourceName: String,
    ): String {
        if (stored.size < NONCE_BYTES + TAG_BYTES) {
            throw CredentialDecryptionException("stored credential is shorter than a nonce plus tag")
        }
        val nonce = stored.copyOfRange(0, NONCE_BYTES)
        val ciphertextAndTag = stored.copyOfRange(NONCE_BYTES, stored.size)
        return try {
            val cipher = newCipher(Cipher.DECRYPT_MODE, nonce, datasourceName)
            String(cipher.doFinal(ciphertextAndTag), Charsets.UTF_8)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw CredentialDecryptionException("authentication tag verification failed (tampered, wrong key, or wrong datasource)", e)
        }
    }

    private fun newCipher(
        mode: Int,
        nonce: ByteArray,
        datasourceName: String,
    ): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(datasourceName.toByteArray(Charsets.UTF_8))
        }

    companion object {
        /** SunJCE transformation; the JDK ships this with no external provider. */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** AES-256 → 32-byte key. */
        const val KEY_BYTES = 32

        /** 96-bit nonce, the GCM-recommended size. */
        const val NONCE_BYTES = 12

        /** 128-bit authentication tag. */
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        private val SECURE_RANDOM = SecureRandom()

        /**
         * Builds an encryptor from the base64 config value, failing fast on any defect so the
         * application does not start with an unusable credential store.
         *
         * @param configuredKey the raw `DATAPIPELINES_DB_ENCRYPTION_KEY` value, or null/blank
         *   when unset.
         * @throws IllegalStateException when the key is missing, not valid base64, or not
         *   exactly 32 bytes — deliberately a plain startup-configuration failure, not a
         *   catalog error (there is no request in flight).
         */
        fun fromBase64Key(configuredKey: String?): CredentialEncryptor {
            val raw =
                configuredKey?.takeIf { it.isNotBlank() }
                    ?: fail("is missing — set DATAPIPELINES_DB_ENCRYPTION_KEY to a base64-encoded 32-byte key")
            val decoded =
                try {
                    java.util.Base64
                        .getDecoder()
                        .decode(raw.trim())
                } catch (e: IllegalArgumentException) {
                    fail("is not valid base64", e)
                }
            if (decoded.size != KEY_BYTES) {
                fail("must decode to exactly $KEY_BYTES bytes but decoded to ${decoded.size}")
            }
            return CredentialEncryptor(decoded)
        }

        /** Builds an encryptor from raw key material (key rotation, tests). Requires 32 bytes. */
        fun fromRawKey(key: ByteArray): CredentialEncryptor {
            check(key.size == KEY_BYTES) { "encryption key must be exactly $KEY_BYTES bytes, was ${key.size}" }
            return CredentialEncryptor(key.copyOf())
        }

        private fun fail(
            reason: String,
            cause: Throwable? = null,
        ): Nothing = throw IllegalStateException("datapipelines.db.encryption-key $reason.", cause)
    }
}

/** Thrown when a stored credential cannot be decrypted — a wrong key or a tampered value. */
class CredentialDecryptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
