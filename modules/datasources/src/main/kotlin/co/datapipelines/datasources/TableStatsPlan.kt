package co.datapipelines.datasources

/**
 * One dialect's catalog-statistics recipe: the SQL a [TableStatsReader] runs against the
 * engine's OWN catalog — never the table (no `COUNT(*)`, no `COUNT(DISTINCT)` anywhere) —
 * declared on the adapter per the [DialectAdapter.rowLimitStyle] precedent. The interface's
 * default is UNSUPPORTED (null), and each adapter whose engine has a catalog worth reading
 * declares its queries in [TableStatsPlans].
 *
 * Every [Query] is a prepared statement whose `?` binds are filled in the declared order —
 * never interpolated text: [Bind.SCHEMA] is the innermost namespace segment (the schema, or
 * the database under MySQL's catalog routing), [Bind.TABLE] the table name, both already
 * resolved by the reader's namespace routing, so the SQL sees plain equality arguments.
 *
 * Canonical column contract — the reader is generic because every plan emits the same labels:
 *
 *  - [rowEstimate]: zero or one row of `row_estimate` (any numeric/text type — stringified for
 *    the wire) and optionally `stats_as_of` (a JDBC timestamp, rendered as an ISO instant).
 *  - each [indexes] query: rows of `index_name`, `is_unique`, `is_primary`, plus EITHER
 *    `ordinal`+`column_name` (one row per key column, in key order) or an `expressions` text
 *    carrying the whole key list — DuckDB's `duckdb_indexes()` exposes an explicit index's
 *    columns only as that expression-list text (verified 1.5.5.1: `['"label"', fare]`), which
 *    the reader parses conservatively.
 *  - [columnStats]: one row per column of `column_name` plus any of `n_distinct`,
 *    `null_fraction`, `min_value`, `max_value` — or a `histogram_bounds` text (Postgres's
 *    `anyarray` column cannot be unnested in SQL; the reader splits its text form and takes
 *    the first/last element as min/max).
 */
data class TableStatsPlan(
    /** The wire's `stats_source` — names the catalog the numbers come from. */
    val source: String,
    val rowEstimate: Query? = null,
    val indexes: List<Query> = emptyList(),
    val columnStats: Query? = null,
    /**
     * Postgres's `pg_stats.n_distinct` convention: a NEGATIVE value is a fraction of the
     * table's rows. The reader normalizes it to an estimate against the row estimate and sets
     * [ColumnStats.distinctIsRatio] when it cannot.
     */
    val distinctStoredAsSignedRatio: Boolean = false,
    /**
     * The row-estimate catalog may not EXIST at query time — SQLite creates `sqlite_stat1`
     * only at the first ANALYZE (verified on sqlite-jdbc 3.49.1.0: querying it before throws
     * "no such table: sqlite_stat1"). The read tolerates that failure and answers null, with
     * `stats_source` falling back to `none`.
     */
    val rowEstimateCatalogOptional: Boolean = false,
) {
    /** One catalog query with its bind order — see the class KDoc for the column contract. */
    data class Query(
        val sql: String,
        val binds: List<Bind>,
    )

    /** What a `?` bind receives. */
    enum class Bind {
        SCHEMA,
        TABLE,
    }
}
