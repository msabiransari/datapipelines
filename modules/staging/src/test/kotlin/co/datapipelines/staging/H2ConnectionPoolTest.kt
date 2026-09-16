package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The per-execution connection pool (staging.md §9, #118): bounded admission, one owner per
 * physical connection, session sanitation on return, and a close that never waits on a
 * connection still inside a JDBC call.
 *
 * Every test opens real H2 connections to one named in-memory database. The `sa` login is a
 * harness convenience — the pool is indifferent to the identity; `H2StagingPrivilegeTest`
 * proves the factory's restricted one on every physical connection.
 */
class H2ConnectionPoolTest {
    private val executionId = UUID.randomUUID()
    private val url = stagingUrl(executionId, H2StagingProperties())

    private fun open(): Connection = DriverManager.getConnection(url, "sa", "")

    private fun pool(
        max: Int,
        opener: () -> Connection = ::open,
        initial: Connection = open(),
    ): H2ConnectionPool = H2ConnectionPool(executionId, initial, opener, max)

    // ---------- overlap and cap ----------

    @Test
    fun `concurrent leases run on distinct physical connections, up to the cap`() {
        val pool = pool(max = 2)
        val holders = CopyOnWriteArrayList<Connection>()
        val bothIn = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        runBlocking {
            val a =
                async(Dispatchers.IO) {
                    pool.lease(LeaseKind.INTERNAL) { c ->
                        holders += c
                        arrive(entered, bothIn)
                        release.await()
                    }
                }
            val b =
                async(Dispatchers.IO) {
                    pool.lease(LeaseKind.INTERNAL) { c ->
                        holders += c
                        arrive(entered, bothIn)
                        release.await()
                    }
                }
            withTimeout(TIMEOUT_MS) { bothIn.await() }
            // Both are INSIDE their leases at this instant, on two different connections.
            pool.activeLeases shouldBe 2
            holders shouldHaveSize 2
            (holders[0] === holders[1]) shouldBe false
            release.complete(Unit)
            awaitAll(a, b)
        }

        pool.peakActiveLeases shouldBe 2
        pool.physicalConnections shouldBe 2
        pool.close()
    }

    @Test
    fun `a lease past the cap waits until one is returned, and never sees a third connection`() {
        val pool = pool(max = 2)
        val twoIn = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val releaseFirstTwo = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()

        runBlocking {
            val first =
                (1..2).map {
                    async(Dispatchers.IO) {
                        pool.lease(LeaseKind.INTERNAL) {
                            arrive(entered, twoIn)
                            releaseFirstTwo.await()
                        }
                    }
                }
            withTimeout(TIMEOUT_MS) { twoIn.await() }
            val third = async(Dispatchers.IO) { pool.lease(LeaseKind.INTERNAL) { thirdEntered.complete(Unit) } }
            // The third caller is queued: it must not be inside a lease while two are held.
            pool.activeLeases shouldBe 2
            thirdEntered.isCompleted shouldBe false
            releaseFirstTwo.complete(Unit)
            first.awaitAll()
            withTimeout(TIMEOUT_MS) { third.await() }
        }

        pool.peakActiveLeases shouldBe 2
        pool.physicalConnections shouldBe 2
        pool.close()
    }

    @Test
    fun `capacity one makes progress under more ready tasks than dispatcher threads`() {
        val pool = pool(max = 1)
        val tiny = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val ran = AtomicInteger()
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    (1..12)
                        .map {
                            launch(tiny) {
                                pool.lease(LeaseKind.INTERNAL) { c ->
                                    c.createStatement().use { it.execute("SELECT 1") }
                                    ran.incrementAndGet()
                                }
                            }
                        }.forEach { it.join() }
                }
            }
        } finally {
            tiny.close()
        }
        ran.get() shouldBe 12
        pool.peakActiveLeases shouldBe 1
        pool.physicalConnections shouldBe 1
        pool.close()
    }

    @Test
    fun `a waiter cancelled in the queue leaves capacity for the next caller`() {
        val pool = pool(max = 1)
        val holderIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        runBlocking {
            val holder =
                async(Dispatchers.IO) {
                    pool.lease(LeaseKind.INTERNAL) {
                        holderIn.complete(Unit)
                        release.await()
                    }
                }
            withTimeout(TIMEOUT_MS) { holderIn.await() }
            val waiter = launch(Dispatchers.IO) { pool.lease(LeaseKind.INTERNAL) { error("the cancelled waiter must never run SQL") } }
            // Let the waiter reach the queue, then cancel it there.
            waitUntil { pool.queuedWaiters == 1 }
            waiter.cancel()
            waiter.join()
            pool.queuedWaiters shouldBe 0
            release.complete(Unit)
            holder.await()
            // The cancelled waiter must not have consumed the one permit.
            withTimeout(TIMEOUT_MS) { pool.lease(LeaseKind.INTERNAL) { } }
        }
        pool.close()
    }

    // ---------- session sanitation ----------

    @Test
    fun `a dirtied session is reused with its defaults restored and nothing leaked`() {
        // Capacity ONE: the next lease is forced onto the very same physical connection, which is
        // the only way a reset can be observed at all — two fresh connections prove nothing.
        val pool = pool(max = 1)
        var dirty: Connection? = null
        runBlocking {
            pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"kept\" (\"id\" INTEGER)") } }
            pool.lease(LeaseKind.AUTHOR) { c ->
                dirty = c
                c.createStatement().use { st ->
                    st.execute("CREATE SCHEMA \"side\"")
                    st.execute("SET SCHEMA \"side\"")
                    st.execute("SET SCHEMA_SEARCH_PATH \"side\", \"public\"")
                    st.execute("SET @leak 42")
                    st.execute("CREATE LOCAL TEMPORARY TABLE \"scratch\" (\"n\" INTEGER)")
                    st.execute("SET TIME ZONE '+05:00'")
                    st.execute("SET QUERY_TIMEOUT 12345")
                    st.execute("SET LOCK_TIMEOUT 777")
                }
                c.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
                c.autoCommit = false
                c.createStatement().use { it.execute("INSERT INTO \"public\".\"kept\" VALUES (1)") }
            }
            pool.lease(LeaseKind.AUTHOR) { c ->
                (c === dirty) shouldBe true
                c.autoCommit shouldBe true
                c.transactionIsolation shouldBe Connection.TRANSACTION_READ_COMMITTED
                c.schema.uppercase() shouldBe "PUBLIC"
                c.createStatement().use { st ->
                    // The uncommitted insert was ROLLED BACK, not committed by the autocommit flip.
                    scalarLong(c, "SELECT COUNT(*) FROM \"kept\"") shouldBe 0L
                    scalarLong(c, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSION_STATE WHERE STATE_KEY = '@leak'") shouldBe 0L
                    scalarLong(c, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSION_STATE WHERE STATE_KEY = 'TABLE scratch'") shouldBe 0L
                    scalarLong(c, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSION_STATE WHERE STATE_KEY = 'TIME ZONE'") shouldBe 0L
                    scalarLong(
                        c,
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSION_STATE " +
                            "WHERE STATE_KEY = 'SCHEMA_SEARCH_PATH' AND STATE_COMMAND LIKE '%side%'",
                    ) shouldBe 0L
                    st.executeQuery("SELECT @leak").use { rs ->
                        rs.next()
                        rs.getObject(1) shouldBe null
                    }
                    // An unqualified reference resolves in PUBLIC again.
                    scalarLong(c, "SELECT COUNT(*) FROM kept") shouldBe 0L
                }
            }
        }
        pool.close()
    }

    @Test
    fun `an explicit rollback inside a lease is honoured and the session still returns clean`() {
        val pool = pool(max = 1)
        runBlocking {
            pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"rb\" (\"id\" INTEGER)") } }
            pool.lease(LeaseKind.AUTHOR) { c ->
                c.autoCommit = false
                c.createStatement().use { it.execute("INSERT INTO \"rb\" VALUES (1)") }
                c.rollback()
                c.createStatement().use { it.execute("INSERT INTO \"rb\" VALUES (2)") }
                c.commit()
            }
            pool.lease(LeaseKind.INTERNAL) { c ->
                c.autoCommit shouldBe true
                scalarLong(c, "SELECT COUNT(*) FROM \"rb\"") shouldBe 1L
            }
        }
        pool.close()
    }

    @Test
    fun `a connection whose reset fails is discarded, replaced, and the database survives`() {
        val opened = AtomicInteger()
        val failing = FailingRollback()
        val pool =
            pool(max = 1, opener = {
                opened.incrementAndGet()
                open()
            }, initial = failing.wrap(open()))
        runBlocking {
            pool.lease(LeaseKind.INTERNAL) { c -> c.createStatement().use { it.execute("CREATE TABLE \"survivor\" (\"id\" INTEGER)") } }
            failing.armed = true
            pool.lease(LeaseKind.AUTHOR) { c -> c.autoCommit = false }
            // The doomed connection was closed AFTER its replacement opened, so the in-memory
            // database — and the table on it — is still there.
            pool.lease(LeaseKind.INTERNAL) { c ->
                (failing.isWrapped(c)) shouldBe false
                scalarLong(c, "SELECT COUNT(*) FROM \"survivor\"") shouldBe 0L
            }
        }
        opened.get() shouldBe 1
        pool.discardedConnections shouldBe 1
        pool.physicalConnections shouldBe 1
        failing.closed shouldBe true
        pool.close()
    }

    // ---------- lifecycle ----------

    @Test
    fun `close on an idle pool closes every physical connection and is idempotent`() {
        val pool = pool(max = 3)
        runBlocking {
            val bothIn = CompletableDeferred<Unit>()
            val entered = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val leases =
                (1..2).map {
                    async(Dispatchers.IO) {
                        pool.lease(LeaseKind.INTERNAL) {
                            arrive(entered, bothIn)
                            release.await()
                        }
                    }
                }
            withTimeout(TIMEOUT_MS) { bothIn.await() }
            release.complete(Unit)
            leases.awaitAll()
        }
        pool.physicalConnections shouldBe 2

        val swept = AtomicInteger()
        pool.close { swept.incrementAndGet() }
        pool.close { swept.incrementAndGet() }

        swept.get() shouldBe 1
        pool.physicalConnections shouldBe 0
        pool.isClosed shouldBe true
        // The last connection is gone, so the database went with it (§3.4).
        DriverManager.getConnection(url, "sa", "").use { fresh -> tableCount(fresh) shouldBe 0 }
    }

    @Test
    fun `after close a new lease is refused rather than opening a connection`() {
        val opened = AtomicInteger()
        val pool =
            pool(max = 2, opener = {
                opened.incrementAndGet()
                open()
            })
        pool.close()
        shouldThrow<IllegalStateException> { runBlocking { pool.lease(LeaseKind.INTERNAL) { } } }
        opened.get() shouldBe 0
    }

    @Test
    fun `close while a lease is active neither waits for it nor touches its connection, and its late return finishes cleanup once`() {
        val pool = pool(max = 2)
        val holderIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val swept = AtomicInteger()
        var held: Connection? = null

        runBlocking {
            val holder =
                async(Dispatchers.IO) {
                    pool.lease(LeaseKind.INTERNAL) { c ->
                        held = c
                        c.createStatement().use { it.execute("CREATE TABLE \"late\" (\"id\" INTEGER)") }
                        holderIn.complete(Unit)
                        release.await()
                        // Still usable AFTER close(): nobody reset or closed it under us.
                        scalarLong(c, "SELECT COUNT(*) FROM \"late\"") shouldBe 0L
                    }
                }
            withTimeout(TIMEOUT_MS) { holderIn.await() }

            val started = System.nanoTime()
            val outcome = pool.close { swept.incrementAndGet() }
            // Bounded: close returned while the lease was still held.
            ((System.nanoTime() - started) / 1_000_000) shouldBeLessThanMs TIMEOUT_MS
            outcome.leasesOutstanding shouldBe 1
            outcome.sweepRan shouldBe false
            pool.isClosed shouldBe true
            requireNotNull(held) { "holder never entered" }.isClosed shouldBe false

            release.complete(Unit)
            holder.await()
        }

        // The deferred cleanup ran exactly once on the late return, and the connection went with it.
        swept.get() shouldBe 1
        requireNotNull(held).isClosed shouldBe true
        pool.physicalConnections shouldBe 0
        DriverManager.getConnection(url, "sa", "").use { fresh -> tableCount(fresh) shouldBe 0 }
    }

    @Test
    fun `an opener failure inside a lease releases the permit and surfaces the driver error`() {
        // The FIRST open fails; later opens succeed, so the closing check can really take a second
        // connection and thereby prove the failed checkout gave its permit back.
        val failedOnce = AtomicInteger()
        val pool =
            pool(max = 2, opener = {
                if (failedOnce.getAndIncrement() ==
                    0
                ) {
                    throw SQLException("no more sessions", "08001")
                } else {
                    open()
                }
            })
        val firstIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runBlocking {
            val first =
                async(Dispatchers.IO) {
                    pool.lease(LeaseKind.INTERNAL) {
                        firstIn.complete(Unit)
                        release.await()
                    }
                }
            withTimeout(TIMEOUT_MS) { firstIn.await() }
            // The second lease has a permit but no idle connection: the opener fails.
            val thrown = shouldThrow<SQLException> { pool.lease(LeaseKind.INTERNAL) { } }
            thrown.sqlState shouldBe "08001"
            release.complete(Unit)
            first.await()
        }
        // The failed checkout returned its permit and reserved no phantom connection: two more
        // leases fit inside the cap of two, bounded so a leaked permit reads as red, not a hang.
        pool.activeLeases shouldBe 0
        pool.physicalConnections shouldBe 1
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                pool.lease(LeaseKind.INTERNAL) { pool.lease(LeaseKind.INTERNAL) { } }
            }
        }
        pool.close()
    }

    @Test
    fun `a leaked permit is impossible by construction — every exit path returns it`() {
        val pool = pool(max = 1)
        runBlocking {
            shouldThrow<IllegalStateException> { pool.lease(LeaseKind.INTERNAL) { error("callback failure") } }
            // Every later lease is bounded: with capacity ONE, a permit leaked by the failure above
            // would make the next acquisition wait forever, and an unbounded wait is a hang, not a
            // red test.
            shouldThrow<SQLException> {
                withTimeout(TIMEOUT_MS) {
                    pool.lease(LeaseKind.AUTHOR) { c -> c.createStatement().use { it.execute("SELECT * FROM \"nope\"") } }
                }
            }
            // Both failures returned their permit and connection: the next lease proceeds at once.
            withTimeout(TIMEOUT_MS) { pool.lease(LeaseKind.INTERNAL) { } }
        }
        pool.activeLeases shouldBe 0
        pool.close()
    }

    @Test
    fun `no method of the pool hands out a connection`() {
        H2ConnectionPool::class.java.methods
            .filter { it.returnType == Connection::class.java }
            .map { it.name } shouldBe emptyList()
    }

    // ---------- fixtures ----------

    /** Marks one arrival and completes [gate] once the second has arrived. */
    private fun arrive(
        entered: AtomicInteger,
        gate: CompletableDeferred<Unit>,
    ) {
        if (entered.incrementAndGet() >= 2) gate.complete(Unit)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) kotlinx.coroutines.delay(POLL_MS)
        }
    }

    private infix fun Long.shouldBeLessThanMs(bound: Long) {
        (this < bound) shouldBe true
    }

    /**
     * A connection proxy whose `rollback()` fails once [armed] — the failed-reset shape. It
     * records whether the pool closed the physical connection it wraps.
     */
    private class FailingRollback {
        @Volatile var armed = false

        @Volatile var closed = false
        private var proxy: Connection? = null

        fun wrap(target: Connection): Connection {
            val p =
                Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    InvocationHandler { _, method, args ->
                        if (method.name == "rollback" && armed) throw SQLException("reset refused", "90067")
                        if (method.name == "close") closed = true
                        try {
                            method.invoke(target, *(args ?: emptyArray()))
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.targetException
                        }
                    },
                ) as Connection
            proxy = p
            return p
        }

        fun isWrapped(c: Connection) = c === proxy
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
