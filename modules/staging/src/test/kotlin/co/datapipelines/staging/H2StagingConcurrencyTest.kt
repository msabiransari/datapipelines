package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The §9.2 invariant after #118: **one owner per physical connection, and real overlap across
 * connections.** The pre-146 guard — "peak concurrent JDBC calls on THE connection == 1" — was
 * the right invariant for one connection and the wrong one for a pool: with four connections a
 * peak of 1 would mean the global lock had come back. So the gauge is now per physical
 * connection (each must never see two callers inside it at once) plus a count of DISTINCT
 * connections inside JDBC at the same instant, which must exceed 1 when the cap allows it.
 *
 * The overlap proof is event-synchronised, not timed: two author blocks each enter a JDBC call
 * that parks on a two-party barrier *inside the driver call*. Both parties arrive only if both
 * are inside JDBC on two connections at the same time; any global serialisation around SQL
 * leaves the second party queued behind the first, the barrier times out, and the test is red.
 */
class H2StagingConcurrencyTest {
    private val props = H2StagingProperties()

    @Test
    fun `two author blocks are inside JDBC on two different connections at the same instant`() {
        val executionId = UUID.randomUUID()
        val gauge = PerConnectionGauge()
        val barrier = CyclicBarrier(2)
        val arrivals = AtomicInteger()
        // Only the two author blocks' own `createStatement` calls park on the barrier — the
        // reset, `stats()` and the close sweep create statements too and must not be paired up.
        val staging =
            instrumentedStaging(executionId, props, gauge) { method ->
                if (method.name == "createStatement" && arrivals.incrementAndGet() <= 2) barrier.await(BARRIER_S, TimeUnit.SECONDS)
            }

        runBlocking {
            awaitAll(
                async(Dispatchers.IO) { staging.withConnection { c -> c.createStatement().use { it.execute(CREATE_A) } } },
                async(Dispatchers.IO) { staging.withConnection { c -> c.createStatement().use { it.execute(CREATE_B) } } },
            )
        }

        // Both parties passed the barrier INSIDE createStatement: two connections, one instant.
        gauge.peakConnectionsInsideJdbc() shouldBeGreaterThanOrEqual 2
        gauge.peakPerConnection().values.forEach { it shouldBe 1 }
        runBlocking { staging.stats() }.tableCount shouldBe 2
        staging.close()
    }

    @Test
    fun `concurrent stages land on distinct connections and never share one`() {
        val executionId = UUID.randomUUID()
        val gauge = PerConnectionGauge()
        val staging = instrumentedStaging(executionId, props.copy(insertBatchSize = SLOW_BATCH_SIZE), gauge)

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

        // The load-bearing assertion: no physical connection ever had two callers inside it.
        gauge.peakPerConnection().values.forEach { it shouldBe 1 }
        // Guard the guard — a gauge that never fired would make the above vacuous.
        gauge.calls() shouldBeGreaterThan 2

        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_a\"") } shouldBe rowsPerTable
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_b\"") } shouldBe rowsPerTable
        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 2
        stats.totalRows shouldBe rowsPerTable * 2
        staging.close()
    }

    /**
     * 108 §B, kept verbatim in spirit: two independent source cursors are **read at the same
     * time**, asserted on timestamps. Under the pool the lease is taken per batch exactly as the
     * lock was, so a cursor's network wait still holds no connection.
     */
    @Test
    fun `two concurrent stages read their source cursors at the same time`() {
        val executionId = UUID.randomUUID()
        val gauge = PerConnectionGauge()
        val staging = instrumentedStaging(executionId, props.copy(insertBatchSize = SLOW_BATCH_SIZE, maxConnections = 1), gauge)

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

                a.reads() shouldBe SLOW_ROWS + 1
                b.reads() shouldBe SLOW_ROWS + 1
                (a.firstReadMs() <= b.lastReadMs() && b.firstReadMs() <= a.lastReadMs()) shouldBe true
            }
        }

        // Capacity ONE here: the single connection still never saw two callers — which is the
        // whole reason the source reads may overlap while the connection does not.
        gauge.peakPerConnection().values.forEach { it shouldBe 1 }
        gauge.peakConnectionsInsideJdbc() shouldBe 1
        staging.close()
    }

    @Test
    fun `a table committed by one connection is visible to a read on another`() {
        val executionId = UUID.randomUUID()
        val staging = instrumentedStaging(executionId, props, PerConnectionGauge())
        val holderIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first: Connection? = null
        var reader: Connection? = null

        runBlocking {
            // Hold the bootstrap-handoff connection so the producer is forced onto a second one.
            val holder =
                async(Dispatchers.IO) {
                    staging.withConnection { c ->
                        first = c
                        holderIn.complete(Unit)
                        release.await()
                    }
                }
            withTimeout(TIMEOUT_MS) { holderIn.await() }
            SourceDb().use { src ->
                src.exec("CREATE TABLE t (id INTEGER)")
                src.exec("INSERT INTO t VALUES (1), (2), (3)")
                staging.stage(src.query("SELECT id FROM t"), "stg_vis", Dialect.H2)
            }
            release.complete(Unit)
            holder.await()
            // The idle stack is LIFO, so the next lease draws the connection released LAST —
            // the holder's, which never touched the staged table.
            staging.withConnection { c ->
                reader = c
                scalarLong(c, "SELECT COUNT(*) FROM \"stg_vis\"") shouldBe 3L
            }
        }
        (reader === first) shouldBe true
        staging.close()
    }

    @Test
    fun `a cursor blocked in result delivery does not stop an independent operation`() {
        val executionId = UUID.randomUUID()
        val staging = instrumentedStaging(executionId, props, PerConnectionGauge())
        runBlocking { staging.execute("CREATE TABLE \"stg_q\" (\"id\" INTEGER)") }
        runBlocking { staging.execute("INSERT INTO \"stg_q\" VALUES (1), (2)") }
        val events = CopyOnWriteArrayList<String>()

        runBlocking {
            val draining = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader =
                async(Dispatchers.IO) {
                    staging.withQuery("SELECT \"id\" FROM \"stg_q\"") { rs: ResultSet ->
                        rs.next()
                        events += "drain-start"
                        draining.complete(Unit)
                        // Stands in for the caller node's suspending drain to the result store —
                        // and this time it waits for the OTHER operation to finish first.
                        release.await()
                        while (rs.next()) rs.getInt(1)
                        events += "drain-end"
                    }
                }
            withTimeout(TIMEOUT_MS) { draining.await() }
            withTimeout(TIMEOUT_MS) { staging.execute("CREATE TABLE \"stg_other\" (\"id\" INTEGER)") }
            events += "other-done"
            release.complete(Unit)
            reader.await()
        }

        events.toList() shouldBe listOf("drain-start", "other-done", "drain-end")
        staging.close()
    }

    @Test
    fun `a duplicate name raced by two stages has exactly one winner and an intact table`() {
        val executionId = UUID.randomUUID()
        val staging = instrumentedStaging(executionId, props, PerConnectionGauge())
        val outcomes =
            SourceDb().use { srcA ->
                SourceDb().use { srcB ->
                    val rsA = srcA.query("SELECT x AS id FROM SYSTEM_RANGE(1, 500)")
                    val rsB = srcB.query("SELECT x AS id FROM SYSTEM_RANGE(1, 700)")
                    runBlocking {
                        awaitAll(
                            async(Dispatchers.IO) { runCatching { staging.stage(rsA, "stg_dup", Dialect.H2).rowsStaged } },
                            async(Dispatchers.IO) { runCatching { staging.stage(rsB, "stg_dup", Dialect.H2).rowsStaged } },
                        )
                    }
                }
            }

        val winners = outcomes.mapNotNull { it.getOrNull() }
        val losers = outcomes.mapNotNull { it.exceptionOrNull() }
        winners.size shouldBe 1
        losers.size shouldBe 1
        (losers.single() is StagingTableAlreadyExistsException) shouldBe true
        // The winner's table is whole — the loser dropped nothing of it — and counted once.
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_dup\"") } shouldBe winners.single()
        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 1
        stats.totalRows shouldBe winners.single()
        // The name stays owned: a third attempt is refused the same way.
        SourceDb().use { src ->
            shouldThrow<StagingTableAlreadyExistsException> {
                runBlocking { staging.stage(src.query("SELECT x AS id FROM SYSTEM_RANGE(1, 3)"), "stg_dup", Dialect.H2) }
            }
        }
        staging.close()
    }

    @Test
    fun `stats under concurrent writers reports every completed stage exactly once`() {
        val executionId = UUID.randomUUID()
        val staging = instrumentedStaging(executionId, props, PerConnectionGauge())
        val names = (1..6).map { "stg_c$it" }
        val sources = names.map { SourceDb() }
        try {
            runBlocking {
                names
                    .mapIndexed { i, name ->
                        val cursor = sources[i].query("SELECT x AS id FROM SYSTEM_RANGE(1, ${100 * (i + 1)})")
                        async(Dispatchers.IO) { staging.stage(cursor, name, Dialect.H2) }
                    }.awaitAll()
            }
        } finally {
            sources.forEach { it.close() }
        }
        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 6
        stats.totalRows shouldBe (1..6).sumOf { 100L * it }
        staging.readFromStaging { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        } shouldContainExactlyInAnyOrder names
        staging.close()
    }

    // ---------- fixtures ----------

    /**
     * A cursor that costs [DELAY_PER_ROW_MS] per row and records when it was read. The delay
     * stands in for the network wait on a real source database.
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
     * Wraps every physical connection the pool opens so each call **into** it — its own methods
     * and every method of the statements it hands out — brackets that connection's own counter,
     * and a global counter of distinct connections currently inside a call.
     *
     * It brackets CALLS, not statement lifetimes (108 §B's lesson): a drain legitimately holds an
     * open statement across several leases; what §9.2 forbids is two callers inside one
     * connection at once, and that is a property of the calls.
     */
    private class PerConnectionGauge {
        private val perConnection = ConcurrentHashMap<Int, AtomicInteger>()
        private val perConnectionPeak = ConcurrentHashMap<Int, Int>()
        private val connectionsInside = AtomicInteger()
        private val peakInside = AtomicInteger()
        private val calls = AtomicInteger()

        fun peakPerConnection(): Map<Int, Int> = perConnectionPeak.toMap()

        fun peakConnectionsInsideJdbc() = peakInside.get()

        fun calls() = calls.get()

        fun instrument(
            target: Connection,
            hook: (Method) -> Unit,
        ): Connection {
            val id = System.identityHashCode(target)
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                InvocationHandler { _, method, args ->
                    val result =
                        bracket(id) {
                            hook(method)
                            invoke(target, method, args)
                        }
                    if (method.name in STATEMENT_FACTORIES) wrapStatement(id, result) else result
                },
            ) as Connection
        }

        private fun wrapStatement(
            id: Int,
            statement: Any?,
        ): Any? {
            val interfaces = statement!!.javaClass.interfaces
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                interfaces,
                InvocationHandler { _, method, args -> bracket(id) { invoke(statement, method, args) } },
            )
        }

        private fun <T> bracket(
            id: Int,
            call: () -> T,
        ): T {
            calls.incrementAndGet()
            val counter = perConnection.computeIfAbsent(id) { AtomicInteger() }
            val now = counter.incrementAndGet()
            perConnectionPeak.merge(id, now, ::maxOf)
            if (now == 1) peakInside.updateAndGet { maxOf(it, connectionsInside.incrementAndGet()) }
            try {
                return call()
            } finally {
                if (counter.decrementAndGet() == 0) connectionsInside.decrementAndGet()
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

        private companion object {
            val STATEMENT_FACTORIES = setOf("createStatement", "prepareStatement", "prepareCall")
        }
    }

    /**
     * A staging instance over a pool whose every physical connection is instrumented by [gauge].
     * The `sa` login is harness scaffolding — the pool wraps whatever it is given; §9.5 identity
     * is `H2StagingPrivilegeTest`'s job on a real factory instance.
     */
    private fun instrumentedStaging(
        executionId: UUID,
        props: H2StagingProperties,
        gauge: PerConnectionGauge,
        hook: (Method) -> Unit = {},
    ): Staging = stagingOverConnections(executionId, props) { raw -> gauge.instrument(raw, hook) }

    private companion object {
        const val SLOW_ROWS = 60L
        const val SLOW_BATCH_SIZE = 10
        const val DELAY_PER_ROW_MS = 5L
        const val TIMEOUT_MS = 10_000L
        const val BARRIER_S = 10L
        const val CREATE_A = "CREATE TABLE \"a\" (\"id\" INTEGER)"
        const val CREATE_B = "CREATE TABLE \"b\" (\"id\" INTEGER)"
    }
}
