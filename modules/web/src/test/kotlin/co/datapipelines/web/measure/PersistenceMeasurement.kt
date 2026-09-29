package co.datapipelines.web.measure

import co.datapipelines.auth.AuditLogger
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeProgress
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.InMemoryCancellationRegistry
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.NodeStatus
import co.datapipelines.executor.OperationDestination
import co.datapipelines.executor.OperationKind
import co.datapipelines.executor.OperationPhase
import co.datapipelines.executor.OperationSnapshot
import co.datapipelines.executor.OperationState
import co.datapipelines.executor.RedisCancellationFlags
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.TestRedis
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * #266 A.2 / C — **what does persisting execution events and audit rows cost under concurrent load,
 * and what does the batched writer change?**
 *
 * The REAL [WebEventEmitter] over the REAL [ExecutionEventRepository], [ExecutionRepository] and
 * [SseEventLog], against the module's shared Postgres (the shipped migrations, `fsync` on — the
 * image's default) and a Redis container, through a 10-connection Hikari pool (the shipped
 * `maximum-pool-size`). No pipeline and no executor: a synthetic executor loop drives the emitter
 * exactly the way `PipelineExecutor` does — `execution_started` (the RUNNING row), node events, the
 * terminal `pipeline_completed` (the terminal UPDATE), each `emit` awaited before the next — so what
 * is measured is the persistence path and nothing else.
 *
 * ## One arm = N executions in flight for [ARM_SECONDS]
 * N worker coroutines each run executions of [eventsPerExecution] events back to back, on a
 * 32-thread stand-in for the executor's dispatcher, so N executions are in flight for the whole
 * window. Beside them, an OPEN-loop audit load: [auditRatePerSecond] `AuditEventSink.log` calls a
 * second, issued on a 32-thread stand-in for the servlet pool through the REAL [AuditLogger].
 * The first [WARMUP_SECONDS] of every arm are discarded.
 *
 * Reported per arm: emit latency p50/p95/p99/max (what the executor waits per event), events/s
 * committed, audit call latency p50/p95/p99 and rows/s, the `dp-event-persist` pool's queue depth
 * (mean/max, sampled every [SAMPLE_MILLIS] ms), Hikari's peak active and peak waiting threads, and the
 * process RSS (start, peak). Persistence lag — enqueue to durable — equals the emit latency on a
 * build whose emit awaits its own write; the batched build additionally reports its writers' own
 * enqueue-to-commit lag.
 *
 * Non-vacuity: after each arm the durable record is recounted — every execution's rows are exactly
 * `1..M` and the committed count equals the emitted count, or the arm prints `COUNT MISMATCH`. A
 * number over zero executions is never printed as a number.
 *
 * Two modes, same rig, same arms: `direct` — the pre-#266 path this build still carries (the
 * emitter's default recorder, the audit logger without a writer; on the base commit this class ran
 * the same path as the only one) — and `batched`, the production wiring: both event writers and the
 * audit writer, with their meters read per arm through `WebMetrics.bindPersistence`.
 *
 * Gated on `DP_MEASURE=1` (scripts/measure/README.md); a measurement reports, it never asserts a
 * threshold. `DP_MEASURE_N` (comma list, default `1,8,32,100`), `DP_MEASURE_M` (default 40),
 * `DP_MEASURE_AUDIT_RATE` (default 500), `DP_MEASURE_MODES` (default `direct,batched`) and
 * `DP_MEASURE_LINGER_MS` (default 0) narrow or widen the arms.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class PersistenceMeasurement {
    private val ns = System.getenv("DP_MEASURE_N")?.split(",")?.map { it.trim().toInt() } ?: listOf(1, 8, 32, 100)
    private val eventsPerExecution = System.getenv("DP_MEASURE_M")?.toInt() ?: DEFAULT_EVENTS
    private val auditRatePerSecond = System.getenv("DP_MEASURE_AUDIT_RATE")?.toInt() ?: DEFAULT_AUDIT_RATE
    private val modes = System.getenv("DP_MEASURE_MODES")?.split(",")?.map { it.trim() } ?: listOf(MODE_DIRECT, MODE_BATCHED)
    private val lingerMs = System.getenv("DP_MEASURE_LINGER_MS")?.toLong() ?: 0L

    @Test
    fun `N concurrent executions through the real emitter beside an open-loop audit load`() {
        println("### #266 persistence — N executions × M=$eventsPerExecution events, audit A=$auditRatePerSecond rows/s")
        println()
        println(
            "window: ${ARM_SECONDS}s measured after ${WARMUP_SECONDS}s warmup per arm; Hikari max=${POOL_SIZE}; " +
                "executor stand-in threads=$EXECUTOR_THREADS; servlet stand-in threads=$SERVLET_THREADS; " +
                "cpus=${Runtime.getRuntime().availableProcessors()}; linger-ms=$lingerMs (batched arms)",
        )
        modes.forEach { mode ->
            val rig = Rig(mode, lingerMs)
            try {
                rig.seed()
                println()
                println("#### mode: $mode — ${rig.buildLabel}")
                println("dp-event-persist queue: ${rig.persistQueueDescription()}")
                println()
                println(
                    "| N | executions | events | events/s committed | emit p50 ms | emit p95 ms | emit p99 ms | emit max ms | " +
                        "audit rows | audit rows/s | audit p50 ms | audit p95 ms | audit p99 ms | " +
                        "persist queue mean | persist queue max | " +
                        "hikari active max | hikari waiting max | RSS start MB | RSS peak MB | recount |",
                )
                println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
                // One unreported warm-up arm so the first reported row is not paying for JIT and pool growth.
                runArm(rig, WARMUP_N, report = false)
                val writerRows = mutableListOf<String>()
                ns.forEach { n ->
                    rig.resetWriterMeters()
                    println(runArm(rig, n, report = true))
                    writerRows += rig.writerReport(n)
                }
                if (rig.batched) {
                    println()
                    println("writers (this mode): the meters WebMetrics.bindPersistence registers, read per arm")
                    println()
                    println(
                        "| N | store | batches | batch size mean | batch size max | batch ms mean | lag mean ms | lag max ms | " +
                            "queue depth max | fallbacks | failures |",
                    )
                    println("|---|---|---|---|---|---|---|---|---|---|---|")
                    writerRows.forEach(::println)
                }
            } finally {
                rig.close()
            }
        }
    }

    private fun runArm(
        rig: Rig,
        n: Int,
        report: Boolean,
    ): String {
        val window = Window()
        val sampler = Sampler(rig)
        rig.writerDepthMax = 0
        val rssStart = rssMb()
        val auditTicker = startAuditLoad(rig, window)
        sampler.start(window.measuring)
        runWorkers(rig, n, window)
        auditTicker.shutdownNow()
        rig.servlet.awaitQuiet()
        sampler.stop()
        val recount = rig.recount(window.executions.toList(), eventsPerExecution)
        return if (report) row(n, window, sampler, rssStart, recount) else ""
    }

    /** What one arm observed inside its measured window. */
    private class Window {
        val emitLatencies = ConcurrentLinkedQueue<Long>()
        val auditLatencies = ConcurrentLinkedQueue<Long>()
        val executions = ConcurrentLinkedQueue<UUID>()
        val emitted = AtomicLong()
        val audited = AtomicLong()
        val measuring = AtomicBoolean(false)
        val stop = AtomicBoolean(false)
    }

    /** The open-loop audit load: a fixed-rate tick submits one log call to the servlet stand-in. */
    private fun startAuditLoad(
        rig: Rig,
        window: Window,
    ): ScheduledExecutorService {
        val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "m-audit-tick").apply { isDaemon = true } }
        ticker.scheduleAtFixedRate(
            {
                rig.servlet.execute {
                    val t0 = System.nanoTime()
                    rig.audit.log(
                        event = "mcp.tool.called",
                        userId = rig.userId,
                        keyId = "k_measure_${t0 % KEY_SPREAD}",
                        sourceIp = "127.0.0.1",
                        userAgent = "measure",
                        details =
                            mapOf(
                                "tool" to "pipelines_get",
                                "outcome" to "success",
                                "correlation_id" to UUID.randomUUID().toString(),
                            ),
                    )
                    if (window.measuring.get()) {
                        window.auditLatencies += System.nanoTime() - t0
                        window.audited.incrementAndGet()
                    }
                }
            },
            0,
            MICROS_PER_SECOND / auditRatePerSecond,
            TimeUnit.MICROSECONDS,
        )
        return ticker
    }

    /** N workers, each running synthetic executions back to back until the window closes. */
    private fun runWorkers(
        rig: Rig,
        n: Int,
        window: Window,
    ) = runBlocking {
        val workers =
            (1..n).map { worker ->
                launch(rig.executorDispatcher) {
                    while (!window.stop.get()) {
                        val executionId = UUID.randomUUID()
                        val emitter = rig.emitter()
                        val counted = window.measuring.get()
                        if (counted) window.executions += executionId
                        syntheticExecution(executionId, rig.pipelineId, worker).forEach { event ->
                            val t0 = System.nanoTime()
                            emitter.emit(event)
                            if (counted) {
                                window.emitLatencies += System.nanoTime() - t0
                                window.emitted.incrementAndGet()
                            }
                        }
                    }
                }
            }
        check(workers.size == n) { "launched ${workers.size} workers, expected $n" }
        kotlinx.coroutines.delay(WARMUP_SECONDS * MILLIS_PER_SECOND)
        window.measuring.set(true)
        kotlinx.coroutines.delay(ARM_SECONDS * MILLIS_PER_SECOND)
        window.measuring.set(false)
        window.stop.set(true)
    }

    private fun row(
        n: Int,
        window: Window,
        sampler: Sampler,
        rssStart: Long,
        recount: String,
    ): String {
        val emits = window.emitLatencies.toLongArray().also { it.sort() }
        val audits = window.auditLatencies.toLongArray().also { it.sort() }
        return listOf(
            n,
            window.executions.size,
            window.emitted.get(),
            "%.0f".format(window.emitted.get() / ARM_SECONDS.toDouble()),
            ms(emits.pct(P50)),
            ms(emits.pct(P95)),
            ms(emits.pct(P99)),
            ms(emits.lastOrNull() ?: 0),
            window.audited.get(),
            "%.0f".format(window.audited.get() / ARM_SECONDS.toDouble()),
            ms(audits.pct(P50)),
            ms(audits.pct(P95)),
            ms(audits.pct(P99)),
            "%.1f".format(sampler.queueMean()),
            sampler.queueMax,
            sampler.activeMax,
            sampler.waitingMax,
            rssStart,
            sampler.rssPeakMb,
            recount,
        ).joinToString(" | ", prefix = "| ", postfix = " |")
    }

    /**
     * One execution's events, in the order `PipelineExecutor` emits them for a linear pipeline:
     * `execution_started`, then per node `node_started` · `node_progress` · `node_completed`, then the
     * terminal `pipeline_completed`. [eventsPerExecution] sets how many node events there are.
     */
    private fun syntheticExecution(
        executionId: UUID,
        pipelineId: UUID,
        worker: Int,
    ): List<ExecutionEvent> {
        val now = Instant.now()
        val nodeEvents = eventsPerExecution - 2
        val events =
            mutableListOf<ExecutionEvent>(ExecutionStarted(executionId, pipelineId, 1, mapOf("worker" to worker), startedAt = now))
        var node = 0
        while (events.size - 1 < nodeEvents) {
            val nodeId = "n${++node}"
            events += NodeStarted(executionId, nodeId, now)
            if (events.size - 1 < nodeEvents) events += NodeProgress(executionId, snapshot(nodeId, now))
            if (events.size - 1 < nodeEvents) events += NodeCompleted(executionId, nodeId, stats(nodeId, now))
        }
        val stats = (1..node).map { stats("n$it", now) }
        events += PipelineCompleted(executionId, pipelineId, 1, now, now.plusMillis(DURATION_MS), DURATION_MS, stats)
        return events
    }

    private fun stats(
        nodeId: String,
        now: Instant,
    ) = NodeStats(nodeId, NodeStatus.SUCCESS, now, now.plusMillis(DURATION_MS), DURATION_MS, ROWS, BYTES)

    private fun snapshot(
        nodeId: String,
        now: Instant,
    ) = OperationSnapshot(
        nodeId = nodeId,
        attempt = 1,
        sequence = 1,
        kind = OperationKind.STAGE,
        destination = OperationDestination.tempdb("t_$nodeId"),
        state = OperationState.WRITING,
        startedAt = now,
        observedAt = now,
        elapsedMs = DURATION_MS,
        timingsMs = mapOf(OperationPhase.WRITING to DURATION_MS),
        rowsFetched = ROWS,
        rowsWritten = ROWS,
        batchesWritten = 1,
        committed = null,
        rolledBack = null,
        childExecutionId = null,
    )

    /**
     * The measured system, assembled the way `EngineConfiguration` and `AuthConfiguration` assemble it.
     * [emitter] and [audit] are the only two lines that differ between the baseline build and the
     * batched one — everything around them is identical by construction.
     */
    private class Rig(
        mode: String,
        lingerMs: Long,
    ) : AutoCloseable {
        val batched = mode == MODE_BATCHED
        val buildLabel =
            if (batched) {
                "batched (#266): event row + replay entry through two BatchingWriters, audit through the audit writer; " +
                    "emit and log still await"
            } else {
                "direct: emit awaits one write per store on the 4-thread pool; audit is one INSERT on the caller's thread " +
                    "(the pre-#266 path)"
            }
        private val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = SharedPostgres.postgres.jdbcUrl
                    username = SharedPostgres.postgres.username
                    password = SharedPostgres.postgres.password
                    maximumPoolSize = POOL_SIZE
                    poolName = "measure-metadata"
                },
            )
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        private val redis = TestRedis.template()
        private val eventLog = SseEventLog(redis, ExecutorJson.mapper)
        private val events = ExecutionEventRepository(jdbc)
        private val executions = ExecutionRepository(jdbc)

        /** The `eventPersistenceExecutor` bean, verbatim: `Executors.newFixedThreadPool(4)` of daemon `dp-event-persist` threads. */
        private val persistPool =
            Executors.newFixedThreadPool(PERSIST_THREADS) { r -> Thread(r, "dp-event-persist").apply { isDaemon = true } }
        private val persistDispatcher: CoroutineDispatcher = persistPool.asCoroutineDispatcher()
        private val executorPool =
            Executors.newFixedThreadPool(EXECUTOR_THREADS) { r -> Thread(r, "m-executor").apply { isDaemon = true } }
        val executorDispatcher: CoroutineDispatcher = executorPool.asCoroutineDispatcher()
        val servlet = ServletStandIn()
        private val streams =
            ExecutionStreamRegistry(
                SseProperties(),
                ExecutionCancellationService(InMemoryCancellationRegistry(), RedisCancellationFlags(redis), ExecutorConfig()),
                ExecutorJson.mapper,
            )
        private val config =
            co.datapipelines.auth
                .PersistenceProperties(lingerMs = lingerMs)
                .toConfig()
        private val writers =
            if (batched) {
                listOf(
                    co.datapipelines.persistence.BatchingWriter(
                        "execution_events",
                        config,
                        co.datapipelines.web.sse
                            .ExecutionEventRowSink(events),
                    ),
                    co.datapipelines.persistence.BatchingWriter(
                        "replay_log",
                        config,
                        co.datapipelines.web.sse
                            .ReplayLogSink(eventLog),
                    ),
                    co.datapipelines.persistence.BatchingWriter("audit", config, co.datapipelines.auth.AuditRowSink(jdbc)),
                )
            } else {
                emptyList()
            }
        private var registry =
            io.micrometer.core.instrument.simple
                .SimpleMeterRegistry()

        @Suppress("UNCHECKED_CAST")
        private val recorder =
            if (batched) {
                co.datapipelines.web.sse.BatchedEventRecorder(
                    writers[0] as co.datapipelines.persistence.BatchingWriter<co.datapipelines.executor.ExecutionEventRecord>,
                    writers[1] as co.datapipelines.persistence.BatchingWriter<co.datapipelines.web.sse.ReplayLogEntry>,
                    eventLog,
                    persistPool,
                )
            } else {
                null
            }

        @Suppress("UNCHECKED_CAST")
        val audit =
            AuditLogger(
                jdbc,
                ExecutorJson.mapper,
                writers.getOrNull(2) as co.datapipelines.persistence.BatchingWriter<co.datapipelines.auth.AuditRow>?,
            )
        lateinit var userId: UUID

        /** A fresh registry per arm, so each arm's writer meters are its own. */
        fun resetWriterMeters() {
            registry =
                io.micrometer.core.instrument.simple
                    .SimpleMeterRegistry()
            val metrics =
                co.datapipelines.web.metrics
                    .WebMetrics(registry)
            writers.forEach(metrics::bindPersistence)
        }

        fun writerQueueDepth(): Int = writers.sumOf { it.queueDepth() }

        fun writerReport(n: Int): List<String> =
            writers.map { writer ->
                val tag = writer.name
                val size = registry.find("datapipelines.persistence.batch.size").tag("store", tag).summary()
                val duration = registry.find("datapipelines.persistence.batch.duration").tag("store", tag).timer()
                val lag = registry.find("datapipelines.persistence.lag").tag("store", tag).timer()
                val fallbacks =
                    registry
                        .find("datapipelines.persistence.fallbacks")
                        .tag("store", tag)
                        .counters()
                        .sumOf { it.count() }
                val failures =
                    registry
                        .find("datapipelines.persistence.failures")
                        .tag("store", tag)
                        .counters()
                        .sumOf { it.count() }
                listOf(
                    n,
                    tag,
                    size?.count() ?: 0,
                    "%.1f".format(size?.mean() ?: 0.0),
                    "%.0f".format(size?.max() ?: 0.0),
                    "%.2f".format(duration?.mean(TimeUnit.MILLISECONDS) ?: 0.0),
                    "%.2f".format(lag?.mean(TimeUnit.MILLISECONDS) ?: 0.0),
                    "%.2f".format(lag?.max(TimeUnit.MILLISECONDS) ?: 0.0),
                    writerDepthMax,
                    "%.0f".format(fallbacks),
                    "%.0f".format(failures),
                ).joinToString(" | ", prefix = "| ", postfix = " |")
            }

        /** The largest summed writer queue depth the sampler saw in the last arm. */
        var writerDepthMax = 0
        lateinit var pipelineId: UUID

        fun emitter(): WebEventEmitter =
            WebEventEmitter(
                context = ExecutionContext(pipelineId, 1, userId, UUID.randomUUID(), ExecutionTrigger.REST, "{}", DEFAULT_WORKSPACE_ID),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = events,
                executionRepository = executions,
                persistenceDispatcher = persistDispatcher,
                eventRecorder = recorder,
            )

        fun persistQueueDescription(): String {
            val tpe = persistPool as ThreadPoolExecutor
            return "${tpe.queue.javaClass.simpleName}, remainingCapacity=${tpe.queue.remainingCapacity()} (Int.MAX_VALUE=${Int.MAX_VALUE})"
        }

        /**
         * Baseline: the persistence pool's backlog. Batched: that plus every writer's admitted items — the queue
         * the batching introduces.
         */
        fun persistQueueDepth(): Int = (persistPool as ThreadPoolExecutor).queue.size + writerQueueDepth()

        fun hikariActive(): Int = dataSource.hikariPoolMXBean?.activeConnections ?: 0

        fun hikariWaiting(): Int = dataSource.hikariPoolMXBean?.threadsAwaitingConnection ?: 0

        fun seed() {
            jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
            jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
            jdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$DEFAULT_WORKSPACE_ID', 'default', 'Default')",
            )
            TestRedis.flush(redis)
            userId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'M', 'google', :sub)",
                mapOf("id" to userId, "email" to "m$userId@example.com", "sub" to "sub-$userId"),
            )
            pipelineId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                    "VALUES (:id, :name, 'P', :owner, 1, '$DEFAULT_WORKSPACE_ID')",
                mapOf("id" to pipelineId, "name" to "m_${pipelineId.toString().replace("-", "")}", "owner" to userId),
            )
            jdbc.update(
                "INSERT INTO pipeline_versions " +
                    "(pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                    "VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())",
                mapOf("id" to pipelineId, "owner" to userId),
            )
        }

        /**
         * The durable record, recounted: every counted execution holds exactly `1..m`. Anything else
         * is printed — a latency number over a lossy run is not a number.
         */
        fun recount(
            counted: List<UUID>,
            m: Int,
        ): String {
            if (counted.isEmpty()) return "NO EXECUTIONS"
            var bad = 0
            counted.chunked(RECOUNT_CHUNK).forEach { chunk ->
                val rows =
                    jdbc.queryForList(
                        "SELECT execution_id, COUNT(*) AS c, MIN(event_id) AS lo, MAX(event_id) AS hi " +
                            "FROM execution_events WHERE execution_id IN (:ids) GROUP BY execution_id",
                        mapOf("ids" to chunk),
                    )
                val ok =
                    rows.count {
                        (it["c"] as Number).toInt() == m && (it["lo"] as Number).toInt() == 1 && (it["hi"] as Number).toInt() == m
                    }
                bad += chunk.size - ok
            }
            return if (bad == 0) "ok ${counted.size}×$m" else "COUNT MISMATCH $bad/${counted.size}"
        }

        override fun close() {
            writers.forEach { it.close() }
            servlet.shutdown()
            executorPool.shutdownNow()
            persistPool.shutdownNow()
            dataSource.close()
        }
    }

    /** A servlet-pool stand-in: bounded threads, and a way to wait until every submitted call returned. */
    private class ServletStandIn {
        private val pool = Executors.newFixedThreadPool(SERVLET_THREADS) { r -> Thread(r, "m-servlet").apply { isDaemon = true } }
        private val inFlight = AtomicLong()

        fun execute(block: () -> Unit) {
            inFlight.incrementAndGet()
            pool.execute {
                try {
                    block()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        fun awaitQuiet() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUIET_SECONDS)
            while (inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(SAMPLE_MILLIS)
        }

        fun shutdown() {
            pool.shutdownNow()
        }
    }

    private class Sampler(
        private val rig: Rig,
    ) {
        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "m-sampler").apply { isDaemon = true } }
        private var queueSum = 0L
        private var samples = 0L
        var queueMax = 0
        var activeMax = 0
        var waitingMax = 0
        var rssPeakMb = 0L

        fun start(measuring: AtomicBoolean) {
            scheduler.scheduleAtFixedRate(
                {
                    if (measuring.get()) {
                        val depth = rig.persistQueueDepth()
                        queueSum += depth
                        samples++
                        queueMax = maxOf(queueMax, depth)
                        rig.writerDepthMax = maxOf(rig.writerDepthMax, rig.writerQueueDepth())
                        activeMax = maxOf(activeMax, rig.hikariActive())
                        waitingMax = maxOf(waitingMax, rig.hikariWaiting())
                        rssPeakMb = maxOf(rssPeakMb, rssMb())
                    }
                },
                0,
                SAMPLE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        fun stop() {
            scheduler.shutdown()
            scheduler.awaitTermination(1, TimeUnit.SECONDS)
        }

        fun queueMean(): Double = if (samples == 0L) 0.0 else queueSum.toDouble() / samples
    }

    private companion object {
        const val DEFAULT_EVENTS = 40
        const val DEFAULT_AUDIT_RATE = 500
        const val MODE_DIRECT = "direct"
        const val MODE_BATCHED = "batched"
        const val MICROS_PER_SECOND = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val ARM_SECONDS = 6L
        const val WARMUP_SECONDS = 1L
        const val WARMUP_N = 8
        const val POOL_SIZE = 10
        const val PERSIST_THREADS = 4
        const val EXECUTOR_THREADS = 32
        const val SERVLET_THREADS = 32
        const val SAMPLE_MILLIS = 10L
        const val QUIET_SECONDS = 30L
        const val KEY_SPREAD = 64
        const val RECOUNT_CHUNK = 500
        const val DURATION_MS = 12L
        const val ROWS = 100L
        const val BYTES = 4096L
        const val P50 = 0.50
        const val P95 = 0.95
        const val P99 = 0.99
        val DEFAULT_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        fun LongArray.pct(p: Double): Long = if (isEmpty()) 0 else this[((size - 1) * p).toInt()]

        fun ms(nanos: Long): String = "%.2f".format(nanos / 1_000_000.0)

        /** The process's resident set, from the kernel — heap figures would hide the queue's off-heap and thread-stack cost. */
        fun rssMb(): Long =
            File("/proc/self/status")
                .takeIf { it.canRead() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.div(1024)
                ?: (ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024))
    }
}
