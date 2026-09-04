package co.datapipelines.datasources.crypto

import java.util.Base64

/**
 * The default [KeyProvider]: keys come from configuration, base64-encoded
 * (datasources.md §7.2, configuration.md §3.20).
 *
 * This is exactly the behaviour every deployment had before the provider seam existed, with one
 * addition — a key VERSION. `datapipelines.db.encryption-key` is **version 1**, forever; optional
 * `datapipelines.db.encryption-keys` adds `{version -> base64}` entries for a rotation, and
 * `datapipelines.db.encryption-key-current` selects which one new writes use (default: the
 * highest configured version). A deployment that sets none of the optional keys is unchanged and
 * needs no config edit.
 *
 * ## Fail-fast, no fallback
 *
 * Every defect — a missing primary key, invalid base64, a key that is not exactly 32 bytes, a
 * version outside `1..255`, a `current` naming a version that is not configured — throws
 * [IllegalStateException] from [fromConfig]. Because the provider is built at startup wiring,
 * that throw **stops the application from starting**. There is deliberately no generated-key
 * fallback: a silently generated key is how every stored password becomes undecryptable on the
 * next redeploy (datasources.md §7.1 rationale).
 *
 * The messages name the config key and the defect. They never contain key material, not even a
 * prefix — a violation message reaches the logs.
 */
class EnvKeyProvider private constructor(
    private val keys: Map<Int, DataKey>,
    private val currentVersion: Int,
) : KeyProvider {
    override val name: String get() = NAME

    override fun current(): DataKey = keys.getValue(currentVersion)

    override fun byVersion(version: Int): DataKey? = keys[version]

    /** The versions this deployment can decrypt, ascending — for the boot log, never the keys. */
    val configuredVersions: List<Int> get() = keys.keys.sorted()

    companion object {
        /** The `datapipelines.db.key-provider` value that selects this provider. */
        const val NAME: String = "env"

        /** `datapipelines.db.encryption-key` is version 1, forever. */
        const val PRIMARY_VERSION: Int = 1

        private const val PRIMARY_KEY_PROPERTY = "datapipelines.db.encryption-key"
        private const val ADDITIONAL_KEYS_PROPERTY = "datapipelines.db.encryption-keys"
        private const val CURRENT_PROPERTY = "datapipelines.db.encryption-key-current"

        /**
         * The [KeyProviders] factory entry: reads this provider's three config keys and builds it.
         *
         * A non-numeric map key or a non-numeric `encryption-key-current` is refused by NAME
         * rather than allowed to become a binder crash — the operator gets the property they
         * mistyped, which is the whole reason the raw strings are read here.
         */
        fun from(config: KeyProviderConfig): EnvKeyProvider =
            fromConfig(
                primaryKey = config.string(PRIMARY_KEY_PROPERTY),
                additionalKeys =
                    config.map(ADDITIONAL_KEYS_PROPERTY).mapKeys { (version, _) ->
                        version.trim().toIntOrNull()
                            ?: fail("$ADDITIONAL_KEYS_PROPERTY has key '$version', which is not a version number.")
                    },
                currentVersion =
                    config.string(CURRENT_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                        raw.toIntOrNull() ?: fail("$CURRENT_PROPERTY is '$raw', which is not a version number.")
                    },
            )

        /**
         * Builds the provider from the three config values.
         *
         * @param primaryKey `datapipelines.db.encryption-key` — required, becomes version 1.
         * @param additionalKeys `datapipelines.db.encryption-keys` — optional `version -> base64`.
         *   Version 1 may not appear here: it has exactly one spelling, and two spellings of one
         *   version is a config trap that would silently pick a winner.
         * @param currentVersion `datapipelines.db.encryption-key-current` — optional; defaults to
         *   the highest configured version.
         */
        fun fromConfig(
            primaryKey: String?,
            additionalKeys: Map<Int, String> = emptyMap(),
            currentVersion: Int? = null,
        ): EnvKeyProvider {
            val keys = mutableMapOf<Int, DataKey>()
            keys[PRIMARY_VERSION] =
                DataKey(
                    PRIMARY_VERSION,
                    decode(
                        primaryKey?.takeIf { it.isNotBlank() }
                            ?: fail(
                                "$PRIMARY_KEY_PROPERTY is missing — set DATAPIPELINES_DB_ENCRYPTION_KEY to a base64-encoded 32-byte key.",
                            ),
                        PRIMARY_KEY_PROPERTY,
                    ),
                )
            additionalKeys.forEach { (version, encoded) ->
                if (version == PRIMARY_VERSION) {
                    fail("$ADDITIONAL_KEYS_PROPERTY must not declare version $PRIMARY_VERSION — that version is $PRIMARY_KEY_PROPERTY.")
                }
                if (version !in DataKey.MIN_VERSION..DataKey.MAX_VERSION) {
                    fail("$ADDITIONAL_KEYS_PROPERTY has version $version, outside ${DataKey.MIN_VERSION}..${DataKey.MAX_VERSION}.")
                }
                val where = "$ADDITIONAL_KEYS_PROPERTY[$version]"
                keys[version] =
                    DataKey(version, decode(encoded.takeIf { it.isNotBlank() } ?: fail("$where is blank."), where))
            }
            val selected = currentVersion ?: keys.keys.max()
            if (selected !in keys) {
                fail("$CURRENT_PROPERTY is $selected but no key is configured for that version (configured: ${keys.keys.sorted()}).")
            }
            return EnvKeyProvider(keys.toMap(), selected)
        }

        private fun decode(
            encoded: String,
            where: String,
        ): ByteArray {
            val decoded =
                try {
                    Base64.getDecoder().decode(encoded.trim())
                } catch (e: IllegalArgumentException) {
                    fail("$where is not valid base64.", e)
                }
            if (decoded.size != DataKey.KEY_BYTES) {
                fail("$where must decode to exactly ${DataKey.KEY_BYTES} bytes but decoded to ${decoded.size}.")
            }
            return decoded
        }

        private fun fail(
            message: String,
            cause: Throwable? = null,
        ): Nothing = throw IllegalStateException(message, cause)
    }
}
