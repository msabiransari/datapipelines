package co.datapipelines.datasources

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
     * system schemas excluded, capped at [maxSchemas] (`truncated: true` when the cap dropped
     * any — the same cap+1 early-exit discipline as [tables]).
     *
     * The vocabulary follows [DialectAdapter.schemaArrivesInCatalog]: for catalog-routing
     * drivers (Connector/J defaults) the databases ARE the JDBC catalogs, so the listing reads
     * `getCatalogs()`/TABLE_CAT — `getSchemas()` there reports a single blank schema. An EMPTY
     * list is a valid result, not an error: a schemaless dialect (SQLite, single-db DuckDB)
     * genuinely has no schemas to list.
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
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val rs = if (adapter.schemaArrivesInCatalog) meta.catalogs else meta.schemas
            val exempt = datasource.introspectionIncludeSchemas.toSet()
            rs.use {
                val out = mutableListOf<String>()
                var truncated = false
                // Same jump discipline as readTables (whose suppression this mirrors): system
                // rows are skipped WITHOUT counting against the cap, and the cap+1-th USER
                // row is the truncation proof.
                @Suppress("LoopWithTooManyJumpStatements")
                while (it.next()) {
                    // The JDBC "" sentinel ("objects without a catalog") is not a schema
                    // an agent can pass to get_tables — skip it rather than list it.
                    val schema = it.getString(adapter.schemaResultColumn()).asNonBlankOrNull() ?: continue
                    if (adapter.isSystemSchema(schema, exempt)) continue
                    if (out.size == maxSchemas) {
                        truncated = true
                        break
                    }
                    out.add(schema)
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
    ): TablesPage = tables(registry.get(datasourceName) ?: throw notFound(datasourceName), schemaFilter, maxTables)

    /** §7A for an already-gated [datasource] — see [schemas]'s C3 note. */
    fun tables(
        datasource: Datasource,
        schemaFilter: String? = null,
        maxTables: Int = MAX_LISTING_ROWS,
    ): TablesPage =
        withMetaData(datasource) { _, meta, _ ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            // The caller's filter goes through the same blank-sentinel rule as driver-reported
            // values (Spring binds `?schema=` to non-null ""): blank means ABSENT — spans
            // schemas — never the JDBC '' sentinel, which matches nothing on any dialect.
            val filter = schemaFilter.asNonBlankOrNull()
            val (catalog, escapedSchemaPattern) = adapter.routeAndEscape(filter, meta)
            readTables(
                meta,
                adapter,
                catalog,
                escapedSchemaPattern,
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
     * same-named siblings to merge ([DialectAdapter.introspectionSchemaless]).
     */
    fun columns(
        datasourceName: String,
        table: String,
        schemaFilter: String? = null,
    ): List<ColumnInfo> =
        columns(
            registry.get(datasourceName) ?: throw notFound(datasourceName),
            table,
            schemaFilter,
        )

    /** §7A for an already-gated [datasource] — see [schemas]'s C3 note. */
    fun columns(
        datasource: Datasource,
        table: String,
        schemaFilter: String? = null,
    ): List<ColumnInfo> =
        withMetaData(datasource) { connection, meta, _ ->
            val adapter = DialectAdapters.forDialect(datasource.dialect)
            val exempt = datasource.introspectionIncludeSchemas.toSet()
            // A blank caller filter is absent (the same blank-sentinel rule tables() applies).
            // The schemaless exemption is STRUCTURAL, not driver-dependent: a schemaless
            // dialect never consults the connection's current schema at all (R5 F3 — the
            // old order was safe only because the vendored sqlite-jdbc hardcodes
            // getSchema() = null; a future schemaless driver whose getSchema()/getCatalog()
            // throws would have turned a working unfiltered read into a classified failure),
            // so the current-schema default — never the JDBC '' sentinel — applies only to
            // schema-capable dialects.
            val effectiveFilter =
                schemaFilter.asNonBlankOrNull()
                    ?: if (adapter.introspectionSchemaless) null else connection.currentSchema(adapter, datasource.name)
            if (effectiveFilter == null && !adapter.introspectionSchemaless) {
                throw CurrentSchemaUnknownException(datasource.name)
            }
            val (catalog, escapedSchemaPattern) = adapter.routeAndEscape(effectiveFilter, meta)
            meta.getColumns(catalog, escapedSchemaPattern, table.toExactMatch(meta.searchStringEscape), "%").use { rs ->
                buildList {
                    while (rs.next()) {
                        val schema = rs.getString(adapter.schemaResultColumn()).asNonBlankOrNull()
                        if (adapter.isSystemSchema(schema, exempt)) continue
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
                val schema = rs.getString(adapter.schemaResultColumn()).asNonBlankOrNull()
                if (adapter.isSystemSchema(schema, exemptSchemas)) continue
                if (maxRows != null && out.size == maxRows) {
                    truncated = true
                    break
                }
                out.add(
                    TableInfo(
                        schema,
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
     * subclasses, [java.sql.SQLRecoverableException], [java.sql.SQLTimeoutException], and the
     * SQLite connection-loss result codes — checked on the exception itself and along its
     * cause/nextException chains, see [SQLException.isConnectionFailure]) — any other
     * SQLException from a metadata read is likewise a defect and propagates. `Error` is never
     * caught.
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
    @Suppress("TooGenericExceptionCaught")
    private fun <T> withMetaData(
        datasource: Datasource,
        block: (Connection, DatabaseMetaData, Datasource) -> T,
    ): T {
        val datasourceName = datasource.name
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
     *
     * [exemptSchemas] is the datasource's `introspection_include_schemas` allowlist (§3.3):
     * a name listed there is NOT a system schema for this datasource, whatever the floor says —
     * the escape hatch for the floors' one blind spot (a prefix entry like `apex_*` hides a
     * customer's own APEX_REPORTING schema). Lowercase-exact, like the stored allowlist.
     */
    private fun DialectAdapter.isSystemSchema(
        schema: String?,
        exemptSchemas: Set<String> = emptySet(),
    ): Boolean {
        if (schema == null) return false
        val lower = schema.lowercase()
        if (exemptSchemas.isNotEmpty() && lower in exemptSchemas) return false
        return introspectionSystemSchemas.any { entry ->
            if (entry.endsWith("*")) lower.startsWith(entry.dropLast(1)) else lower == entry
        }
    }

    /**
     * The connection-failure family of a post-lease [SQLException]: the named SQLState
     * membership ([CONNECTION_FAILURE_SQLSTATE_CLASSES] plus [CONNECTION_FAILURE_SQLSTATES]),
     * the JDBC connection-exception subclasses, [java.sql.SQLRecoverableException] (whose
     * subclasses include the connection-died-mid-read family some drivers raise),
     * [java.sql.SQLTimeoutException] (extends SQLTransientException, not the connection
     * family — but a dead network surfaces as exactly this shape), and the per-driver
     * connection-loss knowledge — SQLite's null-state result codes, h2's 90xxx
     * closed-connection codes, and the DuckDB/SQLite closed-connection lifecycle messages
     * (see the per-predicate KDocs for each driver's evidence). Everything else is NOT a
     * connection failure.
     *
     * A driver may carry the state on a **wrapped** exception rather than the one it throws, so
     * the check walks the `cause` and `nextException` chains ([CHAIN_WALK_LIMIT] nodes,
     * cycle-safe) instead of inspecting the top-level exception alone.
     */
    private fun SQLException.isConnectionFailure(): Boolean {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var queue = ArrayDeque<Throwable>()
        queue.add(this)
        while (queue.isNotEmpty() && seen.size < CHAIN_WALK_LIMIT) {
            val current = queue.removeFirst()
            if (seen.put(current, true) != null) continue
            if (current is SQLException && current.directlyIsConnectionFailure()) return true
            (current as? SQLException)?.nextException?.let { queue.add(it) }
            current.cause?.let { queue.add(it) }
        }
        return false
    }

    /** The connection-family test for ONE exception, without chain inspection. */
    private fun SQLException.directlyIsConnectionFailure(): Boolean =
        sqlState?.take(2) in CONNECTION_FAILURE_SQLSTATE_CLASSES ||
            sqlState in CONNECTION_FAILURE_SQLSTATES ||
            this is java.sql.SQLTransientConnectionException ||
            this is java.sql.SQLNonTransientConnectionException ||
            this is java.sql.SQLRecoverableException ||
            this is java.sql.SQLTimeoutException ||
            isSqliteConnectionLoss() ||
            isH2ConnectionLoss() ||
            isDriverClosedConnectionMessage()

    /**
     * The vendored sqlite-jdbc's `SQLiteException` extends plain `SQLException` with a **null
     * SQLState** (its sole constructor passes null for the state and `code & 0xFF` for the
     * vendor code — verified in the 3.49.1.0 bytecode), so a deleted or locked db file
     * mid-metadata-walk fails every state-based branch. Classified instead by SQLite's own
     * primary result codes on the standard [SQLException.getErrorCode] — deliberately NOT a
     * blanket "null SQLState means down": a null-state SQLiteException with any other code
     * stays a defect and propagates (round 2's R5 narrowing).
     *
     * Name-based, never a compiled reference: `datasources` does not compile against any
     * driver (§10.3) — the class is matched through its hierarchy so a driver-side subclass
     * still classifies.
     */
    private fun SQLException.isSqliteConnectionLoss(): Boolean =
        generateSequence(javaClass as Class<*>?) { it.superclass }.any { it.name == SQLITE_EXCEPTION_CLASS } &&
            errorCode in SQLITE_CONNECTION_LOSS_PRIMARY_CODES

    /**
     * h2's closed-connection family, same per-driver pattern as [isSqliteConnectionLoss] —
     * but the name-based class match cannot work here: h2's typed carriers
     * (`JdbcSQLNonTransientException`, …) extend the **java.sql** exception types directly
     * and only IMPLEMENT h2's `JdbcException` interface, so no h2-named class walks up the
     * superclass chain. The stable signature is the code's DOUBLE representation: h2 carries
     * its engine error code both as the SQLState STRING and as the vendor code
     * (`"90007"`/90007 — live-probed 2026-08-16), and the 90xxx class is outside the SQL
     * standard's class range, so no standard-state driver collides. Codes, verified against
     * the pinned h2 2.3.232 (live probe; `EmbeddedDialectBehaviorTest` re-pins the shape on
     * every run):
     * 90007 = "The object is already closed" (connection closed),
     * 90098 = "Database is closed",
     * 90121 = "Database is already closed" — this last one usually arrives TYPED
     * (`JdbcSQLNonTransientConnectionException`), which the generic connection-exception
     * branch already classifies; it is listed here so a plain-exception carrier classifies
     * too.
     */
    private fun SQLException.isH2ConnectionLoss(): Boolean =
        sqlState != null && sqlState.toIntOrNull() == errorCode && errorCode in H2_CONNECTION_LOSS_CODES

    /**
     * The closed-connection lifecycle messages of the drivers that report them as a **plain
     * `java.sql.SQLException`** with NULL SQLState and vendor code 0 — leaving type, state,
     * and code with nothing to say:
     * - duckdb_jdbc 1.5.5.1: `getSchema()`/`getCatalog()` on a closed connection throw
     *   exactly `"Connection was closed"`. Its NATIVE errors are ALSO plain null-state
     *   `SQLException`s — with driver-specific text ("Invalid Input Error: …"), so the exact
     *   message is the only discriminator, and the exact-class test (`java.sql.SQLException`
     *   itself, not a subclass) keeps this from matching any driver's specific exception
     *   type. Live-probed 2026-08-16; both arms re-pinned in `EmbeddedDialectBehaviorTest`.
     * - sqlite-jdbc 3.49.1.0: a closed connection's `getCatalog()` throws exactly
     *   `"database connection closed"`, the same null-state plain-`SQLException` shape (its
     *   `getSchema()` returns hardcoded null instead of throwing — adapter KDoc).
     *
     * Deliberately NOT a blanket "null SQLState means down" (round 2's R5 narrowing): the
     * exact message + exact plain class + null state + zero code is the drivers' JDBC-layer
     * lifecycle text, not a server error echo. If a driver bump rewords the text, this rule
     * stops matching and the shape degrades to the round-4 behavior (propagate) — the
     * behavior pin fails and forces re-derivation.
     */
    private fun SQLException.isDriverClosedConnectionMessage(): Boolean =
        sqlState == null &&
            errorCode == 0 &&
            javaClass == SQLException::class.java &&
            message in CLOSED_CONNECTION_MESSAGES

    /**
     * Where the escaped schema filter goes: the catalog argument for drivers that carry the
     * database there ([DialectAdapter.schemaArrivesInCatalog]), the schemaPattern otherwise.
     */
    private fun DialectAdapter.routeSchemaFilter(filter: String?): Pair<String?, String?> =
        if (schemaArrivesInCatalog) filter to null else null to filter

    /**
     * The shared route-FIRST, escape-only-the-pattern dance of tables() and columns():
     * returns `(catalog, escapedSchemaPattern)`. The JDBC **catalog argument is a LITERAL**
     * ("must match the catalog name as it is stored"), so an escaped value there matches
     * nothing — any MySQL database named with `_`/`%`. Only true pattern arguments
     * (`schemaPattern`, `tableNamePattern`) get [toExactMatch] — and `getSearchStringEscape`
     * is read only when a pattern actually needs escaping. One home for the rule, so an
     * escaping fix can never land in one call site and miss the other.
     */
    private fun DialectAdapter.routeAndEscape(
        filter: String?,
        meta: DatabaseMetaData,
    ): Pair<String?, String?> {
        val (catalog, schemaPattern) = routeSchemaFilter(filter)
        val escaped = if (schemaPattern == null) null else schemaPattern.toExactMatch(meta.searchStringEscape)
        return catalog to escaped
    }

    /** The result column that carries the schema: TABLE_CAT for catalog-routing drivers, TABLE_SCHEM otherwise. */
    private fun DialectAdapter.schemaResultColumn(): String = if (schemaArrivesInCatalog) "TABLE_CAT" else "TABLE_SCHEM"

    /**
     * The connection's current schema, in this dialect's own vocabulary: the **catalog** for
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
    private fun Connection.currentSchema(
        adapter: DialectAdapter,
        datasourceName: String,
    ): String? =
        try {
            if (adapter.schemaArrivesInCatalog) catalog else schema
        } catch (_: SQLFeatureNotSupportedException) {
            // The typed capability statement: the driver reports none. Deliberately
            // discarded — the exception type itself is the entire signal.
            null
        } catch (e: SQLException) {
            when {
                e.sqlState == FEATURE_UNSUPPORTED_STATE -> null
                e.isConnectionFailure() -> unreachable(datasourceName, e)
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
        /**
         * The SQLState membership of the connection-failure family — the predicate's
         * contract as a VISIBLE NAMED SET (034 C2): five rounds each widened this
         * classifier for the shapes they enumerated and missed the adjacent shape in the
         * same predicate, so the family is a list now, and the next adjacent shape is a
         * one-line addition here plus its pin in `SchemaIntrospectorCapAndLeaseTest`, not
         * a sixth discovery.
         *
         * [CONNECTION_FAILURE_SQLSTATE_CLASSES] holds whole CLASSES (leading two
         * characters) — today only `08`, the SQL standard's own connection-exception
         * class. [CONNECTION_FAILURE_SQLSTATES] holds individual states whose class is
         * NOT wholly connection-shaped: PostgreSQL's class-57 (operator intervention)
         * shutdown states 57P01 (admin_shutdown), 57P02 (crash_shutdown), 57P03
         * (cannot_connect_now) and 57P04 (database_shutdown, PG 16), which pgjdbc raises
         * as PLAIN `PSQLException`s — a `pg_terminate_backend` or server shutdown
         * mid-introspection used to fall through to the 400 "reports no current schema"
         * branch instead of the 502 unreachable translation. The rest of class 57 is
         * deliberately NOT in the family: 57014 (query_canceled) means the STATEMENT
         * died and the connection is alive — pinned to `CurrentSchemaUnknownException`
         * by `SchemaIntrospectorRoutingTest` (a class-wide "57" was the brief's first
         * draft and broke exactly that pin).
         */
        val CONNECTION_FAILURE_SQLSTATE_CLASSES = setOf("08")
        val CONNECTION_FAILURE_SQLSTATES = setOf("57P01", "57P02", "57P03", "57P04")

        /**
         * The §7A listing cap — bounds ONE introspection call's payload and walk, shared by the
         * tables and schemas listings (both hold the pooled lease while they iterate; on MySQL
         * catalog routing the schemas walk is every database the server grants).
         */
        const val MAX_LISTING_ROWS = 2000

        /** The vendored sqlite-jdbc's exception class (never compiled against — §10.3). */
        const val SQLITE_EXCEPTION_CLASS = "org.sqlite.SQLiteException"

        /** h2's closed-connection error codes (see [isH2ConnectionLoss] for per-code evidence). */
        val H2_CONNECTION_LOSS_CODES = setOf(90007, 90098, 90121)

        /**
         * The drivers' JDBC-layer closed-connection lifecycle texts (see
         * [isDriverClosedConnectionMessage] for the per-driver evidence).
         */
        val CLOSED_CONNECTION_MESSAGES = setOf("Connection was closed", "database connection closed")

        /** SQLState "feature not supported" — the untyped sibling of [SQLFeatureNotSupportedException]. */
        const val FEATURE_UNSUPPORTED_STATE = "0A000"

        /**
         * SQLite primary result codes that mean "the database could not be reached": BUSY(5),
         * IOERR(10), CANTOPEN(14), NOTADB(26). The driver's own constructor masks extended
         * codes with `& 0xFF`, so every SQLITE_IOERR_* / SQLITE_CANTOPEN_* / SQLITE_BUSY_*
         * folds onto its primary member here.
         */
        val SQLITE_CONNECTION_LOSS_PRIMARY_CODES = setOf(5, 10, 14, 26)

        /** Bound on the cause/nextException chain walk — a driver bug must not loop us. */
        const val CHAIN_WALK_LIMIT = 16
    }
}
