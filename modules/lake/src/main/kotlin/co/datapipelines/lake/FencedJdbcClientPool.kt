package co.datapipelines.lake

import org.apache.iceberg.jdbc.JdbcClientPool
import org.apache.iceberg.jdbc.UncheckedSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * The catalog's connection pool under ONE lease (§8a "How the token reaches Iceberg's connection"; E1 #774
 * `FencedPool`). Two departures from Iceberg's stock [JdbcClientPool]:
 *
 * 1. **Every connection carries the lease.** [newClient] opens a DEDICATED connection through `DriverManager` and
 *    sets `dp.lease_token`, `dp.operation_id`, `lock_timeout` and `statement_timeout` as SESSION settings
 *    (`set_config(…, false)`), which the V53 fence trigger reads. Session scope, not the brief's transaction-local
 *    form (owner ruling 2026-11-02): Iceberg 1.12.0's JDBC code never opens a transaction, so a transaction-local
 *    setting would vanish before the library's own statement ran. Session scope is safe because the pool is
 *    private to one lease holder and is closed with its catalog when the lease is released — no connection
 *    outlives its lease, and DP's own Hikari pool never sees these settings (it never hands out these
 *    connections).
 * 2. **No reconnect-and-replay.** [isConnectionException] answers false, so `ClientPoolImpl.run` never reconnects
 *    and re-runs the action after a lost connection. A replayed commit UPDATE whose first attempt DID commit
 *    reports a false `CommitFailedException`, and the library then deletes the committed files (E1 F3, case A).
 *    The lost connection instead surfaces once, as [LakeCatalogException.OutcomeUnknown].
 *
 * The connection properties carry the catalog database's user and password; they are never logged.
 */
class FencedJdbcClientPool(
    private val url: String,
    private val connectionProperties: Properties,
    private val lease: LeaseHandle,
    private val lockTimeoutSeconds: Long,
    private val statementTimeoutSeconds: Long,
    poolSize: Int = DEFAULT_POOL_SIZE,
) : JdbcClientPool(poolSize, url, emptyMap()) {
    override fun newClient(): Connection {
        val connection =
            try {
                DriverManager.getConnection(url, connectionProperties)
            } catch (e: SQLException) {
                throw UncheckedSQLException(e, "Failed to connect to the lake catalog")
            }
        try {
            connection.prepareStatement(SESSION_SQL).use { statement ->
                statement.setString(1, lease.token.toString())
                statement.setString(2, lease.operationId.toString())
                statement.setString(3, "${lockTimeoutSeconds}s")
                statement.setString(4, "${statementTimeoutSeconds}s")
                statement.executeQuery().use { it.next() }
            }
        } catch (e: SQLException) {
            runCatching { connection.close() }
            throw UncheckedSQLException(e, "Failed to set the lake lease on a catalog connection")
        }
        return connection
    }

    /** Never a reconnect-and-replay (§8a, E1 F3) — see the class note. */
    override fun isConnectionException(e: Exception): Boolean = false

    companion object {
        /** E1's size: Iceberg holds at most one connection per catalog call, and a lease session is one caller. */
        const val DEFAULT_POOL_SIZE = 2

        /** The four session settings, one round-trip. Parameterised: no lease value is ever interpolated. */
        internal const val SESSION_SQL =
            "SELECT set_config('dp.lease_token', ?, false), set_config('dp.operation_id', ?, false)," +
                " set_config('lock_timeout', ?, false), set_config('statement_timeout', ?, false)"
    }
}
