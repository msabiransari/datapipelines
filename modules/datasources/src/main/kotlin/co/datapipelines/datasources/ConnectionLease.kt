package co.datapipelines.datasources

import java.sql.Connection
import java.sql.SQLException

/**
 * The module's shared **lease boundary** — the one place a pooled connection is leased for a
 * caller and a connection failure becomes [DatasourceUnreachableException] (datasources.md
 * §7A/§7B).
 *
 * Born as `SchemaIntrospector`'s private discipline; extracted when the §7B query surface
 * (037 C) needed the identical translation. Two decoders of "is this SQLException the database
 * going away" is exactly how two surfaces drift on the next driver shape — the same reasoning
 * that made [ResultRowReader] shared.
 *
 * Both exception families the registry's probe KDoc names are translated (see
 * `DefaultDatasourceRegistry.probe`): the `SQLException` of a refused/timed-out lease or a
 * connection that died mid-read, AND the RuntimeException family of pool construction —
 * `HikariPool.PoolInitializationException` at first lease on a down database, a missing driver
 * class, a property a driver rejects at parse time.
 *
 * Both catches stop at the lease-and-connection boundary: a RuntimeException thrown by the
 * caller's own work is a defect in the caller, and masking it as "the caller's database is
 * unreachable" would hide it. Post-lease, the SQLException catch narrows to the CONNECTION
 * family only — any other SQLException from the caller's read propagates for the caller to
 * classify (the introspector treats it as a defect; `SqlRunner` wraps it as a statement
 * failure). `Error` is never caught.
 */
internal object ConnectionLease {
    /** Leases a connection for [datasource] and runs [block] with it, closed on exit. */
    @Suppress("TooGenericExceptionCaught")
    fun <T> lease(
        registry: DatasourceRegistry,
        datasource: Datasource,
        block: (Connection) -> T,
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
            connection.use(block)
        } catch (e: SQLException) {
            // Post-lease, only the CONNECTION family means "the database went away" — any
            // other SQLException from the caller's read is the caller's to classify.
            if (e.isConnectionFailure()) unreachable(datasourceName, e) else throw e
        }
    }

    /** The single throw point of the lease boundary's translation. */
    fun unreachable(
        datasourceName: String,
        cause: Throwable,
    ): Nothing = throw DatasourceUnreachableException(datasourceName, cause)

    /**
     * The SQLState membership of the connection-failure family — the predicate's contract as a
     * VISIBLE NAMED SET (034 C2): five rounds each widened this classifier for the shapes they
     * enumerated and missed the adjacent shape in the same predicate, so the family is a list
     * now, and the next adjacent shape is a one-line addition here plus its pin in
     * `SchemaIntrospectorCapAndLeaseTest`, not a sixth discovery.
     *
     * [CONNECTION_FAILURE_SQLSTATE_CLASSES] holds whole CLASSES (leading two characters) —
     * today only `08`, the SQL standard's own connection-exception class.
     * [CONNECTION_FAILURE_SQLSTATES] holds individual states whose class is NOT wholly
     * connection-shaped: PostgreSQL's class-57 (operator intervention) shutdown states 57P01
     * (admin_shutdown), 57P02 (crash_shutdown), 57P03 (cannot_connect_now) and 57P04
     * (database_shutdown, PG 16), which pgjdbc raises as PLAIN `PSQLException`s. The rest of
     * class 57 is deliberately NOT in the family: 57014 (query_canceled) means the STATEMENT
     * died and the connection is alive — pinned to `CurrentSchemaUnknownException` by
     * `SchemaIntrospectorRoutingTest` (a class-wide "57" was the brief's first draft and broke
     * exactly that pin).
     */
    val CONNECTION_FAILURE_SQLSTATE_CLASSES = setOf("08")
    val CONNECTION_FAILURE_SQLSTATES = setOf("57P01", "57P02", "57P03", "57P04")

    /** The vendored sqlite-jdbc's exception class (never compiled against — §10.3). */
    const val SQLITE_EXCEPTION_CLASS = "org.sqlite.SQLiteException"

    /** h2's closed-connection error codes (see [isH2ConnectionLoss] for per-code evidence). */
    val H2_CONNECTION_LOSS_CODES = setOf(90007, 90098, 90121)

    /** The drivers' JDBC-layer closed-connection lifecycle texts (per-driver evidence in KDoc below). */
    val CLOSED_CONNECTION_MESSAGES = setOf("Connection was closed", "database connection closed")

    /**
     * SQLite primary result codes that mean "the database could not be reached": BUSY(5),
     * IOERR(10), CANTOPEN(14), NOTADB(26). The driver's own constructor masks extended codes
     * with `& 0xFF`, so every SQLITE_IOERR_* / SQLITE_CANTOPEN_* / SQLITE_BUSY_* folds onto
     * its primary member here.
     */
    val SQLITE_CONNECTION_LOSS_PRIMARY_CODES = setOf(5, 10, 14, 26)

    /** Bound on the cause/nextException chain walk — a driver bug must not loop us. */
    const val CHAIN_WALK_LIMIT = 16
}

/**
 * The connection-failure family of a post-lease [SQLException] (top-level so every classifier
 * in the module — the lease boundary above AND the introspector's current-schema read — calls
 * this ONE implementation): the named SQLState membership, the JDBC connection-exception
 * subclasses, [java.sql.SQLRecoverableException] (whose subclasses include the
 * connection-died-mid-read family some drivers raise), [java.sql.SQLTimeoutException] (extends
 * SQLTransientException, not the connection family — but a dead network surfaces as exactly
 * this shape), and the per-driver connection-loss knowledge — SQLite's null-state result
 * codes, h2's 90xxx closed-connection codes, and the DuckDB/SQLite closed-connection
 * lifecycle messages (see the per-function KDocs for each driver's evidence). Everything else
 * is NOT a connection failure.
 *
 * A driver may carry the state on a **wrapped** exception rather than the one it throws, so
 * the check walks the `cause` and `nextException` chains ([ConnectionLease.CHAIN_WALK_LIMIT]
 * nodes, cycle-safe) instead of inspecting the top-level exception alone.
 */
internal fun SQLException.isConnectionFailure(): Boolean {
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    var queue = ArrayDeque<Throwable>()
    queue.add(this)
    while (queue.isNotEmpty() && seen.size < ConnectionLease.CHAIN_WALK_LIMIT) {
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
    sqlState?.take(2) in ConnectionLease.CONNECTION_FAILURE_SQLSTATE_CLASSES ||
        sqlState in ConnectionLease.CONNECTION_FAILURE_SQLSTATES ||
        this is java.sql.SQLTransientConnectionException ||
        this is java.sql.SQLNonTransientConnectionException ||
        this is java.sql.SQLRecoverableException ||
        this is java.sql.SQLTimeoutException ||
        isSqliteConnectionLoss() ||
        isH2ConnectionLoss() ||
        isDriverClosedConnectionMessage()

/**
 * The vendored sqlite-jdbc's `SQLiteException` extends plain `SQLException` with a **null
 * SQLState** (its sole constructor passes null for the state and `code & 0xFF` for the vendor
 * code — verified in the 3.49.1.0 bytecode), so a deleted or locked db file mid-read fails
 * every state-based branch. Classified instead by SQLite's own primary result codes on the
 * standard [SQLException.getErrorCode] — deliberately NOT a blanket "null SQLState means
 * down": a null-state SQLiteException with any other code stays a defect and propagates
 * (round 2's R5 narrowing).
 *
 * Name-based, never a compiled reference: `datasources` does not compile against any driver
 * (§10.3) — the class is matched through its hierarchy so a driver-side subclass still
 * classifies.
 */
private fun SQLException.isSqliteConnectionLoss(): Boolean =
    generateSequence(javaClass as Class<*>?) { it.superclass }
        .any { it.name == ConnectionLease.SQLITE_EXCEPTION_CLASS } &&
        errorCode in ConnectionLease.SQLITE_CONNECTION_LOSS_PRIMARY_CODES

/**
 * h2's closed-connection family, same per-driver pattern as [isSqliteConnectionLoss] — but the
 * name-based class match cannot work here: h2's typed carriers (`JdbcSQLNonTransientException`,
 * …) extend the **java.sql** exception types directly and only IMPLEMENT h2's `JdbcException`
 * interface, so no h2-named class walks up the superclass chain. The stable signature is the
 * code's DOUBLE representation: h2 carries its engine error code both as the SQLState STRING
 * and as the vendor code (`"90007"`/90007 — live-probed 2026-08-16), and the 90xxx class is
 * outside the SQL standard's class range, so no standard-state driver collides. Codes,
 * verified against the pinned h2 2.3.232 (live probe; `EmbeddedDialectBehaviorTest` re-pins
 * the shape on every run):
 * 90007 = "The object is already closed" (connection closed),
 * 90098 = "Database is closed",
 * 90121 = "Database is already closed" — this last one usually arrives TYPED
 * (`JdbcSQLNonTransientConnectionException`), which the generic connection-exception branch
 * already classifies; it is listed here so a plain-exception carrier classifies too.
 */
private fun SQLException.isH2ConnectionLoss(): Boolean =
    sqlState != null && sqlState.toIntOrNull() == errorCode && errorCode in ConnectionLease.H2_CONNECTION_LOSS_CODES

/**
 * The closed-connection lifecycle messages of the drivers that report them as a **plain
 * `java.sql.SQLException`** with NULL SQLState and vendor code 0 — leaving type, state, and
 * code with nothing to say:
 * - duckdb_jdbc 1.5.5.1: `getSchema()`/`getCatalog()` on a closed connection throw exactly
 *   `"Connection was closed"`. Its NATIVE errors are ALSO plain null-state `SQLException`s —
 *   with driver-specific text ("Invalid Input Error: …"), so the exact message is the only
 *   discriminator, and the exact-class test (`java.sql.SQLException` itself, not a subclass)
 *   keeps this from matching any driver's specific exception type. Live-probed 2026-08-16;
 *   both arms re-pinned in `EmbeddedDialectBehaviorTest`.
 * - sqlite-jdbc 3.49.1.0: a closed connection's `getCatalog()` throws exactly
 *   `"database connection closed"`, the same null-state plain-`SQLException` shape (its
 *   `getSchema()` returns hardcoded null instead of throwing — adapter KDoc).
 *
 * Deliberately NOT a blanket "null SQLState means down" (round 2's R5 narrowing): the exact
 * message + exact plain class + null state + zero code is the drivers' JDBC-layer lifecycle
 * text, not a server error echo. If a driver bump rewords the text, this rule stops matching
 * and the shape degrades to the round-4 behavior (propagate) — the behavior pin fails and
 * forces re-derivation.
 */
private fun SQLException.isDriverClosedConnectionMessage(): Boolean =
    sqlState == null &&
        errorCode == 0 &&
        javaClass == SQLException::class.java &&
        message in ConnectionLease.CLOSED_CONNECTION_MESSAGES
