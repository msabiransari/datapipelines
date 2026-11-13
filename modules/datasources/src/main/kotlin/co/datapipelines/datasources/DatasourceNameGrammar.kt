package co.datapipelines.datasources

/**
 * The datasource name grammar (datasources.md §3.3): `[a-z0-9_-]`, 1–63 characters. ONE definition — the registry's
 * save-path validator ([DatasourceValidator]) and every other caller that must refuse a malformed name before any lookup
 * (the dependency tree route, #875 G4, record §11.1 "checked by that family's native name validator, never a copy") ask
 * this object.
 */
object DatasourceNameGrammar {
    /** The longest legal name, in characters. */
    const val MAX_LENGTH = 63

    private val PATTERN = Regex("^[a-z0-9_-]+$")

    /** The pattern source, for a refusal message or a form hint. */
    val pattern: String get() = PATTERN.pattern

    /** True when [name] is a legal datasource name. */
    fun matches(name: String): Boolean = name.length in 1..MAX_LENGTH && PATTERN.matches(name)
}
