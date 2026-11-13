package co.datapipelines.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * [BoundedJdbc] — #311's construction, lifted by #326: 0 is the template itself (unbounded, every
 * direct construction in the tests); a positive bound is a NEW template on the SAME DataSource, so
 * only its statements carry the `queryTimeout` and the caller's template is left as it was. That the
 * driver cancels at the bound is `LifecycleWriteBoundIntegrationTest`'s case A (the lifecycle writes)
 * and `ScheduledJobsSchedulerE2eTest` (the sweep), both on a real Postgres.
 *
 * #837 — the same template also bounds the SOCKET: every statement sent through it runs on a connection whose network
 * timeout is the statement bound plus five seconds, in milliseconds, so a server that accepts and never answers ends
 * the statement at that bound, where the driver's cancel (a second connection the paused server never reads) cannot.
 * The caller's own template sends none. That the bound ends a statement against a paused Postgres is
 * `ScheduledJobsMetadataBoundsE2eTest`'s; that a real pool restores its own timeout, and that the bound rides the
 * transaction's connection, is `BoundedJdbcPostgresIntegrationTest`'s.
 */
class BoundedJdbcTest {
    private val dataSource = mockk<DataSource>()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    @Test
    fun `zero is the template itself - unbounded`() {
        BoundedJdbc.bounded(jdbc, 0, "bound") shouldBeSameInstanceAs jdbc
    }

    @Test
    fun `a positive bound is a new template on the same DataSource, and the caller's template is untouched`() {
        val bounded = BoundedJdbc.bounded(jdbc, SECONDS, "bound")

        bounded shouldNotBeSameInstanceAs jdbc
        bounded.jdbcTemplate.queryTimeout shouldBe SECONDS
        bounded.jdbcTemplate.dataSource shouldBeSameInstanceAs dataSource
        jdbc.jdbcTemplate.queryTimeout shouldBe UNBOUNDED
    }

    @Test
    fun `a positive bound refuses a template with no DataSource, naming the bound`() {
        val noDataSource = NamedParameterJdbcTemplate(JdbcTemplate())

        shouldThrow<IllegalArgumentException> { BoundedJdbc.bounded(noDataSource, SECONDS, "sweepStatementTimeoutSeconds") }
            .message shouldBe "sweepStatementTimeoutSeconds requires a DataSource-backed NamedParameterJdbcTemplate"
    }

    @Test
    fun `a statement through a positive bound sets the connection's network timeout to the bound plus five seconds, in milliseconds`() {
        val connection = connectionAnsweringOneUpdate()
        val executor = slot<Executor>()
        every { connection.setNetworkTimeout(capture(executor), any()) } returns Unit

        BoundedJdbc.bounded(jdbcOver(connection), SECONDS, "bound").update("UPDATE t SET a = 1", emptyMap<String, Any>())

        verify(exactly = 1) { connection.setNetworkTimeout(any(), SOCKET_MILLIS) }
        // The driver is handed an executor that runs inline: no thread is spent on a bound.
        var ran = false
        executor.captured.execute { ran = true }
        ran shouldBe true
    }

    @Test
    fun `the caller's template and a zero bound send no network timeout - the unbounded statement keeps the driver's own`() {
        val connection = connectionAnsweringOneUpdate()

        jdbcOver(connection).update("UPDATE t SET a = 1", emptyMap<String, Any>())
        BoundedJdbc.bounded(jdbcOver(connection), 0, "bound").update("UPDATE t SET a = 1", emptyMap<String, Any>())

        verify(exactly = 0) { connection.setNetworkTimeout(any(), any()) }
    }

    @Test
    fun `every statement of a bounded template carries the bound - not only the first`() {
        val connection = connectionAnsweringOneUpdate()
        val bounded = BoundedJdbc.bounded(jdbcOver(connection), SECONDS, "bound")

        repeat(STATEMENTS) { bounded.update("UPDATE t SET a = 1", emptyMap<String, Any>()) }

        verify(exactly = STATEMENTS) { connection.setNetworkTimeout(any(), SOCKET_MILLIS) }
    }

    @Test
    fun `a driver that refuses the network timeout fails the statement - a bound that cannot be set is not skipped`() {
        val connection = connectionAnsweringOneUpdate()
        every { connection.setNetworkTimeout(any(), any()) } throws SQLFeatureNotSupportedException("no")

        shouldThrow<DataAccessException> {
            BoundedJdbc.bounded(jdbcOver(connection), SECONDS, "bound").update("UPDATE t SET a = 1", emptyMap<String, Any>())
        }
    }

    /** A connection whose one prepared statement reports itself as running on it and answers 0 rows. */
    private fun connectionAnsweringOneUpdate(): Connection {
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { statement.connection } returns connection
        every { statement.executeUpdate() } returns 0
        every { connection.prepareStatement(any<String>()) } returns statement
        return connection
    }

    private fun jdbcOver(connection: Connection): NamedParameterJdbcTemplate {
        val source = mockk<DataSource>()
        every { source.connection } returns connection
        return NamedParameterJdbcTemplate(source)
    }

    private companion object {
        const val SECONDS = 7

        /** #837: the bound plus five seconds, in milliseconds — a literal, never computed from the class under test. */
        const val SOCKET_MILLIS = 12_000
        const val STATEMENTS = 3

        /** `JdbcTemplate`'s own default: -1, the driver's. */
        const val UNBOUNDED = -1
    }
}
