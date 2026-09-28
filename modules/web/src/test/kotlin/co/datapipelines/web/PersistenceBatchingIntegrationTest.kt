package co.datapipelines.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuditRow
import co.datapipelines.auth.AuditRowSink
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.InMemoryCancellationRegistry
import co.datapipelines.executor.RedisCancellationFlags
import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.web.config.ExecutionDrainLifecycle
import co.datapipelines.web.config.PersistenceDrainLifecycle
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.sse.BatchedEventRecorder
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionEventRowSink
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.ReplayLogEntry
import co.datapipelines.web.sse.ReplayLogSink
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource

/**
 * #266 D — the batched persistence path's acceptance list, on REAL stores with the REAL classes: the
 * `WebEventEmitter`, the `BatchedEventRecorder`, the three `BatchingWriter`s over their production
 * sinks (`ExecutionEventRowSink`, `ReplayLogSink`, `AuditRowSink`), the `AuditLogger`, the meters
 * `WebMetrics.bindPersistence` registers, and the two shutdown lifecycles in a real Spring context —
 * against the module's Postgres (the shipped migrations) and Redis, and PRIVATE ones for the cases
 * that pause a store (a paused shared container would stall every later suite in this JVM).
 *
 * Here, not in `tests/integration-tests`: that module compiles against `app` alone (module-structure
 * §4.2), so it can drive the wire but cannot hold a writer, a sink or a meter; the full-application
 * proofs over the wire are `PersistenceBatchingE2eTest` and `PersistenceRestartE2eTest` there.
 *
 * - **D.1** 32 executions × 62 events at once: 1..62 in the durable record and the replay; no write
 *   failure of any kind (an event row before its RUNNING row is an FK refusal, counted `poison`).
 * - **D.3** one event whose payload JSONB cannot hold (a NUL character) among 199 queued behind a
 *   held commit: the 199 commit, the one is counted and named, no emit threw.
 * - **D.4** a store paused 5 s under load: every emit returns within the writers' bound; Postgres —
 *   the unconfirmed event rows are counted, the replay is whole; Redis — the durable record is whole,
 *   the replay's unconfirmed entries are counted and WARNed.
 * - **D.5** the terminal emit, under `NonCancellable` as `emitTerminal` runs it, while the event table
 *   is locked: it returns within `2 × record-max-wait-ms`, the terminal UPDATE lands, and the terminal
 *   event lands once the lock lifts.
 * - **D.6** 500 audit rows queued behind a held commit at shutdown: the persistence drain runs AFTER the
 *   execution drain (Spring's lifecycle order, read off the log) and writes all 500; with the store
 *   still held it gives up at `shutdown-drain-ms` and reports what it lost.
 * - **D.8** the audit queue full: callers wait `record-max-wait-ms` then write their own rows (none
 *   lost), `submit` refuses and counts, the depth gauge reads the bound, and ONE saturation WARN.
 * - **D.9** no `(execution_id, event_id)` pair twice, over the whole database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PersistenceBatchingIntegrationTest {
    private val logs = ListAppender<ILoggingEvent>()
    private val watched =
        listOf(
            BatchingWriter::class.java,
            PersistenceDrainLifecycle::class.java,
            ExecutionDrainLifecycle::class.java,
            BatchedEventRecorder::class.java,
        ).map { LoggerFactory.getLogger(it) as Logger }

    @BeforeEach
    fun listen() {
        logs.list.clear()
        logs.start()
        watched.forEach { it.addAppender(logs) }
    }

    @AfterEach
    fun unlisten() {
        watched.forEach { it.detachAppender(logs) }
    }

    // ------------------------------------------------------------------ D.1

    @Test
    fun `D1 - 32 executions emitting at once keep 1 to N in the durable record and the replay, with no failed write`() {
        Rig(shared(), TestRedis.template()).use { rig ->
            val ids = (1..CONCURRENT).map { UUID.randomUUID() }
            runBlocking { ids.map { id -> async(Dispatchers.IO) { rig.emitExecution(id, EVENTS) } }.awaitAll() }

            ids.forEach { id ->
                withClue("execution $id") {
                    rig.events.findByExecution(id).map { it.eventId } shouldBe (1..EVENTS + 2).toList()
                    rig.eventLog.replay(id)!!.map { it.eventId } shouldBe (1..EVENTS + 2).toList()
                    rig.executions.findById(DEFAULT_WORKSPACE, id)!!.status shouldBe ExecutionStatus.SUCCESS
                }
            }
            withClue("no failure of any kind — an event row ahead of its RUNNING row would be an FK refusal, counted poison") {
                rig.count("datapipelines.persistence.failures") shouldBe 0.0
            }
            withClue("non-vacuity: the executions shared commits") { rig.maxBatch("execution_events") shouldBeGreater 1.0 }
        }
    }

    // ------------------------------------------------------------------ D.3

    @Test
    fun `D3 - one poison event among 199 queued behind a held commit - the 199 commit, the one is counted and named`() {
        Rig(shared(), TestRedis.template()).use { rig ->
            val ids = (1..POISON_BATCH).map { UUID.randomUUID() }
            // One emitter per execution, for both of its events: the sequence is the emitter's.
            val emitters = ids.associateWith { rig.emitter(it) }
            runBlocking {
                ids.forEach { id ->
                    emitters.getValue(id).emit(ExecutionStarted(id, rig.pipelineId, 1, emptyMap(), startedAt = Instant.now()))
                }
            }
            val poison = ids.last()
            val threw = AtomicInteger()
            holdingLock(SharedPostgres.postgres.jdbcUrl, "execution_events") { release ->
                runBlocking {
                    fun emitAsync(
                        id: UUID,
                        node: String,
                    ) = async(Dispatchers.IO) {
                        runCatching {
                            emitters
                                .getValue(
                                    id,
                                ).emit(NodeStarted(id, node, Instant.now()))
                        }.onFailure { threw.incrementAndGet() }
                    }
                    val healthy = ids.dropLast(1).mapIndexed { i, id -> emitAsync(id, "n$i") }
                    // The 199 are waiting — each writer's first one inside the held commit, the rest queued —
                    // before the poison arrives, so the poison can only land in a queued, multi-row batch.
                    awaitCondition { rig.rows.queueDepth() >= POISON_BATCH - 1 }
                    val bad = emitAsync(poison, "poison\u0000node")
                    awaitCondition { rig.rows.queueDepth() >= POISON_BATCH }
                    release()
                    (healthy + bad).awaitAll()
                }
            }
            threw.get() shouldBe 0
            ids.dropLast(1).forEach { rig.events.findByExecution(it).map { e -> e.eventId } shouldBe listOf(1, 2) }
            rig.events.findByExecution(poison).map { it.eventId } shouldBe listOf(1)
            rig.count("datapipelines.persistence.failures", "store" to "execution_events", "kind" to "poison") shouldBe 1.0
            withClue("the poison was isolated by the singles retry of a multi-row batch") {
                rig.count("datapipelines.persistence.batches.retried", "store" to "execution_events") shouldBeGreater 0.0
            }
            withClue("one WARN names the poison row by its ids — never its payload") {
                val warn =
                    logs.list.map { it.formattedMessage }.single {
                        it.startsWith(
                            "event=persistence.batch_retried writer=execution_events",
                        )
                    }
                warn.contains("execution=$poison event_id=2") shouldBe true
                warn.contains("poison\u0000node") shouldBe false
            }
        }
    }

    // ------------------------------------------------------------------ D.4

    @Test
    fun `D4 - Postgres paused 5 s under load - every emit returns within the bound, the unconfirmed are counted, the replay stays whole`() {
        privatePostgres().use { pg ->
            Rig(pg.dataSource, TestRedis.template(), config = BatchingConfig(recordMaxWaitMillis = SHORT_WAIT_MS)).use { rig ->
                val run = rig.underLoad(LOADED) { pause(pg.container) }
                withClue("the longest emit while Postgres was paused: ${run.maxEmitMs} ms") { run.maxEmitMs shouldBeLessThan BOUND_MS }
                withClue("the unconfirmed event rows are counted") {
                    rig.count("datapipelines.persistence.failures", "store" to "execution_events").toInt() shouldBeGreaterThan 0
                }
                run.emitted.forEach { (id, eventIds) ->
                    rig.eventLog
                        .replay(id)!!
                        .map { it.eventId }
                        .containsAll(eventIds) shouldBe true
                }
                Thread.sleep(SETTLE_MS)
                val stored = run.emitted.keys.sumOf { rig.events.findByExecution(it).size }
                println(
                    "persistence-it D4-postgres emitted=${run.emitted.values.sumOf {
                        it.size
                    }} stored=$stored max_emit_ms=${run.maxEmitMs}",
                )
            }
        }
    }

    @Test
    fun `D4 - Redis paused 5 s under load - the durable record is whole, the replay's unconfirmed entries are counted and said`() {
        privateRedis().use { redis ->
            Rig(shared(), redis.template, config = BatchingConfig(recordMaxWaitMillis = SHORT_WAIT_MS)).use { rig ->
                val run = rig.underLoad(LOADED) { pause(redis.container) }
                withClue("the longest emit while Redis was paused: ${run.maxEmitMs} ms") { run.maxEmitMs shouldBeLessThan BOUND_MS }
                Thread.sleep(SETTLE_MS)
                run.emitted.forEach { (id, eventIds) ->
                    withClue("the durable record of $id is whole") {
                        rig.events
                            .findByExecution(id)
                            .map { it.eventId }
                            .containsAll(eventIds) shouldBe
                            true
                    }
                }
                withClue("the replay's unconfirmed entries are counted") {
                    rig.count("datapipelines.persistence.failures", "store" to "replay_log").toInt() shouldBeGreaterThan 0
                }
                logs.list.any { it.formattedMessage.contains("replay will be incomplete") } shouldBe true
            }
        }
    }

    // ------------------------------------------------------------------ D.5

    @Test
    fun `D5 - the terminal emit under NonCancellable returns within the bound while its row cannot commit, and lands after`() {
        Rig(shared(), TestRedis.template(), config = BatchingConfig(recordMaxWaitMillis = SHORT_WAIT_MS)).use { rig ->
            val id = UUID.randomUUID()
            val emitter = rig.emitter(id)
            runBlocking { emitter.emit(ExecutionStarted(id, rig.pipelineId, 1, emptyMap(), startedAt = Instant.now())) }
            var tookMs = 0L
            holdingLock(SharedPostgres.postgres.jdbcUrl, "execution_events") { release ->
                val t0 = System.nanoTime()
                // PipelineExecutor.emitTerminal's shape: the terminal emit cannot be cancelled, so its
                // wait must end on its own.
                runBlocking {
                    withContext(NonCancellable) {
                        emitter.emit(ExecutionAborted(id, rig.pipelineId, AbortReason.CANCELLED, Instant.now(), emptyList()))
                    }
                }
                tookMs = (System.nanoTime() - t0) / NANOS_PER_MS
                withClue("the terminal UPDATE ran — pipeline_executions is not the locked table") {
                    rig.executions.findById(DEFAULT_WORKSPACE, id)!!.status shouldBe ExecutionStatus.ABORTED
                }
                release()
            }
            withClue("2 × record-max-wait-ms ($SHORT_WAIT_MS) plus slack, measured $tookMs ms") { tookMs shouldBeLessThan BOUND_MS }
            awaitCondition { rig.events.findByExecution(id).any { it.eventType == "execution_aborted" } }
            rig.eventLog.replay(id)!!.any { it.eventName == "execution_aborted" } shouldBe true
            rig.events.findByExecution(id).map { it.eventId } shouldBe listOf(1, 2)
        }
    }

    // ------------------------------------------------------------------ D.6

    @Test
    fun `D6 - 500 audit rows queued at shutdown are all written, and the drain runs after the execution drain`() {
        val jdbc = NamedParameterJdbcTemplate(shared())
        val writer = BatchingWriter("audit", BatchingConfig(), AuditRowSink(jdbc))
        val marker = UUID.randomUUID().toString()
        val context = drainContext(writer)
        holdingLock(SharedPostgres.postgres.jdbcUrl, "audit_log") { release ->
            repeat(QUEUED) { n -> writer.submit(auditRow("persistence.it.drain", "k_drain_$n", marker)) shouldBe true }
            val closer = Thread { context.close() }.apply { start() }
            awaitCondition { logged("event=shutdown.persistence_drain_started") }
            release()
            closer.join(TimeUnit.MINUTES.toMillis(1))
        }
        rowsWith(jdbc, marker) shouldBe QUEUED
        val order = logs.list.map { it.formattedMessage }
        val executionDrain = order.indexOfFirst { it.startsWith("event=shutdown.drain_complete") }
        val persistenceDrain = order.indexOfFirst { it.startsWith("event=shutdown.persistence_drain_started") }
        val auditDrained = order.indexOfFirst { it.startsWith("event=persistence.drained writer=audit") }
        withClue("the order off the log: $order") {
            (executionDrain in 0 until persistenceDrain) shouldBe true
            (persistenceDrain < auditDrained) shouldBe true
        }
    }

    @Test
    fun `D6 - with the store still held, the drain gives up at shutdown-drain-ms and reports what it lost`() {
        val jdbc = NamedParameterJdbcTemplate(shared())
        val writer = BatchingWriter("audit", BatchingConfig(shutdownDrainMillis = HUNG_DRAIN_MS), AuditRowSink(jdbc))
        val marker = UUID.randomUUID().toString()
        val context = drainContext(writer)
        var closedInMs = 0L
        holdingLock(SharedPostgres.postgres.jdbcUrl, "audit_log") { release ->
            repeat(QUEUED) { n -> writer.submit(auditRow("persistence.it.hung", "k_hung_$n", marker)) }
            val t0 = System.nanoTime()
            context.close()
            closedInMs = (System.nanoTime() - t0) / NANOS_PER_MS
            release()
        }
        val line = logs.list.map { it.formattedMessage }.single { it.startsWith("event=persistence.drain_incomplete writer=audit") }
        val lost = Regex("lost=(\\d+)").find(line)!!.groupValues[1].toInt()
        val inFlight = Regex("in_flight=(\\d+)").find(line)!!.groupValues[1].toInt()
        lost shouldBeGreaterThan 0
        withClue("the close waited shutdown-drain-ms ($HUNG_DRAIN_MS) plus slack, measured $closedInMs ms") {
            closedInMs shouldBeLessThan
                HUNG_DRAIN_MS + DRAIN_SLACK_MS
        }
        awaitCondition { rowsWith(jdbc, marker) + lost >= QUEUED - inFlight }
        val landed = rowsWith(jdbc, marker)
        withClue("landed + lost accounts for every item but those in flight: landed=$landed lost=$lost in_flight=$inFlight") {
            (landed + lost in (QUEUED - inFlight)..QUEUED) shouldBe true
        }
    }

    // ------------------------------------------------------------------ D.8

    @Test
    fun `D8 - a full audit queue - callers wait then write their own rows, submit refuses, one WARN per interval`() {
        val jdbc = NamedParameterJdbcTemplate(shared())
        val registry = SimpleMeterRegistry()
        val writer =
            BatchingWriter("audit", BatchingConfig(queueMaxEvents = QUEUE_BOUND, recordMaxWaitMillis = SHORT_WAIT_MS), AuditRowSink(jdbc))
        WebMetrics(registry).bindPersistence(writer)
        val logger = AuditLogger(jdbc, ExecutorJson.mapper, writer)
        val marker = UUID.randomUUID().toString()
        var depthSeen = 0.0
        var submitRefused = false
        try {
            holdingLock(SharedPostgres.postgres.jdbcUrl, "audit_log") { release ->
                val callers = Executors.newFixedThreadPool(CALLERS)
                val done = CountDownLatch(CALLERS)
                repeat(CALLERS) { n ->
                    callers.execute {
                        logger.log("persistence.it.saturation", keyId = "k_saturation", details = mapOf("marker" to marker, "n" to n))
                        done.countDown()
                    }
                }
                awaitCondition { writer.queueDepth() >= QUEUE_BOUND }
                depthSeen =
                    registry
                        .find("datapipelines.persistence.queue.depth")
                        .tag("store", "audit")
                        .gauge()!!
                        .value()
                submitRefused = !writer.submit(auditRow("persistence.it.submit", "k_saturation", marker))
                Thread.sleep(SATURATION_HOLD_MS)
                release()
                done.await(1, TimeUnit.MINUTES) shouldBe true
                callers.shutdown()
            }
            depthSeen shouldBe QUEUE_BOUND.toDouble()
            submitRefused shouldBe true
            awaitCondition { rowsWith(jdbc, marker) == CALLERS }

            // Every caller got its row written: by the writer (its batches), or — the queue full, or its
            // item waited out record-max-wait-ms behind the held commit — by its own direct INSERT.
            // Which of the two waits a caller hit depends on when room freed up; the sum does not.
            fun fallbacks(reason: String) =
                registry
                    .find("datapipelines.persistence.fallbacks")
                    .tags("store", "audit", "reason", reason)
                    .counter()
                    ?.count() ?: 0.0
            val byWriter =
                registry
                    .find("datapipelines.persistence.batch.size")
                    .tag("store", "audit")
                    .summary()!!
                    .totalAmount()
            withClue("saturated=${fallbacks("saturated")} timeout=${fallbacks("timeout")} by_writer=$byWriter") {
                (fallbacks("saturated") > 0.0) shouldBe true
                fallbacks("saturated") + fallbacks("timeout") + byWriter shouldBe CALLERS.toDouble()
            }
            registry
                .find("datapipelines.persistence.dropped")
                .tag("store", "audit")
                .counter()!!
                .count() shouldBe 1.0
            withClue("saturation is a state, logged once per interval — not once per caller") {
                logs.list.count { it.formattedMessage.startsWith("event=persistence.saturated writer=audit") } shouldBe 1
            }
        } finally {
            writer.close()
        }
    }

    // ------------------------------------------------------------------ D.9

    @Test
    fun `D9 - no event id is ever stored twice for one execution, over the whole database`() {
        JdbcTemplate(shared()).queryForObject(
            "SELECT COUNT(*) FROM (SELECT execution_id, event_id FROM execution_events GROUP BY 1, 2 HAVING COUNT(*) > 1) d",
            Int::class.java,
        ) shouldBe 0
    }

    // ------------------------------------------------------------------ the rig

    /**
     * The production wiring (PersistenceConfiguration + EngineConfiguration), assembled by hand over
     * [dataSource] and [redis]: a 10-connection Hikari pool, the three writers, the recorder, the
     * `dp-event-persist` pool, and every writer's meters bound through `WebMetrics`.
     */
    private inner class Rig(
        dataSource: DataSource,
        redis: StringRedisTemplate,
        config: BatchingConfig = BatchingConfig(),
    ) : AutoCloseable {
        private val pool =
            HikariDataSource(
                HikariConfig().apply {
                    this.dataSource = dataSource
                    maximumPoolSize = POOL_SIZE
                },
            )
        val jdbc = NamedParameterJdbcTemplate(pool)
        val executions = ExecutionRepository(jdbc)
        val events = ExecutionEventRepository(jdbc)
        val eventLog = SseEventLog(redis, ExecutorJson.mapper)
        private val registry = SimpleMeterRegistry()
        val rows = BatchingWriter("execution_events", config, ExecutionEventRowSink(events))
        private val replay = BatchingWriter("replay_log", config, ReplayLogSink(eventLog))
        private val persistPool =
            Executors.newFixedThreadPool(
                config.writers,
            ) { r -> Thread(r, "dp-event-persist").apply { isDaemon = true } }
        private val recorder = BatchedEventRecorder(rows, replay, eventLog, persistPool)
        private val streams =
            ExecutionStreamRegistry(
                SseProperties(),
                ExecutionCancellationService(InMemoryCancellationRegistry(), RedisCancellationFlags(redis), ExecutorConfig()),
                ExecutorJson.mapper,
            )
        val userId: UUID = UUID.randomUUID()
        val pipelineId: UUID = UUID.randomUUID()

        init {
            WebMetrics(registry).also { it.bindPersistence(rows) }.bindPersistence(replay)
            seedPipeline(jdbc, userId, pipelineId)
        }

        /** An emitter as the runners build it — one per execution, which owns the execution's sequence. */
        @Suppress("UnusedParameter") // the id names the execution the caller builds it for
        fun emitter(executionId: UUID): WebEventEmitter =
            WebEventEmitter(
                context = ExecutionContext(pipelineId, 1, userId, UUID.randomUUID(), ExecutionTrigger.REST, "{}", DEFAULT_WORKSPACE),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = events,
                executionRepository = executions,
                persistenceDispatcher = persistPool.asCoroutineDispatcher(),
                eventRecorder = recorder,
            )

        suspend fun emitExecution(
            id: UUID,
            nodeEvents: Int,
        ) {
            val emitter = emitter(id)
            val now = Instant.now()
            val sequence =
                listOf<ExecutionEvent>(ExecutionStarted(id, pipelineId, 1, emptyMap(), startedAt = now)) +
                    (1..nodeEvents).map { NodeStarted(id, "n$it", now) } +
                    PipelineCompleted(id, pipelineId, 1, now, now.plusMillis(1), 1, emptyList())
            sequence.forEach { emitter.emit(it) }
        }

        /** [executions] emitting node events in a loop; [outage] runs 500 ms in, for 5 s, and is undone. */
        fun underLoad(
            executionsInFlight: Int,
            outage: () -> () -> Unit,
        ): LoadRun {
            val ids = (1..executionsInFlight).map { UUID.randomUUID() }
            val emitters = ids.associateWith { emitter(it) }
            runBlocking {
                ids.forEach { id ->
                    emitters.getValue(id).emit(ExecutionStarted(id, pipelineId, 1, emptyMap(), startedAt = Instant.now()))
                }
            }
            val durations = ConcurrentLinkedQueue<Long>()
            val emitted = ids.associateWith { ConcurrentLinkedQueue<Int>() }
            val stop = AtomicBoolean(false)
            val loop =
                Thread {
                    runBlocking {
                        ids
                            .map { id ->
                                async(Dispatchers.IO) {
                                    val emitter = emitters.getValue(id)
                                    var n = 1
                                    while (!stop.get()) {
                                        val t0 = System.nanoTime()
                                        emitter.emit(NodeStarted(id, "n$n", Instant.now()))
                                        durations += (System.nanoTime() - t0) / NANOS_PER_MS
                                        emitted.getValue(id) += ++n
                                    }
                                }
                            }.awaitAll()
                    }
                }.apply { start() }
            Thread.sleep(LOAD_LEAD_MS)
            val undo = outage()
            try {
                Thread.sleep(OUTAGE_MS)
            } finally {
                undo()
            }
            stop.set(true)
            loop.join(TimeUnit.MINUTES.toMillis(1))
            return LoadRun(durations.max(), emitted.mapValues { it.value.toList() })
        }

        fun count(
            name: String,
            vararg tags: Pair<String, String>,
        ): Double =
            registry
                .find(name)
                .tags(*tags.flatMap { listOf(it.first, it.second) }.toTypedArray())
                .counters()
                .sumOf { it.count() }

        fun maxBatch(store: String): Double =
            registry
                .find("datapipelines.persistence.batch.size")
                .tag("store", store)
                .summary()
                ?.max() ?: 0.0

        override fun close() {
            rows.close()
            replay.close()
            persistPool.shutdownNow()
            pool.close()
        }
    }

    private data class LoadRun(
        val maxEmitMs: Long,
        val emitted: Map<UUID, List<Int>>,
    )

    /** A real Spring context holding the two shutdown lifecycles, so their order is Spring's, not the test's. */
    private fun drainContext(writer: BatchingWriter<AuditRow>): AnnotationConfigApplicationContext {
        val redis = TestRedis.template()
        val registry = InMemoryCancellationRegistry()
        val service = ExecutionCancellationService(registry, RedisCancellationFlags(redis), ExecutorConfig())
        return AnnotationConfigApplicationContext().apply {
            registerBean(
                "executionDrain",
                ExecutionDrainLifecycle::class.java,
                Supplier { ExecutionDrainLifecycle(service, registry, this) },
            )
            registerBean("persistenceDrain", PersistenceDrainLifecycle::class.java, Supplier { PersistenceDrainLifecycle(listOf(writer)) })
            refresh()
            start()
        }
    }

    private fun auditRow(
        event: String,
        keyId: String,
        marker: String,
    ) = AuditRow(event, null, keyId, null, null, """{"marker":"$marker"}""")

    private fun rowsWith(
        jdbc: NamedParameterJdbcTemplate,
        marker: String,
    ): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE details_json ->> 'marker' = :marker",
            mapOf("marker" to marker),
            Int::class.java,
        ) ?: 0

    private fun logged(prefix: String): Boolean = logs.list.any { it.formattedMessage.startsWith(prefix) }

    private fun shared(): DataSource = SharedPostgres.dataSource()

    /** A private, migrated Postgres — for the case that PAUSES it. */
    private class PrivatePostgres : AutoCloseable {
        val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("p266")
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

    /** A private Redis — for the case that PAUSES it. */
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
            runCatching { unpause(container) }
            factory.destroy()
            container.stop()
        }
    }

    private fun privatePostgres() = PrivatePostgres()

    private fun privateRedis() = PrivateRedis()

    private companion object {
        const val CONCURRENT = 32
        const val EVENTS = 60
        const val POISON_BATCH = 200
        const val LOADED = 8
        const val LOAD_LEAD_MS = 500L
        const val OUTAGE_MS = 5_000L
        const val SETTLE_MS = 3_000L
        const val SHORT_WAIT_MS = 500L

        /** 2 × record-max-wait-ms (500) per store — the two stores waited concurrently — plus slack for a loaded box. */
        const val BOUND_MS = 2_500L
        const val QUEUED = 500
        const val HUNG_DRAIN_MS = 1_000L
        const val DRAIN_SLACK_MS = 3_000L
        const val QUEUE_BOUND = 8
        const val CALLERS = 30
        const val SATURATION_HOLD_MS = 1_500L
        const val POOL_SIZE = 10
        const val REDIS_PORT = 6379
        const val POLL_MS = 20L
        const val TEN_SECONDS = 10L
        const val NANOS_PER_MS = 1_000_000L
        val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        infix fun Double.shouldBeGreater(other: Double) = (this > other) shouldBe true

        fun pause(container: GenericContainer<*>): () -> Unit {
            DockerClientFactory
                .instance()
                .client()
                .pauseContainerCmd(container.containerId)
                .exec()
            return { unpause(container) }
        }

        fun unpause(container: GenericContainer<*>) {
            DockerClientFactory
                .instance()
                .client()
                .unpauseContainerCmd(container.containerId)
                .exec()
        }

        fun seedPipeline(
            jdbc: NamedParameterJdbcTemplate,
            userId: UUID,
            pipelineId: UUID,
        ) {
            jdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name) " +
                    "VALUES ('$DEFAULT_WORKSPACE', 'default', 'Default') ON CONFLICT DO NOTHING",
            )
            jdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'P', 'google', :sub)",
                mapOf("id" to userId, "email" to "p266-$userId@example.com", "sub" to "p266-$userId"),
            )
            jdbc.update(
                "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                    "VALUES (:id, :name, 'P', :owner, 1, '$DEFAULT_WORKSPACE')",
                mapOf("id" to pipelineId, "name" to "p266_${pipelineId.toString().replace("-", "")}", "owner" to userId),
            )
            jdbc.update(
                "INSERT INTO pipeline_versions " +
                    "(pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                    "VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())",
                mapOf("id" to pipelineId, "owner" to userId),
            )
        }

        /** Holds a lock on [table] from a side connection: every INSERT into it waits until [block] releases. */
        fun holdingLock(
            jdbcUrl: String,
            table: String,
            block: (release: () -> Unit) -> Unit,
        ) {
            val side = DriverManager.getConnection(jdbcUrl, SharedPostgres.postgres.username, SharedPostgres.postgres.password)
            side.autoCommit = false
            side.createStatement().execute("LOCK TABLE $table IN SHARE ROW EXCLUSIVE MODE")
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

        fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEN_SECONDS)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "condition not met within $TEN_SECONDS s" }
                Thread.sleep(POLL_MS)
            }
        }
    }
}
