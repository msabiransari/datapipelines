package co.datapipelines.executor.measure

import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorHarness
import co.datapipelines.executor.FakeDatasourceRegistry
import co.datapipelines.executor.Fixtures
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.StoredResult
import co.datapipelines.executor.StoredResultView
import co.datapipelines.executor.h2Datasource
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.staging.H2Staging
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.lang.management.ManagementFactory
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 146 / #118 — **what does the bounded staging pool change for a whole pipeline?**
 *
 * Three shapes, identical inputs and DAGs for every arm, run through the REAL executor:
 *
 * 1. **fan-out + chain** — four independent source nodes stage 50 000 rows each, a three-node
 *    serial CTAS chain folds them, a caller node reports. The shape the pool exists for.
 * 2. **slow drain + unrelated work** — a caller node drains 20 000 rows into a deliberately slow
 *    result store while three independent DDL nodes run beside it. Under one connection the
 *    drain's lease blocks them; under the pool it should not.
 * 3. **concurrent executions** — shape 1 run four at once, for throughput and p95.
 *
 * Each shape is run at `max-connections` 1, 2 and 4 in this tree. The pre-146 baseline — one
 * connection under a global lock — is NOT this tree's capacity 1 (that removes the lock but keeps
 * one connection); it is this same class run against the base commit, with the two
 * capacity-specific lines patched out (see the evidence note for the exact patch). Reports,
 * never asserts — gated on `DP_MEASURE=1`; `DP_MEASURE_CAPACITIES` narrows the arms.
 *
 * Every arm prints its caller-result checksum first; a differing checksum across arms is a
 * correctness finding, not a performance one, and would void the timings.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class StagingPoolMeasurement {
    @Test
    fun `fan-out + serial chain, slow drain + unrelated work, and four concurrent executions at each capacity`() {
        printEnvironment()
        val capacities = System.getenv("DP_MEASURE_CAPACITIES")?.split(",")?.map { it.trim().toInt() } ?: listOf(1, 2, 4)
        println("### Shape 1 — four sources × $SOURCE_ROWS rows, three-node CTAS chain, caller ($WARMUP warmup, $REPS measured)")
        println()
        println(
            "| capacity | checksum | median ms | p95 ms | min ms | source node median ms | chain median ms | peak leases | " +
                "conns opened | lease wait ms/run | GC ms/run | CPU ms/run | peak heap MB |",
        )
        println("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        capacities.forEach { capacity -> println(measureShape1(capacity)) }
        println()
        println("### Shape 2 — caller drain of $DRAIN_ROWS rows into a slow sink beside three independent DDL nodes")
        println()
        println(
            "| capacity | checksum | median ms | p95 ms | drain node median ms | ddl nodes median ms (sum) | peak leases | " +
                "lease wait ms/run |",
        )
        println("|---|---|---|---|---|---|---|---|")
        capacities.forEach { capacity -> println(measureShape2(capacity)) }
        println()
        println("### Shape 3 — shape 1 run $CONCURRENT executions at once")
        println()
        println("| capacity | checksums equal | batch median ms | per-execution p95 ms | executions/s | GC ms/batch | CPU ms/batch |")
        println("|---|---|---|---|---|---|---|")
        capacities.forEach { capacity -> println(measureShape3(capacity)) }
        println()
        println("Median and p95 are over the measured repetitions; per-node figures come from the")
        println("executor's own node_stats durations. Lease wait is the pool's own counter (this tree")
        println("only — the base tree has no such counter). GC/CPU/heap are JVM-wide deltas for the run.")
    }

    // ------------------------------------------------------------ shape 1

    private fun measureShape1(capacity: Int): String {
        val sources = (1..SOURCES).map { i -> h2Datasource("m_src_$i", sourceDdl()) }
        val nodes =
            (1..SOURCES).map { i -> Fixtures.node("src$i", source = "m_src_$i", output = NodeOutput.Tempdb("s_$i")) } +
                listOf(
                    Fixtures.node("chain1", output = NodeOutput.Tempdb("c1"), dependsOn = (1..SOURCES).map { "src$it" }),
                    Fixtures.node("chain2", output = NodeOutput.Tempdb("c2"), dependsOn = listOf("chain1")),
                    Fixtures.node("chain3", output = NodeOutput.Tempdb("c3"), dependsOn = listOf("chain2")),
                    Fixtures.node("report", output = NodeOutput.Caller, dependsOn = listOf("chain3")),
                )
        val sql =
            (1..SOURCES).associate { "src$it" to "SELECT n, amount, label FROM s" } +
                mapOf(
                    "chain1" to (1..SOURCES).joinToString(" UNION ALL ") { """SELECT "n", "amount", "label" FROM "s_$it"""" },
                    "chain2" to
                        """SELECT "label", SUM("amount") AS total, COUNT(*) AS cnt, COUNT("amount") AS nn FROM "c1" GROUP BY "label"""",
                    "chain3" to """SELECT "label", "total", "cnt", "nn" FROM "c2" WHERE "cnt" > 0""",
                    "report" to """SELECT "label", "total", "cnt", "nn" FROM "c3" ORDER BY "label"""",
                )
        val store = RecordingStore()
        val pipeline = Fixtures.pipeline(nodes)
        val registry = FakeDatasourceRegistry((1..SOURCES).associate { "m_src_$it" to sources[it - 1] })
        val tracker = PoolTracker()
        ExecutorHarness(
            templateEngine = engine(sql),
            registry = registry,
            config = config(),
            resultStore = store,
            stagingFactory = tracker.factory(capacity),
        ).use { h ->
            repeat(WARMUP) { runBlocking { h.executor.execute(Fixtures.request(pipeline)) } }
            val runs = (1..REPS).map { Sample.of { runBlocking { h.executor.execute(Fixtures.request(pipeline)) } } }
            val checksum = runs.map { store.checksumOf(it.result.executionId) }.distinct()
            val sourceMedians =
                runs.map { r ->
                    median(
                        r.result.nodeStats
                            .filter { it.nodeId.startsWith("src") }
                            .map { it.durationMs.toDouble() },
                    )
                }
            val chainMedians =
                runs.map { r ->
                    r.result.nodeStats
                        .filter { it.nodeId.startsWith("chain") }
                        .sumOf { it.durationMs }
                        .toDouble()
                }
            return "| $capacity | ${checksum.joinToString(
                "/",
            )} | ${fmt(median(runs.map { it.wallMs }))} | ${fmt(p95(runs.map { it.wallMs }))} | " +
                "${fmt(runs.minOf { it.wallMs })} | ${fmt(median(sourceMedians))} | ${fmt(median(chainMedians))} | " +
                "${tracker.peakLeases()} | ${tracker.opened()} | ${fmt(tracker.waitMsPerRun(runs.size + WARMUP))} | " +
                "${fmt(median(runs.map { it.gcMs }))} | ${fmt(median(runs.map { it.cpuMs }))} | ${runs.maxOf { it.peakHeapMb }} |"
        }
    }

    // ------------------------------------------------------------ shape 2

    private fun measureShape2(capacity: Int): String {
        val nodes =
            listOf(
                Fixtures.node("seed", type = NodeType.DDL),
                Fixtures.node("drain", output = NodeOutput.Caller, dependsOn = listOf("seed")),
                Fixtures.node("w1", type = NodeType.DDL, dependsOn = listOf("seed")),
                Fixtures.node("w2", type = NodeType.DDL, dependsOn = listOf("seed")),
                Fixtures.node("w3", type = NodeType.DDL, dependsOn = listOf("seed")),
            )
        val sql =
            mapOf(
                "seed" to
                    """CREATE TABLE "big" AS SELECT "X" AS n, CAST("X" AS DECIMAL(12,2)) / 4 AS amount FROM SYSTEM_RANGE(1, $DRAIN_ROWS)""",
                "drain" to """SELECT "n", "amount" FROM "big"""",
                "w1" to """CREATE TABLE "w1" AS SELECT "X" AS n FROM SYSTEM_RANGE(1, $WORK_ROWS)""",
                "w2" to """CREATE TABLE "w2" AS SELECT "X" AS n FROM SYSTEM_RANGE(1, $WORK_ROWS)""",
                "w3" to """CREATE TABLE "w3" AS SELECT "X" AS n FROM SYSTEM_RANGE(1, $WORK_ROWS)""",
            )
        val store = RecordingStore(sleepPerRows = SLOW_SINK_ROWS_PER_SLEEP)
        val pipeline = Fixtures.pipeline(nodes)
        val tracker = PoolTracker()
        ExecutorHarness(
            templateEngine = engine(sql),
            config = config(),
            resultStore = store,
            stagingFactory = tracker.factory(capacity),
        ).use { h ->
            repeat(WARMUP) { runBlocking { h.executor.execute(Fixtures.request(pipeline)) } }
            val runs = (1..REPS).map { Sample.of { runBlocking { h.executor.execute(Fixtures.request(pipeline)) } } }
            val checksum = runs.map { store.checksumOf(it.result.executionId) }.distinct()
            val drain =
                runs.map { r ->
                    r.result.nodeStats
                        .single { it.nodeId == "drain" }
                        .durationMs
                        .toDouble()
                }
            val ddl =
                runs.map { r ->
                    r.result.nodeStats
                        .filter { it.nodeId.startsWith("w") }
                        .sumOf { it.durationMs }
                        .toDouble()
                }
            return "| $capacity | ${checksum.joinToString(
                "/",
            )} | ${fmt(median(runs.map { it.wallMs }))} | ${fmt(p95(runs.map { it.wallMs }))} | " +
                "${fmt(median(drain))} | ${fmt(median(ddl))} | ${tracker.peakLeases()} | ${fmt(tracker.waitMsPerRun(runs.size + WARMUP))} |"
        }
    }

    // ------------------------------------------------------------ shape 3

    private fun measureShape3(capacity: Int): String {
        val sources = (1..SOURCES).map { i -> h2Datasource("m_conc_$i", sourceDdl()) }
        val nodes =
            (1..SOURCES).map { i -> Fixtures.node("src$i", source = "m_conc_$i", output = NodeOutput.Tempdb("s_$i")) } +
                listOf(
                    Fixtures.node("chain1", output = NodeOutput.Tempdb("c1"), dependsOn = (1..SOURCES).map { "src$it" }),
                    Fixtures.node("chain2", output = NodeOutput.Tempdb("c2"), dependsOn = listOf("chain1")),
                    Fixtures.node("report", output = NodeOutput.Caller, dependsOn = listOf("chain2")),
                )
        val sql =
            (1..SOURCES).associate { "src$it" to "SELECT n, amount, label FROM s" } +
                mapOf(
                    "chain1" to (1..SOURCES).joinToString(" UNION ALL ") { """SELECT "n", "amount", "label" FROM "s_$it"""" },
                    "chain2" to
                        """SELECT "label", SUM("amount") AS total, COUNT(*) AS cnt, COUNT("amount") AS nn FROM "c1" GROUP BY "label"""",
                    "report" to """SELECT "label", "total", "cnt", "nn" FROM "c2" ORDER BY "label"""",
                )
        val store = RecordingStore()
        val pipeline = Fixtures.pipeline(nodes)
        val registry = FakeDatasourceRegistry((1..SOURCES).associate { "m_conc_$it" to sources[it - 1] })
        val tracker = PoolTracker()
        ExecutorHarness(
            templateEngine = engine(sql),
            registry = registry,
            config = config(),
            resultStore = store,
            stagingFactory = tracker.factory(capacity),
        ).use { h ->
            fun batch(): Pair<Double, List<ExecutionResult>> {
                val started = System.nanoTime()
                val results = runBlocking { (1..CONCURRENT).map { async { h.executor.execute(Fixtures.request(pipeline)) } }.awaitAll() }
                return (System.nanoTime() - started) / NANOS_PER_MS to results
            }
            repeat(WARMUP) { batch() }
            val batches = (1..REPS).map { Sample.of(::batch) }
            val checksums = batches.flatMap { b -> b.result.second.map { store.checksumOf(it.executionId) } }.distinct()
            val perExecution = batches.flatMap { b -> b.result.second.map { it.durationMs.toDouble() } }
            val batchMedian = median(batches.map { it.wallMs })
            return "| $capacity | ${checksums.size == 1} (${checksums.first()}) | ${fmt(batchMedian)} | ${fmt(p95(perExecution))} | " +
                "${fmt(
                    CONCURRENT * MS_PER_S / batchMedian,
                )} | ${fmt(median(batches.map { it.gcMs }))} | ${fmt(median(batches.map { it.cpuMs }))} |"
        }
    }

    // ------------------------------------------------------------ fixtures

    private fun sourceDdl(): List<String> =
        listOf(
            "CREATE TABLE s (n INT, amount DECIMAL(12,2), label VARCHAR(20))",
            """INSERT INTO s SELECT "X", CASE WHEN MOD("X", 5) = 0 THEN NULL ELSE "X" * 1.25 END, 'l' || MOD("X", 7) """ +
                "FROM SYSTEM_RANGE(1, $SOURCE_ROWS)",
        )

    private fun config() = ExecutorConfig(maxParallelNodes = PARALLEL_NODES, executionTimeoutSeconds = EXECUTION_TIMEOUT_S)

    /**
     * Exact-argument stubs with `returns`, one per template — never an `answers` block, which
     * MockK does not build for a single mock invoked from many threads at once (Fixtures'
     * `constantTemplateEngine` records the hang).
     */
    private fun engine(sqlByTemplateId: Map<String, String>): TemplateEngine {
        val engine = mockk<TemplateEngine>()
        sqlByTemplateId.forEach { (id, sql) -> every { engine.render(TemplateRef(id, 1), any(), any()) } returns sql }
        return engine
    }

    private fun printEnvironment() {
        val os = ManagementFactory.getOperatingSystemMXBean()
        println("### Environment")
        println()
        val runtime = Runtime.getRuntime()
        println(
            "- JVM ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}), " +
                "H2 ${org.h2.engine.Constants.FULL_VERSION}, ${os.name} ${os.arch}, ${runtime.availableProcessors()} CPUs, " +
                "max heap ${runtime.maxMemory() / MB} MB, 1-min load ${"%.2f".format(os.systemLoadAverage)}",
        )
        println(
            "- executor max-parallel-nodes $PARALLEL_NODES, harness dispatcher threads 8, warmup $WARMUP, " +
                "measured $REPS, concurrent batch $CONCURRENT",
        )
        println()
    }

    /** One timed run with JVM-wide GC time, process CPU time and peak heap deltas around it. */
    private class Sample<T>(
        val result: T,
        val wallMs: Double,
        val gcMs: Double,
        val cpuMs: Double,
        val peakHeapMb: Long,
    ) {
        companion object {
            fun <T> of(run: () -> T): Sample<T> {
                val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
                val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
                pools.forEach { it.resetPeakUsage() }
                val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                val gc0 = gcBeans.sumOf { it.collectionTime }
                val cpu0 = os.processCpuTime
                val t0 = System.nanoTime()
                val result = run()
                val wall = (System.nanoTime() - t0) / NANOS_PER_MS
                val cpu = (os.processCpuTime - cpu0) / NANOS_PER_MS
                val gc = (gcBeans.sumOf { it.collectionTime } - gc0).toDouble()
                val peak = pools.sumOf { it.peakUsage.used } / MB
                return Sample(result, wall, gc, cpu, peak)
            }
        }
    }

    /** Wraps the real factory to read each execution's pool counters after it closes. */
    private class PoolTracker {
        private val peak = AtomicLong()
        private val opened = AtomicLong()
        private val waitNanos = AtomicLong()

        fun factory(capacity: Int): StagingFactory {
            val real = H2StagingFactory(H2StagingProperties(maxConnections = capacity))
            return object : StagingFactory {
                override fun create(
                    executionId: UUID,
                    engine: StagingEngine,
                ): Staging = Tracked(real.create(executionId, engine) as H2Staging)
            }
        }

        fun peakLeases() = peak.get()

        fun opened() = opened.get()

        fun waitMsPerRun(runs: Int) = waitNanos.get() / NANOS_PER_MS / runs

        private inner class Tracked(
            private val inner: H2Staging,
        ) : Staging by inner {
            override fun close() {
                val stats = inner.poolStats()
                peak.updateAndGet { maxOf(it, stats.peakActiveLeases.toLong()) }
                opened.updateAndGet { maxOf(it, stats.physicalOpened.toLong()) }
                waitNanos.addAndGet(stats.leaseWaitNanos)
                inner.close()
            }
        }
    }

    /**
     * A result store that keeps a checksum per execution and — optionally — sleeps every
     * [sleepPerRows] rows while draining, standing in for a slow network sink.
     */
    private class RecordingStore(
        private val sleepPerRows: Int = 0,
    ) : ResultStore {
        private val checksums = ConcurrentHashMap<UUID, String>()
        private val config = ResultConfig()

        fun checksumOf(executionId: UUID): String = checksums[executionId] ?: "none"

        override suspend fun materialize(
            executionId: UUID,
            resultSet: ResultSet,
            sourceDialect: Dialect,
            ttlSeconds: Long,
        ): StoredResult {
            val schema = ResultRowReader.schemaOf(resultSet.metaData, sourceDialect)
            var rows = 0L
            var hash = 17L
            while (resultSet.next()) {
                val row = schema.columns.mapIndexed { i, c -> ResultRowReader.readValue(resultSet, i + 1, c) }
                hash = hash * HASH_PRIME + row.joinToString("|") { it?.toString() ?: "null" }.hashCode()
                rows++
                if (sleepPerRows > 0 && rows % sleepPerRows == 0L) Thread.sleep(SLOW_SINK_SLEEP_MS)
            }
            checksums[executionId] = "rows=$rows,h=${java.lang.Long.toHexString(hash)}"
            return StoredResult("m:$executionId", rows, 0, Instant.now().plusSeconds(ttlSeconds), schema.warnings)
        }

        override suspend fun materializeRows(
            executionId: UUID,
            schema: List<ColumnSchema>,
            rows: Sequence<List<Any?>>,
            ttlSeconds: Long,
        ): StoredResult = error("not used by these shapes")

        override fun keyFor(executionId: UUID): String = "m:$executionId"

        override fun describe(
            key: String,
            firstPageRows: Int?,
        ): StoredResultView? = null

        override fun page(
            key: String,
            offset: Long,
            limit: Int,
        ): ResultPage? = null

        override fun discard(key: String) = Unit

        private companion object {
            const val HASH_PRIME = 31L
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.isEmpty()) Double.NaN else sorted[sorted.size / 2]
    }

    private fun p95(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.isEmpty()) Double.NaN else sorted[minOf(sorted.size - 1, (sorted.size * P95).toInt())]
    }

    private fun fmt(value: Double) = "%.1f".format(value)

    private companion object {
        const val SOURCES = 4
        const val SOURCE_ROWS = 50_000
        const val DRAIN_ROWS = 20_000
        const val WORK_ROWS = 100_000
        const val SLOW_SINK_ROWS_PER_SLEEP = 500
        const val SLOW_SINK_SLEEP_MS = 5L
        const val PARALLEL_NODES = 4
        const val EXECUTION_TIMEOUT_S = 300L
        const val WARMUP = 2
        const val REPS = 7
        const val CONCURRENT = 4
        const val NANOS_PER_MS = 1_000_000.0
        const val MS_PER_S = 1_000.0
        const val MB = 1024L * 1024L
        const val P95 = 0.95
    }
}
