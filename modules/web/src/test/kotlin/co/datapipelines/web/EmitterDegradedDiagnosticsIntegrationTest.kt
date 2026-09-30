package co.datapipelines.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.sse.BatchedEventRecorder
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionEventRowSink
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.ReplayLogSink
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The terminal record's two degraded diagnostics (#336 D5), asserted at the PERSISTED level —
 * real repositories over the shared Postgres, the terminal row read back from the database:
 *
 * - **An unserializable context snapshot**: the terminal UPDATE's `contextJson` is null, so the
 *   COALESCE keeps the row's insert-time `parameters_json` — the designed degradation (the
 *   emitter's T36 note). What was missing is ANY evidence: the serialization failure was
 *   discarded. Now: one WARN, the failure's class, the row's fate stated.
 * - **An unreadable duration read**: `metadata-db` §8.3 (F1) requires every terminal row to
 *   carry `duration_ms`, so an unreadable read records 0 — the contract — and the WARN says the
 *   duration is unknown (never a fabricated one presented as measured).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmitterDegradedDiagnosticsIntegrationTest {
    private val rig = Rig()

    @AfterAll
    fun close() {
        rig.close()
    }

    @Test
    fun `an unserializable context snapshot logs its degradation and the row keeps its insert-time parameters`() {
        val executionId = UUID.randomUUID()
        rig.emitStarted(executionId)

        val events =
            captureEmitterLogs {
                runBlocking {
                    rig.emitter(executionId, rig.executions).emit(
                        ExecutionAborted(
                            executionId,
                            rig.pipelineId,
                            AbortReason.CANCELLED,
                            Instant.now(),
                            emptyList(),
                            contextSnapshot = mapOf("poisoned" to Any()), // no properties — Jackson refuses
                        ),
                    )
                }
            }

        val row = rig.jdbcRow(executionId)
        row["status"] shouldBe "ABORTED"
        // The COALESCE kept the RUNNING insert's parameters_json — the designed degradation.
        row["parameters_json"] shouldBe "{}"

        val warns = events.filter { it.level == ch.qos.logback.classic.Level.WARN }
        warns.shouldHaveSize(1)
        warns.single().formattedMessage.shouldContain("could not be serialized")
        warns.single().formattedMessage.shouldContain("insert-time")
    }

    @Test
    fun `an unreadable duration read records 0 and says the duration is unknown`() {
        val executionId = UUID.randomUUID()
        rig.emitStarted(executionId)
        // The store-side read fault: the column the read SELECTs is GONE (schema drift) — a
        // genuine read failure through the real repository, not a stubbed double. The terminal
        // UPDATE names no started_at, so only the duration read fails.
        rig.jdbc.jdbcTemplate.execute("ALTER TABLE pipeline_executions RENAME COLUMN started_at TO started_at_d5")
        val emitted: List<ILoggingEvent>
        try {
            emitted =
                captureEmitterLogs {
                    runBlocking {
                        rig.emitter(executionId).emit(
                            ExecutionAborted(executionId, rig.pipelineId, AbortReason.CANCELLED, Instant.now(), emptyList()),
                        )
                    }
                }
        } finally {
            rig.jdbc.jdbcTemplate.execute("ALTER TABLE pipeline_executions RENAME COLUMN started_at_d5 TO started_at")
        }

        // §8.3's contract: every terminal row carries a duration. The read failed, so the
        // value is the 0 sentinel — stated as unknown in the WARN, never presented as measured.
        val row = rig.jdbcRow(executionId)
        row["status"] shouldBe "ABORTED"
        (row["duration_ms"] as Long) shouldBe 0L

        val warns = emitted.filter { it.level == ch.qos.logback.classic.Level.WARN }
        warns.shouldHaveSize(1)
        warns.single().formattedMessage.shouldContain("duration")
        warns.single().formattedMessage.shouldContain("unknown")
    }

    private fun captureEmitterLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(WebEventEmitter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** The production wiring over the shared Postgres and one private Redis — the D9 rig, minimal. */
    private class Rig : AutoCloseable {
        private val pool =
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = SharedPostgres.dataSource()
                    maximumPoolSize = 4
                },
            )
        val jdbc = NamedParameterJdbcTemplate(pool)
        val executions = ExecutionRepository(jdbc)
        private val events = ExecutionEventRepository(jdbc)
        private val redis = PrivateRedis()
        private val eventLog = SseEventLog(redis.template, co.datapipelines.executor.ExecutorJson.mapper)
        private val rows = BatchingWriter("execution_events", co.datapipelines.persistence.BatchingConfig(), ExecutionEventRowSink(events))
        private val replay = BatchingWriter("replay_log", co.datapipelines.persistence.BatchingConfig(), ReplayLogSink(eventLog))
        private val persistPool =
            Executors.newFixedThreadPool(2) { r -> Thread(r, "dp-event-persist").apply { isDaemon = true } }
        private val recorder = BatchedEventRecorder(rows, replay, eventLog, persistPool)
        private val streams =
            ExecutionStreamRegistry(
                SseProperties(),
                co.datapipelines.executor.ExecutionCancellationService(
                    co.datapipelines.executor.InMemoryCancellationRegistry(),
                    co.datapipelines.executor.RedisCancellationFlags(redis.template),
                    co.datapipelines.executor.ExecutorConfig(),
                ),
                co.datapipelines.executor.ExecutorJson.mapper,
            )
        private val userId: UUID = UUID.randomUUID()
        val pipelineId: UUID = UUID.randomUUID()

        init {
            cleanSchema()
            seedPipeline()
        }

        fun emitter(
            executionId: UUID,
            executionsOverride: ExecutionRepository = executions,
        ): WebEventEmitter =
            WebEventEmitter(
                context = ExecutionContext(pipelineId, 1, userId, executionId, ExecutionTrigger.REST, "{}", DEFAULT_WORKSPACE),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = events,
                executionRepository = executionsOverride,
                persistenceDispatcher = persistPool.asCoroutineDispatcher(),
                eventRecorder = recorder,
            )

        fun emitStarted(
            executionId: UUID,
            executionsOverride: ExecutionRepository = executions,
        ) {
            runBlocking {
                emitter(executionId, executionsOverride).emit(
                    ExecutionStarted(executionId, pipelineId, 1, emptyMap(), startedAt = Instant.now()),
                )
            }
        }

        fun jdbcRow(executionId: UUID): Map<String, Any> =
            JdbcTemplate(
                pool,
            ).queryForMap(
                "SELECT status, parameters_json::text AS parameters_json, duration_ms FROM pipeline_executions WHERE execution_id = ?",
                executionId,
            )

        private fun cleanSchema() {
            JdbcTemplate(pool).execute("TRUNCATE pipeline_executions, pipeline_versions, pipelines, users, workspaces CASCADE")
            JdbcTemplate(pool).execute("DELETE FROM execution_events")
        }

        private fun seedPipeline() {
            jdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$DEFAULT_WORKSPACE', 'default', 'Default') ON CONFLICT DO NOTHING",
            )
            jdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'P', 'google', :sub)",
                mapOf("id" to userId, "email" to "d5-$userId@example.com", "sub" to "d5-$userId"),
            )
            jdbc.update(
                "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                    "VALUES (:id, :name, 'P', :owner, 1, '$DEFAULT_WORKSPACE')",
                mapOf("id" to pipelineId, "name" to "d5_${pipelineId.toString().replace("-", "")}", "owner" to userId),
            )
            jdbc.update(
                "INSERT INTO pipeline_versions " +
                    "(pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                    "VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())",
                mapOf("id" to pipelineId, "owner" to userId),
            )
        }

        override fun close() {
            persistPool.shutdown()
            rows.close()
            replay.close()
            redis.close()
            pool.close()
        }
    }

    /** A private Redis — the replay log's store for this suite only. */
    private class PrivateRedis : AutoCloseable {
        private val container =
            org.testcontainers.containers
                .GenericContainer(
                    org.testcontainers.utility.DockerImageName
                        .parse("redis:7-alpine"),
                ).withExposedPorts(6379)
                .also { it.start() }
        private val factory =
            LettuceConnectionFactory(RedisStandaloneConfiguration(container.host, container.getMappedPort(6379)))
                .apply { afterPropertiesSet() }
        val template = StringRedisTemplate(factory).apply { afterPropertiesSet() }

        override fun close() {
            factory.destroy()
            container.stop()
        }
    }

    private companion object {
        val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000002")
    }
}
