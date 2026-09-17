package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.StageObserver
import co.datapipelines.staging.StagingEngine
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * Instrumentation overhead on the hot path (149 §4): the SAME 100 000-row stage through real
 * H2, with the observer OFF ([StageObserver.NONE]) and ON (the production bridge onto a real
 * [NodeOperationTracker]), in alternating order, six runs each. Outputs must be identical; the
 * timings are printed for the evidence record and are NOT asserted — a wall-clock threshold
 * on a shared box is a coin toss, and the guard here is the identical output plus the bounded
 * sample count, which cannot vary with load.
 */
class NodeProgressOverheadTest {
    @Test
    fun `instrumented and uninstrumented stages produce identical tables, with bounded samples`() {
        val source =
            h2Datasource(
                "ov",
                listOf("CREATE TABLE t (id INT, v VARCHAR(16))", """INSERT INTO t SELECT "X", 'v' || "X" FROM SYSTEM_RANGE(1, $ROWS)"""),
            )
        val factory = H2StagingFactory(H2StagingProperties(insertBatchSize = BATCH, maxConnections = 2))
        val off = mutableListOf<Long>()
        val on = mutableListOf<Long>()
        var checksumOff: Long? = null
        var checksumOn: Long? = null
        var samples = 0
        repeat(RUNS) { run ->
            // The SECOND stage of a pair is measurably slower whatever it is (the first one's
            // heap is still being collected under §8.2's `System.gc()`), so the order alternates
            // per run: each variant goes first equally often, and the medians compare like with like.
            val order = if (run % 2 == 0) listOf(false, true) else listOf(true, false)
            order.forEach { instrumented ->
                val tracker =
                    NodeOperationTracker("n", 1, OperationKind.STAGE, OperationDestination.tempdb("stg"), sampleIntervalMs = INTERVAL_MS)
                val observer: StageObserver =
                    if (instrumented) {
                        StagingObserverBridge(
                            "n",
                            tracker,
                            NodeProgressSink.NONE,
                        )
                    } else {
                        StageObserver.NONE
                    }
                val (millis, checksum) = stageOnce(source, factory, observer)
                if (instrumented) {
                    on += millis
                    checksumOn = checksum
                    // Simulate the pump: how many samples would the rules have produced? Drive it
                    // once at the end — every first-entry sample and the terminal one; the periodic
                    // ones are bounded by elapsed / interval.
                    samples = maxOf(samples, countSamples(tracker, millis))
                } else {
                    off += millis
                    checksumOff = checksum
                }
                if (run == 0) println("overhead run warm-up instrumented=$instrumented ms=$millis")
            }
        }
        checksumOn shouldBe checksumOff
        val medianOff = off.sorted()[off.size / 2]
        val medianOn = on.sorted()[on.size / 2]
        println(
            "MEASUREMENT stage $ROWS rows × ${ROWS / BATCH} batches: observer OFF median=${medianOff}ms runs=$off; " +
                "observer ON median=${medianOn}ms runs=$on; samples/op ≤ $samples",
        )
        (samples <= 1 + OperationPhase.entries.size + (medianOn / INTERVAL_MS).toInt() + 2).shouldBeTrue()
    }

    /** One full stage of the source into a fresh tempdb: wall time in ms, and the staged table's checksum. */
    private fun stageOnce(
        source: Datasource,
        factory: H2StagingFactory,
        observer: StageObserver,
    ): Pair<Long, Long> =
        factory.create(UUID.randomUUID(), StagingEngine.H2).use { st ->
            SourceCursor(source).use { cursor ->
                val started = System.nanoTime()
                val staged = runBlocking { st.stage(cursor.rows, "stg", Dialect.H2, observer) }
                val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
                staged.rowsStaged shouldBe ROWS.toLong()
                elapsed to runBlocking { st.withConnection { checksum(it) } }
            }
        }

    /** The source cursor over `t`, owning its connection and statement so one `use` closes all three. */
    private class SourceCursor(
        source: Datasource,
    ) : AutoCloseable {
        private val connection = DriverManager.getConnection(source.jdbcUrl, source.username, "")
        private val statement = connection.createStatement()
        val rows: ResultSet = statement.executeQuery("SELECT id, v FROM t")

        override fun close() {
            rows.close()
            statement.close()
            connection.close()
        }
    }

    private fun countSamples(
        tracker: NodeOperationTracker,
        elapsedMs: Long,
    ): Int {
        // The tracker was driven by the bridge; its sequence counter after one terminal flush is
        // the number of samples the pump COULD have taken had it ticked at every due moment.
        var n = 0
        while (tracker.sampleIfDue() != null) n++
        n += 1 // terminal
        tracker.finish(OperationOutcome.COMPLETED, committed = true)
        return n + (elapsedMs / INTERVAL_MS).toInt()
    }

    private fun checksum(c: java.sql.Connection): Long =
        c.createStatement().use { st ->
            st.executeQuery("""SELECT SUM("id" * 31 + LENGTH("v")), COUNT(*) FROM "stg"""").use { rs ->
                rs.next()
                rs.getLong(1) * 1_000_003L + rs.getLong(2)
            }
        }

    private companion object {
        const val ROWS = 100_000
        const val BATCH = 500
        const val RUNS = 6
        const val INTERVAL_MS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
