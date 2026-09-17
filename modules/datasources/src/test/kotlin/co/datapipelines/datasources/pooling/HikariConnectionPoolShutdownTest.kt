package co.datapipelines.datasources.pooling

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 152 (R152-1) — the SHUTDOWN ownership of [HikariConnectionPool]: one caller runs the whole
 * retire → Hikari close → owner release sequence, whatever happens around it.
 *
 * The first delivery guarded the owner release with a once-only flag AFTER `HikariDataSource
 * .close()` — and Hikari's close returns at once for a second caller, so a concurrent second
 * `close()` won the flag and released the owner while the first close was still draining
 * physical connections (reproduced by the review with a latch in the driver's close). These
 * tests are that reproduction made deterministic, on the pinned HikariCP 6.3.3 and the real
 * DuckDB driver, plus the two neighbours the correction brief named: an exceptional physical
 * close, and a physical connection whose creation straddles the shutdown.
 */
class HikariConnectionPoolShutdownTest {
    /** A recording owner: when it was retired/closed, by which thread, and with Hikari in what state. */
    private class RecordingOwner(
        private val hikari: () -> HikariDataSource,
    ) : PoolInstanceOwner {
        val retireCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)

        @Volatile
        var closedBy: Thread? = null

        @Volatile
        var hikariClosedAtRelease: Boolean? = null

        override fun retire() {
            retireCalls.incrementAndGet()
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closedBy = Thread.currentThread()
            hikariClosedAtRelease = hikari().isClosed
        }
    }

    /** A connection whose `close()` the test controls — the latch the review put in the driver. */
    private class LatchedCloseConnection(
        private val delegate: Connection,
        private val onClose: (Connection) -> Unit,
    ) : Connection by delegate {
        override fun close() = onClose(delegate)
    }

    /**
     * The owner's retained connection with a GATE on `duplicate()`: the real driver's duplicate
     * is opened only once the test releases it, and every duplicate is remembered so its fate
     * can be asserted. Named (not anonymous) so the reflective call from another package works.
     */
    private class GatedConnection(
        private val raw: Connection,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : Connection by raw {
        val duplicates = java.util.concurrent.CopyOnWriteArrayList<Connection>()

        @Suppress("unused") // reached reflectively by LakeInstanceOwner
        fun duplicate(): Connection {
            entered.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
            return (raw.javaClass.getMethod("duplicate").invoke(raw) as Connection).also { duplicates += it }
        }
    }

    private fun duckdb(): DataSource = DriverDataSource("jdbc:duckdb::memory:", "org.duckdb.DuckDBDriver", Properties(), null, null)

    private fun hikari(
        source: DataSource,
        connectionTimeoutMs: Long = 250,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                dataSource = source
                minimumIdle = 0
                maximumPoolSize = 1
                connectionTimeout = connectionTimeoutMs
                initializationFailTimeout = -1 // no fail-fast connection at construction: creation is what the tests drive
                poolName = "shutdown-test"
            },
        )

    @Test
    fun `a concurrent second close returns at once and does NOT release the owner - the first caller owns the sequence`() {
        val armed = CountDownLatch(1)
        val physicalCloseEntered = CountDownLatch(1)
        val releasePhysicalClose = CountDownLatch(1)
        val source =
            object : DataSource by duckdb() {
                override fun getConnection(): Connection =
                    LatchedCloseConnection(duckdb().connection) { real ->
                        if (armed.count == 0L) {
                            physicalCloseEntered.countDown()
                            releasePhysicalClose.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                        }
                        real.close()
                    }
            }
        lateinit var hikari: HikariDataSource
        val owner = RecordingOwner { hikari }
        hikari = hikari(source)
        val pool = HikariConnectionPool("lake_overlap", hikari, owner)
        pool.leaseConnection().close() // one physical connection now idle in the bag
        armed.countDown()

        val first = Thread(pool::close, "first-close").apply { start() }
        withClue("the first close must reach the driver's close") { physicalCloseEntered.await(WAIT_S, TimeUnit.SECONDS) shouldBe true }
        val ownerClosedBeforeSecond = owner.closeCalls.get()
        pool.close() // the concurrent second caller — must return immediately
        val ownerClosedAfterSecond = owner.closeCalls.get()
        val firstStillRunning = first.isAlive
        releasePhysicalClose.countDown()
        first.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertAll(
            { withClue("first close was still draining when the second returned") { firstStillRunning shouldBe true } },
            { withClue("owner released by the second caller mid-drain") { ownerClosedAfterSecond shouldBe ownerClosedBeforeSecond } },
            { ownerClosedBeforeSecond shouldBe 0 },
            { withClue("first close finished") { first.isAlive shouldBe false } },
            { owner.closeCalls.get() shouldBe 1 },
            { owner.retireCalls.get() shouldBe 1 },
            { owner.closedBy?.name shouldBe "first-close" },
            { owner.hikariClosedAtRelease shouldBe true },
            { pool.isClosed shouldBe true },
        )
    }

    @Test
    fun `an exceptional physical close still ends with the owner released exactly once - after Hikari`() {
        val source =
            object : DataSource by duckdb() {
                override fun getConnection(): Connection =
                    LatchedCloseConnection(duckdb().connection) { real ->
                        runCatching { real.close() }
                        throw SQLException("driver refused to close")
                    }
            }
        lateinit var hikari: HikariDataSource
        val owner = RecordingOwner { hikari }
        hikari = hikari(source)
        val pool = HikariConnectionPool("lake_exceptional", hikari, owner)
        pool.leaseConnection().close()

        pool.close()
        pool.close()

        assertAll(
            { owner.retireCalls.get() shouldBe 1 },
            { owner.closeCalls.get() shouldBe 1 },
            { owner.hikariClosedAtRelease shouldBe true },
            { pool.isClosed shouldBe true },
        )
    }

    /**
     * The close/open interleaving: Hikari's creator thread is inside `duplicate()` when the
     * pool closes. Hikari waits for it only as long as the DataSource's login timeout (which
     * Hikari set from `connectionTimeout`; 250 ms ⇒ 1 s), then closes its bag — a connection
     * handed over after that can no longer be added and would be lost with its handle on the
     * instance. The duplicate is released only AFTER Hikari has given up waiting, so the guard
     * proves the late creation closes itself: no handle survives, no fresh instance is opened,
     * and the owner is released once, at the end.
     */
    @Test
    fun `a physical connection whose creation outlasts the shutdown wait closes itself instead of leaking`() {
        val duplicateEntered = CountDownLatch(1)
        val releaseDuplicate = CountDownLatch(1)
        val raw = duckdb().connection
        val gate = GatedConnection(raw, duplicateEntered, releaseDuplicate)
        val owner =
            LakeInstanceOwner.open(
                object : DataSource by duckdb() {
                    override fun getConnection(): Connection = gate
                },
                "lake_straddle",
            ) {}
        val lakeSource = LakeInstanceDataSource(owner, sessionInit = emptyList())
        val hikari = hikari(lakeSource)
        // The owner's release is held until the late duplicate exists, so the branch under test
        // — "created after Hikari stopped waiting, refused because retired, closes itself" — is
        // the one that runs, rather than the race's other (also leak-free) outcome in which the
        // owner closes first and the driver refuses to duplicate at all.
        val proceedToRelease = CountDownLatch(1)
        val heldOwner =
            object : PoolInstanceOwner {
                override fun retire() = owner.retire()

                override fun close() {
                    proceedToRelease.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                    owner.close()
                }
            }
        val pool = HikariConnectionPool("lake_straddle", hikari, heldOwner)

        val lease = Thread { runCatching { pool.leaseConnection() } }.apply { start() }
        withClue("Hikari's creator must be inside duplicate()") { duplicateEntered.await(WAIT_S, TimeUnit.SECONDS) shouldBe true }
        val loginTimeoutSetByHikari = lakeSource.loginTimeout

        val logged =
            capturingLogs {
                val closer = Thread(pool::close, "closer").apply { start() }
                // Hikari has given up waiting for the creator (its own WARN) — the bag is closing.
                waitUntil { it.any { line -> line.contains("Timed-out waiting for add connection executor") } }
                releaseDuplicate.countDown()
                // The late duplicate is created and — because the owner is retired — closed by its creator.
                waitUntil { gate.duplicates.size == 1 && gate.duplicates.single().isClosed }
                proceedToRelease.countDown()
                closer.join(TimeUnit.SECONDS.toMillis(WAIT_S))
                lease.join(TimeUnit.SECONDS.toMillis(WAIT_S))
            }

        assertAll(
            { withClue("Hikari sets the login timeout it later waits on") { loginTimeoutSetByHikari shouldBeGreaterThan 0 } },
            { gate.duplicates.size shouldBe 1 },
            { withClue("the late duplicate must have closed itself") { gate.duplicates.single().isClosed shouldBe true } },
            { owner.isOpen shouldBe false },
            { pool.isClosed shouldBe true },
            { logged.count { it.startsWith("event=lake.instance_opened") } shouldBe 0 },
            { logged.count { it.startsWith("event=lake.instance_closed datasource=lake_straddle") } shouldBe 1 },
        )
        raw.close()
    }

    @Test
    fun `after retire every new duplicate is refused - the owner stays open for Hikari's own abort`() {
        val owner = LakeInstanceOwner.open(duckdb(), "lake_retired") {}
        val source = LakeInstanceDataSource(owner, sessionInit = emptyList())
        val survivor = source.connection

        owner.retire()

        assertAll(
            { shouldThrow<SQLException> { source.connection }.sqlState shouldBe "08003" },
            { owner.isRetired shouldBe true },
            { withClue("retire is not close") { owner.isOpen shouldBe true } },
            { survivor.isClosed shouldBe false },
        )
        survivor.close()
        owner.close()
    }

    // ------------------------------------------------------------------ helpers

    private val appender = ListAppender<ILoggingEvent>()

    private fun capturingLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        appender.start()
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    /** Polls the live log capture until [condition] holds — synchronised on the event, never on a fixed sleep. */
    private fun waitUntil(condition: (List<String>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition(appender.list.map { it.formattedMessage })) {
            withClue("condition not met within ${WAIT_S}s") { (System.nanoTime() < deadline) shouldBe true }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val WAIT_S = 10L
        const val POLL_MS = 20L
    }
}
