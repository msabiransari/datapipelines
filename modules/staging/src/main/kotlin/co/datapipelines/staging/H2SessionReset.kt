package co.datapipelines.staging

import java.sql.Connection
import java.sql.SQLException

/**
 * What a lease may do to a physical connection, which decides how much sanitation its return
 * costs (staging.md §9.2).
 */
internal enum class LeaseKind {
    /**
     * Staging's own generated SQL — `CREATE TABLE`, batched `INSERT`, catalog reads, the cleanup
     * sweep. None of it changes session state, so the return only has to undo a transaction the
     * driver may have left open.
     */
    INTERNAL,

    /**
     * Author-authored SQL (`withConnection`, `withQuery`, `execute`). It can do anything the
     * restricted user can, including `SET SCHEMA`, `SET @var`, `SET AUTOCOMMIT OFF` and
     * `CREATE LOCAL TEMPORARY TABLE`, so the return restores every supported session default.
     */
    AUTHOR,
}

/** The session defaults every physical connection of one pool is restored to (§9.2). */
internal data class SessionDefaults(
    val schema: String,
    val transactionIsolation: Int,
) {
    companion object {
        /** Read off the bootstrap-handoff connection, so the defaults are the driver's, not a guess. */
        fun capture(connection: Connection): SessionDefaults =
            SessionDefaults(schema = connection.schema, transactionIsolation = connection.transactionIsolation)
    }
}

/**
 * Lease-return sanitation, verified against the pinned driver (2.3.232): what a pooled
 * connection carries between leases unless something undoes it, and how each is undone.
 *
 * ## Why this exists at all
 *
 * H2's own pooled handle (`JdbcXAConnection.PooledJdbcConnection.close`) does `rollback()` +
 * `setAutoCommit(true)` and nothing else — and swallows the `SQLException` if either fails, so a
 * broken connection is recycled silently. A logical `Connection.close()` on a plain connection
 * is not a reset either: it is a physical close. Neither shape lets a node that received a
 * connection another node dirtied rely on defaults, which is the promise §9.2 makes: **session
 * state is per lease, never a cross-node channel**, whichever idle connection a node draws.
 *
 * ## What is restored, and how
 *
 * - **An open transaction is rolled back, never committed.** `setAutoCommit(true)` on a
 *   connection mid-transaction COMMITS it per JDBC, so the rollback runs first, and only when
 *   the driver reports autocommit off — the ordinary case pays one flag read.
 * - **Isolation level** back to the captured default (`SET TRANSACTION ISOLATION LEVEL` is
 *   session-scoped in H2). Read-only is not on the list: H2's `setReadOnly` is a documented
 *   no-op and `isReadOnly` reports the *database*, so there is nothing to restore.
 * - **Current schema and search path** back to the default schema. `SET SCHEMA` from a previous
 *   node would otherwise re-point every unqualified table name a later node uses.
 * - **Session variables, local temporary tables and the session time zone** are enumerated from
 *   `INFORMATION_SCHEMA.SESSION_STATE` and undone one by one. That table's `STATE_KEY` is H2's
 *   own discriminator for exactly these kinds of session state (`@name`, `TABLE name`, `SCHEMA`,
 *   `SCHEMA_SEARCH_PATH`, `TIME ZONE` — `InformationSchemaTable.sessionState`), so this is a
 *   catalog enumeration, not a parser over SQL text; the `STATE_COMMAND` column is never read.
 * - **`QUERY_TIMEOUT` and `LOCK_TIMEOUT`** back to the driver's session defaults (`0` and
 *   `2000` ms on 2.3.232 — `Constants.INITIAL_LOCK_TIMEOUT`). Neither appears in
 *   `SESSION_STATE`, and a leaked `SET QUERY_TIMEOUT` would bound the next node's batched
 *   inserts, which set no statement-level timeout of their own.
 *
 * Anything an author can `SET` that is not on this list (`VARIABLE_BINARY`, `NON_KEYWORDS`,
 * `THROTTLE`, …) is not restored and is documented as unsupported across nodes (staging.md
 * §9.2). The list is bounded on purpose: sanitation runs once per author lease, on the lease
 * holder's own thread, and every entry is a state a real pipeline could plausibly leak.
 *
 * A reset that throws makes the connection unusable to the pool — it is discarded, never
 * recycled (see [H2ConnectionPool]).
 */
internal object H2SessionReset {
    /** Restores [connection] to [defaults] according to what a [kind] lease may have changed. */
    fun reset(
        connection: Connection,
        defaults: SessionDefaults,
        kind: LeaseKind,
    ) {
        if (!connection.autoCommit) {
            connection.rollback()
            connection.autoCommit = true
        }
        if (kind == LeaseKind.AUTHOR) resetAuthorSession(connection, defaults)
    }

    private fun resetAuthorSession(
        connection: Connection,
        defaults: SessionDefaults,
    ) {
        if (connection.transactionIsolation != defaults.transactionIsolation) {
            connection.transactionIsolation = defaults.transactionIsolation
        }
        val state = readSessionState(connection)
        connection.createStatement().use { st ->
            state.forEach { key -> undo(st, key, defaults) }
            // Executed unconditionally: neither is visible in SESSION_STATE, so there is no
            // cheaper way to know whether an author changed them.
            st.execute("SET QUERY_TIMEOUT $DEFAULT_QUERY_TIMEOUT")
            st.execute("SET LOCK_TIMEOUT $DEFAULT_LOCK_TIMEOUT_MS")
        }
    }

    /**
     * The session's own report of what it carries. Under the restricted user this is the
     * session's view only — H2 shows every session its own state, and no admin right is needed
     * (verified on 2.3.232).
     */
    private fun readSessionState(connection: Connection): List<String> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT STATE_KEY FROM INFORMATION_SCHEMA.SESSION_STATE").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }

    private fun undo(
        st: java.sql.Statement,
        key: String,
        defaults: SessionDefaults,
    ) {
        when {
            // `SET @name NULL` removes the variable (`SessionLocal.setVariable`); the name is
            // quoted because it is catalog-reported text, not because it is untrusted here.
            key.startsWith(VARIABLE_PREFIX) -> st.execute("SET @${quoted(key, VARIABLE_PREFIX)} NULL")

            key.startsWith(TEMP_TABLE_PREFIX) -> st.execute("DROP TABLE IF EXISTS ${quoted(key, TEMP_TABLE_PREFIX)}")

            key == SCHEMA_KEY -> if (st.connection.schema != defaults.schema) st.connection.schema = defaults.schema

            key == SEARCH_PATH_KEY -> st.execute("SET SCHEMA_SEARCH_PATH ${StagingIdentifiers.quote(defaults.schema)}")

            key == TIME_ZONE_KEY -> st.execute("SET TIME ZONE LOCAL")

            else -> throw SQLException("unknown H2 session state key '$key' — the reset list needs extending", UNKNOWN_STATE)
        }
    }

    private fun quoted(
        key: String,
        prefix: String,
    ): String = StagingIdentifiers.quote(key.removePrefix(prefix))

    private const val VARIABLE_PREFIX = "@"
    private const val TEMP_TABLE_PREFIX = "TABLE "
    private const val SCHEMA_KEY = "SCHEMA"
    private const val SEARCH_PATH_KEY = "SCHEMA_SEARCH_PATH"
    private const val TIME_ZONE_KEY = "TIME ZONE"

    /** The pinned driver's session defaults for the two settings `SESSION_STATE` does not report. */
    private const val DEFAULT_QUERY_TIMEOUT = 0
    private const val DEFAULT_LOCK_TIMEOUT_MS = 2000

    /** SQL class `HY` — "general error": a reset this object cannot perform is a reason to discard the connection. */
    private const val UNKNOWN_STATE = "HY000"
}
