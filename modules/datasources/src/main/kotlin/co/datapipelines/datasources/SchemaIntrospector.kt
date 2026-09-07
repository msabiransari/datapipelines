package co.datapipelines.datasources

import co.datapipelines.datasources.Namespaces.toExactMatch
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.IngressTypeMapper
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

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
) {
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
    ): SchemasPage =
        withMetaData(datasource) { _, meta, _ ->
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
    ): TablesPage =
        withMetaData(datasource) { _, meta, _ ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            // The caller's filter goes through the same blank-sentinel rule as driver-reported
            // values (Spring binds `?schema=` to non-null ""): blank means ABSENT — spans
            // namespaces — never the JDBC '' sentinel, which matches nothing on any dialect.
            val filter = Namespaces.filterOf(namespaceFilter, schemaFilter)
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
    ): List<ColumnInfo> =
        withMetaData(datasource) { connection, meta, _ ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val shape = adapter.namespaceShape
            val exempt = datasource.introspectionIncludeSchemas.toSet()
            // A blank caller filter is absent (the same blank-sentinel rule tables() applies).
            // The flat-dialect exemption is STRUCTURAL, not driver-dependent: a flat dialect
            // never consults the connection's current schema at all (R5 F3 — the old order was
            // safe only because the vendored sqlite-jdbc hardcodes getSchema() = null; a future
            // flat driver whose getSchema()/getCatalog() throws would have turned a working
            // unfiltered read into a classified failure), so the current-namespace default —
            // never the JDBC '' sentinel — applies only to dialects that HAVE a namespace.
            val supplied = Namespaces.filterOf(namespaceFilter, schemaFilter)
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

    /**
     * The connection's current NAMESPACE, in this dialect's own vocabulary: the **catalog** for
     * catalog-routing drivers (Connector/J keeps the current database there and leaves
     * `getSchema()` null), `getSchema()` for everyone else. Null when the driver reports none
     * — and the JDBC blank sentinel counts as none: `""` means "objects without a
     * catalog/schema", not a schema named `""`, so the caller reads unfiltered rather than
     * filtering on a name that matches nothing.
     *
     * Every [SQLException] this read can produce is classified HERE, in ONE place — extending
     * the lease boundary's own [SQLException.isConnectionFailure] classification, never
     * forking a second one. Three families (R5 F1; the shapes are live-pinned per driver in
     * `EmbeddedDialectBehaviorTest`):
     *
     * 1. **Feature-unsupported** — [SQLFeatureNotSupportedException], or a driver signaling
     *    the same via plain `SQLException` with SQLState `0A000` — reads as null: a legitimate
     *    capability statement ("driver reports none").
     * 2. **Connection loss** — the [SQLException.isConnectionFailure] family, INCLUDING the
     *    per-driver knowledge the classifier carries (SQLState class 08 + the typed connection
     *    exceptions + SQLite's null-state result codes + H2's closed-object codes + the
     *    DuckDB/SQLite closed-connection lifecycle messages) — becomes
     *    [DatasourceUnreachableException]: the catalogued 502 path, whose recommended
     *    recovery fails honestly on the same dead connection.
     * 3. **Anything left** — a NON-connection failure of the current-schema read itself
     *    (pgjdbc's `getSchema()` executes `select current_schema()` on the server —
     *    bytecode-verified for 42.7.13 — so a statement cancel 57014 or a permission error
     *    arrives here) — is [CurrentSchemaUnknownException] with the driver exception
     *    attached as cause: a catalogued failure whose recovery (pass an explicit schema
     *    filter, which never consults the current schema) works on the live connection.
     *    NEVER a raw rethrow to the surface: the surfaces catch only the two module
     *    exceptions, and a raw driver exception is a 500 / JSON-RPC -32603.
     */
    private fun Connection.currentNamespace(
        adapter: DialectAdapter,
        datasourceName: String,
    ): List<String> {
        val shape = adapter.namespaceShape
        val innermost = currentInnermost(shape, datasourceName) ?: return emptyList()
        // The outer segment matters as much as the inner one on a multi-catalog connection: a
        // current schema of `sales` with no catalog is what merged two ATTACHed catalogs' tables.
        // `getCatalog()` failing is not fatal here — the one-catalog dialects behave as before.
        val outer = if (shape.hasOuterCatalog) runCatching { catalog }.getOrNull()?.takeUnless { it.isBlank() } else null
        return listOfNotNull(outer, innermost)
    }

    /** The innermost current level, classified — see [currentNamespace]'s three families. */
    private fun Connection.currentInnermost(
        shape: NamespaceShape,
        datasourceName: String,
    ): String? =
        try {
            if (shape.innermostArrivesInCatalog) catalog else schema
        } catch (_: SQLFeatureNotSupportedException) {
            // The typed capability statement: the driver reports none. Deliberately
            // discarded — the exception type itself is the entire signal.
            null
        } catch (e: SQLException) {
            when {
                e.sqlState == FEATURE_UNSUPPORTED_STATE -> null
                e.isConnectionFailure() -> ConnectionLease.unreachable(datasourceName, e)
                else -> throw CurrentSchemaUnknownException(datasourceName, e)
            }
        }?.asNonBlankOrNull()

    /**
     * The ONE blank-sentinel rule at the ResultSet boundary: a value that is null, empty, or
     * whitespace-only means "absent", never a name — drivers report the JDBC `""` sentinel
     * ("objects without a catalog/schema") and some report `" "` just as vacuously. Every
     * site that reads a schema or remark (driver-reported or caller-supplied) routes through
     * this rule so the boundary cannot spell it differently per site.
     */
    private fun String?.asNonBlankOrNull(): String? = this?.takeUnless { it.isBlank() }

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

        /** SQLState "feature not supported" — the untyped sibling of [SQLFeatureNotSupportedException]. */
        const val FEATURE_UNSUPPORTED_STATE = "0A000"
    }
}
