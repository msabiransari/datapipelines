package co.datapipelines.datasources.pooling

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 152 (R152-3 / R152-4) — a LAKE generation owns every physical duplicate until its closure is
 * CONFIRMED, and every access to its handle registry takes the same monitor.
 *
 * The round-3 review reproduced two holes in the R152-2 tracking on the real driver: the
 * release callback removed from the `HashSet` outside the monitor the other operations took
 * (a snapshot could be mutated under the snapshotter), and `close()` unregistered BEFORE the
 * driver's close — so a first close that threw left a real, open DuckDB duplicate that the
 * generation's release no longer knew about. These tests are those witnesses, plus the
 * neighbours the brief names: a close already in flight at release (hand-off and one retry), a
 * permanently refusing driver (the honest residual), `abort()` (never a closure), a handle
 * closed behind the wrapper's back, and the shared pool seam with no owner at all.
 */
class LakeHandleOwnershipTest {
    /**
     * The owner's retained connection whose reflective `duplicate()` opens the real duplicate
     * and wraps it so the test controls its `close()`: [beforeClose] runs on every attempt
     * (attempt number given) BEFORE the driver's close and may throw or block.
     */
    private class Factory(
        private val raw: Connection,
        private val beforeClose: (attempt: Int) -> Unit,
    ) : Connection by raw {
        lateinit var duplicate: Connection
        val closeAttempts = AtomicInteger(0)

        @Suppress("unused") // reached reflectively by LakeInstanceOwner
        fun duplicate(): Connection {
            val real = raw.javaClass.getMethod("duplicate").invoke(raw) as Connection
            duplicate = real
            return object : Connection by real {
                override fun close() {
                    beforeClose(closeAttempts.incrementAndGet())
                    real.close()
                }
            }
        }
    }

    private fun driver(): DataSource = DriverDataSource("jdbc:duckdb::memory:", "org.duckdb.DuckDBDriver", Properties(), null, null)

    private fun owner(
        name: String,
        factory: Factory,
    ): LakeInstanceOwner =
        LakeInstanceOwner.open(
            object : DataSource by driver() {
                override fun getConnection(): Connection = factory
            },
            name,
        ) {}

    @Test
    fun `a first close that throws leaves the duplicate OWNED - the generation's release closes it on the next attempt`() {
        val factory = Factory(driver().connection) { attempt -> if (attempt == 1) throw SQLException("controlled first close failure") }
        val owner = owner("lake_failed_close", factory)
        val handle = owner.duplicate()

        shouldThrow<SQLException> { handle.close() }.message shouldBe "controlled first close failure"
        val trackedAfterFailure = owner.liveDuplicates
        val rawOpenAfterFailure = !factory.duplicate.isClosed

        owner.retire()
        val logged = capturingLogs { owner.close() }

        assertAll(
            { withClue("still tracked after the failed close") { trackedAfterFailure shouldBe 1 } },
            { withClue("the raw duplicate really was left open") { rawOpenAfterFailure shouldBe true } },
            { withClue("the generation's release closed it") { factory.duplicate.isClosed shouldBe true } },
            { factory.closeAttempts.get() shouldBe 2 },
            { owner.liveDuplicates shouldBe 0 },
            {
                logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContainAll
                    listOf(" handles=1 ", " closed=1 ", " close_failures=0 ")
            },
        )
        factory.close()
    }

    @Test
    fun `every registry access takes the monitor - a release blocks behind a snapshot instead of mutating under it`() {
        val factory = Factory(driver().connection) {}
        val owner = owner("lake_monitor", factory)
        val handle = owner.duplicate()
        // The ACTUAL monitor the owner uses — read, not replaced, exactly as the review's witness did.
        val handles =
            LakeInstanceOwner::class.java
                .getDeclaredField("handles")
                .apply { isAccessible = true }
                .get(owner)
        val closer = Thread { handle.close() }

        val observedWhileHeld: Pair<Boolean, Int>
        val closerAliveWhileHeld: Boolean
        val releasedUnderMonitor: List<String>
        synchronized(handles) {
            closer.start()
            // The driver's close runs OUTSIDE the monitor — so the raw handle closes while we
            // hold it — and the registry removal must then wait for us.
            waitUntil { factory.duplicate.isClosed }
            Thread.sleep(SETTLE_MS) // give a removal that ignored the monitor every chance to land
            observedWhileHeld = factory.duplicate.isClosed to owner.liveDuplicates
            closerAliveWhileHeld = closer.isAlive
            // The review's own next step: release the generation while still holding the monitor
            // (reentrant for this thread). The snapshot still contains the handle — its forget is
            // waiting behind us — but its phase is already CLOSED, so the release confirms it
            // rather than counting it as a closure of its own.
            owner.retire()
            releasedUnderMonitor = capturingLogs { owner.close() }
        }
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertAll(
            { withClue("driver closed outside the monitor") { observedWhileHeld.first shouldBe true } },
            { withClue("the registry was NOT mutated while the monitor was held") { observedWhileHeld.second shouldBe 1 } },
            { withClue("the releasing thread was waiting for the monitor") { closerAliveWhileHeld shouldBe true } },
            { withClue("and completed once it was free") { closer.isAlive shouldBe false } },
            {
                releasedUnderMonitor.single { it.startsWith("event=lake.instance_handles_closed") } shouldContainAll
                    listOf(" handles=1 ", " closed=0 ", " already_closed=1 ")
            },
            { owner.liveDuplicates shouldBe 0 },
        )
        factory.close()
    }

    @Test
    fun `a close in flight at release is handed to its closer - which finishes it, retrying once if the driver fails`() {
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val factory =
            Factory(driver().connection) { attempt ->
                if (attempt == 1) {
                    closeEntered.countDown()
                    releaseClose.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                    throw SQLException("driver refused the in-flight close")
                }
            }
        val owner = owner("lake_in_flight", factory)
        val handle = owner.duplicate()
        val closer = Thread { handle.close() }.apply { start() }
        closeEntered.await(WAIT_S, TimeUnit.SECONDS) shouldBe true

        owner.retire()
        val logged = capturingLogs { owner.close() } // finds the handle CLOSING: hands off, does not wait, does not double-close
        val trackedAfterRelease = owner.liveDuplicates
        val rawOpenAfterRelease = !factory.duplicate.isClosed
        releaseClose.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertAll(
            {
                logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContainAll
                    listOf(" handles=1 ", " in_flight=1 ", " closed=0 ")
            },
            { withClue("still owned while the closer is inside the driver") { trackedAfterRelease shouldBe 1 } },
            { rawOpenAfterRelease shouldBe true },
            {
                withClue(
                    "the closer's first attempt failed and its transferred retry succeeded",
                ) { factory.closeAttempts.get() shouldBe 2 }
            },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
            { closer.isAlive shouldBe false },
        )
        factory.close()
    }

    @Test
    fun `a driver that refuses every close is the reported residual - still owned, counted, never forgotten`() {
        val factory = Factory(driver().connection) { throw SQLException("driver refuses to close") }
        val owner = owner("lake_refused", factory)
        val handle = owner.duplicate()

        shouldThrow<SQLException> { handle.close() }
        owner.retire()
        val logged = capturingLogs { owner.close() }

        assertAll(
            {
                logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContainAll
                    listOf(" handles=1 ", " closed=0 ", " close_failures=1 ")
            },
            { withClue("the residual stays visible, not silently dropped") { owner.liveDuplicates shouldBe 1 } },
            { factory.closeAttempts.get() shouldBe 2 },
        )
        factory.duplicate.close() // the test's own cleanup, through the raw driver connection
        factory.close()
    }

    @Test
    fun `abort is never a closure - the handle stays owned until a close confirms it`() {
        val factory = Factory(driver().connection) {}
        val owner = owner("lake_abort", factory)
        val handle = owner.duplicate()

        // The pinned driver refuses abort outright (HikariCP then falls back to close); either
        // way nothing about an abort is treated as closure.
        shouldThrow<SQLFeatureNotSupportedException> { handle.abort(Runnable::run) }
        val trackedAfterAbort = owner.liveDuplicates
        handle.close()
        handle.close() // a second close is the no-op the driver's own is

        assertAll(
            { trackedAfterAbort shouldBe 1 },
            { owner.liveDuplicates shouldBe 0 },
            { factory.closeAttempts.get() shouldBe 1 },
        )
        owner.close()
        factory.close()
    }

    @Test
    fun `a handle closed behind the wrapper's back is confirmed at release, not counted as a closure by the generation`() {
        val factory = Factory(driver().connection) {}
        val owner = owner("lake_closed_by_others", factory)
        owner.duplicate()
        factory.duplicate.close() // the raw driver connection, closed by something the wrapper never saw

        owner.retire()
        val logged = capturingLogs { owner.close() }

        assertAll(
            {
                logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContainAll
                    listOf(" already_closed=1 ", " closed=0 ")
            },
            { owner.liveDuplicates shouldBe 0 },
            { factory.closeAttempts.get() shouldBe 0 },
        )
        factory.close()
    }

    @Test
    fun `the shared pool seam with NO owner is unchanged - concurrent closes both return and the pool is closed`() {
        val hikari =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:duckdb::memory:"
                    minimumIdle = 0
                    maximumPoolSize = 1
                    initializationFailTimeout = -1
                },
            )
        val pool = HikariConnectionPool("no_owner", hikari, instanceOwner = null)
        pool.leaseConnection().close()

        val closers = List(2) { Thread(pool::close) }.onEach { it.start() }
        closers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_S)) }

        assertAll(
            { closers.all { !it.isAlive } shouldBe true },
            { pool.isClosed shouldBe true },
            { shouldThrow<SQLException> { pool.leaseConnection() } },
        )
    }

    // ------------------------------------------------------------------ helpers

    private infix fun String.shouldContainAll(parts: List<String>) =
        parts.forEach { part ->
            withClue("'$this' lacks '$part'") {
                (
                    part in
                        this
                ) shouldBe
                    true
            }
        }

    private fun capturingLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition()) {
            withClue("condition not met within ${WAIT_S}s") { (System.nanoTime() < deadline) shouldBe true }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val WAIT_S = 10L
        const val POLL_MS = 10L
        const val SETTLE_MS = 100L
    }
}
