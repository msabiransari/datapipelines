package co.datapipelines.executor

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Statement
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * The house shape for a bounded metadata statement (#311, lifted by #326, socket bound #837): a JDBC `queryTimeout`
 * AND a socket read bound ride a DEDICATED [BoundedJdbcTemplate] on the same DataSource, so only the statements sent
 * through the returned template inherit them — every other statement on [jdbc] stays as it was.
 *
 * - **The statement bound.** At [BoundedJdbc.bounded]'s `seconds` the driver cancels the statement and Postgres reports
 *   `57014` (`query_canceled`); the statement is rolled back, the caller's catch turns it into its own failed-tick line.
 * - **The socket bound (#837).** A server that accepts and never answers cannot be reached by the statement bound
 *   (measured under #311: pgjdbc's cancel is a SECOND connection, which a paused Postgres never reads). So the connection
 *   a bounded statement runs on also gets a network timeout of `seconds` + [BoundedJdbcTemplate.SOCKET_SLACK_SECONDS],
 *   in milliseconds: the read that never returns ends with a `SocketTimeoutException` the driver reports as an I/O
 *   failure (SQLState `08006`), the driver closes the connection, and the pool discards it. The slack lets a statement that
 *   IS answering finish its own cancel first. This is the caller-side bound the statement bound could not be.
 *
 * The same DataSource means the same transaction: inside a Spring-managed transaction the returned template's statements
 * run on the transaction's connection (`DataSourceUtils`), and a transaction timeout, when one is set, takes precedence
 * over `seconds`. A DataSource wrapper would take them OUT of it (the transaction's connection is keyed by DataSource
 * identity), which is why the socket bound is set on the template, per statement, on whichever connection it runs on.
 * Consequence, stated: inside a transaction the connection keeps the bound after the statement, until the transaction ends
 * and HikariCP restores the pool's own network timeout on return (its proxy marks the setting dirty; verified on the
 * pinned HikariCP 6.3.3 and pinned by `BoundedJdbcPostgresIntegrationTest`). So a transaction that mixes statements of a
 * bounded template with a LONGER statement on another template would cut the longer one at the last-applied bound — keep
 * a transaction's statements on templates of one bound.
 */
object BoundedJdbc {
    /**
     * [jdbc] itself when [seconds] is 0 (or less) — unbounded, every direct construction in the
     * tests — else a new [BoundedJdbcTemplate] over [jdbc]'s DataSource carrying `queryTimeout = seconds` and the
     * socket bound.
     *
     * @param bound the name of the bound, for the refusal when [jdbc] has no DataSource.
     */
    fun bounded(
        jdbc: NamedParameterJdbcTemplate,
        seconds: Int,
        bound: String,
    ): NamedParameterJdbcTemplate =
        if (seconds > 0) {
            val dataSource =
                requireNotNull(jdbc.jdbcTemplate.dataSource) {
                    "$bound requires a DataSource-backed NamedParameterJdbcTemplate"
                }
            NamedParameterJdbcTemplate(BoundedJdbcTemplate(dataSource, seconds))
        } else {
            jdbc
        }
}

/**
 * A [JdbcTemplate] whose every statement carries a `queryTimeout` of [seconds] and runs on a connection whose network
 * timeout is [seconds] + [SOCKET_SLACK_SECONDS] (#837). Built only by [BoundedJdbc.bounded].
 *
 * The network timeout is set where Spring applies the statement settings, on the connection the statement itself reports
 * (`Statement.getConnection()`), so it reaches the transaction's connection or a fresh lease alike. A driver that refuses
 * it fails the statement — Spring translates the `SQLException` — rather than silently running without the bound; the
 * metadata store is PostgreSQL, whose driver supports it.
 */
internal class BoundedJdbcTemplate(
    dataSource: DataSource,
    private val seconds: Int,
) : JdbcTemplate(dataSource) {
    init {
        queryTimeout = seconds
    }

    override fun applyStatementSettings(stmt: Statement) {
        super.applyStatementSettings(stmt)
        // A direct executor: the JDBC contract wants one for aborting a connection; pgjdbc never uses it for a read bound.
        stmt.connection.setNetworkTimeout(DIRECT, (seconds + SOCKET_SLACK_SECONDS) * MILLIS_PER_SECOND)
    }

    companion object {
        /** Seconds a socket read may outlast the statement bound: time for the driver's cancel to land when it can. */
        const val SOCKET_SLACK_SECONDS = 5

        private const val MILLIS_PER_SECOND = 1_000
        private val DIRECT = Executor { it.run() }
    }
}
