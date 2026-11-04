package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeUnavailableTable
import co.datapipelines.datasources.pooling.ManagedLakeGeneration
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.sql.Connection

/**
 * #818 (dp-lake record §10 "Unavailable tables", D5) — the CONNECT-time refusal of a node that names a managed
 * table its generation does not serve: archived, purged, no catalog row, no lifecycle row, or the lake disabled.
 * Such a table has no view, so the engine could only answer a bare unknown-relation error. This gate answers
 * `lake.table.unavailable` with the table and the reason instead, before any connection is leased.
 *
 * #818b B: the question is asked of the GENERATION the lease will come from (its snapshot), never of a fresh
 * catalog read. The caller resolves the pool first and passes its [ManagedLakeGeneration]; null (any datasource
 * that is not the managed lake) refuses nothing. So a catalog outage cannot refuse a node the live generation
 * serves, and the reason given is the one that generation was built with.
 *
 * A sibling of [LakeTableGate], which stays unchanged: that gate refuses a table whose view FAILED (a recorded
 * `last_error`); this one refuses a table that was never planned. The brief placed this refusal at "the LAKE
 * adapter's error-mapping seam"; no such seam exists (no engine error is mapped to a lake code anywhere), so it
 * is a check against the rendered SQL, the same shape as its sibling.
 *
 * Matching uses [LakeTableGate]'s four token-bounded spellings: the dotted qualified name, its quoted form, the
 * bare name, and the bare quoted name. A bare name that also names an AVAILABLE table in another namespace
 * still refuses here. Under several namespaces a bare name resolves to nothing in the engine, so naming the
 * unavailable table is the honest diagnosis, as in the sibling.
 */
internal object ManagedLakeTableGate {
    /**
     * The executor's lease (#818b B): resolve the pool, refuse a node naming a table ITS generation serves no view
     * for, then lease from that same pool. One pool resolution, so the generation judged is the generation leased.
     * For every datasource but the managed lake the generation is null and this is `poolFor(…).leaseConnection()`.
     */
    fun leaseServing(
        datasourceRegistry: DatasourceRegistry,
        datasource: Datasource,
        bound: SqlBindTranslator.BoundSql,
    ): Connection {
        val pool = datasourceRegistry.poolFor(datasource)
        enforceAvailable(pool.managedGeneration, datasource, bound)
        return pool.leaseConnection()
    }

    fun enforceAvailable(
        generation: ManagedLakeGeneration?,
        datasource: Datasource,
        bound: SqlBindTranslator.BoundSql,
    ) {
        val unavailable = generation?.unavailableTables.orEmpty()
        if (unavailable.isEmpty()) return
        val hit = unavailable.firstOrNull { table -> referencePatterns(table).any { it.containsMatchIn(bound.sql) } } ?: return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Lake.TABLE_UNAVAILABLE,
            message =
                "Lake table '${hit.qualifiedName}' on datasource '${datasource.name}' is unavailable (${hit.reason}): " +
                    "this server serves no view for it",
            details = mapOf("datasource" to datasource.name, "table" to hit.qualifiedName, "reason" to hit.reason),
        )
    }

    /** [LakeTableGate]'s four spellings of a reference to [table], each token-bounded. */
    private fun referencePatterns(table: LakeUnavailableTable): List<Regex> {
        val quotedQualified = (table.namespace + table.name).joinToString(".") { "\"$it\"" }
        return listOf(table.qualifiedName, quotedQualified, table.name, "\"${table.name}\"")
            .distinct()
            .map { token -> Regex("""(?<![\w."])""" + Regex.escape(token) + """(?![\w"])""") }
    }
}
