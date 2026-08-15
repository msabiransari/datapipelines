package co.datapipelines.datasources

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.IngressTypeMapper
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
    /** §7A — live tables/views, optionally narrowed to one schema, capped at [maxTables]. */
    fun tables(
        datasourceName: String,
        schemaFilter: String? = null,
        maxTables: Int = MAX_TABLES,
    ): TablesPage =
        withMetaData(datasourceName) { meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val (catalog, schemaPattern) = adapter.routeSchemaFilter(schemaFilter?.toExactMatch(meta.searchStringEscape))
            readTables(meta, adapter, catalog, schemaPattern, maxTables)
        }

    /** §7A — one table's columns with canonical types; empty when the table does not exist. */
    fun columns(
        datasourceName: String,
        table: String,
        schemaFilter: String? = null,
    ): List<ColumnInfo> =
        withMetaData(datasourceName) { meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val (catalog, schemaPattern) = adapter.routeSchemaFilter(schemaFilter?.toExactMatch(meta.searchStringEscape))
            meta.getColumns(catalog, schemaPattern, table.toExactMatch(meta.searchStringEscape), "%").use { rs ->
                buildList {
                    while (rs.next()) add(mapColumnRow(rs, adapter.typeMapper))
                }
            }
        }

    /**
     * §7A — the whole schema in one payload, capped at [maxTables] tables (each with its
     * columns). [SchemaSnapshot.truncated] is `true` when the cap dropped tables, so a caller
     * knows to fall back to `tables` + `columns` for the remainder.
     *
     * **One connection lease**: `getTables` plus a single bulk `getColumns(catalog, schema,
     * "%", "%")` grouped by (schema, table) in memory. Leasing per table (up to 201 leases for
     * a full snapshot) would starve the pool and read the schema across connections that can
     * disagree mid-flight; one lease keeps the snapshot read-consistent.
     */
    fun snapshot(
        datasourceName: String,
        maxTables: Int = MAX_SNAPSHOT_TABLES,
    ): SchemaSnapshot =
        withMetaData(datasourceName) { meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val all = readTables(meta, adapter, null, null).tables
            val kept = all.take(maxTables)
            val columnsByTable = readAllColumns(meta, adapter)
            SchemaSnapshot(
                datasource = datasourceName,
                dialect = datasource.dialect.wire,
                tables = kept.map { TableWithColumns(it, columnsByTable[it.key()].orEmpty()) },
                truncated = all.size > kept.size,
            )
        }

    /**
     * The shared getTables walk. [maxRows] caps the iteration at cap+1 `next()` calls (the +1
     * proves truncation) — `null` walks everything (the snapshot path, which caps afterwards).
     */
    private fun readTables(
        meta: DatabaseMetaData,
        adapter: DialectAdapter,
        catalog: String?,
        schemaPattern: String?,
        maxRows: Int? = null,
    ): TablesPage {
        val out = mutableListOf<TableInfo>()
        var truncated = false
        meta.getTables(catalog, schemaPattern, "%", adapter.introspectionTableTypes.toTypedArray()).use { rs ->
            while (rs.next()) {
                val schema = rs.getString(adapter.schemaResultColumn())
                if (adapter.isSystemSchema(schema)) continue
                if (maxRows != null && out.size == maxRows) {
                    truncated = true
                    break
                }
                out.add(TableInfo(schema, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")))
            }
        }
        return TablesPage(out, truncated)
    }

    /** The bulk column read behind [snapshot], grouped by the (schema, table) the tables listing reports. */
    private fun readAllColumns(
        meta: DatabaseMetaData,
        adapter: DialectAdapter,
    ): Map<Pair<String?, String>, List<ColumnInfo>> =
        meta.getColumns(null, null, "%", "%").use { rs ->
            buildList<Pair<Pair<String?, String>, ColumnInfo>> {
                while (rs.next()) {
                    val schema = rs.getString(adapter.schemaResultColumn())
                    if (adapter.isSystemSchema(schema)) continue
                    add(schema to rs.getString("TABLE_NAME") to mapColumnRow(rs, adapter.typeMapper))
                }
            }.groupBy({ it.first }, { it.second })
        }

    private fun mapColumnRow(
        rs: java.sql.ResultSet,
        mapper: IngressTypeMapper,
    ): ColumnInfo {
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
        return ColumnInfo(mapped.column, sourceTypeName, mapped.warnings)
    }

    /** The lookup key [snapshot] joins the tables listing and the bulk column read on. */
    private fun TableInfo.key(): Pair<String?, String> = schema to name

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

    /**
     * Where the escaped schema filter goes: the catalog argument for drivers that carry the
     * database there ([DialectAdapter.schemaArrivesInCatalog]), the schemaPattern otherwise.
     */
    private fun DialectAdapter.routeSchemaFilter(filter: String?): Pair<String?, String?> =
        if (schemaArrivesInCatalog) filter to null else null to filter

    /** The result column that carries the schema: TABLE_CAT for catalog-routing drivers, TABLE_SCHEM otherwise. */
    private fun DialectAdapter.schemaResultColumn(): String = if (schemaArrivesInCatalog) "TABLE_CAT" else "TABLE_SCHEM"

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
        /** The §7A tables-listing cap — bounds one `datasources_get_tables` call's payload. */
        const val MAX_TABLES = 2000

        /** The §7A snapshot cap — bounds one `datasources_get_schema` call's payload. */
        const val MAX_SNAPSHOT_TABLES = 200
    }
}

/** The §7A tables listing: the kept tables plus whether the cap dropped any. */
data class TablesPage(
    val tables: List<TableInfo>,
    val truncated: Boolean,
)

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
