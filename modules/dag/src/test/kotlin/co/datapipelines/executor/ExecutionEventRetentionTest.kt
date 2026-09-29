package co.datapipelines.executor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [ExecutionEventRetention] against a real Postgres running the **shipped** migrations —
 * 050/T60's two proofs, at the level the job runs:
 *
 * 1. **Retention works:** events of executions that completed past the window are deleted;
 *   events of a fresh execution AND of an old-but-still-`RUNNING` execution are kept (F3:
 *   retention is decided on the execution's `completed_at`, never on the event's own clock).
 * 2. **Blast radius (the rule the round names):** the job deletes `execution_events` rows
 *   ONLY — every `pipeline_executions` row survives untouched, row for row, including the
 *   one whose events were just deleted. `execution_events` is disposable history;
 *   `pipeline_executions` is the durable record.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionEventRetentionTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var events: ExecutionEventRepository
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID

    /** Binds the JDBC template to the module's shared, already-migrated container. */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        events = ExecutionEventRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        userId = insertUser()
        pipelineId = insertPipelineWithVersion()
    }

    @Test
    fun `retention deletes old completed executions' events and keeps everything else`() {
        val oldCompleted = createExecution(status = "SUCCESS", completedAgo = Duration.ofDays(30))
        val oldRunning = createExecution(status = "RUNNING", completedAgo = null, startedAgo = Duration.ofDays(30))
        val fresh = createExecution(status = "SUCCESS", completedAgo = Duration.ofMinutes(5))
        appendEvent(oldCompleted, "old_completed")
        appendEvent(oldRunning, "old_running")
        appendEvent(fresh, "fresh")

        val purged =
            ExecutionEventRetention(events, retention = Duration.ofDays(7)).retainOnce()

        purged shouldBe 1
        remainingEventIds() shouldContainExactly listOf("fresh", "old_running")
    }

    @Test
    fun `the blast-radius rule - pipeline_executions rows survive retention untouched`() {
        val oldCompleted = createExecution(status = "SUCCESS", completedAgo = Duration.ofDays(30))
        val fresh = createExecution(status = "SUCCESS", completedAgo = Duration.ofMinutes(5))
        appendEvent(oldCompleted, "old_completed")
        appendEvent(fresh, "fresh")

        val before = executionRows()

        ExecutionEventRetention(events, retention = Duration.ofDays(7)).retainOnce()

        // Row-for-row identical: no status flip, no deletion, no rewrite — the durable
        // record outlives its events (metadata-db §8.1).
        executionRows() shouldBe before
        remainingEventIds() shouldContainExactly listOf("fresh")
    }

    @Test
    fun `a metadata-DB fault fails the tick as zero - never the scheduler`() {
        val failing =
            ExecutionEventRetention(
                mockk {
                    every { deleteOlderThan(any()) } throws DataAccessResourceFailureException("down")
                },
                retention = Duration.ofDays(7),
            )

        failing.retainOnce() shouldBe 0
    }

    /**
     * #321 (observability §3.4G): the failure line names the store's failure by its class and its
     * SQLState — never its message, which Spring fills with the statement and the driver's text,
     * and never its stack, which would print that message again.
     */
    @Test
    fun `the failure line names the class and the SQLState - never the store's message`() {
        val failing =
            ExecutionEventRetention(
                mockk {
                    every { deleteOlderThan(any()) } throws
                        DataAccessResourceFailureException("DELETE refused: $SENTINEL", SQLException("driver: $SENTINEL", SQL_STATE))
                },
                retention = Duration.ofDays(7),
            )

        val (purged, lines) = captured { failing.retainOnce() }

        purged shouldBe 0
        val warn = lines.single { it.level == Level.WARN }
        warn.formattedMessage shouldContain "event=execution.event_retention_failed"
        warn.formattedMessage shouldContain "error=DataAccessResourceFailureException"
        warn.formattedMessage shouldContain "sql_state=$SQL_STATE"
        warn.formattedMessage shouldNotContain SENTINEL
        warn.formattedMessage shouldNotContain "message="
        warn.throwableProxy.shouldBeNull()
    }

    @Test
    fun `the window comes from the binding - not an interval literal`() {
        // The retention window is resolved from `datapipelines.executions.event-retention-days`
        // by the assembling layer (D8): seven days deletes a 30-day-old execution's events,
        // keeps a 5-day-old one's.
        val old = createExecution(status = "SUCCESS", completedAgo = Duration.ofDays(30))
        val recent = createExecution(status = "SUCCESS", completedAgo = Duration.ofDays(5))
        appendEvent(old, "old")
        appendEvent(recent, "recent")

        ExecutionEventRetention(events, retention = Duration.ofDays(7)).retainOnce()

        remainingEventIds() shouldContainExactly listOf("recent")
    }

    // ------------------------------------------------------------------------ fixtures

    private fun createExecution(
        status: String,
        completedAgo: Duration?,
        startedAgo: Duration = Duration.ZERO,
    ): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                INSERT INTO pipeline_executions
                    (execution_id, pipeline_id, pipeline_version, status, parameters_json,
                     executed_by, triggered_via, correlation_id, started_at, completed_at, root_execution_id)
                VALUES
                    (:id, :pipelineId, 1, :status, '{}',
                     :userId, 'REST', :id, :startedAt, :completedAt, :id)
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "pipelineId" to pipelineId,
                    "status" to status,
                    "userId" to userId,
                    "startedAt" to java.sql.Timestamp.from(Instant.now().minus(startedAgo)),
                    "completedAt" to completedAgo?.let { java.sql.Timestamp.from(Instant.now().minus(it)) },
                ),
            )
        }

    /** One event whose payload carries [tag] — the suite's observable. */
    private fun appendEvent(
        executionId: UUID,
        tag: String,
    ) {
        events.append(
            ExecutionEventRecord(
                executionId = executionId,
                eventId = 1,
                eventType = "node_started",
                timestamp = Instant.now(),
                payloadJson = """"$tag"""",
            ),
        )
    }

    private fun remainingEventIds(): List<String> =
        jdbc.jdbcTemplate
            .queryForList(
                "SELECT payload_json::TEXT FROM execution_events ORDER BY payload_json::TEXT",
                String::class.java,
            ).map { it.trim('"') }

    /** The blast-radius assertion surface: the durable rows, verbatim. */
    private fun executionRows(): List<Map<String, Any>> =
        jdbc.jdbcTemplate.queryForList(
            "SELECT execution_id, status, completed_at::TEXT, error_json::TEXT FROM pipeline_executions ORDER BY execution_id",
        )

    private fun insertUser(): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject)
                VALUES (:id, :email, 'Retention Test', 'google', :sub)
                """.trimIndent(),
                mapOf("id" to id, "email" to "ret-$id@example.com", "sub" to "sub-$id"),
            )
        }

    private fun insertPipelineWithVersion(): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
                VALUES (:id, :name, 'Retention Test Pipeline', :owner, 1, 'defa0000-0000-0000-0000-000000000001')
                """.trimIndent(),
                mapOf("id" to id, "name" to "retention-$id", "owner" to userId),
            )
            jdbc.update(
                """
                -- Seed row carries V6's lifecycle columns (035): body_hash NOT NULL since the
                -- version-lifecycle migration; this suite does not exercise hashing.
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())
                """.trimIndent(),
                mapOf("id" to id, "owner" to userId),
            )
        }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()

    /** Runs [block] with the job's logger captured; returns its value and every line it logged. */
    private fun <T> captured(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(ExecutionEventRetention::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val value =
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
        return value to appender.list.toList()
    }

    private companion object {
        /** Stands in for a refused row's text in the store's message; a log line must never carry it. */
        const val SENTINEL = "SENTINEL-ROW-TEXT"

        /** `connection_failure` — a state the driver, not the message, reports. */
        const val SQL_STATE = "08006"
    }
}
