package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeBrokenTable
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * 109 §A's CONNECT-time gate for LAKE nodes, in its own file: NodeRunner crossed detekt's
 * LargeClass line when 108 (source streaming) and 109 (this gate) landed in the same week, and
 * the gate is a pure function of (registry, datasource, rendered SQL) that needs nothing of the
 * runner's state.
 */
internal object LakeTableGate {
    /**
     * 109 §A — refuses a LAKE node whose rendered SQL references a registered table whose
     * connect-time view creation is recorded as failed (datasources.md §8C.2). Matching is
     * token-bounded on four spellings — the dotted qualified name, its double-quoted form, the
     * bare name, the bare quoted name — never a bare substring (`trips` must not match
     * `trips_2024`, and a bare name must not match the tail of another namespace's
     * `other.trips`). A bare reference under the single-namespace search-path rule resolves to
     * that namespace's table; under multiple namespaces a bare name resolves to NOTHING, so
     * reporting the broken same-named table is still the honest diagnosis.
     */
    fun enforceAvailable(
        datasourceRegistry: DatasourceRegistry,
        datasource: Datasource,
        bound: SqlBindTranslator.BoundSql,
    ) {
        val broken = datasourceRegistry.lakeBrokenTables(datasource.name)
        if (broken.isEmpty()) return
        val hit = broken.firstOrNull { table -> referencePatterns(table).any { it.containsMatchIn(bound.sql) } } ?: return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Datasource.LAKE_TABLE_UNAVAILABLE,
            message =
                "Lake table '${hit.qualifiedName}' on datasource '${datasource.name}' is unavailable: " +
                    "its connect-time view creation failed — ${hit.lastError}",
            details = mapOf("datasource" to datasource.name, "table" to hit.qualifiedName, "last_error" to hit.lastError),
        )
    }

    /** The four spellings a reference to [table] can take in rendered SQL, each token-bounded. */
    private fun referencePatterns(table: LakeBrokenTable): List<Regex> {
        val quotedQualified = (table.namespace + table.name).joinToString(".") { "\"$it\"" }
        return listOf(table.qualifiedName, quotedQualified, table.name, "\"${table.name}\"")
            .distinct()
            .map { token -> Regex("""(?<![\w."])""" + Regex.escape(token) + """(?![\w"])""") }
    }
}
