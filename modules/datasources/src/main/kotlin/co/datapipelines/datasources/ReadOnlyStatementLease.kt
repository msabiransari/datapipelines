package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import org.springframework.jdbc.core.SqlTypeValue
import org.springframework.jdbc.core.StatementCreatorUtils
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The JDBC form of a canonical value: spring-jdbc's `TYPE_UNKNOWN` path hands anything but a String,
 * a `java.util.Date` or a Calendar to `setObject`, and pgjdbc cannot infer a type for an `Instant`
 * ("Can't infer the SQL type to use for an instance of java.time.Instant") — the house repositories
 * convert first, and so does this lease: an `Instant` binds as an `OffsetDateTime` at UTC, which every
 * pinned driver maps to `timestamptz`. Every other canonical value (String, BigDecimal, BigInteger,
 * Boolean, Int, LocalDate, LocalTime, LocalDateTime) already binds through the driver's own table.
 * Found by the 194c security pass (observation 1); `SelectorRunnerIntegrationTest` binds one.
 */
private fun jdbcForm(value: Any?): Any? = if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value

/**
 * The lease the parameter engine's selector runner reads through (parameter-engine record §2.5,
 * P31) — a sibling of [ConnectionLease.lease] for a caller that may have to ABANDON its statement.
 *
 * [ConnectionLease.lease] is block-shaped: the connection lives exactly as long as the block, and
 * the block's thread is the only one that can end it. The selector runner runs each statement on a
 * worker thread and awaits it under the evaluate's deadline; when the deadline fires, the AWAITING
 * side must be able to stop the statement and make sure its connection never serves another
 * borrower, without waiting for a driver that may not listen. So [open] hands back a
 * [LeasedStatement] — the prepared statement on its leased connection — whose [LeasedStatement.abandon]
 * is that discard handle, callable from any thread.
 *
 * The same discipline as [SqlProbe], in the same order:
 *
 *  1. **The gate first.** [SqlStatementClassifier] admits exactly one `SELECT`/`WITH` before
 *     anything is leased — datasources.md §7D's read-only gate; a refusal is
 *     [SqlProbeRefusalException] and nothing ran.
 *  2. **One transaction, one database** ([ConnectionLease.refuseInsideMetadataTransaction]).
 *  3. **The lease**, translated exactly like [ConnectionLease.lease]: a refused or failed lease is
 *     [DatasourceUnreachableException].
 *  4. **The statement**: `queryTimeout` = the datasource's `query_timeout_seconds`, clamped to the
 *     caller's ceiling (the record's §6.2 — `selector-query-timeout-seconds`); `maxRows` and
 *     `fetchSize` = the caller's row budget; the positional values bound through Spring's
 *     [StatementCreatorUtils], the binder every other statement path here uses.
 *
 * [LeasedStatement.query] then classifies a failure the way the probe does: a connection-family
 * `SQLException` is [DatasourceUnreachableException], a statement timeout
 * [SqlProbeTimeoutException], anything else the database said [SqlProbeExecutionException].
 * [ConnectionLease.lease] and [SqlProbe] are untouched: every other caller keeps its block.
 */
class ReadOnlyStatementLease(
    private val registry: DatasourceRegistry,
) {
    /**
     * Gates [sql], leases a connection for [datasource] (already visibility-gated by the caller),
     * and prepares [sql] with [bindValues] bound positionally, at most [maxRows] rows, and a
     * statement timeout of the datasource's own `query_timeout_seconds` clamped to
     * [timeoutCeilingSeconds]. The caller owns the answer: [LeasedStatement.close] on every path, or
     * [LeasedStatement.abandon] when it will not wait for it.
     *
     * @throws SqlProbeRefusalException the SQL is not a single read-only `SELECT`/`WITH` — nothing leased.
     * @throws DatasourceUnreachableException the pool could not hand out a connection.
     * @throws SqlProbeExecutionException the database refused to prepare the statement.
     */
    fun open(
        datasource: Datasource,
        sql: String,
        bindValues: List<Any?>,
        maxRows: Int,
        timeoutCeilingSeconds: Int,
    ): LeasedStatement {
        require(maxRows > 0) { "maxRows must be positive, was $maxRows" }
        require(timeoutCeilingSeconds > 0) { "timeoutCeilingSeconds must be positive, was $timeoutCeilingSeconds" }
        val statementText = SqlStatementClassifier.classify(sql, datasource.dialect)
        ConnectionLease.refuseInsideMetadataTransaction(datasource.name)
        val (pool, connection) = lease(datasource)
        return try {
            val statement = connection.prepareStatement(statementText)
            try {
                statement.queryTimeout = effectiveTimeoutSeconds(datasource, timeoutCeilingSeconds)
                statement.fetchSize = maxRows
                statement.maxRows = maxRows
                bindValues.forEachIndexed { index, value ->
                    StatementCreatorUtils.setParameterValue(statement, index + 1, SqlTypeValue.TYPE_UNKNOWN, jdbcForm(value))
                }
            } catch (e: SQLException) {
                runCatching { statement.close() }
                throw e
            }
            LeasedStatement(datasource.name, pool, connection, statement)
        } catch (e: SQLException) {
            runCatching { connection.close() }
            if (e.isConnectionFailure()) ConnectionLease.unreachable(datasource.name, e)
            throw SqlProbeExecutionException(datasource.name, e)
        }
    }

    /** The pool and one connection from it — a pool that cannot be built or leased from is unreachable, as in [ConnectionLease.lease]. */
    @Suppress("TooGenericExceptionCaught") // ConnectionLease.lease's two families, for the same reasons
    private fun lease(datasource: Datasource): Pair<ConnectionPool, Connection> =
        try {
            val pool = registry.poolFor(datasource)
            pool to pool.leaseConnection()
        } catch (e: SQLException) {
            ConnectionLease.unreachable(datasource.name, e)
        } catch (e: RuntimeException) {
            ConnectionLease.unreachable(datasource.name, e)
        }

    companion object {
        /**
         * The statement timeout: the datasource's own `query_timeout_seconds` when it declares one,
         * never above [ceilingSeconds] (the record's §6.2 clamp); the ceiling itself when it declares none.
         */
        fun effectiveTimeoutSeconds(
            datasource: Datasource,
            ceilingSeconds: Int,
        ): Int = (datasource.queryTimeoutSeconds ?: ceilingSeconds).coerceIn(1, ceilingSeconds)
    }
}

/**
 * One prepared read-only statement on its leased connection — see [ReadOnlyStatementLease].
 *
 * Exactly ONE of two endings wins, decided by one compare-and-set: [close] (the statement finished
 * — the connection goes back to its pool) or [abandon] (the caller gave up on a statement that may
 * still be running — the connection is DISCARDED, never returned). They must exclude each other:
 * an [abandon] that ran after a normal [close] would `cancel()` a connection the pool may already
 * have handed to its next borrower — cancelling THAT borrower's statement — so an [abandon] that
 * loses the race does nothing, and a [close] that loses it closes the statement and returns
 * nothing. Both are idempotent and never throw.
 */
class LeasedStatement internal constructor(
    val datasourceName: String,
    private val pool: ConnectionPool,
    private val connection: Connection,
    private val statement: PreparedStatement,
) : AutoCloseable {
    /** [OPEN] until the first of [close] / [abandon] claims it. */
    private val ending = AtomicInteger(OPEN)

    /** Whether [abandon] won — the connection was discarded, not returned. */
    val isAbandoned: Boolean get() = ending.get() == ABANDONED

    /**
     * Executes the statement and hands its result set to [read] (closed after), translating a
     * failure: a connection-family `SQLException` is [DatasourceUnreachableException], a statement
     * timeout (`SQLTimeoutException`, pgjdbc's `57014`) [SqlProbeTimeoutException] with the time the
     * statement ran, any other `SQLException` [SqlProbeExecutionException] with the bounded driver text.
     */
    fun <T> query(read: (ResultSet) -> T): T {
        val startedAt = System.nanoTime()
        return try {
            statement.executeQuery().use(read)
        } catch (e: SQLException) {
            // The timeout first, as SqlProbe classifies it: `SQLTimeoutException` is ALSO in the
            // connection family (a dead network surfaces as one), and a statement that outlived
            // its own timeout is not an unreachable database.
            when {
                e.isStatementTimeout() -> throw SqlProbeTimeoutException(datasourceName, wallMs(startedAt), null, e)
                e.isConnectionFailure() -> ConnectionLease.unreachable(datasourceName, e)
                else -> throw SqlProbeExecutionException(datasourceName, e)
            }
        }
    }

    /**
     * The discard handle (parameter-engine record §5.2 step 3): `Statement.cancel()` — best effort
     * by the JDBC contract, and a driver may ignore it — then [ConnectionPool.discard], so the
     * physical connection is closed now and never handed to another borrower, whether or not the
     * cancel landed. Callable from any thread while [query] still blocks on another; a no-op once
     * [close] has returned the connection (see the class KDoc).
     */
    fun abandon() {
        if (!ending.compareAndSet(OPEN, ABANDONED)) return
        runCatching { statement.cancel() }
        pool.discard(connection)
    }

    /** Closes the statement and — unless [abandon] claimed the connection first — returns the connection to its pool. */
    override fun close() {
        if (ending.compareAndSet(OPEN, CLOSED)) {
            runCatching { statement.close() }
            runCatching { connection.close() }
        } else if (ending.get() == ABANDONED) {
            // The connection is the pool's no more; only the statement object is ours to release.
            runCatching { statement.close() }
        }
    }

    private fun wallMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private companion object {
        const val OPEN = 0
        const val CLOSED = 1
        const val ABANDONED = 2
    }
}
