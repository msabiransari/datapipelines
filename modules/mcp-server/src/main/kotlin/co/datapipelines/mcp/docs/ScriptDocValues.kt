package co.datapipelines.mcp.docs

/**
 * The `datapipelines.script.*` numbers the narrative prose quotes — the script half of the
 * render context (#780). `web` builds it from `ScriptProperties` (`ScriptDocsConfiguration`)
 * and the assembled `docSet` bean takes it as a REQUIRED constructor parameter, like
 * [DocErrorCatalog]: a context that serves the manual without declaring its script numbers
 * fails the boot — there is no silent fallback to the shipped defaults, because a deployment
 * serving defaults it does not run is a stale manual.
 */
data class ScriptDocValues(
    val maxInputRows: Long = 100_000,
    val poolSize: Int = 4,
    val poolQueue: Int = 64,
    val maxDepth: Int = 100,
)
