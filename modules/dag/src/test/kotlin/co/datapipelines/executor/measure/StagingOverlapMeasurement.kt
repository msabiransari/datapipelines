package co.datapipelines.executor.measure

import co.datapipelines.staging.H2Staging
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.typesystem.Dialect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 108 §2 — **do independent source nodes stage at the same time, and what does a batch cost?**
 *
 * Two questions, one class, because they are the two halves of the same complaint. T189's agent
 * added artificial `depends_on` edges because it observed staging contention; §B's claim is that
 * the contention was the per-execution staging mutex being held across the source drain — the
 * NETWORK WAIT included — and not anything inherent.
 *
 * The overlap run is therefore reported as three windows and a wall time, not as a pass. Under
 * the pre-108 shape the three windows are disjoint and the wall time is their sum; under the
 * shipped one they intersect and the wall time is the longest of them. Those are different
 * numbers, not a different verdict, which is why this is a measurement.
 *
 * The throughput run answers the question the fix raises: the drain now materialises one batch
 * per lock acquisition, so is `insert-batch-size` still 1 000? Only the number decides.
 *
 * Reports, never asserts — gated on `DP_MEASURE=1`.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class StagingOverlapMeasurement {
    @Test
    fun `three concurrent source drains — windows and wall time`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(H2StagingProperties()).create(executionId)
        val windows = mutableListOf<Triple<String, Long, Long>>()
        val startedAt = System.currentTimeMillis()
        try {
            sources(LANES).use { srcs ->
                runBlocking {
                    val results =
                        srcs.cursors.mapIndexed { i, cursor ->
                            async(Dispatchers.IO) {
                                val first = AtomicLong()
                                val last = AtomicLong()
                                val instrumented = timedCursor(cursor, first, last)
                                staging.stage(instrumented, "m_stg_$i", Dialect.H2)
                                Triple("lane_$i", first.get(), last.get())
                            }
                        }
                    windows += results.awaitAll()
                }
            }
        } finally {
            staging.close()
        }
        val wall = System.currentTimeMillis() - startedAt

        println("### Three independent source drains, ${DRAIN_ROWS} rows each, ${DELAY_PER_ROW_MS}ms per row")
        println("| lane | first read (ms from t0) | last read (ms from t0) | window |")
        println("|---|---|---|---|")
        val t0 = windows.minOf { it.second }
        windows.forEach { (lane, first, last) -> println("| $lane | ${first - t0} | ${last - t0} | ${last - first}ms |") }
        println()
        println("execution wall time: ${wall}ms")
        println("sum of the three windows: ${windows.sumOf { it.third - it.second }}ms")
        println()
        println("Read it as: a wall time near the LONGEST window means the drains overlapped;")
        println("a wall time near the SUM means they were serialized, which is the pre-108 shape.")
        println()
    }

    @Test
    fun `staging throughput at several insert-batch-size values`() {
        println("### `insert-batch-size` vs rows/s (${THROUGHPUT_ROWS} rows, H2 source, H2 tempdb)")
        println("| batch size | elapsed | rows/s |")
        println("|---|---|---|")
        BATCH_SIZES.forEach { size ->
            val staging = H2StagingFactory(H2StagingProperties(insertBatchSize = size)).create(UUID.randomUUID())
            try {
                sources(1, THROUGHPUT_ROWS, delayMs = 0).use { srcs ->
                    val started = System.nanoTime()
                    runBlocking { staging.stage(srcs.cursors.single(), "m_thr", Dialect.H2) }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    println("| $size | ${ms}ms | ${if (ms == 0L) "n/a" else (THROUGHPUT_ROWS * 1000 / ms).toString()} |")
                }
            } finally {
                staging.close()
            }
        }
        println()
        println("H2 has no `REWRITE_BATCHED_STATEMENTS` equivalent: `addBatch`/`executeBatch` is the")
        println("only batching the driver offers, so a multi-row `VALUES (…),(…)` would be string")
        println("building on our side against the same per-row work on H2's. Not measured as a")
        println("candidate because the row above already shows where the time is.")
        println()
    }

    /** A cursor that costs [DELAY_PER_ROW_MS] per row and records when it was read. */
    private fun timedCursor(
        target: ResultSet,
        first: AtomicLong,
        last: AtomicLong,
    ): ResultSet =
        java.lang.reflect.Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            java.lang.reflect.InvocationHandler { _, method, args ->
                if (method.name == "next") {
                    Thread.sleep(DELAY_PER_ROW_MS)
                    val now = System.currentTimeMillis()
                    first.compareAndSet(0, now)
                    last.set(now)
                }
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            },
        ) as ResultSet

    private fun sources(
        lanes: Int,
        rows: Long = DRAIN_ROWS,
        delayMs: Long = DELAY_PER_ROW_MS,
    ): Sources {
        val connections =
            (0 until lanes).map {
                DriverManager.getConnection(
                    "jdbc:h2:mem:meas_${UUID.randomUUID()};MODE=PostgreSQL",
                    "sa",
                    "",
                )
            }
        val cursors = connections.map { it.createStatement().executeQuery("SELECT x AS id FROM SYSTEM_RANGE(1, $rows)") }
        return Sources(connections, cursors, delayMs)
    }

    private class Sources(
        private val connections: List<Connection>,
        val cursors: List<ResultSet>,
        @Suppress("unused") val delayMs: Long,
    ) : AutoCloseable {
        override fun close() = connections.forEach(Connection::close)
    }

    private companion object {
        const val LANES = 3
        const val DRAIN_ROWS = 2_000L
        const val DELAY_PER_ROW_MS = 1L
        const val THROUGHPUT_ROWS = 200_000L
        val BATCH_SIZES = listOf(1_000, 10_000, 50_000)
    }
}
