package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The §9.2 serialization guarantee: two coroutines staging into the same instance at the same
 * time are serialized by the instance's own `Mutex`, on real threads (`Dispatchers.IO`),
 * through the one shared JDBC connection.
 *
 * **The row-count assertions alone were a false green (ST-TEST-1).** H2's own connection
 * happens to serialize enough internally that both tables still land with correct counts even
 * with the mutex removed — so the old test could not fail for the reason it claimed to guard.
 * The real assertion has to observe *our* lock, not the outcome, so the staging instance is
 * built over an instrumented `Connection` that counts how many staging operations are inside
 * the connection at once. Under the mutex that gauge can never exceed 1; without it, two
 * concurrent `stage()` calls drive it to 2.
 */
class H2StagingConcurrencyTest {
    private val props = H2StagingProperties()

    @Test
    fun `concurrent stage calls never overlap inside the connection`() {
        val executionId = UUID.randomUUID()
        // Instrumented `sa` connection, NOT the factory's restricted one: this test has to wrap
        // the Connection to count overlap, which the factory does not expose. It is about the
        // mutex, not about §9.5 privilege — that is H2StagingPrivilegeTest's job, on a real
        // factory instance. Read the `sa` here as a test harness, never as an endorsement.
        val raw = DriverManager.getConnection(stagingUrl(executionId, props), "sa", "")
        val gauge = ConcurrencyGauge()
        val staging = H2Staging(executionId, gauge.instrument(raw), props)

        val rowsPerTable = 4_000L
        SourceDb().use { srcA ->
            SourceDb().use { srcB ->
                val rsA = srcA.query("SELECT x AS id FROM SYSTEM_RANGE(1, $rowsPerTable)")
                val rsB = srcB.query("SELECT x AS id FROM SYSTEM_RANGE(1, $rowsPerTable)")

                val results =
                    runBlocking {
                        awaitAll(
                            async(Dispatchers.IO) { staging.stage(rsA, "stg_a", Dialect.H2) },
                            async(Dispatchers.IO) { staging.stage(rsB, "stg_b", Dialect.H2) },
                        )
                    }

                results.map { it.rowsStaged }.toSet() shouldBe setOf(rowsPerTable)
            }
        }

        // The load-bearing assertion: never two operations inside the connection at once.
        gauge.peak() shouldBe 1
        // Guard the guard — a gauge that never fired would make `peak() == 1` vacuous, and a
        // proxy that missed the JDBC calls entirely would read 0.
        gauge.calls() shouldBeGreaterThan 2

        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_a\"") } shouldBe rowsPerTable
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_b\"") } shouldBe rowsPerTable

        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 2
        stats.totalRows shouldBe rowsPerTable * 2

        staging.close()
    }

    /**
     * 108 §B, the load-bearing claim of this round's staging change: two independent source
     * cursors are **read at the same time**.
     *
     * Before 108 `stage()` held the per-execution mutex for the entire drain — the network wait on
     * the source database included — so two source nodes of one pipeline staged strictly one after
     * the other. The pipeline that motivated this round had an agent adding artificial `depends_on`
     * edges because it could see the contention.
     *
     * The assertion is on TIMESTAMPS, not on a green suite: each source records when its first and
     * last row were read, and the two intervals must intersect. A green suite proves the rows
     * arrived; only overlapping read windows prove the lock was released between batches. Each
     * cursor sleeps per row, so under the old shape the windows are strictly disjoint by
     * construction and this test is red.
     *
     * The serialization guarantee is asserted in the same run: `peak() == 1` says the connection
     * itself never had two calls inside it, which is the invariant §9.2 actually states.
     */
    @Test
    fun `two concurrent stages read their source cursors at the same time`() {
        val executionId = UUID.randomUUID()
        val raw = DriverManager.getConnection(stagingUrl(executionId, props), "sa", "")
        val gauge = ConcurrencyGauge()
        // A small batch size so the drain takes the lock many times: a single-batch stage would
        // overlap trivially and prove nothing about releasing between batches.
        val staging = H2Staging(executionId, gauge.instrument(raw), props.copy(insertBatchSize = SLOW_BATCH_SIZE))

        SourceDb().use { srcA ->
            SourceDb().use { srcB ->
                val a = SlowCursor(srcA.query("SELECT x AS id FROM SYSTEM_RANGE(1, $SLOW_ROWS)"))
                val b = SlowCursor(srcB.query("SELECT x AS id FROM SYSTEM_RANGE(1, $SLOW_ROWS)"))

                runBlocking {
                    awaitAll(
                        async(Dispatchers.IO) { staging.stage(a.cursor, "slow_a", Dialect.H2) },
                        async(Dispatchers.IO) { staging.stage(b.cursor, "slow_b", Dialect.H2) },
                    )
                }

                // Both cursors really were read row by row — without this the windows below could
                // be two instants and "overlap" would mean nothing.
                a.reads() shouldBe SLOW_ROWS + 1
                b.reads() shouldBe SLOW_ROWS + 1
                // The claim: A was still reading when B started, or the other way round.
                (a.firstReadMs() <= b.lastReadMs() && b.firstReadMs() <= a.lastReadMs()) shouldBe true
            }
        }

        gauge.peak() shouldBe 1
        staging.close()
    }

    /**
     * A cursor that costs [DELAY_PER_ROW_MS] per row and records when it was read.
     *
     * The delay stands in for the network wait on a real source database — the thing the old
     * `stage()` held the staging mutex across. Nothing else about it is synthetic: the rows come
     * from a real H2 `ResultSet`.
     */
    private class SlowCursor(
        target: ResultSet,
    ) {
        private val reads = AtomicInteger()
        private val first =
            java.util.concurrent.atomic
                .AtomicLong()
        private val last =
            java.util.concurrent.atomic
                .AtomicLong()

        val cursor: ResultSet =
            Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "next") {
                        Thread.sleep(DELAY_PER_ROW_MS)
                        val now = System.currentTimeMillis()
                        first.compareAndSet(0, now)
                        last.set(now)
                        reads.incrementAndGet()
                    }
                    try {
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw e.targetException
                    }
                },
            ) as ResultSet

        fun reads() = reads.get()

        fun firstReadMs() = first.get()

        fun lastReadMs() = last.get()
    }

    /**
     * Wraps a [Connection] so every call **into** it — its own methods and every method of the
     * statements it hands out — brackets a counter. The peak value is the maximum number of JDBC
     * calls that were ever executing against the connection at the same instant.
     *
     * **It brackets CALLS, not statement lifetimes, and since 108 §B it has to.** The drain now
     * prepares one `INSERT` statement, uses it under the lock once per batch, and closes it at the
     * end — so two concurrent stages legitimately hold two OPEN statements on the connection while
     * never executing against it at the same time. A gauge measuring "statement created until
     * closed" would read 2 for a perfectly serialized run, which is a guard that fails on correct
     * code. What §9.2 actually forbids is two callers inside the connection at once, and that is a
     * property of the calls.
     */
    private class ConcurrencyGauge {
        private val inFlight = AtomicInteger()
        private val peak = AtomicInteger()
        private val calls = AtomicInteger()

        fun peak() = peak.get()

        fun calls() = calls.get()

        fun instrument(target: Connection): Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                InvocationHandler { _, method, args ->
                    val result = bracket { invoke(target, method, args) }
                    if (method.name in STATEMENT_FACTORIES) wrapStatement(result) else result
                },
            ) as Connection

        private fun wrapStatement(statement: Any?): Any? {
            val interfaces = statement!!.javaClass.interfaces
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                interfaces,
                InvocationHandler { _, method, args -> bracket { invoke(statement, method, args) } },
            )
        }

        /** Runs [call] with the in-flight counter raised — the whole measurement. */
        private fun <T> bracket(call: () -> T): T {
            enter()
            try {
                return call()
            } finally {
                exit()
            }
        }

        private fun invoke(
            target: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }

        private fun enter() {
            calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
        }

        private fun exit() {
            inFlight.decrementAndGet()
        }

        private companion object {
            val STATEMENT_FACTORIES = setOf("createStatement", "prepareStatement", "prepareCall")
        }
    }

    private companion object {
        /** Enough rows that the drain takes the lock many times at [SLOW_BATCH_SIZE]. */
        const val SLOW_ROWS = 60L
        const val SLOW_BATCH_SIZE = 10

        /** 60 rows × 5 ms ≈ 300 ms per cursor — long enough that disjoint windows are unmistakable. */
        const val DELAY_PER_ROW_MS = 5L
    }
}
