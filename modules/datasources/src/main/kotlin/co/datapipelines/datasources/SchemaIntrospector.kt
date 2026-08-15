package co.datapipelines.datasources

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.TypeMappingWarning
import java.sql.DatabaseMetaData

/**
 * Reads live schema metadata from a registered datasource (datasources.md §7A) via JDBC
 * [DatabaseMetaData], mapping column types through the dialect's IngressTypeMapper so agents see
 * canonical types, not driver-specific names.
 *
 * Read-only by construction: only `metaData` calls, no statements. An unknown datasource is the
 * catalogued `datasource.not_found` ([DatasourceErrorCodes.NOT_FOUND]); an unknown table/schema
 * filter matches nothing and returns empty — a filter for something that does not exist means
 * "no results", not an error (the same philosophy as `datasources_list`'s dialect filter).
 *
 * `table` and `schema` filters are **exact-match identifiers, not LIKE patterns**: `_` and `%`
 * are escaped with the driver's [DatabaseMetaData.getSearchStringEscape], so a table named
 * `order_items` cannot match its wildcard sibling `order1items`.
 */
class SchemaIntrospector(
    private val registry: DatasourceRegistry,
) {
    /** §7A — every live table/view, optionally narrowed to one schema. */
    fun tables(
        datasourceName: String,
        schemaFilter: String? = null,
    ): List<TableInfo> =
        withMetaData(datasourceName) { meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val escape = meta.searchStringEscape
            meta.getTables(null, schemaFilter?.toExactMatch(escape), "%", adapter.introspectionTableTypes.toTypedArray()).use { rs ->
                buildList {
                    while (rs.next()) {
                        val schema = rs.getString("TABLE_SCHEM")
                        if (adapter.isSystemSchema(schema)) continue
                        add(TableInfo(schema, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")))
                    }
                }
            }
        }

    /** §7A — one table's columns with canonical types; empty when the table does not exist. */
    fun columns(
        datasourceName: String,
        table: String,
        schemaFilter: String? = null,
    ): List<ColumnInfo> =
        withMetaData(datasourceName) { meta, datasource ->
            val mapper = DialectAdapters.forDialect(datasource.dialect).typeMapper
            val escape = meta.searchStringEscape
            meta.getColumns(null, schemaFilter?.toExactMatch(escape), table.toExactMatch(escape), "%").use { rs ->
                buildList {
                    while (rs.next()) {
                        val sourceTypeName = rs.getString("TYPE_NAME") ?: ""
                        val mapped =
                            mapper.mapColumn(
                                name = rs.getString("COLUMN_NAME"),
                                sqlType = rs.getInt("DATA_TYPE"),
                                precision = rs.getInt("COLUMN_SIZE"),
                                scale = rs.getInt("DECIMAL_DIGITS"),
                                typeName = sourceTypeName,
                                nullable =
                                    when (rs.getInt("NULLABLE")) {
                                        DatabaseMetaData.columnNoNulls -> false
                                        DatabaseMetaData.columnNullable -> true
                                        else -> null
                                    },
                            )
                        add(ColumnInfo(mapped.column, sourceTypeName, mapped.warnings))
                    }
                }
            }
        }

    /**
     * §7A — the whole schema in one payload, capped at [maxTables] tables (each with its
     * columns). [SchemaSnapshot.truncated] is `true` when the cap dropped tables, so a caller
     * knows to fall back to `tables` + `columns` for the remainder.
     */
    fun snapshot(
        datasourceName: String,
        maxTables: Int = MAX_SNAPSHOT_TABLES,
    ): SchemaSnapshot {
        val datasource = registry.get(datasourceName) ?: throw notFound(datasourceName)
        val all = tables(datasourceName)
        val kept = all.take(maxTables)
        return SchemaSnapshot(
            datasource = datasourceName,
            dialect = datasource.dialect.wire,
            tables = kept.map { TableWithColumns(it, columns(datasourceName, it.name, it.schema)) },
            truncated = all.size > kept.size,
        )
    }

    private fun <T> withMetaData(
        datasourceName: String,
        block: (DatabaseMetaData, Datasource) -> T,
    ): T {
        val datasource = registry.get(datasourceName) ?: throw notFound(datasourceName)
        return registry.poolFor(datasource).leaseConnection().use { block(it.metaData, datasource) }
    }

    /** [DialectAdapter.introspectionSystemSchemas], matched case-insensitively (null = not a system schema). */
    private fun DialectAdapter.isSystemSchema(schema: String?): Boolean =
        schema != null && schema.lowercase() in introspectionSystemSchemas

    private fun notFound(name: String): DatapipelinesException =
        DatapipelinesException(
            code = DatasourceErrorCodes.NOT_FOUND,
            message = "Datasource '$name' is not registered in this environment.",
            details = mapOf("datasource" to name),
        )

    /**
     * Escapes `_`, `%` and the escape character itself so the string matches **only itself**
     * as a JDBC metadata name pattern (the driver's `getSearchStringEscape` says how to escape).
     */
    private fun String.toExactMatch(escape: String): String =
        buildString {
            this@toExactMatch.forEach { ch ->
                if (ch == '_' || ch == '%' || (escape.isNotEmpty() && ch == escape[0])) append(escape)
                append(ch)
            }
        }

    private companion object {
        /** The §7A snapshot cap — bounds one `datasources_get_schema` call's payload. */
        const val MAX_SNAPSHOT_TABLES = 200
    }
}

/** One live table/view: `type` is the raw JDBC table type (`TABLE`, `VIEW`, ...). */
data class TableInfo(
    val schema: String?,
    val name: String,
    val type: String,
)

/** One column: the canonical [column] descriptor plus the source type name it came from. */
data class ColumnInfo(
    val column: ColumnSchema,
    val sourceTypeName: String,
    val warnings: List<TypeMappingWarning>,
)

/** A table with its columns, as carried by [SchemaSnapshot]. */
data class TableWithColumns(
    val table: TableInfo,
    val columns: List<ColumnInfo>,
)

/** The whole-schema payload of `datasources_get_schema` / `GET .../schema` (§7A). */
data class SchemaSnapshot(
    val datasource: String,
    val dialect: String,
    val tables: List<TableWithColumns>,
    val truncated: Boolean,
)
