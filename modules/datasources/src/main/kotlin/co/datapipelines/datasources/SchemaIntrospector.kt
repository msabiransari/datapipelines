package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.IngressTypeMapper
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException

/**
 * Reads live schema metadata from a registered datasource (datasources.md §7A) via JDBC
 * [DatabaseMetaData], mapping column types through the dialect's IngressTypeMapper so agents see
 * canonical types, not driver-specific names. The introspection flow is
 * [schemas] → [tables] → [columns]: nothing bundles columns into a table listing.
 *
 * Read-only by construction: only `metaData` calls, no statements. An unknown datasource is the
 * catalogued `datasource.not_found` ([DatasourceErrorCodes.NOT_FOUND]); an unknown table/schema
 * filter matches nothing and returns empty — a filter for something that does not exist means
 * "no results", not an error (the same philosophy as `datasources_list`'s dialect filter).
 *
 * `table` and `schema` filters are **exact-match identifiers, not LIKE patterns**: `_` and `%`
 * are escaped with the driver's [DatabaseMetaData.getSearchStringEscape], so a table named
 * `order_items` cannot match its wildcard sibling `order1items`. The escape applies only to
 * the true pattern arguments (`schemaPattern`, `tableNamePattern`) — the **catalog argument is
 * a literal** and is never escaped, or a catalog-routing driver (Connector/J) could not select
 * a database whose stored name contains `_`/`%`.
 */
class SchemaIntrospector(
    private val registry: DatasourceRegistry,
) {
    /**
     * §7A — the schema listing, the entry point of the introspection flow (schemas → tables →
     * columns). A plain list of schema names as the driver reported them, with the dialect's
     * system schemas excluded.
     *
     * The vocabulary follows [DialectAdapter.schemaArrivesInCatalog]: for catalog-routing
     * drivers (Connector/J defaults) the databases ARE the JDBC catalogs, so the listing reads
     * `getCatalogs()`/TABLE_CAT — `getSchemas()` there reports a single blank schema. An EMPTY
     * list is a valid result, not an error: a schemaless dialect (SQLite, single-db DuckDB)
     * genuinely has no schemas to list.
     */
    fun schemas(datasourceName: String): List<String> =
        withMetaData(datasourceName) { _, meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val rs = if (adapter.schemaArrivesInCatalog) meta.catalogs else meta.schemas
            rs.use {
                buildList {
                    while (it.next()) {
                        val schema = it.getString(adapter.schemaResultColumn())
                        // The JDBC "" sentinel ("objects without a catalog") is not a schema
                        // an agent can pass to get_tables — skip it rather than list it.
                        if (schema.isNullOrBlank() || adapter.isSystemSchema(schema)) continue
                        add(schema)
                    }
                }
            }
        }

    /** §7A — live tables/views, optionally narrowed to one schema, capped at [maxTables]. */
    fun tables(
        datasourceName: String,
        schemaFilter: String? = null,
        maxTables: Int = MAX_TABLES,
    ): TablesPage =
        withMetaData(datasourceName) { _, meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            // Route FIRST, then escape only the schemaPattern side: the catalog argument is a
            // LITERAL ("must match the catalog name as it is stored"), so an escaped value
            // there matches nothing — any MySQL database named with '_'/'%'. Only true
            // pattern arguments (schemaPattern, tableNamePattern) get [toExactMatch].
            val (catalog, rawSchemaPattern) = adapter.routeSchemaFilter(schemaFilter)
            readTables(meta, adapter, catalog, rawSchemaPattern?.toExactMatch(meta.searchStringEscape), maxTables)
        }

    /**
     * §7A — one table's columns with canonical types; empty when the table does not exist.
     *
     * Without a schema filter the read defaults to the **connection's current schema** (routed
     * per dialect exactly like an explicit filter): an unfiltered `getColumns` would merge the
     * columns of same-named tables across schemas into one list. A driver that reports no
     * current schema falls back to unfiltered — with system schemas still excluded row by row.
     */
    fun columns(
        datasourceName: String,
        table: String,
        schemaFilter: String? = null,
    ): List<ColumnInfo> =
        withMetaData(datasourceName) { connection, meta, datasource ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val effectiveFilter = schemaFilter ?: connection.currentSchema(adapter)
            // Same rule as tables(): the catalog argument is a literal (never escaped — an
            // escaped `my_app` catalog matches nothing on MySQL), schemaPattern is a pattern
            // (always escaped).
            val (catalog, rawSchemaPattern) = adapter.routeSchemaFilter(effectiveFilter)
            val escape = meta.searchStringEscape
            meta.getColumns(catalog, rawSchemaPattern?.toExactMatch(escape), table.toExactMatch(escape), "%").use { rs ->
                buildList {
                    while (rs.next()) {
                        val schema = rs.getString(adapter.schemaResultColumn())
                        if (adapter.isSystemSchema(schema)) continue
                        add(mapColumnRow(rs, adapter.typeMapper))
                    }
                }
            }
        }

    /**
     * The shared getTables walk. [maxRows] caps the iteration at cap+1 `next()` calls (the +1
     * proves truncation); `null` walks everything.
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
            // Two jumps on purpose: system-schema rows are skipped WITHOUT counting against
            // the cap, and the cap+1-th USER row is the truncation proof — checking the cap
            // before the system-row test would flag truncation on a trailing system row.
            @Suppress("LoopWithTooManyJumpStatements")
            while (rs.next()) {
                val schema = rs.getString(adapter.schemaResultColumn())
                if (adapter.isSystemSchema(schema)) continue
                if (maxRows != null && out.size == maxRows) {
                    truncated = true
                    break
                }
                out.add(TableInfo(schema, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"), rs.getString("REMARKS")))
            }
        }
        return TablesPage(out, truncated)
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
        return ColumnInfo(mapped.column, sourceTypeName, mapped.warnings, rs.getString("REMARKS"))
    }

    /**
     * The lease boundary every operation reads through — and the ONE place a connection failure
     * becomes [DatasourceUnreachableException].
     *
     * Both exception families the registry's probe KDoc names are translated (see
     * `DefaultDatasourceRegistry.probe`): the `SQLException` of a refused/timed-out lease or a
     * connection that died mid-read, AND the RuntimeException family of pool construction —
     * `HikariPool.PoolInitializationException` at first lease on a down database, a missing
     * driver class, a property a driver rejects at parse time. Catching only `SQLException`
     * here (round 1) let the RuntimeException family escape as a raw 500 / -32603.
     *
     * Both catches stop at the lease-and-connection boundary: a RuntimeException thrown by the
     * metadata walk itself is a defect in this module or a driver bug, and masking it as "the
     * caller's database is unreachable" would hide it. Post-lease, the SQLException catch
     * narrows to the CONNECTION family only (SQLState class 08, the connection-exception
     * subclasses, [java.sql.SQLRecoverableException]) — any other SQLException from a metadata
     * read is likewise a defect and propagates. `Error` is never caught.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> withMetaData(
        datasourceName: String,
        block: (Connection, DatabaseMetaData, Datasource) -> T,
    ): T {
        val datasource = registry.get(datasourceName) ?: throw notFound(datasourceName)
        val connection =
            try {
                registry.poolFor(datasource).leaseConnection()
            } catch (e: SQLException) {
                unreachable(datasourceName, e)
            } catch (e: RuntimeException) {
                unreachable(datasourceName, e)
            }
        return try {
            connection.use { block(it, it.metaData, datasource) }
        } catch (e: SQLException) {
            // Post-lease, only the CONNECTION family means "the database went away" — any
            // other SQLException from a metadata read is a defect in this module or a driver
            // bug and propagates, mirroring the RuntimeException policy below.
            if (e.isConnectionFailure()) unreachable(datasourceName, e) else throw e
        }
    }

    /** The single throw point of the lease boundary's translation (keeps [withMetaData] under ThrowsCount). */
    private fun unreachable(
        datasourceName: String,
        cause: Throwable,
    ): Nothing = throw DatasourceUnreachableException(datasourceName, cause)

    /**
     * [DialectAdapter.introspectionSystemSchemas]: exact names match case-insensitively; an
     * entry ending in `*` matches by case-insensitive PREFIX (Oracle's versioned `apex_*`
     * schemas). Null is never a system schema.
     */
    private fun DialectAdapter.isSystemSchema(schema: String?): Boolean {
        if (schema == null) return false
        val lower = schema.lowercase()
        return introspectionSystemSchemas.any { entry ->
            if (entry.endsWith("*")) lower.startsWith(entry.dropLast(1)) else lower == entry
        }
    }

    /**
     * The connection-failure family of a post-lease [SQLException]: SQLState class `08`
     * (connection exception), the JDBC connection-exception subclasses, or
     * [java.sql.SQLRecoverableException] (whose subclasses include the connection-died-mid-read
     * family some drivers raise). Everything else is NOT a connection failure.
     */
    private fun SQLException.isConnectionFailure(): Boolean =
        sqlState?.startsWith("08") == true ||
            this is java.sql.SQLTransientConnectionException ||
            this is java.sql.SQLNonTransientConnectionException ||
            this is java.sql.SQLRecoverableException

    /**
     * Where the escaped schema filter goes: the catalog argument for drivers that carry the
     * database there ([DialectAdapter.schemaArrivesInCatalog]), the schemaPattern otherwise.
     */
    private fun DialectAdapter.routeSchemaFilter(filter: String?): Pair<String?, String?> =
        if (schemaArrivesInCatalog) filter to null else null to filter

    /** The result column that carries the schema: TABLE_CAT for catalog-routing drivers, TABLE_SCHEM otherwise. */
    private fun DialectAdapter.schemaResultColumn(): String = if (schemaArrivesInCatalog) "TABLE_CAT" else "TABLE_SCHEM"

    /**
     * The connection's current schema, in this dialect's own vocabulary: the **catalog** for
     * catalog-routing drivers (Connector/J keeps the current database there and leaves
     * `getSchema()` null), `getSchema()` for everyone else. Null when the driver reports none
     * — and the JDBC blank sentinel counts as none: `""` means "objects without a
     * catalog/schema", not a schema named `""`, so the caller reads unfiltered rather than
     * filtering on a name that matches nothing.
     */
    private fun Connection.currentSchema(adapter: DialectAdapter): String? =
        try {
            if (adapter.schemaArrivesInCatalog) catalog else schema
        } catch (_: SQLException) {
            null
        }?.takeUnless { it.isNullOrEmpty() }

    private fun notFound(name: String): DatapipelinesException =
        DatapipelinesException(
            code = DatasourceErrorCodes.NOT_FOUND,
            message = "Datasource '$name' is not registered in this environment.",
            details = mapOf("datasource" to name),
        )

    /**
     * Escapes `_`, `%` and the escape character itself so the string matches **only itself**
     * as a JDBC metadata name pattern (the driver's `getSearchStringEscape` says how to escape).
     * An empty escape string means the driver defines none — the name passes through as-is.
     */
    private fun String.toExactMatch(escape: String): String {
        if (escape.isEmpty()) return this
        val escapeChar = escape[0]
        return buildString {
            this@toExactMatch.forEach { ch ->
                if (ch == '_' || ch == '%') {
                    append(escape)
                } else if (ch == escapeChar) {
                    append(escape)
                }
                append(ch)
            }
        }
    }

    private companion object {
        /** The §7A tables-listing cap — bounds one `datasources_get_tables` call's payload. */
        const val MAX_TABLES = 2000
    }
}
