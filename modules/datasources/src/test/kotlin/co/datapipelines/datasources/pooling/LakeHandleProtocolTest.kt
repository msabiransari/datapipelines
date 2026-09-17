package co.datapipelines.datasources.pooling

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * 152 (R152-5 / R152-6 / R152-7) — the ownership protocol's failure exits, forced at the
 * production boundaries the round-4 review reproduced (design record: evidence
 * `12-ownership-protocol.md`).
 *
 * R152-5: a holder's close fails an instant before the generation's release would have marked
 * the hand-off; with independent flags the retry was lost and the handle was reported
 * `IN_FLIGHT` with nobody inside it. Now the failure and the hand-off are one critical
 * section — both orders are forced here, plus the success at the same boundary, and a bounded
 * stress run supplements them. R152-6: a nonfatal `RuntimeException` from the driver is the same
 * transition as an `SQLException` — never a stranded `CLOSING`, never an exit from the
 * release, never a skipped sibling or retained owner. R152-7: a duplicate refused at
 * registration is owned by its creator, before or after the release.
 */
class LakeHandleProtocolTest {
    /**
     * The owner's retained connection: real duplicates, with the test controlling each
     * duplicate's `close()` and, optionally, holding its creation.
     */
    private class Factory(
        private val raw: Connection,
        private val afterAllocation: () -> Unit = {},
        private val beforeClose: (attempt: Int) -> Unit,
    ) : Connection by raw {
        val duplicates = java.util.concurrent.CopyOnWriteArrayList<Connection>()
        val closeAttempts = AtomicInteger(0)

        @Suppress("unused") // reached reflectively by LakeInstanceOwner
        fun duplicate(): Connection {
            val real = raw.javaClass.getMethod("duplicate").invoke(raw) as Connection
            duplicates += real
            afterAllocation()
            return object : Connection by real {
                override fun close() {
                    beforeClose(closeAttempts.incrementAndGet())
                    real.close()
                }
            }
        }

        val duplicate: Connection get() = duplicates.single()
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

    // ------------------------------------------------------------------ R152-5

    @Test
    fun `hand-off side A - the release finds the closer INSIDE the driver, the closer's failure takes the transferred retry`() {
        val inDriver = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory =
            Factory(driver().connection) { attempt ->
                if (attempt == 1) {
                    inDriver.countDown()
                    proceed.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                    throw SQLException("first refusal")
                }
            }
        val owner = owner("lake_handoff_a", factory)
        val handle = owner.duplicate()
        val closerError = AtomicReference<Throwable?>()
        val closer = Thread { closerError.set(runCatching { handle.close() }.exceptionOrNull()) }.apply { start() }
        inDriver.await(WAIT_S, TimeUnit.SECONDS) shouldBe true

        owner.retire()
        val logged = capturingLogs { owner.close() } // sees CLOSING → hands off
        proceed.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertAll(
            { logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContain " in_flight=1 " },
            { withClue("the closer retried once and succeeded") { factory.closeAttempts.get() shouldBe 2 } },
            { withClue("its caller saw no failure — the retry closed it") { closerError.get() shouldBe null } },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
        )
        factory.close()
    }

    @Test
    fun `hand-off side B - the closer failed and EXITED before the release, the release takes its own attempt`() {
        // The exact R152-5 order: with independent flags the release then saw OPEN, set the flag
        // for nobody and reported IN_FLIGHT. Now it finds OPEN and closes the handle itself.
        val factory = Factory(driver().connection) { attempt -> if (attempt == 1) throw SQLException("first refusal") }
        val owner = owner("lake_handoff_b", factory)
        val handle = owner.duplicate()
        shouldThrow<SQLException> { handle.close() }
        val ownedAfterFailure = owner.liveDuplicates

        owner.retire()
        val logged = capturingLogs { owner.close() }

        assertAll(
            { ownedAfterFailure shouldBe 1 },
            { logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContain " closed=1 " },
            { logged.none { it.startsWith("event=lake.instance_handle_exhausted") } shouldBe true },
            { factory.closeAttempts.get() shouldBe 2 },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
        )
        factory.close()
    }

    @Test
    fun `hand-off boundary - a close that SUCCEEDS while the release passes is reported in flight and confirmed by its closer`() {
        val inDriver = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory =
            Factory(driver().connection) {
                inDriver.countDown()
                proceed.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
            }
        val owner = owner("lake_handoff_ok", factory)
        val handle = owner.duplicate()
        val closer = Thread { handle.close() }.apply { start() }
        inDriver.await(WAIT_S, TimeUnit.SECONDS) shouldBe true

        owner.retire()
        val logged = capturingLogs { owner.close() }
        val ownedWhileInFlight = owner.liveDuplicates
        proceed.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertAll(
            { logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContain " in_flight=1 " },
            { withClue("still owned while its closer was inside the driver") { ownedWhileInFlight shouldBe 1 } },
            { factory.closeAttempts.get() shouldBe 1 },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
        )
        factory.close()
    }

    /**
     * A supplement, not the guard: the review's race harness shape — a transient first refusal,
     * closer and release started together — for a bounded number of iterations. Whatever the
     * interleaving, after BOTH actors return the raw duplicate is closed and the generation owns
     * nothing. It cannot prove the protocol (the forced tests above do); it can only fail it.
     */
    @Test
    fun `stress supplement - a transient first refusal racing the release never leaves an unowned open duplicate`() {
        var inFlightSeen = 0
        repeat(STRESS_ITERATIONS) { i ->
            val factory = Factory(driver().connection) { attempt -> if (attempt == 1) throw SQLException("transient refusal $i") }
            val owner = owner("lake_stress", factory)
            val handle = owner.duplicate()
            val gate = CyclicBarrier(2)
            val closer =
                Thread {
                    gate.await()
                    runCatching { handle.close() }
                }.apply { start() }
            gate.await()
            owner.retire()
            val logged = capturingLogs { owner.close() }
            closer.join(TimeUnit.SECONDS.toMillis(WAIT_S))
            if (logged.any { " in_flight=1 " in it }) inFlightSeen++
            withClue("iteration $i: raw duplicate closed after both actors returned") { factory.duplicate.isClosed shouldBe true }
            withClue("iteration $i: nothing left owned") { owner.liveDuplicates shouldBe 0 }
            factory.close()
        }
        println("stress supplement: $STRESS_ITERATIONS iterations, $inFlightSeen observed the hand-off")
    }

    // ------------------------------------------------------------------ R152-6

    @Test
    fun `a nonfatal RuntimeException from the holder's close is a failure transition - never a stranded CLOSING`() {
        val factory =
            Factory(driver().connection) { attempt ->
                if (attempt ==
                    1
                ) {
                    error("controlled unchecked close failure")
                }
            }
        val owner = owner("lake_runtime_holder", factory)
        val handle = owner.duplicate()

        shouldThrow<IllegalStateException> { handle.close() }
        // Not stranded: the same holder can enter again (phase went back to OPEN) — the driver
        // then succeeds, and that is a confirmed closure by the holder itself.
        handle.close()

        assertAll(
            { factory.closeAttempts.get() shouldBe 2 },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
        )
        owner.close()
        factory.close()
    }

    @Test
    fun `a nonfatal RuntimeException in the release's own attempt fails that handle only - sibling and owner still close`() {
        val poison = AtomicReference<Connection?>()
        // Two duplicates: the first refuses with an unchecked exception, the second is healthy.
        val rawOwner = driver().connection
        val attemptsByDuplicate = mutableMapOf<Int, AtomicInteger>()
        val throwing =
            object : Connection by rawOwner {
                val duplicates = java.util.concurrent.CopyOnWriteArrayList<Connection>()

                @Suppress("unused")
                fun duplicate(): Connection {
                    val real = rawOwner.javaClass.getMethod("duplicate").invoke(rawOwner) as Connection
                    val index = duplicates.size
                    duplicates += real
                    val attempts = attemptsByDuplicate.getOrPut(index) { AtomicInteger(0) }
                    return object : Connection by real {
                        override fun close() {
                            attempts.incrementAndGet()
                            if (index == 0) error("controlled unchecked close failure")
                            real.close()
                        }
                    }
                }
            }
        val owner =
            LakeInstanceOwner.open(
                object : DataSource by driver() {
                    override fun getConnection(): Connection = throwing
                },
                "lake_runtime_release",
            ) {}
        owner.duplicate()
        owner.duplicate()
        poison.set(throwing.duplicates[0])

        owner.retire()
        val logged = capturingLogs { owner.close() } // must not throw, must reach the owner
        owner.close() // idempotent

        assertAll(
            {
                logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContain
                    " handles=2 closed=1 already_closed=0 in_flight=0 close_failures=1 "
            },
            { logged.single { it.startsWith("event=lake.instance_handle_exhausted") } shouldContain "controlled unchecked close failure" },
            { withClue("the sibling was closed") { throwing.duplicates[1].isClosed shouldBe true } },
            { withClue("the retained owner was closed") { rawOwner.isClosed shouldBe true } },
            { owner.isOpen shouldBe false },
            { withClue("the refusing handle is the counted residual") { owner.liveDuplicates shouldBe 1 } },
            { attemptsByDuplicate.getValue(0).get() shouldBe 1 },
        )
        poison.get()?.close() // the test's own cleanup through the raw driver connection
    }

    // ------------------------------------------------------------------ R152-7

    @Test
    fun `a duplicate refused AFTER the release is owned by its creator - a first refusal is retried, the raw handle closes`() {
        val allocated = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory =
            Factory(
                driver().connection,
                beforeClose = { attempt -> if (attempt == 1) throw SQLException("first refusal on the refused handle") },
                afterAllocation = {
                    allocated.countDown()
                    proceed.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                },
            )
        val owner = owner("lake_refused_late", factory)
        val creatorError = AtomicReference<Throwable?>()
        val creator = Thread { creatorError.set(runCatching { owner.duplicate() }.exceptionOrNull()) }.apply { start() }
        allocated.await(WAIT_S, TimeUnit.SECONDS) shouldBe true

        owner.retire()
        owner.close() // the release runs — and its snapshot cannot contain the unregistered duplicate
        val logged =
            capturingLogs {
                proceed.countDown()
                creator.join(TimeUnit.SECONDS.toMillis(WAIT_S))
            }

        assertAll(
            { creatorError.get()?.let { it is SQLNonTransientConnectionException } shouldBe true },
            { withClue("creator's own attempt, then its last-resort retry") { factory.closeAttempts.get() shouldBe 2 } },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
            { logged.none { it.startsWith("event=lake.instance_handle_exhausted") } shouldBe true },
        )
        factory.close()
    }

    @Test
    fun `a duplicate refused after the release that the driver refuses to close twice is the reported, counted residual`() {
        val allocated = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory =
            Factory(
                driver().connection,
                beforeClose = { throw SQLException("driver refuses every close") },
                afterAllocation = {
                    allocated.countDown()
                    proceed.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                },
            )
        val owner = owner("lake_refused_exhausted", factory)
        val creator = Thread { runCatching { owner.duplicate() } }.apply { start() }
        allocated.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
        owner.retire()
        owner.close()

        val logged =
            capturingLogs {
                proceed.countDown()
                creator.join(TimeUnit.SECONDS.toMillis(WAIT_S))
            }

        assertAll(
            { factory.closeAttempts.get() shouldBe 2 },
            {
                logged.single { it.startsWith("event=lake.instance_handle_exhausted datasource=lake_refused_exhausted") } shouldContain
                    " attempts=2 "
            },
            { withClue("still counted, never forgotten") { owner.liveDuplicates shouldBe 1 } },
            { factory.duplicate.isClosed shouldBe false },
        )
        factory.duplicate.close() // the test's own cleanup through the raw driver connection
        factory.close()
    }

    @Test
    fun `a duplicate refused BEFORE the release - retired but not yet snapshotted - is left to the release, which closes it`() {
        val allocated = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory =
            Factory(
                driver().connection,
                beforeClose = { attempt -> if (attempt == 1) throw SQLException("first refusal") },
                afterAllocation = {
                    allocated.countDown()
                    proceed.await(WAIT_S, TimeUnit.SECONDS) shouldBe true
                },
            )
        val owner = owner("lake_refused_early", factory)
        val creator = Thread { runCatching { owner.duplicate() } }.apply { start() }
        allocated.await(WAIT_S, TimeUnit.SECONDS) shouldBe true

        owner.retire() // retired, NOT released
        proceed.countDown()
        creator.join(TimeUnit.SECONDS.toMillis(WAIT_S))
        val ownedAfterRefusal = owner.liveDuplicates
        val logged = capturingLogs { owner.close() }

        assertAll(
            { withClue("the creator's refused close failed and left the handle to the release") { ownedAfterRefusal shouldBe 1 } },
            { logged.single { it.startsWith("event=lake.instance_handles_closed") } shouldContain " closed=1 " },
            { factory.closeAttempts.get() shouldBe 2 },
            { factory.duplicate.isClosed shouldBe true },
            { owner.liveDuplicates shouldBe 0 },
        )
        factory.close()
    }

    @Test
    fun `the attempt bound is what the protocol says - repeated holder closes each try once, the generation once, the hand-off once`() {
        val factory = Factory(driver().connection) { throw SQLException("always refused") }
        val owner = owner("lake_bound", factory)
        val handle = owner.duplicate()

        repeat(3) { shouldThrow<SQLException> { handle.close() } } // three explicit calls: three attempts, the caller's choice
        val afterHolder = factory.closeAttempts.get()
        owner.retire()
        capturingLogs { owner.close() } // the generation's one attempt
        val afterRelease = factory.closeAttempts.get()
        shouldThrow<SQLException> { handle.close() } // a later explicit call: still one attempt, no hand-off retry left to take

        assertAll(
            { afterHolder shouldBe 3 },
            { afterRelease shouldBe 4 },
            { factory.closeAttempts.get() shouldBe 5 },
            { owner.liveDuplicates shouldBe 1 },
        )
        factory.duplicate.close()
        factory.close()
    }

    // ------------------------------------------------------------------ helpers

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

    private companion object {
        const val WAIT_S = 10L
        const val STRESS_ITERATIONS = 200
    }
}
