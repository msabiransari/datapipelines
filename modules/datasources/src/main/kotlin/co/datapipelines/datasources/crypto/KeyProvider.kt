package co.datapipelines.datasources.crypto

/**
 * A 32-byte AES data key and the version every ciphertext encrypted under it carries
 * (datasources.md §7.1).
 *
 * The version is the first byte of the stored blob, so it is a single unsigned byte: `1..255`.
 * `0` is deliberately excluded — it is the value an all-zero or truncated buffer produces, and a
 * version that a zeroed page can forge is not a version.
 *
 * The bytes are the plaintext key. They are copied in and out so a caller cannot mutate the
 * provider's state, and they are **never** logged, never put in an exception message and never
 * put in an audit `details` map (configuration.md's bearer-secret rule).
 */
class DataKey(
    val version: Int,
    bytes: ByteArray,
) {
    private val key: ByteArray = bytes.copyOf()

    init {
        require(version in MIN_VERSION..MAX_VERSION) {
            "a data key version must be in $MIN_VERSION..$MAX_VERSION (it is the ciphertext's first byte), was $version"
        }
        require(key.size == KEY_BYTES) {
            "a data key must be exactly $KEY_BYTES bytes (AES-256), was ${key.size}"
        }
    }

    /** The raw key material, defensively copied. */
    val bytes: ByteArray get() = key.copyOf()

    /** Never renders the key — a data key that prints itself is a credential in a log line. */
    override fun toString(): String = "DataKey(version=$version, bytes=<redacted>)"

    companion object {
        /** AES-256 → 32-byte key. */
        const val KEY_BYTES: Int = 32

        /** The first version byte a deployment can carry; `0` is reserved as "not a version". */
        const val MIN_VERSION: Int = 1

        /** The version byte is one unsigned byte. */
        const val MAX_VERSION: Int = 255
    }
}

/**
 * Supplies data keys for [CredentialEncryptor] (datasources.md §7.1; the implementation guide is
 * `docs/key-providers.md`).
 *
 * Implementations own **how** a key reaches the process — an environment variable, AWS KMS, GCP
 * KMS, Azure Key Vault, Vault transit — and **none of them ever sees a password**. That is the
 * whole point of the seam: tomorrow's KMS customer is an implementation of this interface, not a
 * change to the crypto.
 *
 * Three invariants every implementation must satisfy, and which `KeyProviderContractTest` pins:
 *
 * 1. [current] is **stable for the life of the process** — the encryptor may (and does) cache it.
 * 2. [byVersion] must keep answering for any version [current] ever returned on this deployment,
 *    for as long as a stored row still carries that version byte.
 * 3. Both must fail at **BOOT**, not at first decrypt, when the backing service is unreachable —
 *    a misconfigured pod must never start and serve.
 *
 * An unknown version returns `null`; [CredentialEncryptor] turns that into a
 * [CredentialDecryptionException] — the "wrong deployment / lost key" tamper signal.
 */
interface KeyProvider {
    /** The config value that selects this provider: `env`, `aws-kms`, … (`datapipelines.db.key-provider`). */
    val name: String

    /** The key every new encryption uses. Stable for the life of the process. */
    fun current(): DataKey

    /** The key a row written under [version] needs, or `null` when this deployment has no such key. */
    fun byVersion(version: Int): DataKey?
}
