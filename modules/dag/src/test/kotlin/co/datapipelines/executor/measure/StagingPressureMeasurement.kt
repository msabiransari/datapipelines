package co.datapipelines.executor.measure

import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.typesystem.Dialect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 108 §3 — **what does one execution's staging actually cost, against the budget it is given?**
 *
 * `max-memory-mb` is a per-EXECUTION ceiling, and
 * `max-concurrent-executions-per-instance × max-memory-mb` is what the JVM can be asked for: with
 * the shipped defaults, 100 GB. Whether that matters depends on a number nobody had measured —
 * how much heap an execution staging a large table actually holds — so this measures it, and the
 * §C startup warning exists to say the arithmetic out loud rather than to guess at it.
 *
 * The reading is the same one `checkMemoryBudget` uses (post-GC used heap), because the point is
 * to compare like with like: if the guard's own metric says 400 MB for 2M rows, then the guard is
 * measuring the right thing and the ceiling means what it says.
 *
 * **RSS is deliberately NOT read here.** Process RSS includes the test JVM, the H2 driver, the
 * Gradle worker and every container client on the classpath; attributing it to "an execution
 * staging 2M rows" would be a number with the wrong name on it. `scripts/measure/03-pressure.sh`
 * samples the JVM's RSS from OUTSIDE with `ps` while this runs, which is the honest way to get it.
 *
 * Reports, never asserts — gated on `DP_MEASURE=1`.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class StagingPressureMeasurement {
    @Test
    fun `heap held by one execution staging at the default budget`() {
        println("### One execution's staging footprint (the guard's own metric)")
        println("| rows | staged heap (MB) | max heap (MB) | budget (MB) |")
        println("|---|---|---|---|")
        ROW_COUNTS.forEach { rows ->
            val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())
            try {
                val baseline = usedHeapMb()
                source(rows).use { src ->
                    runBlocking { staging.stage(src.cursor, "m_pressure", Dialect.H2) }
                    val used = usedHeapMb()
                    println(
                        "| $rows | ${used - baseline} (abs $used) | ${Runtime.getRuntime().maxMemory() / MB} | " +
                            "${H2StagingProperties().maxMemoryMb} |",
                    )
                }
            } finally {
                staging.close()
            }
        }
        println()
        println("The `max_memory_mb` guard reads the JVM's used heap, not this execution's share of")
        println("it (staging §8.2's stated limit), so with concurrent executions every instance sees")
        println("the same number. The absolute column is what the guard would compare; the delta is")
        println("what this execution actually added.")
        println()
    }

    /**
     * §3's other half — **in-process DuckDB shares the JVM's CPUs with the executor**, so what does
     * a lake scan cost when executions are staging beside it?
     *
     * DuckDB with `threads` unset takes ALL cores. The executor is on the same box competing for
     * the same ones, so a lake node and two staging drains are three parallel consumers of one CPU
     * budget, and nothing arbitrates. This measures the scan alone, then with two concurrent
     * staging drains, at DuckDB's default thread count and at a capped one.
     *
     * The workload is a real DuckDB aggregation over generated rows rather than Parquet on disk:
     * the question is CPU contention between the embedded engine and the JVM, and a synthetic scan
     * exercises exactly that without needing an object store. A real lake read adds I/O, which
     * would make the contention LESS visible, not more — so this is the honest direction to err in.
     */
    @Test
    fun `a lake scan alone, and with two executions staging beside it`() {
        val cores = Runtime.getRuntime().availableProcessors()
        val proposed = maxOf(2, cores / 2)
        // Two levels of executor load, because the hypothesis is ABOUT saturation: two drains on a
        // ten-core box leaves most of the machine idle and would refute a cap that only matters
        // when the box is full. `cores - 2` is the executor genuinely competing.
        val loads = listOf(2, maxOf(2, cores - 2))
        println("### DuckDB / executor CPU contention (cores reported to the JVM: $cores)")
        println("| threads setting | staging drains | scan alone | scan under load | slowdown |")
        println("|---|---|---|---|---|")
        listOf(null, proposed).forEach { threads ->
            val label = threads?.let { "SET threads = $it (hypothesis)" } ?: "unset — DuckDB takes all $cores"
            val alone = lakeScanMs(threads, staging = 0)
            loads.forEach { load ->
                val contended = lakeScanMs(threads, staging = load)
                println("| $label | $load | ${alone}ms | ${contended}ms | ${"%.2f".format(contended.toDouble() / alone)}× |")
            }
        }
        println()
        println("A LOWER slowdown means the lake read degrades less when the executor is busy.")
        println("Capping trades solo speed for that — IF it buys anything. Read both load levels")
        println("before believing either.")
        println()
    }

    /** One DuckDB aggregation, optionally with [staging] H2 drains running against the same CPUs. */
    private fun lakeScanMs(
        threads: Int?,
        staging: Int,
    ): Long {
        val noise = Executors.newFixedThreadPool(maxOf(1, staging))
        val running = AtomicBoolean(true)
        repeat(staging) { noise.submit { stageUntilStopped(running) } }
        // Let the drains reach steady state, or the scan measures an idle box for its first half.
        if (staging > 0) Thread.sleep(WARMUP_MS)
        return try {
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { st ->
                    threads?.let { st.execute("SET threads = $it") }
                    // Warm the engine so the number is the scan, not class loading.
                    st.executeQuery("SELECT count(*) FROM range(1000)").close()
                    val started = System.nanoTime()
                    st.executeQuery(LAKE_SCAN_SQL).use { rs ->
                        var n = 0
                        while (rs.next()) n++
                    }
                    (System.nanoTime() - started) / 1_000_000
                }
            }
        } finally {
            running.set(false)
            noise.shutdownNow()
        }
    }

    /** An H2 staging drain on a loop — the executor-side load a lake scan competes with. */
    private fun stageUntilStopped(running: AtomicBoolean) {
        while (running.get()) {
            val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())
            try {
                source(NOISE_ROWS).use { src ->
                    runBlocking { staging.stage(src.cursor, "m_noise", Dialect.H2) }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                return // the pool is shutting down; the scan already has its number
            } finally {
                staging.close()
            }
        }
    }

    private fun usedHeapMb(): Long {
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB
    }

    private fun source(rows: Long): Source {
        val connection = DriverManager.getConnection("jdbc:h2:mem:pressure_${UUID.randomUUID()};MODE=PostgreSQL", "sa", "")
        val cursor =
            connection
                .createStatement()
                .executeQuery("SELECT x AS id, RPAD('a', 200, 'a') AS payload FROM SYSTEM_RANGE(1, $rows)")
        return Source(connection, cursor)
    }

    private class Source(
        private val connection: Connection,
        val cursor: java.sql.ResultSet,
    ) : AutoCloseable {
        override fun close() = connection.close()
    }

    private companion object {
        const val MB = 1024L * 1024L

        /** Up to 2M rows — the figure T188's pipeline scanned, and the one §C's warning is about. */
        val ROW_COUNTS = listOf(100_000L, 500_000L, 2_000_000L)

        /**
         * A DuckDB aggregation heavy enough to be several seconds of real CPU — a group-by over
         * 40M generated rows with a hash on each, which is the shape a lake scan's aggregation has.
         */
        const val LAKE_SCAN_SQL =
            "SELECT k, count(*) AS c, sum(v) AS s FROM (" +
                "SELECT hash(i) % 1000 AS k, i AS v FROM range(40000000) t(i)" +
                ") GROUP BY k ORDER BY c DESC LIMIT 10"

        /** Rows per staging drain in the contention run — big enough to keep a core busy. */
        const val NOISE_ROWS = 300_000L

        const val WARMUP_MS = 400L
    }
}
