package co.datapipelines.executor.measure

import co.datapipelines.staging.H2Staging
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.typesystem.Dialect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    fun `three concurrent source drains — before and after, back to back`() {
        println("### Three independent source drains, ${DRAIN_ROWS} rows each, ${DELAY_PER_ROW_MS}ms per row")
        println()
        println("| shape | lane windows (ms from t0) | execution wall time | sum of windows |")
        println("|---|---|---|---|")
        // BEFORE first, so a reader sees the two in the order the change happened — and so a run
        // where the box degrades midway makes the AFTER look worse, never better. Erring against
        // the result you want is the only way a self-measured before/after is worth reading.
        listOf(true, false).forEach { serialized ->
            val run = drainThreeLanes(serialized)
            val label = if (serialized) "**before** — one lock for the whole drain" else "**after** — lock per batch (shipped)"
            val windows = run.windows.joinToString(", ") { (lane, first, last) -> "$lane ${first - run.t0}–${last - run.t0}" }
            println("| $label | $windows | **${run.wallMs}ms** | ${run.sumMs}ms |")
        }
        println()
        println("The BEFORE arm holds one mutex across each whole `stage()` call, which is exactly")
        println("what `H2Staging` did before 108 §B — the drain, the network wait included, inside")
        println("the lock. Both arms use the same staging instance, the same cursors and the same")
        println("rows, back to back in one JVM, so the only difference between the two rows is the")
        println("shape. A wall time near the SUM is serialized; near the LONGEST window is overlapped.")
        println()
    }

    /** One three-lane drain. [serialized] reproduces the pre-108 whole-drain lock. */
    private fun drainThreeLanes(serialized: Boolean): LaneRun {
        val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())
        val legacyLock = Mutex()
        val windows = mutableListOf<Triple<String, Long, Long>>()
        val startedAt = System.currentTimeMillis()
        try {
            sources(LANES).use { srcs ->
                runBlocking {
                    windows +=
                        srcs.cursors
                            .mapIndexed { i, cursor ->
                                async(Dispatchers.IO) {
                                    val first = AtomicLong()
                                    val last = AtomicLong()
                                    val instrumented = timedCursor(cursor, first, last)
                                    val table = "m_stg_${if (serialized) "before" else "after"}_$i"
                                    if (serialized) {
                                        legacyLock.withLock { staging.stage(instrumented, table, Dialect.H2) }
                                    } else {
                                        staging.stage(instrumented, table, Dialect.H2)
                                    }
                                    Triple("lane_$i", first.get(), last.get())
                                }
                            }.awaitAll()
                }
            }
        } finally {
            staging.close()
        }
        return LaneRun(
            windows = windows,
            t0 = windows.minOf { it.second },
            wallMs = System.currentTimeMillis() - startedAt,
            sumMs = windows.sumOf { it.third - it.second },
        )
    }

    private class LaneRun(
        val windows: List<Triple<String, Long, Long>>,
        val t0: Long,
        val wallMs: Long,
        val sumMs: Long,
    )

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
    }

    /**
     * The alternative the brief asked for as a NUMBER, not as an argument: a multi-row
     * `INSERT … VALUES (…), (…)` against `addBatch`/`executeBatch`.
     *
     * H2 offers no `REWRITE_BATCHED_STATEMENTS` equivalent — MySQL's driver rewrites a batch into
     * one multi-row statement, H2's does not — so the question is whether building that statement
     * OURSELVES beats letting the driver loop. Both forms below insert the same rows into the same
     * table through the same connection; only the statement shape differs.
     *
     * A multi-row VALUES cannot use a single prepared statement across batches (the placeholder
     * count changes with the batch), so the parameterised form re-prepares per batch. Both are
     * measured: re-prepared, and literal-inlined (which is what one would actually write, and
     * which gives up bind parameters — a trade this codebase does not make lightly, §4.5).
     */
    @Test
    fun `multi-row VALUES against addBatch`() {
        println("### `addBatch` vs multi-row `VALUES (…),(…)` — ${THROUGHPUT_ROWS} rows, batch ${VALUES_BATCH}")
        println("| form | elapsed | rows/s |")
        println("|---|---|---|")
        listOf<Pair<String, (java.sql.Connection, String) -> Unit>>(
            "addBatch / executeBatch (shipped)" to ::insertByBatch,
            "multi-row VALUES, re-prepared per batch" to ::insertByValuesPrepared,
            "multi-row VALUES, literals inlined" to ::insertByValuesLiteral,
        ).forEach { (label, insert) ->
            val connection = DriverManager.getConnection("jdbc:h2:mem:vals_${UUID.randomUUID()};MODE=PostgreSQL", "sa", "")
            connection.use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE t (n BIGINT)") }
                val started = System.nanoTime()
                insert(conn, "t")
                val ms = (System.nanoTime() - started) / 1_000_000
                println("| $label | ${ms}ms | ${if (ms == 0L) "n/a" else (THROUGHPUT_ROWS * 1000 / ms).toString()} |")
            }
        }
        println()
    }

    private fun insertByBatch(
        conn: java.sql.Connection,
        table: String,
    ) {
        conn.prepareStatement("INSERT INTO $table VALUES (?)").use { stmt ->
            for (i in 1..THROUGHPUT_ROWS) {
                stmt.setLong(1, i)
                stmt.addBatch()
                if (i % VALUES_BATCH == 0L) stmt.executeBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun insertByValuesPrepared(
        conn: java.sql.Connection,
        table: String,
    ) {
        val placeholders = (1..VALUES_BATCH).joinToString(",") { "(?)" }
        var written = 0L
        while (written < THROUGHPUT_ROWS) {
            val rows = minOf(VALUES_BATCH, THROUGHPUT_ROWS - written).toInt()
            val sql =
                "INSERT INTO $table VALUES " + if (rows == VALUES_BATCH.toInt()) placeholders else (1..rows).joinToString(",") { "(?)" }
            conn.prepareStatement(sql).use { stmt ->
                for (i in 1..rows) stmt.setLong(i, written + i)
                stmt.executeUpdate()
            }
            written += rows
        }
    }

    private fun insertByValuesLiteral(
        conn: java.sql.Connection,
        table: String,
    ) {
        var written = 0L
        conn.createStatement().use { stmt ->
            while (written < THROUGHPUT_ROWS) {
                val rows = minOf(VALUES_BATCH, THROUGHPUT_ROWS - written)
                val values = (1..rows).joinToString(",") { "(${written + it})" }
                stmt.executeUpdate("INSERT INTO $table VALUES $values")
                written += rows
            }
        }
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

        /** The shipped default, so the three forms are compared at the size that actually runs. */
        const val VALUES_BATCH = 1_000L
    }
}
