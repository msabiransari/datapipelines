package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The 146b ownership invariant (staging.md §9.2, #118): every physical session has exactly one
 * owner from reservation to close, every open is reconciled with the pool's state before it is
 * published or any callback runs, retirement counts only real database holders, and continuity
 * failure is explicit. Companion to the review's counterexamples in [H2PoolReviewRaceTest].
 *
 * Every wait is bounded, every event is a latch, and every raw session opened by a test is
 * closed in its `finally` whatever the assertions decide.
 */
class H2PoolOwnershipTest {
    @Test
    fun `close during a checkout's open closes the late session, refuses the lease, and never runs the callback`() {
        Harness(max = 2).use { h ->
            val opening = CountDownLatch(1)
            val finishOpen = CountDownLatch(1)
            h.onOpen = {
                opening.countDown()
                check(finishOpen.await(WAIT_S, TimeUnit.SECONDS))
            }
            val callbackRan = AtomicBoolean()
            val holderIn = CountDownLatch(1)
            val releaseHolder = CountDownLatch(1)
            val swept = AtomicInteger()
            runBlocking {
                // The idle stack is empty (one lease holds the bootstrap session), so the second
                // lease must OPEN — and is parked inside the driver while the pool closes.
                val holder =
                    async(Dispatchers.IO) {
                        h.pool.lease(LeaseKind.INTERNAL) {
                            holderIn.countDown()
                            check(releaseHolder.await(WAIT_S, TimeUnit.SECONDS))
                        }
                    }
                check(holderIn.await(WAIT_S, TimeUnit.SECONDS))
                val late = async(Dispatchers.IO) { runCatching { h.pool.lease(LeaseKind.INTERNAL) { callbackRan.set(true) } } }
                check(opening.await(WAIT_S, TimeUnit.SECONDS))
                h.pool.physicalConnections shouldBe 2

                val outcome = h.pool.close { swept.incrementAndGet() }
                outcome.leasesOutstanding shouldBe 2
                outcome.sweepRan shouldBe false
                h.pool.isClosed shouldBe true

                finishOpen.countDown()
                val refusal = withTimeout(WAIT_MS) { late.await() }.exceptionOrNull()
                (refusal is IllegalStateException) shouldBe true
                refusal?.message.orEmpty() shouldContain "closed"
                callbackRan.get() shouldBe false

                releaseHolder.countDown()
                withTimeout(WAIT_MS) { holder.await() }
            }
            // The deferred sweep ran exactly once, on the last session out, and every session is gone.
            swept.get() shouldBe 1
            h.pool.physicalConnections shouldBe 0
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `close during a guardian's replacement at capacity two closes both the guardian and the late replacement`() {
        Harness(max = 2).use { h ->
            val replacementOpening = CountDownLatch(1)
            val finishOpen = CountDownLatch(1)
            h.onOpen = {
                replacementOpening.countDown()
                check(finishOpen.await(WAIT_S, TimeUnit.SECONDS))
            }
            runBlocking {
                // Only the bootstrap session exists and its reset fails: it is the last holder, so
                // it guards and opens a replacement — which is parked while the pool closes.
                h.armed.set(true)
                val lease = async(Dispatchers.IO) { h.pool.lease(LeaseKind.INTERNAL) { } }
                check(replacementOpening.await(WAIT_S, TimeUnit.SECONDS))
                val outcome = h.pool.close()
                outcome.leasesOutstanding shouldBe 1
                h.armed.set(false)
                finishOpen.countDown()
                withTimeout(WAIT_MS) { lease.await() }
            }
            h.pool.isClosed shouldBe true
            h.pool.physicalConnections shouldBe 0
            h.pool.discardedConnections shouldBe 1
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `two simultaneous failed resets at capacity three keep the staged table and leave exactly one holder`() {
        Harness(max = 3).use { h ->
            val closing = CountDownLatch(2)
            h.beforeClose = {
                if (h.armed.get()) {
                    closing.countDown()
                    check(closing.await(WAIT_S, TimeUnit.SECONDS))
                }
            }
            runBlocking {
                h.pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"kept\" (\"id\" INTEGER)") } }
                h.pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("INSERT INTO \"kept\" VALUES (7)") } }
                val inside = CountDownLatch(2)
                val release = CountDownLatch(1)
                val leases =
                    (1..2).map {
                        async(Dispatchers.IO) {
                            h.pool.lease(LeaseKind.INTERNAL) {
                                inside.countDown()
                                check(release.await(WAIT_S, TimeUnit.SECONDS))
                            }
                        }
                    }
                check(inside.await(WAIT_S, TimeUnit.SECONDS))
                h.armed.set(true)
                release.countDown()
                withTimeout(WAIT_MS) { leases.awaitAll() }
                h.armed.set(false)
                // Both doomed sessions were discarded; one of them guarded and was replaced.
                h.pool.discardedConnections shouldBe 2
                h.pool.physicalConnections shouldBe 1
                h.pool.isLost shouldBe false
                h.pool.lease(LeaseKind.INTERNAL) { c -> scalarLong(c, "SELECT SUM(\"id\") FROM \"kept\"") shouldBe 7L }
            }
            h.pool.close()
            h.pool.physicalConnections shouldBe 0
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `a replacement that fails to open marks the pool lost instead of continuing against an empty database`() {
        Harness(max = 1).use { h ->
            val openerCalls = AtomicInteger()
            h.onOpen = {
                openerCalls.incrementAndGet()
                throw SQLException("no session", "08001")
            }
            runBlocking {
                h.pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"gone\" (\"id\" INTEGER)") } }
                h.armed.set(true)
                // The operation itself succeeds — its failure is the reset AFTER it.
                withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { 42 } } shouldBe 42
                h.armed.set(false)
                h.pool.isLost shouldBe true
                h.pool.physicalConnections shouldBe 0
                // Refused with the loss named — and the opener is NOT tried again, so nothing can
                // quietly connect to a fresh, empty database under the same name.
                val refusal = shouldThrow<IllegalStateException> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { } } }
                refusal.message.orEmpty() shouldContain "was lost"
                openerCalls.get() shouldBe 1
            }
            h.pool.close()
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `a failed reset with another holder present is discarded without a replacement, keeping the database`() {
        Harness(max = 2).use { h ->
            val openerCalls = AtomicInteger()
            h.onOpen = { openerCalls.incrementAndGet() }
            runBlocking {
                h.pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"stay\" (\"id\" INTEGER)") } }
                val holderIn = CountDownLatch(1)
                val release = CountDownLatch(1)
                val holder =
                    async(Dispatchers.IO) {
                        h.pool.lease(LeaseKind.INTERNAL) {
                            holderIn.countDown()
                            check(release.await(WAIT_S, TimeUnit.SECONDS))
                        }
                    }
                check(holderIn.await(WAIT_S, TimeUnit.SECONDS))
                // The second lease opens a session (opener call 1); its reset fails on return while
                // the holder still owns the first — so it closes at once, no replacement.
                h.armed.set(true)
                withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { } }
                h.armed.set(false)
                release.countDown()
                withTimeout(WAIT_MS) { holder.await() }
                openerCalls.get() shouldBe 1
                h.pool.discardedConnections shouldBe 1
                h.pool.physicalConnections shouldBe 1
                h.pool.lease(LeaseKind.INTERNAL) { c -> scalarLong(c, "SELECT COUNT(*) FROM \"stay\"") shouldBe 0L }
            }
            h.pool.close()
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `a waiter admitted after close with every permit held is refused, and its permit is returned`() {
        Harness(max = 1).use { h ->
            val holderIn = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackRan = AtomicBoolean()
            runBlocking {
                val holder =
                    async(Dispatchers.IO) {
                        h.pool.lease(LeaseKind.INTERNAL) {
                            holderIn.countDown()
                            check(release.await(WAIT_S, TimeUnit.SECONDS))
                        }
                    }
                check(holderIn.await(WAIT_S, TimeUnit.SECONDS))
                val waiter = async(Dispatchers.IO) { runCatching { h.pool.lease(LeaseKind.INTERNAL) { callbackRan.set(true) } } }
                withTimeout(WAIT_MS) { while (h.pool.queuedWaiters != 1) kotlinx.coroutines.delay(POLL_MS) }
                h.pool.close()
                release.countDown()
                withTimeout(WAIT_MS) { holder.await() }
                val refusal = withTimeout(WAIT_MS) { waiter.await() }.exceptionOrNull()
                (refusal is IllegalStateException) shouldBe true
                callbackRan.get() shouldBe false
                // The permit came back: a third caller is refused at once, not parked.
                shouldThrow<IllegalStateException> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { } } }
            }
            h.pool.physicalConnections shouldBe 0
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `a callback failure survives a reset that also fails, and the permit and session are still released`() {
        Harness(max = 1).use { h ->
            h.resetFault = { IllegalStateException("driver fault during reset") }
            runBlocking {
                h.armed.set(true)
                val thrown =
                    shouldThrow<CallbackFailed> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { throw CallbackFailed() } } }
                thrown.suppressed.toList() shouldBe emptyList()
                h.armed.set(false)
                // The doomed session was discarded and replaced; the next lease is not parked.
                h.pool.discardedConnections shouldBe 1
                withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { 1 } } shouldBe 1
            }
            h.pool.close()
            h.allClosed() shouldBe true
        }
    }

    @Test
    fun `a cleanup Error after a callback failure rides as a suppressed exception, never as the result`() {
        Harness(max = 2).use { h ->
            h.resetFault = { CleanupBlewUp() }
            runBlocking {
                h.armed.set(true)
                val thrown =
                    shouldThrow<CallbackFailed> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { throw CallbackFailed() } } }
                (thrown.suppressed.single() is CleanupBlewUp) shouldBe true
                h.armed.set(false)
                // The permit was returned even though the return blew up: two leases still fit.
                withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { h.pool.lease(LeaseKind.INTERNAL) { } } }
            }
            h.pool.close()
        }
    }

    // ---------- fixtures ----------

    private class CallbackFailed : RuntimeException("the node's own failure")

    private class CleanupBlewUp : Error("cleanup error")

    /**
     * One database, a pool over proxied `sa` sessions, and the hooks the tests above pull:
     * [onOpen] runs inside the opener, [beforeClose] inside each session's `close()`, and while
     * [armed] the reset's first call (`getAutoCommit`) throws [resetFault]. Every raw session is
     * recorded and closed by [close].
     */
    private class Harness(
        max: Int,
    ) : AutoCloseable {
        private val executionId = UUID.randomUUID()
        private val url = stagingUrl(executionId, H2StagingProperties())
        private val raw = CopyOnWriteArrayList<Connection>()
        val armed = AtomicBoolean()
        var onOpen: () -> Unit = {}
        var beforeClose: () -> Unit = {}
        var resetFault: () -> Throwable = { SQLException("reset refused", "HY000") }

        val pool: H2ConnectionPool =
            H2ConnectionPool(executionId, proxied(), {
                onOpen()
                proxied()
            }, max)

        private fun proxied(): Connection {
            val real = DriverManager.getConnection(url, "sa", "").also { raw += it }
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "getAutoCommit" && armed.get()) throw resetFault()
                if (method.name == "close") beforeClose()
                try {
                    method.invoke(real, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
        }

        fun allClosed(): Boolean = raw.all { it.isClosed }

        override fun close() {
            armed.set(false)
            beforeClose = {}
            pool.close()
            raw.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val WAIT_S = 10L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
