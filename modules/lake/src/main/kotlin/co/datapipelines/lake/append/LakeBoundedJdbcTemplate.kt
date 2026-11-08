package co.datapipelines.lake.append

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Statement
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * The managed lake runtime's statement template (#858): every statement sent through it carries a `queryTimeout` of
 * [seconds] and runs on a connection whose network timeout is [seconds] + [SOCKET_SLACK_SECONDS], in milliseconds.
 * Built only by `LakeAppendRuntime.statements`.
 *
 * The same contract as the house helper `BoundedJdbc` in `modules/dag` (#837), restated here on purpose: `modules/lake`
 * does not depend on `dag`, and the persistence module carries no Spring JDBC (the ratified module boundary), so the
 * small override is duplicated rather than lifted. `LakeBoundedJdbcTemplateTest` pins this copy to that contract.
 *
 * - **The statement bound.** At [seconds] the driver cancels the statement; Postgres answers `57014`.
 * - **The socket bound.** A server that accepts and never answers cannot be reached by the statement bound: the cancel is
 *   a second connection the paused server never reads. The read that never returns ends with a `SocketTimeoutException`,
 *   which pgjdbc reports as SQLState `08006`; the driver closes the connection and the pool discards it. Measured under
 *   #837 (pgjdbc 42.7.14, a paused container): the statement ends at the LATER of the socket bound and [seconds] plus the
 *   driver's `cancelSignalTimeout` (10 s by default), so at a 5 s bound it ends at 15 s, not 10 s.
 *
 * The network timeout is set where Spring applies the statement settings, on the connection the statement reports
 * (`Statement.getConnection()`), so it reaches a transaction's connection and a fresh lease alike, for every statement
 * shape Spring creates (plain, prepared, callable). The DataSource is the runtime's own, never wrapped: inside a Spring
 * transaction the statement runs on that transaction's connection, which Spring keys by DataSource identity. There the
 * connection keeps the bound until the transaction ends and HikariCP restores the pool's value on return; every
 * transaction the runtime opens is a five-second one, whose statements Spring caps at the time left, so none of them is
 * longer than the socket bound. A driver that refuses the setting fails the statement (Spring translates the
 * `SQLException`) rather than running it without the bound.
 */
internal class LakeBoundedJdbcTemplate(
    dataSource: DataSource,
    private val seconds: Int,
) : JdbcTemplate(dataSource) {
    init {
        require(seconds > 0) { "the lake runtime's statement bound must be positive: $seconds" }
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
