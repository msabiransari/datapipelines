package co.datapipelines.datasources

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.TypeMappingWarning

/*
 * The §7A introspection payloads (datasources.md §7A), split out of `SchemaIntrospector` to
 * keep that reader under the house 300-line rule. The wire projections of these shapes live
 * in `SchemaWire.kt` beside them — one home per payload, shared verbatim by both surfaces.
 */

/** The §7A tables listing: the kept tables plus whether the cap dropped any. */
data class TablesPage(
    val tables: List<TableInfo>,
    val truncated: Boolean,
)

/**
 * One namespace a caller can pass back as a filter: the ordered path plus the label to show.
 *
 * [namespace] is outermost-first and as deep as the dialect's [NamespaceShape] reports — one
 * segment on MySQL and Oracle, two on Postgres/H2 (`[database, schema]`) and on a lake
 * (`[catalog, schema]`), and the same two on every warehouse this contract was designed against.
 * [label] is its last segment: the name an operator recognises, and what the pre-087 wire's
 * `schemas` array carried.
 */
data class SchemaEntry(
    val namespace: List<String>,
    val label: String,
)

/**
 * The §7A schemas listing: the kept namespaces plus whether the cap dropped any. A plain
 * list of names was the v2.2 shape; the page (v2.6) mirrors TablesPage because the listing
 * walks `getCatalogs()`/`getSchemas()` under the pooled lease and on MySQL catalog routing
 * that is every database the server grants — bounded like tables() or not at all.
 *
 * Entries became NAMESPACES in 087: two `sales` schemas in two catalogs are two different
 * places, and a listing of bare names merged them into one — the defect the contract audit
 * inferred and a DuckDB probe then reproduced (`a1.sales` and `a2.sales` both listing as
 * `sales`, and an unqualified `getColumns` returning both tables' columns as one).
 */
data class SchemasPage(
    val entries: List<SchemaEntry>,
    val truncated: Boolean,
) {
    /** The pre-087 projection: each entry's label, in order. Kept for one release, derived. */
    val schemas: List<String> get() = entries.map { it.label }

    companion object {
        /** A page of single-segment namespaces — the shape a one-level dialect produces. */
        fun ofLabels(
            labels: List<String>,
            truncated: Boolean = false,
        ): SchemasPage = SchemasPage(labels.map { SchemaEntry(listOf(it), it) }, truncated)
    }
}

/**
 * One live table/view: `type` is the raw JDBC table type (`TABLE`, `VIEW`, ...); `remarks` is
 * the engine-stored comment from JDBC REMARKS, null when the driver/database has none.
 *
 * [namespace] is the containing path, outermost first — `["app", "public"]` on Postgres,
 * `["my_db"]` on MySQL, `["a1", "sales"]` on a lake with two ATTACHed catalogs, empty on SQLite.
 * The fully-qualified name is `namespace + [name]`.
 */
data class TableInfo(
    val namespace: List<String>,
    val name: String,
    val type: String,
    val remarks: String? = null,
) {
    /**
     * The pre-087 field, DERIVED: the LAST segment of [namespace], null when there is none.
     *
     * Kept for one release under the frozen-shape rule (datasources.md §12.1) so no client breaks
     * on the day `namespace` appears. It is the last segment and not the last-but-one because
     * `namespace` is the CONTAINER path — the table's own name is [name], and `SchemaEntry`'s
     * `{namespace, label}` pins the same reading for the schemas listing.
     */
    val schema: String? get() = namespace.lastOrNull()
}

/**
 * One column: the canonical [column] descriptor plus the source type name it came from;
 * `remarks` is the engine-stored comment from JDBC REMARKS, null when there is none.
 */
data class ColumnInfo(
    val column: ColumnSchema,
    val sourceTypeName: String,
    val warnings: List<TypeMappingWarning>,
    val remarks: String? = null,
)

/**
 * One table's CATALOG statistics — the engine's own stored estimates, never a scan of the
 * table: no `COUNT(*)`, no `COUNT(DISTINCT)` anywhere on the read path. This is the answer an
 * agent needs for "how big is this, roughly" and "what is it indexed by" without paying for
 * the read.
 *
 * [rowEstimate] is a STRING under the BIGINTEGER wire convention (type-system §7.3); null when
 * the catalog has none — a table Postgres has never ANALYZEd carries `reltuples = -1`, reported
 * as null rather than as a lie. [statsAsOf] is the engine's last analyze time as an ISO instant
 * where the catalog exposes one (Postgres, Oracle). [statsSource] names the catalog the numbers
 * came from (`pg_class`, `information_schema.tables`, `parquet_metadata`, ...) — `none` when
 * the dialect has no catalog stats for this table, which is a valid answer, not an error.
 */
data class TableStats(
    val rowEstimate: String?,
    val statsAsOf: String?,
    val statsSource: String,
    val indexes: List<IndexStats>,
    val columns: List<ColumnStats>,
)

/**
 * One index as the catalog reports it, [columns] in key order. [kind] is `index` for an
 * ordinary catalog index and `partition` for a lake table's partition column — a lake has no
 * indexes; the partition column IS the access structure the engine prunes on, so it reports as
 * the pseudo-index it is.
 */
data class IndexStats(
    val name: String,
    val columns: List<String>,
    val unique: Boolean,
    val primary: Boolean,
    val kind: String = INDEX_KIND_INDEX,
) {
    companion object {
        const val INDEX_KIND_INDEX = "index"
        const val INDEX_KIND_PARTITION = "partition"
    }
}

/**
 * One column's catalog statistics. [nDistinct] is a STRING under the BIGINTEGER wire
 * convention. Postgres stores a NEGATIVE `n_distinct` as a fraction of the table; the reader
 * normalizes it to an estimate against the row estimate, and [distinctIsRatio] is true only
 * when that normalization could not run (no row estimate) — the catalog holds a ratio and the
 * wire reports null rather than a guess. [min]/[max] are the catalog's histogram bounds where
 * it holds them (Postgres `pg_stats`, Parquet footers), null elsewhere.
 */
data class ColumnStats(
    val name: String,
    val nDistinct: String?,
    val distinctIsRatio: Boolean,
    val nullFraction: Double?,
    val min: String?,
    val max: String?,
)
