package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The §7C catalog-statistics engine behind [SchemaIntrospector.tableStats], split out because
 * the introspector sits at the house size ceiling (the `SchemaPayloads` precedent).
 *
 * Every number here comes from the engine's OWN catalog — `pg_class`, `information_schema`,
 * `sqlite_stat1`, Parquet footers — and never from scanning the table: there is no `COUNT(*)`
 * and no `COUNT(DISTINCT)` anywhere on this path, by construction. The per-dialect SQL is the
 * adapter's [DialectAdapter.tableStatsPlan] (`TableStatsPlans`); this reader is generic over
 * the plan's canonical column contract.
 *
 * Reads run through the module's one lease boundary ([ConnectionLease]), like every other
 * datasource read, with a SHORT bounded statement timeout ([MAX_STATS_QUERY_TIMEOUT_SECONDS]) —
 * catalog reads must never wait on a table scan's budget. JDBC-path identifiers reach the
 * engine only as prepared-statement binds (never interpolated); the LAKE path's location is
 * interpolated into `parquet_metadata(...)` only after the [isSafeLakeLocation] refusal — the
 * same total grammar [LakeViewStatements] enforces at view generation.
 *
 * ## The LAKE branch
 *
 * A lake table's statistics are its Parquet footers: `parquet_metadata('<location>')` (DuckDB
 * 1.5.5, verified against the pinned driver) returns one row per (file, row group, column)
 * carrying `row_group_num_rows`, `stats_min_value`/`stats_max_value` (VARCHAR — the comparison
 * is done in Kotlin, numeric-aware, never SQL's lexical MIN/MAX), `stats_null_count` and
 * `num_values`. The row estimate is the sum over DISTINCT (file, row group); a registered
 * directory location scans recursively (verified: a bare directory path works). Iceberg is a
 * documented gap: the registered location is the table's metadata JSON, and the DuckDB 1.5.x
 * iceberg extension exposes no per-column statistics a footer-style read can reach without the
 * extension loaded (and it loads only for iceberg catalog kinds) — an Iceberg table answers
 * nulls with `stats_source: "none"`.
 *
 * A lake has no indexes; the registry's partition column IS the access structure the engine
 * prunes on, so it reports as a `kind: "partition"` pseudo-index.
 */
internal class TableStatsReader(
    private val registry: DatasourceRegistry,
    private val lakeTables: LakeTableCatalog,
    private val lakeCache: LakeIntrospectionCache,
) {
    fun tableStats(
        datasource: Datasource,
        table: String,
        namespaceFilter: List<String>?,
    ): TableStats {
        val filter = Namespaces.filterOf(namespaceFilter, null)
        if (datasource.dialect == Dialect.LAKE) return lakeTableStats(datasource, table, filter)
        val adapter = DialectAdapters.forDialect(datasource.dialect)
        val plan = adapter.tableStatsPlan ?: return emptyStats(STATS_SOURCE_NONE)
        val shape = adapter.namespaceShape
        return ConnectionLease.lease(registry, datasource) { connection ->
            // The same effective-filter rule as columns(): the caller's filter, else the
            // connection's current namespace — and the flat dialects never consult one.
            val effective =
                filter.ifEmpty { if (shape.isFlat) emptyList() else connection.currentNamespace(adapter, datasource.name) }
            if (effective.isEmpty() && !shape.isFlat) throw CurrentSchemaUnknownException(datasource.name)
            // A filter deeper than the dialect's namespace names no real place (the
            // Namespaces.route rule) — empty stats, like columns()'s empty list.
            if (effective.size > shape.labels.size) return@lease emptyStats(plan.source)
            readPlan(connection, datasource, plan, effective.lastOrNull(), table)
        }
    }

    private fun readPlan(
        connection: Connection,
        datasource: Datasource,
        plan: TableStatsPlan,
        schema: String?,
        table: String,
    ): TableStats {
        var statsSource = plan.source
        var rowEstimate: String? = null
        var statsAsOf: String? = null
        plan.rowEstimate?.let { query ->
            val row = runQuery(connection, datasource, plan, query, schema, table).firstOrNull()
            rowEstimate = row?.get("row_estimate")?.toString()
            statsAsOf = (row?.get("stats_as_of") as? java.util.Date)?.toInstant()?.toString()
            // SQLite: the estimate IS the proof the catalog holds stats for this table.
            if (plan.rowEstimateCatalogOptional && rowEstimate == null) statsSource = STATS_SOURCE_NONE
        }
        val indexes = assembleIndexes(plan.indexes.flatMap { runQuery(connection, datasource, plan, it, schema, table) })
        val columns =
            plan.columnStats
                ?.let { query ->
                    runQuery(connection, datasource, plan, query, schema, table).map { columnStatsRow(it, plan, rowEstimate) }
                }.orEmpty()
        return TableStats(rowEstimate, statsAsOf, statsSource, indexes, columns)
    }

    /** One canonical column-stats row → [ColumnStats]; see [TableStatsPlan]'s column contract. */
    private fun columnStatsRow(
        row: Map<String, Any?>,
        plan: TableStatsPlan,
        rowEstimate: String?,
    ): ColumnStats {
        val (nDistinct, isRatio) =
            normalizeDistinct(row["n_distinct"]?.toString(), plan.distinctStoredAsSignedRatio, rowEstimate)
        val (histogramMin, histogramMax) = pgHistogramBounds(row["histogram_bounds"] as? String)
        return ColumnStats(
            name = row.getValue("column_name").toString(),
            nDistinct = nDistinct,
            distinctIsRatio = isRatio,
            nullFraction = (row["null_fraction"] as? Number)?.toDouble(),
            min = histogramMin ?: row["min_value"]?.toString(),
            max = histogramMax ?: row["max_value"]?.toString(),
        )
    }

    /**
     * One plan query's rows as label → value maps (labels lowercased — H2 reports its
     * information_schema labels uppercased). Binds fill in the declared order; a null schema
     * bind means the flat dialects, whose plans declare none.
     *
     * [TableStatsPlan.rowEstimateCatalogOptional]: the one tolerated failure — the optional
     * catalog that does not exist until the engine first analyzes (SQLite's `sqlite_stat1`)
     * answers "no estimate" instead of failing the whole read.
     */
    private fun runQuery(
        connection: Connection,
        datasource: Datasource,
        plan: TableStatsPlan,
        query: TableStatsPlan.Query,
        schema: String?,
        table: String,
    ): List<Map<String, Any?>> {
        try {
            connection.prepareStatement(query.sql).use { statement ->
                statement.queryTimeout = statsTimeoutSeconds(datasource)
                query.binds.forEachIndexed { index, bind ->
                    statement.setString(
                        index + 1,
                        when (bind) {
                            TableStatsPlan.Bind.SCHEMA -> schema
                            TableStatsPlan.Bind.TABLE -> table
                        },
                    )
                }
                statement.executeQuery().use { rs ->
                    return buildList {
                        while (rs.next()) add(rs.rowMap())
                    }
                }
            }
        } catch (e: SQLException) {
            if (plan.rowEstimateCatalogOptional && query === plan.rowEstimate) return emptyList()
            throw e
        }
    }

    /** One row as a lowercase-label → value map. */
    private fun ResultSet.rowMap(): Map<String, Any?> {
        val meta = metaData
        return (1..meta.columnCount).associate { index -> meta.getColumnLabel(index).lowercase() to getObject(index) }
    }

    /**
     * Rows of the plan's index queries, grouped by `index_name` in first-seen order (later
     * queries' same-named rows drop out — DuckDB's two catalogs overlap on nothing verified,
     * but a name collision would otherwise double the columns). A row carries its key columns
     * either as `ordinal`+`column_name` (one row per column, already ordered by the SQL) or as
     * an `expressions` text (DuckDB's `duckdb_indexes()` — see [parseIndexExpressions]). A row
     * whose column is null (MySQL's functional key parts) names no column and contributes none.
     */
    private fun assembleIndexes(rows: List<Map<String, Any?>>): List<IndexStats> {
        val byName = LinkedHashMap<String, IndexAccumulator>()
        rows.forEach { row ->
            val name = row["index_name"]?.toString() ?: return@forEach
            val accumulator = byName.getOrPut(name) { IndexAccumulator(row.asFlag("is_unique"), row.asFlag("is_primary")) }
            accumulator.columns.addAll(indexColumnsOf(row))
        }
        return byName.map { (name, acc) -> IndexStats(name, acc.columns, acc.unique, acc.primary) }
    }

    /** One index row's columns: the per-column row's single name, or the expressions text's list. */
    private fun indexColumnsOf(row: Map<String, Any?>): List<String> {
        row["expressions"]?.toString()?.let { return parseIndexExpressions(it) }
        return listOfNotNull(row["column_name"]?.toString())
    }

    private class IndexAccumulator(
        val unique: Boolean,
        val primary: Boolean,
        val columns: MutableList<String> = mutableListOf(),
    )

    /** A driver value as a boolean flag: JDBC bit/boolean, or the 0/1 the CASE expressions emit. */
    private fun Map<String, Any?>.asFlag(key: String): Boolean =
        when (val value = this[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value?.toString().toBoolean()
        }

    /**
     * `n_distinct` to the wire's string form. Postgres stores a NEGATIVE value as a fraction
     * of the table ([TableStatsPlan.distinctStoredAsSignedRatio]): normalized to an estimate
     * against the row estimate; when there is no row estimate the ratio cannot resolve, the
     * field reports null, and the ratio flag travels as the honest statement of what the
     * catalog holds. Whole-valued estimates stringify without a fraction (10.0 → "10").
     */
    private fun normalizeDistinct(
        raw: String?,
        signedRatio: Boolean,
        rowEstimate: String?,
    ): Pair<String?, Boolean> {
        if (raw == null) return null to false
        val value = raw.toDoubleOrNull() ?: return raw to false
        if (signedRatio && value < 0) {
            val rows = rowEstimate?.toLongOrNull()
            return if (rows != null) (-value * rows).roundToLong().toString() to false else null to true
        }
        return if (value == floor(value)) value.toLong().toString() to false else raw to false
    }

    // ------------------------------------------------------------------ LAKE

    /**
     * §7C for a LAKE datasource — the lakeColumns twin: the registry is the catalog, and the
     * same single-namespace resolution applies to the unfiltered read (exactly one registered
     * namespace resolves there; several refuse with [CurrentSchemaUnknownException]; an
     * unregistered table is empty stats, never an error).
     */
    private fun lakeTableStats(
        datasource: Datasource,
        table: String,
        filter: List<String>,
    ): TableStats {
        val registered = resolveLakeTable(datasource, table, filter) ?: return emptyStats(STATS_SOURCE_NONE)
        val partitionIndexes =
            listOfNotNull(
                registered.partitionColumn?.let {
                    IndexStats(
                        name = PARTITION_INDEX_NAME,
                        columns = listOf(it),
                        unique = false,
                        primary = false,
                        kind = IndexStats.INDEX_KIND_PARTITION,
                    )
                },
            )
        // Iceberg's registered location is the table's metadata JSON, not Parquet footers —
        // see the class KDoc for the gap.
        if (registered.format != LAKE_FORMAT_PARQUET) {
            return TableStats(null, null, STATS_SOURCE_NONE, partitionIndexes, emptyList())
        }
        val qualifier = (registered.namespace + registered.name).joinToString(Namespaces.SEPARATOR.toString())
        return lakeCache.get(datasource.name, "stats", qualifier) {
            ConnectionLease.lease(registry, datasource) { connection ->
                parquetFooterStats(connection, datasource, registered, partitionIndexes)
            }
        }
    }

    /** The registry row for (namespace, table), resolving the unfiltered single-namespace rule. */
    private fun resolveLakeTable(
        datasource: Datasource,
        table: String,
        filter: List<String>,
    ): LakeRegisteredTable? {
        val effective =
            filter.ifEmpty {
                val namespaces = lakeTables.registeredTables(datasource.name).map { it.namespace }.distinct()
                when (namespaces.size) {
                    0 -> return null
                    1 -> namespaces.single()
                    else -> throw CurrentSchemaUnknownException(datasource.name)
                }
            }
        if (effective.size > MAX_LAKE_NAMESPACE_SEGMENTS) return null
        return lakeTables
            .registeredTables(datasource.name)
            .firstOrNull { it.namespace == effective && it.name == table }
    }

    /**
     * One `parquet_metadata` scan over the registered location: the row estimate sums DISTINCT
     * (file, row group) counts — the function reports one row per (file, row group, COLUMN), so
     * a naive sum multiplies by the column count — and the column min/max pick the extreme
     * `stats_min_value`/`stats_max_value` with a numeric-aware comparison, because the catalog
     * types those columns VARCHAR and SQL's MIN/MAX would compare `"100" < "9"` lexically.
     * `null_fraction` is the summed null count over the summed value count; a writer that
     * omits null counts anywhere makes the fraction unknowable (null, not a guess).
     */
    private fun parquetFooterStats(
        connection: Connection,
        datasource: Datasource,
        table: LakeRegisteredTable,
        indexes: List<IndexStats>,
    ): TableStats {
        refuseUnsafeLocation(table)
        connection.createStatement().use { statement ->
            statement.queryTimeout = statsTimeoutSeconds(datasource)
            val scan =
                statement
                    .executeQuery(
                        """
                        SELECT file_name, row_group_id, row_group_num_rows, path_in_schema,
                               stats_min_value, stats_max_value, stats_null_count, num_values
                        FROM parquet_metadata('${table.location}')
                        """.trimIndent(),
                    ).use { rs -> scanFooters(rs) }
            if (scan.rowGroups.isEmpty()) return TableStats(null, null, STATS_SOURCE_NONE, indexes, emptyList())
            return TableStats(
                rowEstimate =
                    scan.rowGroups.values
                        .sum()
                        .toString(),
                statsAsOf = null,
                statsSource = "parquet_metadata",
                indexes = indexes,
                columns = scan.columns.map { (name, acc) -> acc.toColumnStats(name) },
            )
        }
    }

    /**
     * One footer row at a time into the scan's accumulators, bounded at [MAX_FOOTER_ROWS] — the
     * bound exists so a million-file glob cannot stream its catalog through the wire.
     */
    private fun scanFooters(rs: ResultSet): FooterScan {
        val rowGroups = mutableMapOf<Pair<String, Long>, Long>()
        val columns = LinkedHashMap<String, ParquetColumnAccumulator>()
        var rows = 0
        while (rs.next() && ++rows <= MAX_FOOTER_ROWS) {
            rowGroups.putIfAbsent(rs.getString("file_name") to rs.getLong("row_group_id"), rs.getLong("row_group_num_rows"))
            rs.getString("path_in_schema")?.let { column ->
                columns.getOrPut(column) { ParquetColumnAccumulator() }.accumulate(rs)
            }
        }
        return FooterScan(rowGroups, columns)
    }

    private class FooterScan(
        val rowGroups: Map<Pair<String, Long>, Long>,
        val columns: Map<String, ParquetColumnAccumulator>,
    )

    /** One column's running footer aggregates. */
    private class ParquetColumnAccumulator(
        var min: String? = null,
        var max: String? = null,
        var nullCount: Long = 0,
        var valueCount: Long = 0,
        var nullCountsComplete: Boolean = true,
    ) {
        fun accumulate(rs: ResultSet) {
            min = extremeOf(min, rs.getString("stats_min_value"), takeLower = true)
            max = extremeOf(max, rs.getString("stats_max_value"), takeLower = false)
            val nulls = rs.getObject("stats_null_count") as? Number
            if (nulls == null) nullCountsComplete = false
            nullCount += nulls?.toLong() ?: 0L
            valueCount += (rs.getObject("num_values") as? Number)?.toLong() ?: 0L
        }

        fun toColumnStats(name: String): ColumnStats =
            ColumnStats(
                name = name,
                nDistinct = null,
                distinctIsRatio = false,
                nullFraction = if (nullCountsComplete && valueCount > 0) nullCount.toDouble() / valueCount else null,
                min = min,
                max = max,
            )
    }

    /** The location refusal at THIS SQL-emission boundary — [LakeViewStatements]'s twin. */
    private fun refuseUnsafeLocation(table: LakeRegisteredTable) {
        if (isSafeLakeLocation(table.location)) return
        val qualified = (table.namespace + table.name).joinToString(".")
        throw DatapipelinesException(
            DatasourceErrorCodes.LAKE_LOCATION_INVALID,
            "Lake table '$qualified' has a location that fails the registry's grammar " +
                "(s3:// or file://; no quotes, backslashes, whitespace or control characters) — " +
                "refusing to interpolate it into a parquet_metadata read.",
            mapOf("table" to qualified),
        )
    }

    private fun emptyStats(source: String) = TableStats(null, null, source, emptyList(), emptyList())

    /**
     * [datasource]'s timeout clamped to the catalog-read bound. Catalog reads never scan a
     * table — the whole point of §7C — so they must not wait on a table scan's budget: the
     * datasource's own `queryTimeoutSeconds` applies only when TIGHTER. The lake's footer read
     * is the heaviest thing here, and a glob over many Parquet footers is still metadata.
     */
    private fun statsTimeoutSeconds(datasource: Datasource): Int =
        minOf(datasource.queryTimeoutSeconds ?: MAX_STATS_QUERY_TIMEOUT_SECONDS, MAX_STATS_QUERY_TIMEOUT_SECONDS)

    private companion object {
        /** The wire's stats_source when the engine has no catalog stats for the table. */
        const val STATS_SOURCE_NONE = "none"

        /** DuckDB's catalog.schema depth for a lake — a deeper filter names no real place. */
        const val MAX_LAKE_NAMESPACE_SEGMENTS = 2

        /** The pseudo-index name for a lake table's partition column. */
        const val PARTITION_INDEX_NAME = "partition"

        /** The registry's wire value for a Parquet table (metadata-db §4.15). */
        const val LAKE_FORMAT_PARQUET = "parquet"

        /** The bound on a stats statement — see [statsTimeoutSeconds]. */
        const val MAX_STATS_QUERY_TIMEOUT_SECONDS = 10

        /** A footer read's row budget: files × row groups × columns — see [scanFooters]. */
        const val MAX_FOOTER_ROWS = 65536
    }
}

/** The lower/upper of two footer bounds; numeric when both parse, lexical otherwise. */
private fun extremeOf(
    current: String?,
    candidate: String?,
    takeLower: Boolean,
): String? {
    if (candidate == null) return current
    if (current == null) return candidate
    val currentNumber = current.toBigDecimalOrNull()
    val candidateNumber = candidate.toBigDecimalOrNull()
    val candidateWins =
        if (currentNumber != null && candidateNumber != null) {
            (candidateNumber < currentNumber) == takeLower
        } else {
            (candidate < current) == takeLower
        }
    return if (candidateWins) candidate else current
}

/**
 * DuckDB's `duckdb_indexes().expressions` text to a column list — verified on duckdb_jdbc
 * 1.5.5.1 as `['"label"', fare]` for `CREATE UNIQUE INDEX ... ON t("label", fare)`. The parse
 * is conservative on purpose: a top-level comma split that respects the single-quoted repr;
 * an element is a column when it unwraps to a double-quoted identifier or a bare one, and is
 * DROPPED otherwise (a computed index's `'(a + b)'` names no column, and guessing one would
 * invent an index shape the engine does not have).
 */
internal fun parseIndexExpressions(expressions: String): List<String> {
    val inner = expressions.trim().removePrefix("[").removeSuffix("]")
    if (inner.isBlank()) return emptyList()
    return splitTopLevel(inner, '\'', backslashEscapes = false).mapNotNull(::asIndexColumn)
}

/** One `expressions` element as a column name, or null when it is not a plain column. */
private fun asIndexColumn(raw: String): String? {
    val element = raw.trim()
    val unwrapped = if (isWrapped(element, '\'')) element.substring(1, element.length - 1).replace("''", "'") else element
    return when {
        isWrapped(unwrapped, '"') -> unwrapped.substring(1, unwrapped.length - 1).replace("\"\"", "\"")
        unwrapped.matches(PLAIN_IDENTIFIER) -> unwrapped
        else -> null
    }
}

/** A bare SQL identifier — anything richer is an expression, not a column name. */
private val PLAIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_$]*")

/**
 * A Postgres array's text form to its first and last elements — `pg_stats.histogram_bounds`
 * is `anyarray`, whose unnest is refused outright (verified live on postgres:16-alpine:
 * "cannot determine element type of \"anyarray\" argument"), so the bounds travel as
 * `histogram_bounds::text` and are split here. The first/last elements of a histogram_bounds
 * array are the column's observed min/max. A non-array-shaped text answers (null, null)
 * rather than guessing.
 */
internal fun pgHistogramBounds(text: String?): Pair<String?, String?> {
    val inner = text?.takeIf(::isBracketed)?.let { it.substring(1, it.length - 1) } ?: return null to null
    if (inner.isEmpty()) return null to null
    val elements = splitTopLevel(inner, '"', backslashEscapes = true)
    return unwrapQuoted(elements.first()) to unwrapQuoted(elements.last())
}

/** `"{...}"` — the Postgres array text envelope. */
private fun isBracketed(text: String): Boolean = text.length >= 2 && text.first() == '{' && text.last() == '}'

/** One array element, its surrounding double quotes (if any) removed. */
private fun unwrapQuoted(element: String): String = if (isWrapped(element, '"')) element.substring(1, element.length - 1) else element

private fun isWrapped(
    value: String,
    quote: Char,
): Boolean = value.length >= 2 && value.first() == quote && value.last() == quote

/**
 * A list-text split on TOP-LEVEL commas: commas inside [quote]-quoted elements stay in their
 * element, and with [backslashEscapes] a `\` inside a quote escapes the next character
 * (Postgres's array text) rather than closing anything. Quotes are KEPT in the returned
 * elements — the callers unwrap.
 */
private fun splitTopLevel(
    inner: String,
    quote: Char,
    backslashEscapes: Boolean,
): List<String> {
    val elements = mutableListOf<String>()
    val current = StringBuilder()
    var inQuote = false
    var escaped = false
    inner.forEach { ch ->
        when {
            escaped -> {
                current.append(ch)
                escaped = false
            }

            backslashEscapes && inQuote && ch == '\\' -> {
                escaped = true
            }

            ch == quote -> {
                inQuote = !inQuote
                current.append(ch)
            }

            ch == ',' && !inQuote -> {
                elements += current.toString()
                current.clear()
            }

            else -> {
                current.append(ch)
            }
        }
    }
    elements += current.toString()
    return elements
}
