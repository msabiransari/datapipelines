package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #818d (record §10 RULING 4 amended, item 6) — the reaper's pass decides and hands each close to the closer; a
 * close that never returns costs a `dp-pool-close` thread, never the next pass, and is reported once at its deadline.
 *
 * Every test drives the REAL [ConnectionPoolManager] pass with the production [DeadlinePoolCloser] and fake pools
 * whose close blocks on a latch the test owns. The plant that turns the pass/closer pair red is the synchronous close
 * restored in `ConnectionPoolManager.reapRetiring` (`entry.pool.close()` in place of the closer): the pass then
 * blocks on the stuck pool, and the next pass, and the second pool's close, never come.
 */
class DeadlinePoolCloserTest {
    private val released = CountDownLatch(1)
    private val passes = Executors.newSingleThreadExecutor { Thread(it, "test-reaper").apply { isDaemon = true } }
    private val metrics = CountingMetrics()

    @AfterEach
    fun tearDown() {
        released.countDown()
        passes.shutdownNow()
    }

    @Test
    fun `a close that never returns does not block the next pass`() {
        val closer = DeadlinePoolCloser(metrics)
        val stuck = FakePool("stuck", blockOn = released)
        val next = FakePool("next")
        val manager = manager(closer, stuck, next)

        manager.retire("stuck")
        pass(manager)
        stuck.awaitCloseStarted()
        manager.retire("next")
        pass(manager)

        next.awaitClosed()
        stuck.closeCalls.get() shouldBe 1
        stuck.closeFinished shouldBe false
        closer.destroy()
    }

    @Test
    fun `two pools retiring together - the first stuck - the second still closes on time`() {
        val closer = DeadlinePoolCloser(metrics)
        val stuck = FakePool("a-stuck", blockOn = released)
        val second = FakePool("b-second")
        val manager = manager(closer, stuck, second)
        manager.retire("a-stuck")
        manager.retire("b-second")

        pass(manager)

        second.awaitClosed()
        stuck.closeFinished shouldBe false
        second.closedOn.get() shouldStartWith DeadlinePoolCloser.THREAD_PREFIX
        stuck.closedOn.get() shouldStartWith DeadlinePoolCloser.THREAD_PREFIX
        closer.destroy()
    }

    @Test
    fun `a close past its deadline is reported once with its fields and counted, never retried`() {
        // #840: the deadline is the pool's own; with the fixed terms scaled to zero it is the pool's connectionTimeout.
        val closer = DeadlinePoolCloser(metrics, fixedTerms = Duration.ZERO)
        val stuck =
            FakePool(
                "stuck",
                blockOn = released,
                active = 2,
                generation = "gen12345",
                connectionTimeout = Duration.ofMillis(SHORT_DEADLINE_MILLIS),
            )
        val manager = manager(closer, stuck, ceiling = Duration.ZERO)
        manager.retire("stuck")

        val events =
            capturingLogEvents(closerLogger) {
                pass(manager)
                awaitUntil { metrics.closeStuck.get() == 1 }
                repeat(EXTRA_PASSES) { pass(manager) }
                Thread.sleep(SHORT_DEADLINE_MILLIS * 2)
            }

        val stuckLines = events.filter { it.formattedMessage.startsWith("event=datasource.pool_close_stuck") }
        stuckLines shouldHaveSize 1
        stuckLines.single().level shouldBe Level.WARN
        stuckLines.single().formattedMessage.substringBefore(" message=") shouldMatch
            Regex(
                "event=datasource\\.pool_close_stuck datasource=stuck generation=gen12345 elapsed_seconds=\\d+ active_connections=2 " +
                    "deadline_seconds=0",
            )
        metrics.closeStuck.get() shouldBe 1
        metrics.closeStuckNames shouldBe listOf("stuck")
        stuck.closeCalls.get() shouldBe 1
        closer.destroy()
    }

    @Test
    fun `a close inside its deadline reports nothing`() {
        val closer = DeadlinePoolCloser(metrics)
        val quick = FakePool("quick")
        val manager = manager(closer, quick)
        manager.retire("quick")

        val events =
            capturingLogEvents(closerLogger) {
                pass(manager)
                quick.awaitClosed()
            }

        events.shouldBeEmpty()
        metrics.closeStuck.get() shouldBe 0
        closer.destroy()
    }

    @Test
    fun `a close that throws is contained to its thread and reported by class only`() {
        val closer = DeadlinePoolCloser(metrics)
        val throwing = FakePool("throwing", throwOnClose = true)
        val next = FakePool("next")
        val manager = manager(closer, throwing, next)
        manager.retire("throwing")
        manager.retire("next")

        val events =
            capturingLogEvents(closerLogger) {
                pass(manager)
                next.awaitClosed()
                awaitUntil { closer.inFlight() == 0 }
                Thread.sleep(SETTLE_MILLIS)
            }

        events.map { it.formattedMessage.substringBefore(" message=") } shouldBe
            listOf("event=datasource.pool_close_failed datasource=throwing generation=none error=IllegalStateException")
        events.single().formattedMessage.contains("secret-in-message") shouldBe false
        closer.destroy()
    }

    @Test
    fun `after destruction a hand-off closes on the caller's thread rather than leaving the pool open`() {
        val closer = DeadlinePoolCloser(metrics)
        closer.destroy()
        val late = FakePool("late")

        closer.close(late)

        late.closeFinished shouldBe true
        late.closedOn.get() shouldBe Thread.currentThread().name
    }

    @Test
    fun `close threads are daemon and named`() {
        val closer = DeadlinePoolCloser(metrics)
        val pool = FakePool("named")
        closer.close(pool)
        pool.awaitClosed()

        pool.closedOn.get() shouldStartWith DeadlinePoolCloser.THREAD_PREFIX
        pool.closedOnDaemon.get() shouldBe true
        closer.destroy()
    }

    /** One reaper pass on the test's own pass thread, which must come back well inside the bound. */
    private fun pass(manager: ConnectionPoolManager) {
        passes.submit { manager.reapRetiring() }.get(PASS_RETURN_SECONDS, TimeUnit.SECONDS)
    }

    /** [ceiling] zero hands a pool with connections out to the closer on the first pass (the hard close). */
    private fun manager(
        closer: DeadlinePoolCloser,
        vararg pools: FakePool,
        ceiling: Duration = ConnectionPoolManager.DEFAULT_RETIRE_CEILING,
    ): ConnectionPoolManager {
        val byName = pools.associateBy { it.name }
        val manager =
            ConnectionPoolManager(
                poolFactory = { datasource: Datasource -> byName.getValue(datasource.name) },
                retireCeiling = ceiling,
                closer = closer,
                metrics = metrics,
            )
        pools.forEach { manager.poolFor(datasource(it.name)) }
        return manager
    }

    private fun datasource(name: String): Datasource =
        Datasource(name = name, displayName = name, dialect = Dialect.H2, jdbcUrl = "jdbc:h2:mem:$name")

    private class FakePool(
        override val name: String,
        private val blockOn: CountDownLatch? = null,
        private val throwOnClose: Boolean = false,
        private val active: Int = 0,
        override val generation: String? = null,
        override val connectionTimeout: Duration = ConnectionPool.DEFAULT_CONNECTION_TIMEOUT,
    ) : ConnectionPool {
        val closeCalls = AtomicInteger()
        val closedOn = AtomicReference<String>()
        val closedOnDaemon = AtomicReference<Boolean>()
        private val started = CountDownLatch(1)
        private val finished = CountDownLatch(1)

        @Volatile var closeFinished = false
            private set

        override val activeConnections: Int get() = active

        override fun leaseConnection(): Connection = error("not used")

        override fun close() {
            closeCalls.incrementAndGet()
            closedOn.set(Thread.currentThread().name)
            closedOnDaemon.set(Thread.currentThread().isDaemon)
            started.countDown()
            check(!throwOnClose) { "secret-in-message" }
            blockOn?.await()
            closeFinished = true
            finished.countDown()
        }

        fun awaitCloseStarted() {
            started.await(AWAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        }

        fun awaitClosed() {
            finished.await(AWAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
    }

    private class CountingMetrics : PoolLifecycleMetrics {
        val closeStuck = AtomicInteger()
        val closeStuckNames: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override fun poolRetired(datasourceName: String) = Unit

        override fun poolHardClosed(
            datasourceName: String,
            activeConnections: Int,
        ) = Unit

        override fun poolCloseStuck(datasourceName: String) {
            closeStuckNames += datasourceName
            closeStuck.incrementAndGet()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached within $AWAIT_SECONDS s" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        val closerLogger = LoggerFactory.getLogger(DeadlinePoolCloser::class.java) as Logger
        const val SHORT_DEADLINE_MILLIS = 200L
        const val PASS_RETURN_SECONDS = 2L
        const val AWAIT_SECONDS = 10L
        const val EXTRA_PASSES = 3
        const val SETTLE_MILLIS = 100L
        const val POLL_MILLIS = 10L
    }
}
