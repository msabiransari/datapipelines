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
 * The §7A schemas listing: the kept schema names plus whether the cap dropped any. A plain
 * list of names was the v2.2 shape; the page (v2.6) mirrors TablesPage because the listing
 * walks `getCatalogs()`/`getSchemas()` under the pooled lease and on MySQL catalog routing
 * that is every database the server grants — bounded like tables() or not at all.
 */
data class SchemasPage(
    val schemas: List<String>,
    val truncated: Boolean,
)

/**
 * One live table/view: `type` is the raw JDBC table type (`TABLE`, `VIEW`, ...); `remarks` is
 * the engine-stored comment from JDBC REMARKS, null when the driver/database has none.
 */
data class TableInfo(
    val schema: String?,
    val name: String,
    val type: String,
    val remarks: String? = null,
)

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
