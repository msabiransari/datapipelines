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
