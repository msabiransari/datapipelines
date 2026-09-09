package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect

/**
 * Turns one EXPLAIN result set into an [ExplainPlanSummary] — per-dialect best effort over a
 * GENERIC raw assembly, so a plan shape this parser does not know still reaches the caller as
 * [ExplainPlanSummary.raw] with null summaries. The parser is total by construction: it never
 * throws, because a plan that fails to parse must never fail the probe.
 *
 * The raw text is one line per result row, cells joined by ` | `, capped at
 * [SqlProbe.EXPLAIN_RAW_MAX_CHARS]. Two result SHAPES exist in the fleet:
 *
 *  - **text plans** — one column whose rows are the plan text (Postgres's `QUERY PLAN`, H2's
 *    `PLAN`, DuckDB's box drawing in `explain_value`);
 *  - **tabular plans** — MySQL's `EXPLAIN` columns (`type`, `key`, `rows`, ...) and SQLite's
 *    `EXPLAIN QUERY PLAN` (`detail`).
 *
 * Every marker string here is pinned against the pinned drivers (h2 2.3.232, sqlite-jdbc
 * 3.49.1.0, duckdb_jdbc 1.5.5.1 live-probed 2026-09-09; Postgres's `rows=N` plan vocabulary is
 * stable across the supported versions).
 */
internal object ExplainPlanParser {
    /**
     * Summarizes one EXPLAIN result. [columnLabels] are the result set's column labels in order
     * and [rows] its (already bounded) rows of string cells; [dialect] picks the summary rules.
     */
    fun summarize(
        dialect: Dialect,
        columnLabels: List<String>,
        rows: List<List<String?>>,
    ): ExplainPlanSummary {
        val raw = rows.joinToString("\n") { row -> row.joinToString(" | ") { it ?: "" } }.take(SqlProbe.EXPLAIN_RAW_MAX_CHARS)
        val summary =
            runCatching {
                when (dialect) {
                    Dialect.POSTGRES -> postgres(rows)

                    Dialect.MYSQL -> mysql(columnLabels, rows)

                    Dialect.H2 -> h2(raw)

                    Dialect.DUCKDB, Dialect.LAKE -> duckdb(raw)

                    Dialect.SQLITE -> sqlite(columnLabels, rows)

                    // ORACLE and MSSQL declare no EXPLAIN wrapper, so the probe never calls here.
                    Dialect.ORACLE, Dialect.MSSQL -> null
                }
            }.getOrNull()
        return summary?.copy(raw = raw) ?: ExplainPlanSummary(scan = null, estimatedRows = null, raw = raw, null, null)
    }

    /**
     * Postgres: the scan signal lives at the DEEPEST node, not the root — an aggregate-rooted
     * plan (`Finalize GroupAggregate ... -> Parallel Index Scan using idx_x ...`) still answers
     * `index:idx_x`. Any `Seq Scan on` line anywhere in the plan answers `seq` — a full scan of
     * one table in a multi-node plan is exactly the timeout this field exists to warn about.
     * The row estimate is the root's `rows=N` (the output cardinality).
     */
    private fun postgres(rows: List<List<String?>>): ExplainPlanSummary {
        val lines =
            rows.mapNotNull {
                it
                    .firstOrNull()
                    ?.trim()
                    ?.removePrefix("->")
                    ?.trim()
            }
        val root = lines.firstOrNull().orEmpty()
        val scan =
            when {
                lines.any { PG_SEQ.containsMatchIn(it) } -> {
                    "seq"
                }

                else -> {
                    lines.firstNotNullOfOrNull { PG_INDEX.find(it) }?.let { "index:${it.groupValues[1]}" }
                        ?: root.take(SCAN_FALLBACK_CHARS)
                }
            }
        return ExplainPlanSummary(scan, PG_ROWS.find(root)?.groupValues?.get(1), raw = "", null, null)
    }

    /**
     * MySQL: the first EXPLAIN row's `type` — `ALL` is the full scan; anything else with a
     * non-null `key` names the index it reads. The estimate is the `rows` column.
     */
    private fun mysql(
        columnLabels: List<String>,
        rows: List<List<String?>>,
    ): ExplainPlanSummary {
        val labels = columnLabels.map { it.lowercase() }
        val first = rows.firstOrNull().orEmpty()

        fun cell(label: String): String? = labels.indexOf(label).takeIf { it >= 0 }?.let { first.getOrNull(it) }
        val type = cell("type")
        val key = cell("key")?.takeUnless { it.isBlank() }
        val scan =
            when {
                type == null -> null
                type.equals("ALL", ignoreCase = true) -> "seq"
                key != null -> "index:$key"
                else -> type
            }
        return ExplainPlanSummary(scan, cell("rows"), raw = "", null, null)
    }

    /**
     * H2: EXPLAIN emits the rewritten statement whose access path rides in `/* ... */`
     * comments — `/* PUBLIC.TRIPS.tableScan */` for a full scan, `/* PUBLIC.IDX_CITY: CITY = 'x'
     * */` for an index lookup (the condition follows the colon), `/* PUBLIC.IDX_CITY */` for an
     * index-ordered scan (verified against h2 2.3.232). H2's EXPLAIN carries no row estimate.
     */
    private fun h2(raw: String): ExplainPlanSummary {
        val comment =
            H2_ACCESS_COMMENT
                .find(raw)
                ?.groupValues
                ?.get(1)
                ?.trim()
        val scan =
            when {
                comment == null -> null
                comment.endsWith(".tableScan") -> "seq"
                else -> "index:${comment.substringBefore(':').trim()}"
            }
        return ExplainPlanSummary(scan, estimatedRows = null, raw = "", null, null)
    }

    /**
     * DuckDB (and LAKE): the box-drawing plan text, normalized by stripping the drawing
     * characters. The pruning marker is `Scanning Files: x/y` — files selected by static
     * partition/file filters over total files in the scan; the row estimate is the first
     * `~N rows` node annotation (the root's). With no file filter the marker is ABSENT (and a
     * filter selecting every file is optimized away entirely) — both partitions fields stay
     * null, which is the honest "no pruning information", not "all files".
     */
    private fun duckdb(raw: String): ExplainPlanSummary {
        val lines = raw.lines().map { it.replace(BOX_CHARS, "").trim() }
        val files = lines.firstNotNullOfOrNull { DUCKDB_FILES.find(it) }
        val scanned = files?.groupValues?.get(1)?.toIntOrNull()
        val total = files?.groupValues?.get(2)?.toIntOrNull()
        val scan =
            if (scanned != null && total != null) {
                "partition_prune $scanned/$total"
            } else {
                lines.firstOrNull { it in DUCKDB_SCAN_NODES }
            }
        val estimate =
            lines
                .firstNotNullOfOrNull { DUCKDB_ROWS.find(it) }
                ?.groupValues
                ?.get(1)
                ?.replace(",", "")
        return ExplainPlanSummary(scan, estimate, raw = "", scanned, total)
    }

    /**
     * SQLite: `EXPLAIN QUERY PLAN` rows carry a `detail` column — `SCAN trips` is the full
     * scan, `SEARCH trips USING [COVERING] INDEX <name> (...)` an index read (verified against
     * sqlite-jdbc 3.49.1.0). No row estimates.
     */
    private fun sqlite(
        columnLabels: List<String>,
        rows: List<List<String?>>,
    ): ExplainPlanSummary {
        val detailIndex = columnLabels.indexOfFirst { it.equals("detail", ignoreCase = true) }
        val detail = rows.firstNotNullOfOrNull { it.getOrNull(detailIndex) }?.trim()
        val scan =
            when {
                detail == null -> null
                detail.startsWith("SCAN ") -> "seq"
                else -> SQLITE_INDEX.find(detail)?.let { "index:${it.groupValues[1]}" } ?: detail.take(SCAN_FALLBACK_CHARS)
            }
        return ExplainPlanSummary(scan, estimatedRows = null, raw = "", null, null)
    }

    private const val SCAN_FALLBACK_CHARS = 40

    private val PG_SEQ = Regex("""^(?:Parallel )?Seq Scan\b""")
    private val PG_INDEX = Regex("""^(?:Parallel\s+)?(?:Index (?:Only )?Scan|Bitmap Index Scan) using (\S+)""")
    private val PG_ROWS = Regex("""rows=(\d+)""")
    private val H2_ACCESS_COMMENT = Regex("""/\*\s*([^*]+?)\s*\*/""")
    private val BOX_CHARS = Regex("""[│┌┐└┘┬┴─]""")
    private val DUCKDB_FILES = Regex("""Scanning Files: (\d+)/(\d+)""")
    private val DUCKDB_ROWS = Regex("""~([\d,]+) rows""")
    private val SQLITE_INDEX = Regex("""^SEARCH \S+ USING (?:COVERING )?INDEX (\S+)""")
    private val DUCKDB_SCAN_NODES = setOf("READ_PARQUET", "TABLE_SCAN", "SEQ_SCAN", "INDEX_SCAN")
}
