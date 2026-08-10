package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Fixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.sql.Connection
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
 * The second load-bearing property is eviction: a dropped pool must actually be **closed** (an
 * evicted-but-open pool leaks its connections and its source-DB slots for the life of the
 * process), and it must be removed from the map **before** it is closed, so an in-flight caller
 * never receives a pool that is already closing.
 */
class ConnectionPoolManagerTest {
    /**
     * A pool that constructs nothing real — the test counts factory invocations and close calls,
     * not sockets. [closedWhileStillReachable] is the ordering probe: `close()` asks the manager
     * whether the map still points at this pool, which is exactly what §5.2 forbids.
     */
    private class FakePool(
        override val name: String,
        private val manager: () -> ConnectionPoolManager? = { null },
    ) : ConnectionPool {
        var closed: Boolean = false
            private set
        var closedWhileStillReachable: Boolean = false
            private set

        override fun leaseConnection(): Connection = error("not used in this test")

        override fun close() {
            closedWhileStillReachable = manager()?.hasPool(name) ?: false
            closed = true
        }
    }

    @Test
    fun `N concurrent poolFor calls for a cold datasource build exactly one pool`() {
        val builds = AtomicInteger(0)
        val manager =
            ConnectionPoolManager { datasource ->
                builds.incrementAndGet()
                // Widen the race window so a non-atomic implementation would double-build.
                Thread.sleep(WIDEN_WINDOW_MS)
                FakePool(datasource.name)
            }
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
    fun `evict drops the pool so the next poolFor rebuilds a fresh instance`() {
        val builds = AtomicInteger(0)
        val manager =
            ConnectionPoolManager { datasource ->
                builds.incrementAndGet()
                FakePool(datasource.name)
            }
        val datasource = Fixtures.h2(name = "rebuild_ds")

        val first = manager.poolFor(datasource)
        manager.evict("rebuild_ds") shouldBe true
        manager.hasPool("rebuild_ds") shouldBe false
        val second = manager.poolFor(datasource)

        builds.get() shouldBe 2
        System.identityHashCode(first) shouldNotBe System.identityHashCode(second)
        manager.close()
    }

    @Test
    fun `evict closes the evicted pool, and only after removing it from the map`() {
        lateinit var manager: ConnectionPoolManager
        manager = ConnectionPoolManager { datasource -> FakePool(datasource.name) { manager } }
        val pool = manager.poolFor(Fixtures.h2(name = "closed_ds")) as FakePool

        pool.closed shouldBe false
        manager.evict("closed_ds") shouldBe true

        pool.closed shouldBe true
        // §5.2: remove() then close(), never close() on a pool still reachable from the map.
        pool.closedWhileStillReachable shouldBe false
    }

    @Test
    fun `evicting a datasource with no pool is a no-op and reports it`() {
        val manager = ConnectionPoolManager { datasource -> FakePool(datasource.name) }

        manager.evict("never_built") shouldBe false
    }

    @Test
    fun `close closes every pool`() {
        val manager = ConnectionPoolManager { datasource -> FakePool(datasource.name) }
        val first = manager.poolFor(Fixtures.h2(name = "a")) as FakePool
        val second = manager.poolFor(Fixtures.h2(name = "b")) as FakePool

        manager.close()

        first.closed shouldBe true
        second.closed shouldBe true
        manager.hasPool("a") shouldBe false
    }

    @Test
    fun `a real Hikari pool is actually closed by evict, not merely dropped`() {
        // The FakePool cases prove the manager's ordering; this one proves the thing it closes is
        // a live HikariDataSource whose isClosed flips — an evicted-but-open pool would hold its
        // connections (and the source DB's slots) for the life of the process.
        val manager = ConnectionPoolManager()
        val datasource = Fixtures.h2(name = "real_hikari")

        val pool = manager.poolFor(datasource) as HikariConnectionPool
        pool.leaseConnection().use { it.isClosed shouldBe false }

        manager.evict("real_hikari") shouldBe true

        pool.isClosed shouldBe true
    }

    private companion object {
        const val WIDEN_WINDOW_MS = 25L
        const val TIMEOUT_SECONDS = 30L
    }
}
