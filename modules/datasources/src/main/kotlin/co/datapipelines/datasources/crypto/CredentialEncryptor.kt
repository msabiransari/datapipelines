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
 * encryption** from [SecureRandom]; the stored value is `version ‖ nonce ‖ ciphertext ‖ tag`
 * (GCM's `doFinal` already appends the 128-bit tag to the ciphertext). Nonce reuse under one key
 * is the one catastrophic GCM misuse, so it is generated per call and never derived or cached —
 * [CredentialEncryptorTest] asserts two encryptions of the same plaintext produce different
 * nonces.
 *
 * ## The version byte
 *
 * The first stored byte is the [DataKey.version] the value was sealed under. It is what makes
 * key rotation LAZY-SAFE: a row written under key 1 keeps decrypting after `current` moves to
 * key 2, and is rewritten under the current key on its next password write. The operator learns
 * when an old key can be retired by counting versions in SQL (datasources.md §7.3). Without it,
 * rotation is a big-bang re-encrypt with both keys live.
 *
 * The byte is **not** authenticated data on its own — but it does not need to be: it selects the
 * key, and a selected key that is wrong fails the GCM tag. Flipping it on a valid blob therefore
 * throws, which is exactly the behaviour a tamper signal wants.
 *
 * The pre-versioning layout (`nonce ‖ ciphertext ‖ tag`, no version byte) is **refused, never
 * guessed**: shipped rows were migrated to carry `0x01` deterministically, so a heuristic
 * fallback would only ever mask a real defect.
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
 * ## Key source — a [KeyProvider], required and fail-fast
 *
 * Keys arrive through the provider seam; `docs/key-providers.md` is the guide an implementer of
 * a KMS-backed provider works from. [current] is read ONCE, here in the constructor, because the
 * [KeyProvider] contract declares it stable for the life of the process — which also means a
 * provider whose backing service is unreachable fails at BOOT (this class is constructed at
 * startup wiring), never at the first password write. Key bytes never reach a log line, an
 * exception message or an audit `details` map.
 */
class CredentialEncryptor(
    private val keys: KeyProvider,
) {
    /**
     * The key every new encryption uses, read once at construction.
     *
     * Reading it here is what turns an unreachable key service into a startup failure rather
     * than a runtime one, and the [KeyProvider] contract explicitly permits the caching.
     */
    private val currentKey: DataKey = keys.current()

    /**
     * Encrypts [plaintext], returning `version ‖ nonce ‖ ciphertext ‖ tag` for storage as `BYTEA`.
     *
     * @param datasourceName the owning datasource's immutable name (§11.1), bound into the
     *   ciphertext as GCM associated data — [decrypt] must be given the same value.
     */
    fun encrypt(
        plaintext: String,
        datasourceName: String,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val cipher = newCipher(Cipher.ENCRYPT_MODE, currentKey, nonce, datasourceName)
        val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return byteArrayOf(currentKey.version.toByte()) + nonce + ciphertextAndTag
    }

    /**
     * Decrypts a `version ‖ nonce ‖ ciphertext ‖ tag` value produced by [encrypt].
     *
     * @param datasourceName the name the value was sealed under; a mismatch fails the tag.
     * @throws CredentialDecryptionException when the value is too short to contain a version,
     *   nonce and tag; when its version byte names a key this deployment does not hold (the
     *   "wrong deployment / lost key" signal); or when the GCM authentication tag fails — the
     *   tamper signal (mutated bytes, or a ciphertext lifted from a different datasource). The
     *   message never contains the ciphertext, the key, or any derived material.
     */
    @Suppress("ThrowsCount") // three DISTINCT signals, each documented above and each separately asserted
    fun decrypt(
        stored: ByteArray,
        datasourceName: String,
    ): String {
        if (stored.size < VERSION_BYTES + NONCE_BYTES + TAG_BYTES) {
            throw CredentialDecryptionException("stored credential is shorter than a version byte plus a nonce and tag")
        }
        val version = stored[0].toInt() and BYTE_MASK
        val key =
            keys.byVersion(version)
                ?: throw CredentialDecryptionException(
                    "stored credential names key version $version, which this deployment does not hold " +
                        "(wrong deployment, or a retired key)",
                )
        val nonce = stored.copyOfRange(VERSION_BYTES, VERSION_BYTES + NONCE_BYTES)
        val ciphertextAndTag = stored.copyOfRange(VERSION_BYTES + NONCE_BYTES, stored.size)
        return try {
            val cipher = newCipher(Cipher.DECRYPT_MODE, key, nonce, datasourceName)
            String(cipher.doFinal(ciphertextAndTag), Charsets.UTF_8)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw CredentialDecryptionException("authentication tag verification failed (tampered, wrong key, or wrong datasource)", e)
        }
    }

    private fun newCipher(
        mode: Int,
        key: DataKey,
        nonce: ByteArray,
        datasourceName: String,
    ): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(datasourceName.toByteArray(Charsets.UTF_8))
        }

    companion object {
        /** SunJCE transformation; the JDK ships this with no external provider. */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** AES-256 → 32-byte key. */
        const val KEY_BYTES = DataKey.KEY_BYTES

        /** The leading key-version byte (datasources.md §7.1). */
        const val VERSION_BYTES = 1

        /** 96-bit nonce, the GCM-recommended size. */
        const val NONCE_BYTES = 12

        /** 128-bit authentication tag. */
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        private const val BYTE_MASK = 0xFF

        private val SECURE_RANDOM = SecureRandom()
    }
}

/** Thrown when a stored credential cannot be decrypted — a wrong key or a tampered value. */
class CredentialDecryptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
