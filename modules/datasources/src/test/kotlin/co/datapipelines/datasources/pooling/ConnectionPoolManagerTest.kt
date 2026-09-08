package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Fixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ConnectionPoolManager] concurrency and lifecycle (datasources.md §5.2, §13.2).
 *
 * The load-bearing property: N threads calling `poolFor` at once for a cold datasource must
 * construct **exactly one** pool. `computeIfAbsent` is the mechanism; this test is the proof.
 *
 * The second is the RETIREMENT state machine (094). A dropped pool must eventually be **closed**
 * (a dropped-but-open pool leaks its connections and its source-DB slots for the life of the
 * process) and it must be removed from the map **before** anything is done to it, so an
 * in-flight caller never receives a pool that is already closing — but it must NOT be closed
 * while a statement is still running on it, which is the defect 094 exists to fix. The three
 * states are therefore asserted separately: retired-and-draining (still open, out of the map),
 * retired-and-drained (closed by the reaper), and retired-past-its-ceiling (closed anyway, with
 * the hard-close metric).
 */
class ConnectionPoolManagerTest {
    /**
     * A pool that constructs nothing real — the test counts factory invocations, close calls and
     * soft-evictions, not sockets. [closedWhileStillReachable] is the ordering probe: `close()`
     * asks the manager whether the map still points at this pool, which is exactly what §5.2
     * forbids. [active] is the reaper's input, set by the test to stand in for a running query.
     */
    private class FakePool(
        override val name: String,
        var active: Int = 0,
        private val manager: () -> ConnectionPoolManager? = { null },
    ) : ConnectionPool {
        var closed: Boolean = false
            private set
        var closedWhileStillReachable: Boolean = false
            private set
        var softEvicted: Boolean = false
            private set

        override fun leaseConnection(): Connection = error("not used in this test")

        override fun softEvict() {
            softEvicted = true
        }

        override val activeConnections: Int get() = active

        override fun close() {
            closedWhileStillReachable = manager()?.hasPool(name) ?: false
            closed = true
        }
    }

    /** A recording [PoolLifecycleMetrics] — never a strict mock: a missing call must be VISIBLE. */
    private class RecordingMetrics : PoolLifecycleMetrics {
        val retired = mutableListOf<String>()
        val hardClosed = mutableListOf<Pair<String, Int>>()

        override fun poolRetired(datasourceName: String) {
            retired += datasourceName
        }

        override fun poolHardClosed(
            datasourceName: String,
            activeConnections: Int,
        ) {
            hardClosed += datasourceName to activeConnections
        }
    }

    @Test
    fun `N concurrent poolFor calls for a cold datasource build exactly one pool`() {
        val builds = AtomicInteger(0)
        val manager =
            ConnectionPoolManager({ datasource ->
                builds.incrementAndGet()
                // Widen the race window so a non-atomic implementation would double-build.
                Thread.sleep(WIDEN_WINDOW_MS)
                FakePool(datasource.name)
            })
        val datasource = Fixtures.h2(name = "race_ds")
        val threads = 32
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val results =
                (1..threads).map {
                    pool.submit<ConnectionPool> {
                        startGate.await()
                        manager.poolFor(datasource)
                    }
                }
            startGate.countDown()
            val pools = results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            builds.get() shouldBe 1
            // Every caller received the very same instance.
            pools.distinctBy { System.identityHashCode(it) }.size shouldBe 1
            manager.hasPool("race_ds") shouldBe true
        } finally {
            pool.shutdownNow()
            manager.close()
        }
    }

    @Test
    fun `retire drops the pool so the next poolFor rebuilds a fresh instance`() {
        val builds = AtomicInteger(0)
        val manager =
            ConnectionPoolManager({ datasource ->
                builds.incrementAndGet()
                FakePool(datasource.name)
            })
        val datasource = Fixtures.h2(name = "rebuild_ds")

        val first = manager.poolFor(datasource)
        manager.retire("rebuild_ds") shouldBe true
        manager.hasPool("rebuild_ds") shouldBe false
        val second = manager.poolFor(datasource)

        builds.get() shouldBe 2
        System.identityHashCode(first) shouldNotBe System.identityHashCode(second)
        manager.close()
    }

    @Test
    fun `retire soft-evicts and leaves the pool OPEN while a statement is still running on it`() {
        // The whole point of 094: the old `evict` closed here, and HikariCP then aborted the
        // in-use connection after its shutdown grace — killing a mid-query execution.
        lateinit var manager: ConnectionPoolManager
        val metrics = RecordingMetrics()
        manager =
            ConnectionPoolManager(
                { datasource -> FakePool(datasource.name, active = 1) { manager } },
                metrics = metrics,
            )
        val pool = manager.poolFor(Fixtures.h2(name = "busy_ds")) as FakePool

        manager.retire("busy_ds") shouldBe true

        pool.softEvicted shouldBe true
        pool.closed shouldBe false
        manager.hasPool("busy_ds") shouldBe false
        manager.retiringCount() shouldBe 1
        metrics.retired shouldBe listOf("busy_ds")

        // One reap tick with the statement still running changes nothing.
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        pool.closed shouldBe false

        // The statement finishes; the next tick closes the pool, and only after it left the map.
        pool.active = 0
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)
        pool.closed shouldBe true
        pool.closedWhileStillReachable shouldBe false
        manager.retiringCount() shouldBe 0
        metrics.hardClosed shouldBe emptyList()
    }

    @Test
    fun `a drained pool is closed on the very first reap tick`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name, active = 0) })
        val pool = manager.poolFor(Fixtures.h2(name = "idle_ds")) as FakePool

        manager.retire("idle_ds") shouldBe true
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)

        pool.closed shouldBe true
    }

    @Test
    fun `the ceiling closes a pool whose statement outlived it, and counts it as a hard close`() {
        // A hung statement must not pin a deleted datasource's pool forever (§5.2).
        var now = Instant.parse("2026-09-07T10:00:00Z")
        val metrics = RecordingMetrics()
        val manager =
            ConnectionPoolManager(
                { datasource -> FakePool(datasource.name, active = 3) },
                retireCeiling = Duration.ofSeconds(CEILING_SECONDS),
                metrics = metrics,
                clock = { now },
            )
        val pool = manager.poolFor(Fixtures.h2(name = "hung_ds")) as FakePool
        manager.retire("hung_ds") shouldBe true

        // One second before the ceiling: still waiting, still open.
        now = now.plusSeconds(CEILING_SECONDS - 1)
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        pool.closed shouldBe false

        // At the ceiling: closed regardless, with the active count carried into the metric.
        now = now.plusSeconds(1)
        manager.reapRetiring() shouldBe ReapOutcome(drained = 0, hardClosed = 1)
        pool.closed shouldBe true
        metrics.hardClosed shouldBe listOf("hung_ds" to 3)
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `two pools for the same datasource can retire at once and both are closed`() {
        // save → lease → save again: the first pool is still draining when the second retires.
        // A `retiring` map keyed by NAME would drop the first one un-closed; the queue does not.
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name, active = 1) })
        val datasource = Fixtures.h2(name = "twice_ds")

        val first = manager.poolFor(datasource) as FakePool
        manager.retire("twice_ds") shouldBe true
        val second = manager.poolFor(datasource) as FakePool
        manager.retire("twice_ds") shouldBe true

        manager.retiringCount() shouldBe 2
        first.active = 0
        second.active = 0
        manager.reapRetiring() shouldBe ReapOutcome(drained = 2, hardClosed = 0)
        first.closed shouldBe true
        second.closed shouldBe true
    }

    @Test
    fun `retiring a datasource with no pool is a no-op and reports it`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name) })

        manager.retire("never_built") shouldBe false
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `reaping with nothing retired is free and reports nothing`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name) })

        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
    }

    @Test
    fun `close closes every pool, live and still draining`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name, active = 1) })
        val first = manager.poolFor(Fixtures.h2(name = "a")) as FakePool
        val second = manager.poolFor(Fixtures.h2(name = "b")) as FakePool
        // `a` is mid-retirement and would NOT be reaped (its statement is still running);
        // shutdown closes it anyway — the drain lifecycle has already cancelled the statements.
        manager.retire("a") shouldBe true

        manager.close()

        first.closed shouldBe true
        second.closed shouldBe true
        manager.hasPool("b") shouldBe false
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `livePoolNames reports exactly the pools the reconcile may compare`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name) })
        manager.poolFor(Fixtures.h2(name = "live_a"))
        manager.poolFor(Fixtures.h2(name = "live_b"))
        manager.retire("live_a")

        manager.livePoolNames() shouldBe setOf("live_b")
        manager.close()
    }

    @Test
    fun `a real Hikari pool is soft-evicted on retire and actually closed by the reaper`() {
        // The FakePool cases prove the manager's ordering; this one proves the thing it drives is
        // a live HikariDataSource — soft-evict leaves it open for the caller that already holds a
        // connection, and the reaper's close() flips isClosed.
        val manager = ConnectionPoolManager()
        val datasource = Fixtures.h2(name = "real_hikari")

        val pool = manager.poolFor(datasource) as HikariConnectionPool
        val leased = pool.leaseConnection()

        manager.retire("real_hikari") shouldBe true
        // A connection still out: the pool reports it, so the reaper leaves the pool alone and
        // the running statement keeps its connection.
        pool.activeConnections shouldBe 1
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        pool.isClosed shouldBe false
        leased.isClosed shouldBe false

        // The caller returns it; Hikari closes the evicted entry, and the next tick reaps.
        leased.close()
        pool.activeConnections shouldBe 0
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)
        pool.isClosed shouldBe true
    }

    private companion object {
        const val WIDEN_WINDOW_MS = 25L
        const val TIMEOUT_SECONDS = 30L
        const val CEILING_SECONDS = 90L
    }
}
