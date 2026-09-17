package co.datapipelines.datasources.pooling

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLNonTransientConnectionException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * 152 (#128) — the `DataSource` a LAKE pool hands HikariCP: every physical connection is a
 * [LakeInstanceOwner.duplicate] of the generation's retained owner, followed by the
 * SESSION half of initialization.
 *
 * The split is the pinned driver's own (measured, see [LakeInstanceOwner]): extensions, the
 * secret, ATTACHes, engine limits and the per-table views are INSTANCE state and were applied
 * once on the owner; `search_path` is per connection, so the plan's postlude runs here on
 * every duplicate. Nothing global is re-run beside active readers — no `SET memory_limit`, no
 * `CREATE OR REPLACE VIEW` — on routine connection creation.
 *
 * HikariCP picks the overload by the config (verified on the pinned 6.3.3 `PoolBase`:
 * `username == null ? getConnection() : getConnection(username, password)`), and a `kind:
 * password` lake — an S3 key pair — carries its key id as the username. Both overloads
 * therefore open the SAME thing: a duplicate of the owner, which was itself opened through the
 * pool's `DriverDataSource` with that same username/password, and whose `CREATE SECRET` is
 * where the key pair actually reaches the engine. The arguments are not re-applied because
 * there is nothing to apply them to — a duplicate joins an instance, it does not log in. Of the
 * `CommonDataSource` plumbing only the login timeout is real (see [loginTimeoutSeconds]); the
 * wrapper owns no log writer.
 */
class LakeInstanceDataSource(
    private val owner: LakeInstanceOwner,
    /** Per-connection statements — the plan postlude (`SET search_path`), in order. */
    private val sessionInit: List<String>,
) : DataSource {
    /**
     * Hikari sets this from its `connectionTimeout` at pool start (`PoolBase.setLoginTimeout`,
     * pinned 6.3.3) and reads it back at shutdown as the bound on how long it waits for a
     * physical connection that is mid-creation on its creator thread before closing the bag —
     * a connection created after that point can no longer be added and would leak. Honouring
     * the value (rather than answering 0, which makes that wait zero) is what keeps the close/
     * open interleaving bounded: an in-flight duplicate is either handed over in time and then
     * aborted with the rest, or refused/closed by the retire check below.
     */
    @Volatile
    private var loginTimeoutSeconds: Int = 0

    override fun getConnection(): Connection {
        val connection = owner.duplicate()
        try {
            sessionInit.forEach { statement -> connection.createStatement().use { it.execute(statement) } }
            // Shutdown began while this duplicate was being set up: it must not be handed to a
            // pool that is closing (Hikari would abort it, or — after its bag closed — lose it).
            if (owner.isRetired) throw retiring()
        } catch (
            // SQLException from the driver or a RuntimeException DuckDB surfaces (the probe's
            // DS-SEC-6 rule): either way the duplicate is closed, never handed out half-set-up.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            runCatching { connection.close() }
            throw e
        }
        return connection
    }

    private fun retiring(): SQLNonTransientConnectionException =
        SQLNonTransientConnectionException(
            "the DuckDB instance owner for datasource '${owner.datasourceName}' (generation ${owner.generation}) " +
                "is shutting down; the connection was closed instead of joining it",
            SQLSTATE_CONNECTION_FAILURE,
        )

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = getConnection()

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) {
        loginTimeoutSeconds = seconds
    }

    override fun getLoginTimeout(): Int = loginTimeoutSeconds

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("no parent logger")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException("not a wrapper")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false

    private companion object {
        /** SQL standard class 08 — the connection-exception family every lease boundary already classifies. */
        const val SQLSTATE_CONNECTION_FAILURE = "08003"
    }
}
