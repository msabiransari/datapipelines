package co.datapipelines.application.lake

import co.datapipelines.datasources.SqlTableReferenceExtractor
import co.datapipelines.datasources.WrittenTableReference

/**
 * What a written table position names on a LAKE connection (#778, reference-scanner slice D-B) — the connection's own
 * naming, restated (datasources.md's lake section): a registered table with a TWO-segment namespace is a view in catalog
 * `ns1`, schema `ns2`; a ONE-segment namespace is a schema of the default catalog; and the connection's `search_path`
 * names a schema only when the datasource registers exactly one namespace.
 *
 * - `c.s.t` → `[c, s]` (or `[s]` when `c` is the default catalog);
 * - `s.t` → `[s]`;
 * - `t` → `[ns]` when the datasource registers exactly one namespace `ns`, else UNRESOLVED.
 *
 * NOT a table position at all (no index row): a CTE name in scope, the engine's system catalogs (`system`, `temp`) and
 * schemas (`information_schema`, `pg_catalog`), and its internal views (`duckdb_tables`, …) spelled bare or in `main`.
 *
 * The identity is spelled as the registry spells it when the registry holds it (identifiers match case-insensitively),
 * else as written. Pure: the registry's tables and the engine's facts are arguments.
 */
object LakeTableReferenceResolver {
    sealed interface Resolution {
        /** The position names no table (CTE, system object): it writes no row. */
        data object NotATable : Resolution

        /** A table position whose registry identity the written form does not determine (a bare name, several namespaces). */
        data object Unresolved : Resolution

        data class Resolved(
            val identity: LakeTableIdentity,
        ) : Resolution
    }

    fun resolve(
        reference: WrittenTableReference,
        registered: List<LakeTableIdentity>,
        engine: SqlTableReferenceExtractor.EngineFacts,
    ): Resolution {
        if (isSystemObject(reference, engine)) return Resolution.NotATable
        val catalog = reference.catalog
        val schema = reference.schema
        val namespace =
            when {
                catalog != null && schema != null -> {
                    if (catalog.equals(engine.defaultCatalog, ignoreCase = true)) listOf(schema) else listOf(catalog, schema)
                }

                schema != null -> {
                    listOf(schema)
                }

                else -> {
                    registered.map { it.namespace.map(String::lowercase) }.distinct().singleOrNull()?.let { only ->
                        registered.first { candidate -> candidate.namespace.map(String::lowercase) == only }.namespace
                    } ?: return Resolution.Unresolved
                }
            }
        val written = LakeTableIdentity(namespace, reference.table)
        return Resolution.Resolved(registered.firstOrNull { it.sameAs(written) } ?: written)
    }

    private fun isSystemObject(
        reference: WrittenTableReference,
        engine: SqlTableReferenceExtractor.EngineFacts,
    ): Boolean {
        if (reference.cte) return true
        val catalog = reference.catalog?.lowercase()
        val schema = reference.schema?.lowercase()
        if (catalog in SYSTEM_CATALOGS || schema in SYSTEM_SCHEMAS) return true
        val internalView = reference.table.lowercase() in engine.internalViews
        return internalView && (schema == null || schema == MAIN_SCHEMA)
    }

    private val SYSTEM_CATALOGS = setOf("system", "temp")
    private val SYSTEM_SCHEMAS = setOf("information_schema", "pg_catalog")
    private const val MAIN_SCHEMA = "main"
}
