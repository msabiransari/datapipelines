package co.datapipelines.datasources

/**
 * The dp-lake catalog, as the `datasources` module sees it (metadata-db §4.15, the 2026-09-07
 * lake-datasource design record §2, round 089 §B/§C): the registered tables of ONE LAKE-dialect
 * datasource, read behind a port because the registry's rows live in the metadata DB — a store
 * this module cannot reach (module-structure §4.2: `datasources` depends on `typesystem` alone).
 *
 * ONE port serves two readers, deliberately:
 *
 * - **Phase B** (`DefaultDatasourceRegistry`'s pool factory): the rows become the per-table
 *   `CREATE VIEW` statements [LakeViewStatements] generates into a new pool's
 *   `connectionInitSql`.
 * - **Phase C** ([SchemaIntrospector]'s LAKE branches): the rows ARE the schema/tables listing —
 *   a LAKE datasource's tables are exactly its registry rows, so JDBC metadata has nothing to
 *   add.
 *
 * The row carries exactly what both readers need and no more: the namespace segments, the table
 * name, the format as its wire string (`parquet` / `iceberg` — the value the view generator maps
 * to `read_parquet(...)` versus `iceberg_scan(...)`), and the location. Validation is NOT this
 * type's job: rows arrive validated from the registry, and [LakeViewStatements] re-refuses a bad
 * location at the SQL-emission boundary regardless (defense in depth, never trust a stored row).
 */
data class LakeRegisteredTable(
    /** 087's namespace segments, outermost first — `["nyc", "mobility"]`. One to nine segments. */
    val namespace: List<String>,
    val name: String,
    /** The metadata-db §4.15 wire value: `parquet` or `iceberg`. */
    val format: String,
    /** `s3://bucket/prefix[/glob]` (Iceberg: the current metadata FILE, not the table root — datasources.md §8C.7) or a `file://` path. */
    val location: String,
    /**
     * 109 §A (V20) — the connect-time view creation's last recorded failure, NULL when the
     * view last built cleanly. The pool factory's per-table view application reads this to
     * record TRANSITIONS only (an unchanged outcome writes nothing), and the executor reads
     * it to fail a node that references a broken table with `datasource.lake.table_unavailable`
     * instead of the engine's raw "table not found".
     */
    val lastError: String? = null,
)

/**
 * The port the assembling layer implements over the metadata DB's `lake_tables` repository (the
 * [DatasourceReferences] precedent: `datasources` declares the question, `web` wires the answer).
 * The default [NONE] answers "no registered tables", which is also the truthful answer for every
 * non-LAKE datasource — both callers branch on the dialect before consulting it.
 */
fun interface LakeTableCatalog {
    /** The datasource's registered tables, in tree order (namespace, then name). */
    fun registeredTables(datasourceName: String): List<LakeRegisteredTable>

    companion object {
        /** No registry behind this module — tests, and any deployment without the wiring. */
        val NONE = LakeTableCatalog { emptyList() }
    }
}

/**
 * The write half of 109 §A's view-outcome recording — a SEPARATE port from [LakeTableCatalog]
 * on purpose: the catalog is a `fun interface` with lambda implementations in callers and test
 * fixtures this module cannot all see, so growing it would break them; a new seam keeps every
 * existing implementation source-compatible. The assembling layer implements it over
 * `LakeTableRepository.recordViewOutcome`.
 *
 * Callers (the pool factory's per-connection view application) invoke [record] on TRANSITIONS
 * only — error text changed, or success after an error — never per connection, so the hot
 * path stays write-free. [error] is the bounded engine/emission message; `null` is the healthy
 * outcome and CLEARS the row's recorded error.
 */
fun interface LakeViewOutcomeRecorder {
    /** Records [error] (null = healthy) as the outcome for the (datasource, namespace, table) triple. */
    fun record(
        datasourceName: String,
        namespace: List<String>,
        table: String,
        error: String?,
    )

    companion object {
        /** Records nothing — tests, and any deployment without the wiring. */
        val NONE = LakeViewOutcomeRecorder { _, _, _, _ -> }
    }
}

/**
 * 109 §A — one registered lake table whose connect-time view creation last FAILED, as the
 * executor's pre-execution check needs it: a node whose SQL references this table fails with
 * `datasource.lake.table_unavailable` (details `table` + `last_error`) instead of the engine's
 * raw "table not found".
 */
data class LakeBrokenTable(
    val namespace: List<String>,
    val name: String,
    /** The recorded engine/emission error — non-null by construction of the read. */
    val lastError: String,
) {
    /** The dotted qualified name — `nyc.mobility.hvfhv_zone_day`. */
    val qualifiedName: String get() = (namespace + name).joinToString(".")
}
