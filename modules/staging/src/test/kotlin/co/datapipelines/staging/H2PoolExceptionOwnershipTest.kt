package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The exception boundaries of the ownership invariant (146c; staging.md §9.2, §3.4): a fault
 * thrown by sanitisation, by the cleanup sweep or by a physical close never leaves a session
 * counted as owned by nobody, never skips the remaining owned sessions, and never hides that a
 * driver refused to close. Companion to [H2PoolSecondReviewTest] (the review's counterexamples,
 * assertions untouched) and [H2PoolOwnershipTest].
 *
 * Every raw session is recorded and closed by the harness whatever the assertions decide; the
 * product proof is the pool's own counters PLUS `raw.isClosed` before that harness close.
 */
class H2PoolExceptionOwnershipTest {
    @Test
    fun `a reset Error with another holder present retires the session, rides as suppressed, and keeps the table`() {
        Harness(max = 2).use { h ->
            h.resetFault = { AssertionError("injected reset error") }
            val holderIn = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            runBlocking {
                h.pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"kept\" (\"id\" INTEGER)") } }
                val holder =
                    async(Dispatchers.IO) {
                        h.pool.lease(LeaseKind.INTERNAL) {
                            holderIn.complete(Unit)
                            release.await()
                        }
                    }
                withTimeout(WAIT_MS) { holderIn.await() }
                h.armed.set(true)
                val thrown =
                    shouldThrow<OperationFailed> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { throw OperationFailed() } } }
                h.armed.set(false)
                (thrown.suppressed.single() is AssertionError) shouldBe true
                // Unlinked and retired at once (another holder exists): one session closed for real.
                h.pool.activeLeases shouldBe 1
                h.pool.discardedConnections shouldBe 1
                h.pool.physicalConnections shouldBe 1
                h.rawClosedCount() shouldBe 1
                release.complete(Unit)
                withTimeout(WAIT_MS) { holder.await() }
                h.pool.lease(LeaseKind.INTERNAL) { c -> scalarLong(c, "SELECT COUNT(*) FROM \"kept\"") shouldBe 0L }
            }
            h.pool.close()
            h.pool.physicalConnections shouldBe 0
            h.allRawClosed() shouldBe true
        }
    }

    @Test
    fun `a reset Error on the last holder still guards and replaces, and surfaces as the result`() {
        Harness(max = 1).use { h ->
            h.resetFault = { AssertionError("injected reset error") }
            runBlocking {
                h.pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"kept\" (\"id\" INTEGER)") } }
                h.armed.set(true)
                // No operation failure: the cleanup fault IS the result — but the database survived.
                shouldThrow<AssertionError> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { 1 } } }
                h.armed.set(false)
                h.pool.activeLeases shouldBe 0
                h.pool.discardedConnections shouldBe 1
                h.pool.physicalConnections shouldBe 1
                h.pool.isLost shouldBe false
                h.rawClosedCount() shouldBe 1
                withTimeout(
                    WAIT_MS,
                ) { h.pool.lease(LeaseKind.INTERNAL) { c -> scalarLong(c, "SELECT COUNT(*) FROM \"kept\"") shouldBe 0L } }
            }
            h.pool.close()
            h.allRawClosed() shouldBe true
        }
    }

    @Test
    fun `a runtime sweep failure on the deferred late-close path still closes the last session`() {
        Harness(max = 1).use { h ->
            val holderIn = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sweeps = AtomicInteger()
            runBlocking {
                val holder =
                    async(Dispatchers.IO) {
                        h.pool.lease(LeaseKind.INTERNAL) {
                            holderIn.complete(Unit)
                            release.await()
                        }
                    }
                withTimeout(WAIT_MS) { holderIn.await() }
                val outcome =
                    h.pool.close {
                        sweeps.incrementAndGet()
                        error("injected sweep failure")
                    }
                outcome.sweepRan shouldBe false
                outcome.leasesOutstanding shouldBe 1
                release.complete(Unit)
                withTimeout(WAIT_MS) { holder.await() }
            }
            sweeps.get() shouldBe 1
            h.pool.isClosed shouldBe true
            h.pool.physicalConnections shouldBe 0
            h.allRawClosed() shouldBe true
        }
    }

    @Test
    fun `a sweep Error at close still closes every idle session, reaches CLOSED, and propagates`() {
        Harness(max = 3).use { h ->
            runBlocking {
                // Three idle sessions: hold three leases at once, then return them all.
                val inside = CompletableDeferred<Unit>()
                val entered = AtomicInteger()
                val release = CompletableDeferred<Unit>()
                val leases =
                    (1..3).map {
                        async(Dispatchers.IO) {
                            h.pool.lease(LeaseKind.INTERNAL) {
                                if (entered.incrementAndGet() == 3) inside.complete(Unit)
                                release.await()
                            }
                        }
                    }
                withTimeout(WAIT_MS) { inside.await() }
                release.complete(Unit)
                leases.forEach { withTimeout(WAIT_MS) { it.await() } }
            }
            h.pool.physicalConnections shouldBe 3
            shouldThrow<SweepBlewUp> { h.pool.close { throw SweepBlewUp() } }
            // The Error did not skip the finalization: all three closed, state terminal, idempotent.
            h.pool.isClosed shouldBe true
            h.pool.physicalConnections shouldBe 0
            h.allRawClosed() shouldBe true
            h.pool.close().alreadyClosed shouldBe true
        }
    }

    @Test
    fun `a driver that refuses to close is counted as a refusal, never reported as closed`() {
        Harness(max = 2).use { h ->
            h.closeFault = { SQLException("close refused", "08006") }
            runBlocking { h.pool.lease(LeaseKind.INTERNAL) { } }
            h.pool.close()
            h.pool.isClosed shouldBe true
            h.pool.physicalConnections shouldBe 0
            // The honest residual: the slot was released, the counter says so, and the raw session
            // is STILL OPEN — which the harness closes itself afterwards.
            h.pool.refusedCloses shouldBe 1
            h.allRawClosed() shouldBe false
        }
    }

    @Test
    fun `no reset or cleanup JDBC touches a still-owned running session while close runs, at capacity one and two`() {
        listOf(1, 2).forEach { cap ->
            Harness(max = cap).use { h ->
                val holderIn = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val inLease = AtomicBoolean()
                runBlocking {
                    h.recordWhile = inLease
                    val holder =
                        async(Dispatchers.IO) {
                            h.pool.lease(LeaseKind.AUTHOR) { c ->
                                h.watch(c)
                                inLease.set(true)
                                holderIn.complete(Unit)
                                release.await()
                                inLease.set(false)
                            }
                        }
                    withTimeout(WAIT_MS) { holderIn.await() }
                    h.pool.close { }
                    // Nothing was invoked on the leased session while the lease was live and close
                    // ran — no reset, no rollback, no close; the owner's block itself calls nothing.
                    h.recordedCalls().shouldBeEmpty()
                    release.complete(Unit)
                    withTimeout(WAIT_MS) { holder.await() }
                }
                h.pool.physicalConnections shouldBe 0
                h.allRawClosed() shouldBe true
            }
        }
    }

    @Test
    fun `a fresh lease against a lost pool is refused before waiting for a permit`() {
        Harness(max = 1).use { h ->
            h.onOpen = { throw SQLException("no session", "08001") }
            runBlocking {
                h.armed.set(true)
                withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { } }
                h.armed.set(false)
                h.pool.isLost shouldBe true
                val refusal = shouldThrow<IllegalStateException> { withTimeout(WAIT_MS) { h.pool.lease(LeaseKind.INTERNAL) { } } }
                refusal::class shouldBe IllegalStateException::class
                refusal.message.orEmpty() shouldContain "was lost"
                h.pool.queuedWaiters shouldBe 0
            }
            h.pool.close()
            h.allRawClosed() shouldBe true
        }
    }

    // ---------- fixtures ----------

    private class OperationFailed : RuntimeException("the operation's own failure")

    private class SweepBlewUp : Error("sweep error")

    /**
     * One database, a pool over proxied `sa` sessions. While [armed] the reset's first call
     * (`getAutoCommit`) throws [resetFault]; [closeFault] makes every physical `close()` throw;
     * [onOpen] runs inside the opener. Calls on a watched session while [recordWhile] is set are
     * recorded for [recordedCalls]. Every raw session is closed by [close].
     */
    private class Harness(
        max: Int,
    ) : AutoCloseable {
        private val executionId = UUID.randomUUID()
        private val url = stagingUrl(executionId, H2StagingProperties())
        private val raw = CopyOnWriteArrayList<Connection>()
        private val watched = CopyOnWriteArrayList<Connection>()
        private val recorded = CopyOnWriteArrayList<String>()
        val armed = AtomicBoolean()

        /** While this flag is true, every call on a watched session is recorded. */
        var recordWhile: AtomicBoolean = AtomicBoolean(false)
        var onOpen: () -> Unit = {}
        var resetFault: () -> Throwable = { SQLException("reset refused", "HY000") }
        var closeFault: (() -> Throwable)? = null

        val pool: H2ConnectionPool =
            H2ConnectionPool(executionId, proxied(), {
                onOpen()
                proxied()
            }, max)

        @Suppress("ThrowsCount")
        private fun proxied(): Connection {
            val real = DriverManager.getConnection(url, "sa", "").also { raw += it }
            lateinit var proxy: Connection
            proxy =
                Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "getAutoCommit" && armed.get()) throw resetFault()
                    if (method.name == "close") closeFault?.let { throw it() }
                    if (recordWhile.get() && watched.any { it === proxy }) recorded += method.name
                    try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as Connection
            return proxy
        }

        /** Marks the session a lease holds so calls on it can be recorded. */
        fun watch(connection: Connection) {
            watched += connection
        }

        fun recordedCalls(): List<String> = recorded.toList()

        fun rawClosedCount(): Int = raw.count { it.isClosed }

        fun allRawClosed(): Boolean = raw.all { it.isClosed }

        override fun close() {
            armed.set(false)
            closeFault = null
            pool.close()
            raw.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
