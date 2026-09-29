package co.datapipelines.web

import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.InMemoryCancellationRegistry
import co.datapipelines.executor.RedisCancellationFlags
import co.datapipelines.executor.StaleExecutionSweeper
import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.sse.BatchedEventRecorder
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionEventRowSink
import co.datapipelines.web.sse.ExecutionRecordUnwritableException
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.ReplayLogSink
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #311 — the two `pipeline_executions` lifecycle writes are BOUNDED
 * (`datapipelines.executor.lifecycle-write-timeout-seconds`, here 2 s to keep the suite quick):
 * the RUNNING insert and the terminal UPDATE return within the bound against a database that
 * cannot answer, and the outcome is STATED, never fabricated.
 *
 * The two bound layers are proven separately, because they fail differently (measured during this
 * lane: pgjdbc's `queryTimeout` cannot deliver its cancel to a server that never answers):
 *
 * - **A locked table** — the database ANSWERS but the statement waits: the statement-level
 *   `queryTimeout` ([ExecutionRepository]'s dedicated template) cancels it AT the bound, the emit
 *   returns with the failure logged and COUNTED, the row stays RUNNING, and the stale sweep reaps
 *   it. Deterministic: the cancelled statement is gone, so no abandoned writer races the sweep.
 * - **A paused container** — the database never answers: the emitter's caller-side wait gives up
 *   at the bound. For the RUNNING insert the execution continues unrecorded (the interactive
 *   path) or refuses to start (fail-closed, within the bound); the abandoned statement may still
 *   land at recovery — asserted, because "unconfirmed" must not mean "lost". For the terminal
 *   UPDATE the emit returns within the bound with the counter incremented and the row still
 *   RUNNING (never a fabricated COMPLETED); at recovery the abandoned UPDATE lands — also
 *   asserted. The reap-by-sweep arm runs on the locked case, where no abandoned writer races it.
 *
 * Private containers throughout: a paused SHARED Postgres would stall every later suite in this
 * JVM (the #266 rule). Red with the bound removed (both constructor args planted to
 * zero/unbounded): every case hangs and dies on its own test timeout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class LifecycleWriteBoundIntegrationTest {
    // ------------------------------------------------- A: locked (answering) database

    @Test
    fun `A - a locked table cancels the terminal UPDATE at the statement bound, counts it, and the sweep reaps the row`() {
        PrivatePostgres().use { pg ->
            PrivateRedis().use { redis ->
                // The statement cancel (2 s) fires strictly INSIDE the caller bound (6 s): the
                // cancel is delivered and the statement is GONE before the emit returns, so what
                // the row shows afterwards is deterministic. With the two at the same value the
                // cancel's delivery races the caller's give-up, and under load the abandoned
                // UPDATE could still land once the lock lifts.
                Rig(pg, redis.template, callerSeconds = A_CALLER_SECONDS, statementSeconds = BOUND_SECONDS).use { rig ->
                    val id = UUID.randomUUID()
                    runBlocking { rig.emitter(id).emit(rig.startedEvent(id)) }
                    rig.executions.findById(Rig.WORKSPACE, id)!!.status shouldBe ExecutionStatus.RUNNING

                    holdingLock(pg) { release ->
                        val tookMs =
                            measuringMs {
                                runBlocking {
                                    withContext(NonCancellable) {
                                        rig.emitter(id).emit(rig.completedEvent(id))
                                    }
                                }
                            }
                        release()
                        withClue("the caller bound waited out the statement cancel (measured $tookMs ms)") {
                            tookMs shouldBeLessThan A_CEILING_MS
                        }
                        withClue("the emit waited for the statement cancel (measured $tookMs ms) — not an ack from memory") {
                            (tookMs >= BOUND_MS) shouldBe true
                        }
                    }

                    withClue("the failure is counted") { rig.lifecycleWriteFailures() shouldBe 1.0 }
                    withClue("the row is left RUNNING for the sweep — never a fabricated COMPLETED") {
                        rig.executions.findById(Rig.WORKSPACE, id)!!.status shouldBe ExecutionStatus.RUNNING
                    }

                    // The reap: heartbeat cutoffs of 1 ms put every RUNNING row in the past.
                    val swept = StaleExecutionSweeper(rig.executions, Duration.ofMillis(1), Duration.ofMillis(1)).sweepOnce()
                    withClue("the sweep reaped the unrecorded outcome (swept=$swept)") { swept shouldBe 1 }
                    rig.executions.findById(Rig.WORKSPACE, id)!!.status shouldBe ExecutionStatus.ABORTED
                }
            }
        }
    }

    // ------------------------------------------------- B: paused database, RUNNING insert

    @Test
    fun `B - a paused database gives up the RUNNING insert at the bound and the abandoned insert lands at recovery`() {
        PrivatePostgres().use { pg ->
            PrivateRedis().use { redis ->
                Rig(pg, redis.template, callerSeconds = BOUND_SECONDS, statementSeconds = DISARMED_STATEMENT_SECONDS).use { rig ->
                    val id = UUID.randomUUID()
                    pause(pg.container)
                    val tookMs =
                        measuringMs {
                            runBlocking { rig.emitter(id).emit(rig.startedEvent(id)) }
                        }
                    withClue("the caller bound fired while Postgres was paused (measured $tookMs ms)") {
                        tookMs shouldBeLessThan BOUND_CEILING_MS
                    }
                    withClue("the emit waited for the insert it could not reach") { (tookMs >= BOUND_MS) shouldBe true }
                    unpause(pg.container)
                    println("LANE306B unpause at " + java.time.Instant.now())

                    // The interactive path continued (no exception); the write was UNCONFIRMED,
                    // not lost: the abandoned statement lands once the database answers again.
                    awaitCondition { rig.executions.findById(Rig.WORKSPACE, id) != null }
                    rig.executions.findById(Rig.WORKSPACE, id)!!.status shouldBe ExecutionStatus.RUNNING

                    withClue("the RUNNING insert's give-up is not a terminal-write failure") {
                        rig.lifecycleWriteFailures() shouldBe 0.0
                    }
                }
            }
        }
    }

    @Test
    fun `B - the fail-closed rule stops a scheduled execution within the bound when the insert cannot be written`() {
        PrivatePostgres().use { pg ->
            PrivateRedis().use { redis ->
                Rig(pg, redis.template, callerSeconds = BOUND_SECONDS, statementSeconds = DISARMED_STATEMENT_SECONDS).use { rig ->
                    failClosedCase(pg, rig)
                }
            }
        }
    }

    private fun failClosedCase(
        pg: PrivatePostgres,
        rig: Rig,
    ) {
        val id = UUID.randomUUID()
        pause(pg.container)
        try {
            val tookMs = measuringMs { failClosedRefusal(rig, id) }
            withClue("the scheduled refusal came within the bound, not at the container's mercy (measured $tookMs ms)") {
                tookMs shouldBeLessThan BOUND_CEILING_MS
            }
        } finally {
            unpause(pg.container)
        }
    }

    // ------------------------------------------------- C: paused database, terminal UPDATE

    @Test
    fun `C - a paused database gives up the terminal UPDATE at the bound - counted, the update lands at recovery`() {
        PrivatePostgres().use { pg ->
            PrivateRedis().use { redis ->
                Rig(pg, redis.template, callerSeconds = BOUND_SECONDS, statementSeconds = DISARMED_STATEMENT_SECONDS).use { rig ->
                    val id = UUID.randomUUID()
                    runBlocking { rig.emitter(id).emit(rig.startedEvent(id)) }
                    rig.executions.findById(Rig.WORKSPACE, id)!!.status shouldBe ExecutionStatus.RUNNING

                    pause(pg.container)
                    val tookMs =
                        measuringMs {
                            runBlocking {
                                withContext(NonCancellable) {
                                    rig.emitter(id).emit(rig.completedEvent(id))
                                }
                            }
                        }
                    withClue("the caller bound fired while Postgres was paused (measured $tookMs ms)") {
                        tookMs shouldBeLessThan BOUND_CEILING_MS
                    }
                    withClue("the emit waited for the UPDATE it could not reach") { (tookMs >= BOUND_MS) shouldBe true }
                    withClue("the outcome is counted") { rig.lifecycleWriteFailures() shouldBe 1.0 }
                    // (The "row still RUNNING, nothing fabricated COMPLETED" check lives in case A:
                    // while the container is PAUSED no connection can be handed out at all — that
                    // is what the pause means — so the row cannot be read here. A asserts it with
                    // the database up, behind the statement-bound cancel, deterministically.)
                    unpause(pg.container)

                    // The abandoned statement lands at recovery — unconfirmed, not lost.
                    awaitCondition { rig.executions.findById(Rig.WORKSPACE, id)!!.status == ExecutionStatus.SUCCESS }
                }
            }
        }
    }

    // ------------------------------------------------- helpers

    /** The fail-closed emit (emitTerminal's NonCancellable shape): must THROW within the bound. */
    private fun failClosedRefusal(
        rig: Rig,
        id: UUID,
    ) {
        assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            runBlocking {
                shouldThrow<ExecutionRecordUnwritableException> {
                    withContext(NonCancellable) {
                        rig.emitter(id, failClosed = true).emit(rig.startedEvent(id))
                    }
                }
            }
        }
    }

    private fun measuringMs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEN_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $TEN_SECONDS s" }
            Thread.sleep(POLL_MS)
        }
    }

    /** A private, migrated Postgres — for the cases that pause it. */
    private class PrivatePostgres : AutoCloseable {
        val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("p306")
                .withUsername("dp")
                .withPassword("dp")
                .also { it.start() }

        val dataSource = DriverManagerDataSource(container.jdbcUrl, container.username, container.password)

        init {
            val jdbc = JdbcTemplate(dataSource)
            TestRepoFiles.migrationPaths().forEach { path -> jdbc.execute(TestRepoFiles.read(path)) }
        }

        override fun close() {
            runCatching { unpause(container) }
            container.stop()
        }
    }

    /** A private Redis — the replay log's store, so the recorders' writes stay out of the way. */
    private class PrivateRedis : AutoCloseable {
        val container: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT).also {
                it.start()
            }
        private val factory =
            LettuceConnectionFactory(
                RedisStandaloneConfiguration(container.host, container.getMappedPort(REDIS_PORT)),
            ).apply { afterPropertiesSet() }
        val template = StringRedisTemplate(factory).apply { afterPropertiesSet() }

        override fun close() {
            factory.destroy()
            container.stop()
        }
    }

    /**
     * The production shape, assembled by hand (the `PersistenceBatchingIntegrationTest` rig): the
     * real `ExecutionRepository` WITH the statement bound, the real emitter WITH the caller bound
     * and a real metrics registry, and the #266 batched recorder with a short bound of its own so
     * the event-row writes are never this suite's subject.
     */
    private class Rig(
        pg: PrivatePostgres,
        redis: StringRedisTemplate,
        private val callerSeconds: Int,
        statementSeconds: Int = callerSeconds,
    ) : AutoCloseable {
        private val dataSource = pg.dataSource

        // UNPOOLED on purpose (the #266 paused-store precedent): Hikari validates a connection on
        // every checkout, and against a PAUSED server that validation hangs and fails — an
        // abandoned statement would never even be sent. A fresh physical connection per checkout
        // hangs in the connect (the kernel accepts TCP; the handshake never answers), and on
        // unpause the connection completes and the statement EXECUTES — which is exactly the
        // "lands at recovery" outcome this suite states.
        private val jdbc = NamedParameterJdbcTemplate(dataSource)

        // The two layers are decoupled here on purpose: cases B and C set the statement timeout
        // high (60 s — effectively disarmed inside the test) so the CALLER-side give-up is the
        // only bound at work, which is what makes "the abandoned statement lands at recovery"
        // deterministic — an armed statement timeout cancels the statement at recovery instead,
        // and WHICH of the two happens is a race between the cancel connection and the pause.
        // Production ships one key to both layers (§3.2); the proofs isolate them.
        val executions = ExecutionRepository(jdbc, statementSeconds)
        private val events = ExecutionEventRepository(jdbc)
        private val eventLog = SseEventLog(redis, ExecutorJson.mapper)
        private val registry = SimpleMeterRegistry()
        private val metrics = ExecutorMetrics(registry)
        private val eventConfig = BatchingConfig(recordMaxWaitMillis = EVENT_WAIT_MS)
        private val rows = BatchingWriter("execution_events", eventConfig, ExecutionEventRowSink(events))
        private val replay = BatchingWriter("replay_log", eventConfig, ReplayLogSink(eventLog))
        private val persistPool =
            Executors.newFixedThreadPool(WRITERS) { r -> Thread(r, "dp-event-persist").apply { isDaemon = true } }
        private val recorder = BatchedEventRecorder(rows, replay, eventLog, persistPool)
        private val streams =
            ExecutionStreamRegistry(
                SseProperties(),
                ExecutionCancellationService(InMemoryCancellationRegistry(), RedisCancellationFlags(redis), ExecutorConfig()),
                ExecutorJson.mapper,
            )
        private val userId: UUID = UUID.randomUUID()
        val pipelineId: UUID = UUID.randomUUID()

        init {
            seed()
        }

        fun startedEvent(id: UUID) = ExecutionStarted(id, pipelineId, 1, emptyMap(), startedAt = Instant.now())

        fun completedEvent(id: UUID) = PipelineCompleted(id, pipelineId, 1, Instant.now(), Instant.now(), 1, emptyList())

        fun emitter(
            @Suppress("UNUSED_PARAMETER") executionId: UUID,
            failClosed: Boolean = false,
        ): WebEventEmitter =
            WebEventEmitter(
                context =
                    ExecutionContext(
                        pipelineId,
                        1,
                        userId,
                        UUID.randomUUID(),
                        ExecutionTrigger.REST,
                        "{}",
                        WORKSPACE,
                    ),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = events,
                executionRepository = executions,
                persistenceDispatcher = persistPool.asCoroutineDispatcher(),
                lifecycleWriteTimeout = Duration.ofSeconds(callerSeconds.toLong()),
                metrics = metrics,
                eventRecorder = recorder,
                failClosedOnRecord = failClosed,
            )

        fun lifecycleWriteFailures(): Double =
            registry
                .find("datapipelines.executions.lifecycle_write_failed")
                .counter()
                ?.count() ?: 0.0

        private fun seed() {
            val seedJdbc = NamedParameterJdbcTemplate(dataSource)
            seedJdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name) " +
                    "VALUES ('$WORKSPACE', 'default', 'Default') ON CONFLICT DO NOTHING",
            )
            seedJdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'P', 'google', :sub)",
                mapOf("id" to userId, "email" to "p306-$userId@example.com", "sub" to "p306-$userId"),
            )
            seedJdbc.update(
                "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                    "VALUES (:id, :name, 'P', :owner, 1, '$WORKSPACE')",
                mapOf("id" to pipelineId, "name" to "p306_${pipelineId.toString().replace("-", "")}", "owner" to userId),
            )
            seedJdbc.update(
                "INSERT INTO pipeline_versions " +
                    "(pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                    "VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())",
                mapOf("id" to pipelineId, "owner" to userId),
            )
        }

        override fun close() {
            runCatching { rows.close() }
            runCatching { replay.close() }
            persistPool.shutdownNow()
        }

        companion object {
            val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        }
    }

    private companion object {
        /** The bound under test — short enough to keep the suite quick, long enough to be honest. */
        const val BOUND_SECONDS = 2

        const val BOUND_MS = 2_000L

        /** Bound plus slack for a loaded box. */
        const val BOUND_CEILING_MS = 6_000L

        const val TIMEOUT_SECONDS = 15L

        /** Far below the lifecycle bound: the event-row writes must never be this suite's subject. */
        const val EVENT_WAIT_MS = 250L

        /** For B/C: the statement layer disarmed (past the test's patience) so the caller layer is the only bound. */
        const val DISARMED_STATEMENT_SECONDS = 60

        /** For A: the caller bound sits strictly OUTSIDE the statement cancel (see the case). */
        const val A_CALLER_SECONDS = 6
        const val A_CALLER_MS = 6_000L
        const val A_CEILING_MS = 12_000L

        const val WRITERS = 4
        const val REDIS_PORT = 6379
        const val POLL_MS = 50L
        const val TEN_SECONDS = 10L

        fun pause(container: GenericContainer<*>) {
            DockerClientFactory
                .instance()
                .client()
                .pauseContainerCmd(container.containerId)
                .exec()
        }

        fun unpause(container: GenericContainer<*>) {
            DockerClientFactory
                .instance()
                .client()
                .unpauseContainerCmd(container.containerId)
                .exec()
        }

        /** Holds a lock on `pipeline_executions` from a side connection: writes wait, SELECTs pass. */
        fun holdingLock(
            pg: PrivatePostgres,
            block: (release: () -> Unit) -> Unit,
        ) {
            val side = DriverManager.getConnection(pg.container.jdbcUrl, pg.container.username, pg.container.password)
            side.autoCommit = false
            side.createStatement().execute("LOCK TABLE pipeline_executions IN SHARE ROW EXCLUSIVE MODE")
            val released = AtomicBoolean(false)
            val release = {
                if (released.compareAndSet(false, true)) {
                    side.commit()
                    side.close()
                }
            }
            try {
                block(release)
            } finally {
                release()
            }
        }
    }
}
