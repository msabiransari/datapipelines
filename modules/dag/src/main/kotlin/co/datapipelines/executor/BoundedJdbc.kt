package co.datapipelines.executor

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The house shape for a bounded metadata statement (#311, lifted by #326): a JDBC `queryTimeout`
 * rides a DEDICATED `JdbcTemplate` on the same DataSource, so only the statements sent through the
 * returned template inherit it — every other statement on [jdbc] stays as it was. At the bound the
 * driver cancels the statement and Postgres reports `57014` (`query_canceled`); the statement is
 * rolled back, the caller's catch turns it into its own failed-tick line.
 *
 * The same DataSource means the same transaction: inside a Spring-managed transaction the returned
 * template's statements run on the transaction's connection (`DataSourceUtils`), and a transaction
 * timeout, when one is set, takes precedence over [seconds].
 *
 * A server that never answers cannot be reached this way (measured under #311: pgjdbc's cancel
 * cannot be delivered to a paused Postgres) — that is a caller-side bound's job.
 */
object BoundedJdbc {
    /**
     * [jdbc] itself when [seconds] is 0 (or less) — unbounded, every direct construction in the
     * tests — else a new template over [jdbc]'s DataSource carrying `queryTimeout = seconds`.
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
            NamedParameterJdbcTemplate(
                JdbcTemplate(dataSource).apply { queryTimeout = seconds },
            )
        } else {
            jdbc
        }
}
