package co.datapipelines.web.config

import co.datapipelines.auth.AuditProperties
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * #326 — the four `@Scheduled` jobs' metadata statements carry their bounds AS THE APPLICATION BUILDS
 * THEM: every job is constructed by its production `@Bean` method over a DataSource that records the
 * `queryTimeout` each statement is given (Spring applies a template's `queryTimeout` with
 * `Statement.setQueryTimeout` outside a transaction). The numbers are the issue's, literals — never read
 * back from the constants under test — so a constant reverted to 0, or a statement sent on the unbounded
 * template, is red here. That the driver then cancels at the bound is the E2E's
 * (`ScheduledJobsSchedulerE2eTest`, the sweep on a real Postgres) and #311's
 * (`LifecycleWriteBoundIntegrationTest`).
 *
 * The negative half matters as much: the bound rides a DEDICATED template, so the same repositories'
 * other statements — the heartbeat, the event reads — carry none, and the lifecycle writes keep their own.
 */
class ScheduledJobsStatementBoundsTest {
    private val recording = RecordingDataSource()
    private val jdbc = NamedParameterJdbcTemplate(recording)

    @Test
    fun `the stale sweep's UPDATE carries the sweep bound - the repository's other statements do not`() {
        val executions = EngineConfiguration().executionRepository(jdbc, ExecutorProperties(lifecycleWriteTimeoutSeconds = LIFECYCLE))

        executions.sweepStaleRunning(Instant.now(), Instant.now())
        executions.heartbeat(UUID.randomUUID())

        recording.timeoutOf("UPDATE pipeline_executions\n   SET status = 'ABORTED'") shouldBe SWEEP_BOUND
        recording.timeoutOf("UPDATE pipeline_executions SET heartbeat_at") shouldBe UNBOUNDED
    }

    @Test
    fun `the event retention's DELETE carries the retention bound - the event reads do not`() {
        val events = EngineConfiguration().executionEventRepository(jdbc)

        events.deleteOlderThan(Instant.now())
        events.lastEventId(UUID.randomUUID())

        recording.timeoutOf("DELETE FROM execution_events") shouldBe RETENTION_BOUND
        recording.timeoutOf("SELECT COALESCE(MAX(event_id)") shouldBe UNBOUNDED
    }

    @Test
    fun `every statement of the audit retention carries the retention bound - the cutoff read and the batch`() {
        RetentionSchedulingConfiguration().auditLogRetention(jdbc, AuditProperties(), SimpleMeterRegistry()).purgeOnce()

        recording.timeoutOf("SELECT NOW() - make_interval") shouldBe RETENTION_BOUND
        recording.timeoutOf("DELETE FROM audit_log") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
    }

    @Test
    fun `every statement of the keys purge carries the retention bound - the FK read and both DELETEs`() {
        RetentionSchedulingConfiguration().keyRetentionPurge(jdbc).purgeOnce()

        recording.timeoutOf("SELECT kcu.table_name") shouldBe RETENTION_BOUND
        recording.timeoutOf("DELETE FROM api_keys") shouldBe RETENTION_BOUND
        recording.timeoutOf("DELETE FROM users") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
    }

    /**
     * Hands out relaxed JDBC doubles and records, per prepared statement, its SQL and the
     * `queryTimeout` set on it ([UNBOUNDED] when none was). A query answers one row: one column, the
     * database clock for the audit cutoff, a `(table, column)` pair for the keys purge's FK list.
     */
    private class RecordingDataSource : DataSource by mockk(relaxed = true) {
        private val statements = mutableListOf<Pair<String, IntArray>>()

        override fun getConnection(): Connection =
            mockk(relaxed = true) {
                every { prepareStatement(any<String>()) } answers { statement(firstArg()) }
            }

        private fun statement(sql: String): PreparedStatement {
            val timeout = intArrayOf(UNBOUNDED)
            statements += sql to timeout
            return mockk(relaxed = true) {
                every { setQueryTimeout(any()) } answers { timeout[0] = firstArg() }
                every { executeUpdate() } returns 0
                every { executeQuery() } answers { oneRow() }
            }
        }

        private fun oneRow(): ResultSet {
            var rows = 1
            return mockk(relaxed = true) {
                every { next() } answers { rows-- > 0 }
                every { metaData.columnCount } returns 1
                every { getObject(1, OffsetDateTime::class.java) } returns OffsetDateTime.now()
                every { getString("referencing_table") } returns "executions"
                every { getString("referencing_column") } returns "executed_by"
            }
        }

        /** The one statement whose SQL starts with [prefix] (after trimming); its recorded timeout. */
        fun timeoutOf(prefix: String): Int {
            val matching = statements.filter { (sql, _) -> sql.trim().startsWith(prefix) }
            withClue(
                "statements sent: ${statements.map {
                    it.first
                        .trim()
                        .lineSequence()
                        .first()
                }}",
            ) {
                matching.shouldNotBeEmpty()
                matching.map { it.second[0] }.toSet().size shouldBe 1
            }
            return matching.first().second[0]
        }

        fun everyTimeout(): Set<Int> = statements.map { it.second[0] }.toSet()
    }

    private companion object {
        /** #326: the sweep's bound — two-thirds of its 15 s cadence. */
        const val SWEEP_BOUND = 10

        /** #326: the hourly retention's bound — under the 30 s shutdown phase. */
        const val RETENTION_BOUND = 25

        /** A lifecycle bound unlike both, so a statement sent on the lifecycle template cannot pass for the sweep's. */
        const val LIFECYCLE = 3

        /** No `setQueryTimeout` call. */
        const val UNBOUNDED = 0
    }
}
