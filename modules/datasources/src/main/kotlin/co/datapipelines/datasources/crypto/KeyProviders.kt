package co.datapipelines.datasources.crypto

/**
 * Read-only access to the `datapipelines.db.*` configuration subtree, so a [KeyProvider]
 * implementation reads its own settings without this module depending on Spring
 * (module-structure §5.4: `datasources` depends on `typesystem` alone).
 *
 * The aggregation layer supplies the implementation — the same inversion `DatasourceReferences`
 * uses for the pipeline-name lookup. Keys are the FULL dotted property names an operator types,
 * so a provider's failure message names exactly what they must fix.
 */
interface KeyProviderConfig {
    /** The value of one property, or `null` when unset. Blank is returned as-is, not as null. */
    fun string(key: String): String?

    /** A map-valued property (`key.<entry>: value`) as `entry -> value`; empty when unset. */
    fun map(key: String): Map<String, String>

    companion object {
        /** An empty configuration — the shape a test that supplies its keys directly wants. */
        val EMPTY: KeyProviderConfig =
            object : KeyProviderConfig {
                override fun string(key: String): String? = null

                override fun map(key: String): Map<String, String> = emptyMap()
            }
    }
}

/**
 * Dispatches the `datapipelines.db.key-provider` config value to its [KeyProvider]
 * implementation — the same by-key registry shape [co.datapipelines.datasources.DialectAdapters]
 * uses for dialects, which is the pattern `docs/key-providers.md` §4 tells an implementer to copy.
 *
 * Adding a provider is: a class in `crypto/providers/`, one entry here, one `ConfigValidator`
 * branch, and a subclass of `KeyProviderContractTest`. Nothing else in the crypto changes — that
 * is the seam's whole purpose (datasources.md §7.1).
 */
object KeyProviders {
    /** The config key that selects the provider. */
    const val PROPERTY: String = "datapipelines.db.key-provider"

    /** The default, so every deployment that predates the seam is unchanged with no config edit. */
    const val DEFAULT: String = EnvKeyProvider.NAME

    private val BY_NAME: Map<String, (KeyProviderConfig) -> KeyProvider> =
        mapOf(EnvKeyProvider.NAME to { config -> EnvKeyProvider.from(config) })

    /** The provider names this build ships — the `ConfigValidator` and docs surface. */
    fun known(): Set<String> = BY_NAME.keys

    /**
     * Builds the selected provider. A blank or absent [name] selects [DEFAULT].
     *
     * @throws IllegalStateException when [name] is not a shipped provider, or when the selected
     *   provider's own settings are missing or malformed. Both stop startup, by design: a pod
     *   that cannot reach its keys must never serve (`docs/key-providers.md` §2, invariant 3).
     */
    fun create(
        name: String?,
        config: KeyProviderConfig,
    ): KeyProvider {
        val selected = name?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT
        val factory =
            BY_NAME[selected]
                ?: throw IllegalStateException(
                    "$PROPERTY '$selected' is not a known key provider (known: ${known().sorted()}). " +
                        "Implementing one is docs/key-providers.md.",
                )
        return factory(config)
    }
}
