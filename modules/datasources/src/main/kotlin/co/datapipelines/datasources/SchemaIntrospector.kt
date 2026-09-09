package co.datapipelines.datasources

import co.datapipelines.datasources.Namespaces.toExactMatch
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.IngressTypeMapper
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSetMetaData

/**
 * Reads live schema metadata from a registered datasource (datasources.md §7A) via JDBC
 * [DatabaseMetaData], mapping column types through the dialect's IngressTypeMapper so agents see
 * canonical types, not driver-specific names. The introspection flow is
 * [schemas] → [tables] → [columns]: nothing bundles columns into a table listing.
 *
 * Read-only by construction: `metaData` calls, plus statements that are themselves metadata
 * reads — [lakeColumns]'s zero-row scan and [tableStats]'s catalog queries (§7C, assembled in
 * [TableStatsReader]; this class is at the house size ceiling). An unknown datasource is the
 * catalogued `datasource.not_found` ([DatasourceErrorCodes.NOT_FOUND]); an unknown table/schema
 * filter matches nothing and returns empty — a filter for something that does not exist means
 * "no results", not an error (the same philosophy as `datasources_list`'s dialect filter).
 *
 * `table`, `schema` and `namespace` filters are **exact-match identifiers, not LIKE patterns**:
 * `_` and `%` are escaped with the driver's [DatabaseMetaData.getSearchStringEscape], so a table
 * named `order_items` cannot match its wildcard sibling `order1items`. The escape applies only to
 * the true pattern arguments (`schemaPattern`, `tableNamePattern`) — the **catalog argument is
 * a literal** and is never escaped, or a catalog-routing driver (Connector/J) could not select
 * a database whose stored name contains `_`/`%`. Routing and parsing live in [Namespaces].
 *
 * ## Namespaces, and the merge this round removed (087)
 *
 * Every read is qualified by the dialect's [NamespaceShape], not by "a schema". The catalog
 * argument used to be a hard-coded `null` on all three operations, which is correct only while a
 * connection has exactly one catalog. It does not on a DuckDB lake with two ATTACHed files, and
 * the consequence was measured rather than inferred (2026-09-07, duckdb_jdbc 1.5.5.1): two
 * catalogs each holding a `sales.orders` listed as two indistinguishable `sales` schemas, and
 * `getColumns(null, "sales", "orders")` returned BOTH tables' columns as if they were one table's.
 * The same read qualified by catalog returns each table's own columns.
 */
class SchemaIntrospector(
    private val registry: DatasourceRegistry,
    private val lakeTables: LakeTableCatalog = LakeTableCatalog.NONE,
    private val lakeCache: LakeIntrospectionCache = LakeIntrospectionCache.NONE,
) {
    /** The §7C engine — same registry and lake ports as this reader. */
    private val statsReader = TableStatsReader(registry, lakeTables, lakeCache)

    /**
     * §7A — the namespace listing, the entry point of the introspection flow (schemas → tables →
     * columns). Each entry is the ordered path a caller can pass back as a filter plus the label
     * to show, with the dialect's system schemas excluded, capped at [maxSchemas]
     * (`truncated: true` when the cap dropped any — the same cap+1 early-exit discipline as
     * [tables]).
     *
     * The vocabulary follows the dialect's [NamespaceShape]: for catalog-routing drivers
     * (Connector/J defaults) the databases ARE the JDBC catalogs, so the listing reads
     * `getCatalogs()`/TABLE_CAT — `getSchemas()` there reports a single blank schema. For every
     * other dialect it reads `getSchemas()`, whose TABLE_CATALOG column is what makes two
     * same-named schemas in different catalogs two DIFFERENT entries. An EMPTY list is a valid
     * result, not an error: a flat dialect (SQLite) genuinely has no namespaces to list.
     */
    fun schemas(
        datasourceName: String,
        maxSchemas: Int = MAX_LISTING_ROWS,
    ): SchemasPage = schemas(registry.get(datasourceName) ?: throw notFound(datasourceName), maxSchemas)

    /**
     * §7A for an ALREADY-GATED [datasource] (025 C3, the §5.3 surfaces): the caller's
     * visibility gate resolved this snapshot; introspecting it — instead of re-resolving
     * the name through the registry's unscoped [DatasourceRegistry.get] — is what closes
     * the gate-then-re-resolve TOCTOU (a re-bind between the two would introspect a
     * datasource the gate now refuses). The pool build still re-reads the credential by
     * primary key; datasource names are never reused, so the row is the row the gate saw.
     */
    fun schemas(
        datasource: Datasource,
        maxSchemas: Int = MAX_LISTING_ROWS,
    ): SchemasPage {
        if (datasource.dialect == Dialect.LAKE) return lakeSchemas(datasource, maxSchemas)
        return withMetaData(datasource) { _, meta, _ ->
            val shape = DialectAdapters.forDialect(datasource.dialect).namespaceShape
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val rs = if (shape.innermostArrivesInCatalog) meta.catalogs else meta.schemas
            val exempt = datasource.introspectionIncludeSchemas.toSet()
            rs.use {
                val out = mutableListOf<SchemaEntry>()
                var truncated = false
                // Same jump discipline as readTables (whose suppression this mirrors): system
                // rows are skipped WITHOUT counting against the cap, and the cap+1-th USER
                // row is the truncation proof.
                @Suppress("LoopWithTooManyJumpStatements")
                while (it.next()) {
                    // The JDBC "" sentinel ("objects without a catalog") is not a schema
                    // an agent can pass to get_tables — skip it rather than list it.
                    val label = it.getString(shape.innermostResultColumn).asNonBlankOrNull() ?: continue
                    // getSchemas() reports the owning catalog in TABLE_CATALOG (getCatalogs() has
                    // no second column, and its single value IS the innermost level). That column
                    // is what keeps `a1.sales` and `a2.sales` two entries instead of one.
                    val namespace = listOfNotNull(if (shape.hasOuterCatalog) it.catalogOf() else null, label)
                    if (adapter.isSystemSchema(namespace, exempt)) continue
                    if (out.size == maxSchemas) {
                        truncated = true
                        break
                    }
                    out.add(SchemaEntry(namespace, label))
                }
                SchemasPage(out, truncated)
            }
        }
    }

    /**
     * §7A for a LAKE datasource — the dp-lake registry IS the schema listing (089 §C): the
     * engine cannot LIST a bucket, so the distinct namespaces of the registered tables are the
     * whole answer, labeled per the adapter's [NamespaceShape.CATALOG_AND_SCHEMA] (first segment
     * the catalog, second the schema — `SchemaEntry`'s label is the innermost). Served from
     * [lakeCache]; `LakeTableRegistryService.refreshConnections` invalidates on every mutation.
     */
    private fun lakeSchemas(
        datasource: Datasource,
        maxSchemas: Int,
    ): SchemasPage =
        lakeCache.get(datasource.name, "schemas", "") {
            val namespaces = lakeTables.registeredTables(datasource.name).map { it.namespace }.distinct()
            SchemasPage(
                namespaces.take(maxSchemas).map { SchemaEntry(it, it.last()) },
                truncated = namespaces.size > maxSchemas,
            )
        }

    /**
     * §7A — live tables/views, optionally narrowed to one schema, capped at [maxTables].
     *
     * Without a schema filter the listing **spans schemas** (each row carries its own) — a
     * listing cannot merge anything, so there is no current-schema default and no
     * unknown-current-schema guard here, unlike [columns] where an unfiltered read would
     * merge same-named tables' columns across schemas (the hazard lives there). tables()
     * never consults the connection's current schema at all.
     */
    fun tables(
        datasourceName: String,
        schemaFilter: String? = null,
        maxTables: Int = MAX_LISTING_ROWS,
        namespaceFilter: List<String>? = null,
    ): TablesPage = tables(registry.get(datasourceName) ?: throw notFound(datasourceName), schemaFilter, maxTables, namespaceFilter)

    /** §7A for an already-gated [datasource] — see [schemas]'s C3 note. */
    fun tables(
        datasource: Datasource,
        schemaFilter: String? = null,
        maxTables: Int = MAX_LISTING_ROWS,
        namespaceFilter: List<String>? = null,
    ): TablesPage {
        // The caller's filter goes through the same blank-sentinel rule as driver-reported
        // values (Spring binds `?schema=` to non-null ""): blank means ABSENT — spans
        // namespaces — never the JDBC '' sentinel, which matches nothing on any dialect.
        val filter = Namespaces.filterOf(namespaceFilter, schemaFilter)
        if (datasource.dialect == Dialect.LAKE) return lakeTablesPage(datasource, filter, maxTables)
        return withMetaData(datasource) { _, meta, _ ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            // A filter deeper than the dialect's namespace names no real place: empty, not an
            // error, exactly like an unknown schema (Namespaces.route returns null for it).
            val routed = Namespaces.route(adapter.namespaceShape, filter, meta) ?: return@withMetaData TablesPage(emptyList(), false)
            readTables(
                meta,
                adapter,
                routed.first,
                routed.second,
                maxTables,
                datasource.introspectionIncludeSchemas.toSet(),
            )
        }
    }

    /**
     * §7A for a LAKE datasource — the registry's rows in the requested namespace (089 §C). The
     * filter matches a namespace EXACTLY: `["nyc"]` names the one-segment namespace, not every
     * namespace under a `nyc` head — a prefix match would silently merge `nyc` and
     * `nyc.mobility` into one listing. A filter deeper than the two-segment shape names no real
     * place (empty, the [Namespaces.route] rule). Each row reports type `VIEW` — that is what
     * the engine object IS (089 §B) — with the registry's format (`parquet`/`iceberg`) in
     * `remarks`, so `datasources_get_tables` shows both without a new wire field.
     */
    private fun lakeTablesPage(
        datasource: Datasource,
        filter: List<String>,
        maxTables: Int,
    ): TablesPage =
        lakeCache.get(datasource.name, "tables", filter.joinToString(Namespaces.SEPARATOR.toString())) {
            if (filter.size > MAX_LAKE_NAMESPACE_SEGMENTS) {
                TablesPage(emptyList(), false)
            } else {
                val rows =
                    lakeTables
                        .registeredTables(datasource.name)
                        .filter { filter.isEmpty() || it.namespace == filter }
                TablesPage(
                    rows.take(maxTables).map { TableInfo(it.namespace, it.name, LAKE_TABLE_TYPE, it.format) },
                    truncated = rows.size > maxTables,
                )
            }
        }

    /**
     * §7A — one table's columns with canonical types; empty when the table does not exist.
     *
     * Without a schema filter the read defaults to the **connection's current schema** (routed
     * per dialect exactly like an explicit filter): an unfiltered `getColumns` would merge the
     * columns of same-named tables across schemas into one list. A driver that reports no
     * current schema — or the JDBC blank sentinel, which means "objects without a
     * catalog/schema", not a schema named `""` — cannot honor that default, and the unfiltered
     * fallback it used to take is exactly the merge the contract forbids: the read fails with
     * [CurrentSchemaUnknownException] and the caller passes an explicit schema from [schemas].
     * The schemaless dialects are the deliberate exception — no schema dimension means no
     * same-named siblings to merge ([NamespaceShape.isFlat]).
     */
    fun columns(
        datasourceName: String,
        table: String,
        schemaFilter: String? = null,
        namespaceFilter: List<String>? = null,
    ): List<ColumnInfo> =
        columns(
            registry.get(datasourceName) ?: throw notFound(datasourceName),
            table,
            schemaFilter,
            namespaceFilter,
        )

    /** §7A for an already-gated [datasource] — see [schemas]'s C3 note. */
    fun columns(
        datasource: Datasource,
        table: String,
        schemaFilter: String? = null,
        namespaceFilter: List<String>? = null,
    ): List<ColumnInfo> {
        // A blank caller filter is absent (the same blank-sentinel rule tables() applies).
        val supplied = Namespaces.filterOf(namespaceFilter, schemaFilter)
        if (datasource.dialect == Dialect.LAKE) return lakeColumns(datasource, table, supplied)
        return withMetaData(datasource) { connection, meta, _ ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val shape = adapter.namespaceShape
            val exempt = datasource.introspectionIncludeSchemas.toSet()
            // The flat-dialect exemption is STRUCTURAL, not driver-dependent: a flat dialect
            // never consults the connection's current schema at all (R5 F3 — the old order was
            // safe only because the vendored sqlite-jdbc hardcodes getSchema() = null; a future
            // flat driver whose getSchema()/getCatalog() throws would have turned a working
            // unfiltered read into a classified failure), so the current-namespace default —
            // never the JDBC '' sentinel — applies only to dialects that HAVE a namespace.
            val effectiveFilter =
                supplied.ifEmpty { if (shape.isFlat) emptyList() else connection.currentNamespace(adapter, datasource.name) }
            if (effectiveFilter.isEmpty() && !shape.isFlat) {
                throw CurrentSchemaUnknownException(datasource.name)
            }
            val routed = Namespaces.route(shape, effectiveFilter, meta) ?: return@withMetaData emptyList()
            meta.getColumns(routed.first, routed.second, table.toExactMatch(meta.searchStringEscape), "%").use { rs ->
                buildList {
                    while (rs.next()) {
                        if (adapter.isSystemSchema(rs.namespaceOf(shape), exempt)) continue
                        add(mapColumnRow(rs, adapter.typeMapper))
                    }
                }
            }
        }
    }

    /**
     * §7A for a LAKE datasource — the columns of the registered table's VIEW (089 §C): a
     * zero-row `SELECT *` over the view, read through [ResultSetMetaData] and mapped by the
     * adapter's DuckDB mapper exactly like a JDBC `getColumns` row (nested LIST/STRUCT/MAP
     * arrive as STRING with the mapper's warning). `DESCRIBE SELECT * FROM <view>` reports the
     * same schema, but as STRINGS — the mapper's DECIMAL precision/scale inputs would have to
     * be re-parsed out of them, so the structured metadata read is the faithful one.
     *
     * The unfiltered default is the search-path rule's twin ([LakeViewStatements]): with
     * EXACTLY ONE registered namespace the table is resolved there; with several, the read
     * cannot pick one and fails with [CurrentSchemaUnknownException] — the caller passes an
     * explicit namespace, the same recovery as the JDBC path. An unregistered table is empty,
     * never an error (the §7A rule). Cached per (namespace, table) in [lakeCache] — on S3 the
     * zero-row scan is a footer read over the network.
     */
    private fun lakeColumns(
        datasource: Datasource,
        table: String,
        filter: List<String>,
    ): List<ColumnInfo> {
        val effective =
            filter.ifEmpty {
                val namespaces = lakeTables.registeredTables(datasource.name).map { it.namespace }.distinct()
                when (namespaces.size) {
                    // No registered tables: the table cannot exist — empty, like an unknown table.
                    0 -> return emptyList()

                    1 -> namespaces.single()

                    else -> throw CurrentSchemaUnknownException(datasource.name)
                }
            }
        val registered =
            lakeTables
                .registeredTables(datasource.name)
                .firstOrNull { it.namespace == effective && it.name == table }
                ?: return emptyList()
        val qualifier = (effective + table).joinToString(Namespaces.SEPARATOR.toString())
        return lakeCache.get(datasource.name, "columns", qualifier) {
            ConnectionLease.lease(registry, datasource) { connection ->
                val adapter = DialectAdapters.forDialect(datasource.dialect)
                val path = (registered.namespace + registered.name).joinToString(".") { adapter.quoteIdentifier(it) }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $path LIMIT 0").use { rs ->
                        mapResultSetColumns(rs.metaData, adapter.typeMapper)
                    }
                }
            }
        }
    }

    /**
     * §7C — one table's CATALOG statistics: the engine's own stored estimates and index
     * definitions, never a scan of the table (no `COUNT(*)`, no `COUNT(DISTINCT)` anywhere on
     * the path). A dialect with no catalog stats answers `stats_source: "none"` — a valid
     * result, not an error; an unknown table answers empty stats (the §7A rule). The engine
     * lives in [TableStatsReader]; this is the two-overload entry point the surfaces call.
     */
    fun tableStats(
        datasourceName: String,
        table: String,
        namespaceFilter: List<String>? = null,
    ): TableStats = statsReader.tableStats(registry.get(datasourceName) ?: throw notFound(datasourceName), table, namespaceFilter)

    /** §7C for an already-gated [datasource] — see [schemas]'s C3 note. */
    fun tableStats(
        datasource: Datasource,
        table: String,
        namespaceFilter: List<String>? = null,
    ): TableStats = statsReader.tableStats(datasource, table, namespaceFilter)

    /** One [ResultSetMetaData] row set, mapped exactly like [mapColumnRow] maps a `getColumns` row. */
    private fun mapResultSetColumns(
        meta: ResultSetMetaData,
        mapper: IngressTypeMapper,
    ): List<ColumnInfo> =
        (1..meta.columnCount).map { index ->
            val sourceTypeName = meta.getColumnTypeName(index) ?: ""
            val mapped =
                mapper.mapColumn(
                    name = meta.getColumnName(index),
                    sqlType = meta.getColumnType(index),
                    precision = meta.getPrecision(index),
                    scale = meta.getScale(index),
                    typeName = sourceTypeName,
                    nullable =
                        when (meta.isNullable(index)) {
                            ResultSetMetaData.columnNoNulls -> false
                            ResultSetMetaData.columnNullable -> true
                            else -> null
                        },
                )
            ColumnInfo(mapped.column, sourceTypeName, mapped.warnings)
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
        exemptSchemas: Set<String> = emptySet(),
    ): TablesPage {
        val out = mutableListOf<TableInfo>()
        var truncated = false
        meta.getTables(catalog, schemaPattern, "%", adapter.introspectionTableTypes.toTypedArray()).use { rs ->
            // Two jumps on purpose: system-schema rows are skipped WITHOUT counting against
            // the cap, and the cap+1-th USER row is the truncation proof — checking the cap
            // before the system-row test would flag truncation on a trailing system row.
            @Suppress("LoopWithTooManyJumpStatements")
            while (rs.next()) {
                val namespace = rs.namespaceOf(adapter.namespaceShape)
                if (adapter.isSystemSchema(namespace, exemptSchemas)) continue
                if (maxRows != null && out.size == maxRows) {
                    truncated = true
                    break
                }
                out.add(
                    TableInfo(
                        namespace,
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE"),
                        // Blank remarks are absent (F8's rule): Connector/J reports REMARKS as
                        // "" for every uncommented table — the wire contract is omitted-when-none.
                        rs.getString("REMARKS").asNonBlankOrNull(),
                    ),
                )
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
        return ColumnInfo(mapped.column, sourceTypeName, mapped.warnings, rs.getString("REMARKS").asNonBlankOrNull())
    }

    /**
     * The lease boundary every operation reads through — and the ONE place a connection failure
     * becomes [DatasourceUnreachableException].
     *
     * Since 037 C the translation itself lives in [ConnectionLease], shared with the §7B query
     * surface — the classifier's history (five rounds of adjacent-shape escapes) is recorded
     * there, and one shared implementation is the point.
     */
    private fun <T> withMetaData(
        datasourceName: String,
        block: (Connection, DatabaseMetaData, Datasource) -> T,
    ): T = withMetaData(registry.get(datasourceName) ?: throw notFound(datasourceName), block)

    /**
     * The gated-snapshot lease (025 C3): [datasource] arrives already resolved — by the
     * caller's visibility gate or the name-based delegate above — and the pool builds from
     * it directly, never through a second unscoped name lookup.
     */
    private fun <T> withMetaData(
        datasource: Datasource,
        block: (Connection, DatabaseMetaData, Datasource) -> T,
    ): T = ConnectionLease.lease(registry, datasource) { block(it, it.metaData, datasource) }

    /**
     * [DialectAdapter.introspectionSystemSchemas]: exact names match case-insensitively; an
     * entry ending in `*` matches by case-insensitive PREFIX (Oracle's versioned `apex_*`
     * schemas). Null is never a system schema.
     *
     * [exemptSchemas] is the datasource's `introspection_include_schemas` allowlist (§3.3):
     * a name listed there is NOT a system schema for this datasource, whatever the floor says —
     * the escape hatch for the floors' one blind spot (a prefix entry like `apex_*` hides a
     * customer's own APEX_REPORTING schema). Lowercase-exact, like the stored allowlist.
     */
    private fun DialectAdapter.isSystemSchema(
        namespace: List<String>,
        exemptSchemas: Set<String> = emptySet(),
    ): Boolean {
        val schema = namespace.lastOrNull() ?: return false
        val lower = schema.lowercase()
        if (exemptSchemas.isNotEmpty()) {
            // §3.3 (087): an allowlist entry may be the bare schema — today's spelling — or the
            // DOTTED namespace, which is the only way to exempt `a1.sales` while leaving
            // `a2.sales` excluded. Both forms are matched here so the two spellings cannot mean
            // different things on different code paths.
            if (lower in exemptSchemas) return false
            if (Namespaces.join(namespace).lowercase() in exemptSchemas) return false
        }
        val dotted = Namespaces.join(namespace).lowercase()
        return introspectionSystemSchemas.any { entry ->
            when {
                // A prefix entry (Oracle's versioned `apex_*`) matches the schema level only.
                entry.endsWith("*") -> lower.startsWith(entry.dropLast(1))

                // A DOTTED floor entry names a whole namespace (087): DuckDB's engine catalogs
                // hold a schema called `main`, and `main` alone is a perfectly ordinary user
                // schema everywhere else — only `system.main` identifies the engine's own.
                entry.contains(Namespaces.SEPARATOR) -> dotted == entry

                else -> lower == entry
            }
        }
    }

    /**
     * A result row's namespace, outermost first, in this dialect's own vocabulary: the owning
     * catalog (when the shape has a real outer level) then the innermost level from whichever
     * column carries it. Blank segments are dropped — the JDBC `""` sentinel means "objects
     * without a catalog/schema", never a name.
     */
    private fun java.sql.ResultSet.namespaceOf(shape: NamespaceShape): List<String> =
        listOfNotNull(
            if (shape.hasOuterCatalog) getString("TABLE_CAT").asNonBlankOrNull() else null,
            getString(shape.innermostResultColumn).asNonBlankOrNull(),
        )

    /** `getSchemas()`'s owning-catalog column, blank-sentinel-filtered. */
    private fun java.sql.ResultSet.catalogOf(): String? = getString("TABLE_CATALOG").asNonBlankOrNull()

    private fun notFound(name: String): DatapipelinesException =
        DatapipelinesException(
            code = DatasourceErrorCodes.NOT_FOUND,
            message = "Datasource '$name' is not registered in this environment.",
            details = mapOf("datasource" to name),
        )

    private companion object {
        /**
         * The §7A listing cap — bounds ONE introspection call's payload and walk, shared by the
         * tables and schemas listings (both hold the pooled lease while they iterate; on MySQL
         * catalog routing the schemas walk is every database the server grants).
         */
        const val MAX_LISTING_ROWS = 2000

        /** The engine object a registered lake table IS (089 §B's per-table view) — TableInfo.type. */
        const val LAKE_TABLE_TYPE = "VIEW"

        /** DuckDB's catalog.schema namespace depth — a deeper filter names no real place (089 §B). */
        const val MAX_LAKE_NAMESPACE_SEGMENTS = 2
    }
}
