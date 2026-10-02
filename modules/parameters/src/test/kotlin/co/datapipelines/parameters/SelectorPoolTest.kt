package co.datapipelines.parameters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * [SelectorPool] — the `ScriptEvaluationPool` suite's twin (record §12's bulkhead row), over
 * [StubJdbc]: a statement that IGNORES `cancel()` and interrupts (it spins on a flag), so every
 * assertion below is about the pool's own guarantees, none resting on the driver's cooperation.
 * Synchronised on events (a statement's `started` flag, the pool's own counts), never on a sleep.
 */
class SelectorPoolTest {
    private val jdbc = StubJdbc()
    private val label = SelectorLabel("acme/sales/region_filters", "state", "warehouse")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun release() {
        jdbc.releaseAll()
        scope.cancel()
    }

    private fun submit(
        pool: SelectorPool,
        driver: StubJdbc = jdbc,
    ): Deferred<SelectorAdmission> = scope.async { pool.run(label, driver.task()) }

    @Test
    fun `the deadline answers timeout WITHOUT waiting for a statement that ignores cancel - and the adder counts it`() {
        val pool = SelectorPool(size = 1, waiting = 0)
        val answered = underDeadline(pool, jdbc.task())

        withClue("answered within the deadline plus scheduling slack, while the statement still spins") {
            answered.shouldBeTimeout()
            jdbc.stubs
                .single()
                .ended
                .get() shouldBe false
        }
        pool.abandoned.sum() shouldBe 1L
        waitUntil("the abandoned connection is discarded") { jdbc.discarded.size == 1 }
        jdbc.discarded shouldContainExactly listOf(jdbc.stubs.single().connection)
        jdbc.stubs
            .single()
            .cancels
            .get() shouldBeGreaterThanOrEqual 1
    }

    @Test
    fun `the abandoned worker KEEPS its slot until the driver returns - then the slot comes back`() {
        val pool = SelectorPool(size = 1, waiting = 0)
        underDeadline(pool, jdbc.task()).shouldBeTimeout()

        withClue("the runaway still owns the slot: 1 live abandoned worker, nothing more admitted") {
            pool.abandonedThreadsAlive() shouldBe 1
            pool.admitted() shouldBe 1
            bounded { pool.run(label, jdbc.task()) } shouldBe SelectorAdmission.Saturated
        }

        jdbc.releaseAll()
        waitUntil("the worker ended and returned its slot") { pool.abandonedThreadsAlive() == 0 && pool.admitted() == 0 }
        bounded { pool.run(label, StubJdbc(quick = true).task()) }.shouldBeInstanceOf<SelectorAdmission.Completed>()
    }

    @Test
    fun `a fifth submission on a 4 running - 4 waiting pool is admitted and WAITS - the fifth slot never opens while four spin`() {
        val pool = SelectorPool(size = 4, waiting = 4)
        val four = List(4) { submit(pool) }
        waitUntil("four statements run") { jdbc.started() == 4 }

        val fifth = submit(pool)
        waitUntil("the fifth is admitted") { pool.admitted() == 5 }

        withClue("admitted but not running: no fifth lease, the four still spin") {
            jdbc.stubs.size shouldBe 4
            fifth.isCompleted shouldBe false
        }
        jdbc.stubs
            .first()
            .released
            .set(true)
        waitUntil("the freed slot goes to the fifth") { jdbc.started() == 5 }
        jdbc.releaseAll()
        bounded { (four + fifth).forEach { it.await().shouldBeInstanceOf<SelectorAdmission.Completed>() } }
    }

    @Test
    fun `a sixty-ninth submission on 4 running - 64 waiting is refused AT ONCE - selectors_saturated, never a wait`() {
        val pool = SelectorPool(size = 4, waiting = 64)
        val admitted = List(68) { submit(pool) }
        waitUntil("sixty-eight admitted, four running") { pool.admitted() == 68 && jdbc.started() == 4 }
        val begun = System.nanoTime()

        val sixtyNinth = bounded { pool.run(label, jdbc.task()) }

        sixtyNinth shouldBe SelectorAdmission.Saturated
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) shouldBeLessThan AT_ONCE_MS
        jdbc.releaseAll()
        bounded { admitted.forEach { it.await().shouldBeInstanceOf<SelectorAdmission.Completed>() } }
    }

    @Test
    fun `with slots abandoned but queue room, an admitted statement that outlives the deadline is the WHOLE request's timeout`() {
        val pool = SelectorPool(size = 1, waiting = 1)
        underDeadline(pool, jdbc.task()).shouldBeTimeout()

        withClue("admitted (there is queue room), never saturated: the deadline decides after admission") {
            underDeadline(pool, jdbc.task()).shouldBeTimeout()
        }
        withClue("the queued one never leased, and gave its admission back when its wait was cancelled") {
            jdbc.stubs.size shouldBe 1
            pool.admitted() shouldBe 1
        }
    }

    @Test
    fun `a driver that honours cancel ends at once - its worker returns the slot, the connection is still discarded`() {
        val cooperative = StubJdbc(honoursCancel = true)
        val pool = SelectorPool(size = 1, waiting = 0)

        underDeadline(pool, cooperative.task()).shouldBeTimeout()

        waitUntil("the cancelled statement ended and freed its slot") { pool.abandonedThreadsAlive() == 0 && pool.admitted() == 0 }
        cooperative.stubs
            .single()
            .cancelled
            .get() shouldBe true
        cooperative.discarded shouldContainExactly listOf(cooperative.stubs.single().connection)
    }

    @Test
    fun `the abandonment is ONE error line naming the set, the parameter and the datasource - never the SQL`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(SelectorPool::class.java) as Logger
        logger.addAppender(appender)
        try {
            underDeadline(SelectorPool(1, 0), jdbc.task()).shouldBeTimeout()
        } finally {
            logger.detachAppender(appender)
        }

        val line = appender.list.single()
        line.level shouldBe Level.ERROR
        line.formattedMessage shouldContain "set=acme/sales/region_filters parameter=state datasource=warehouse"
        line.formattedMessage shouldNotContain "SELECT"
    }

    @Test
    fun `a task that completes normally returns both permits`() {
        // Three back-to-back runs on a 1/0 pool: `Completed` must mean the slot is already back
        // (the pool joins its finishing worker before answering) — CI's 2-vCPU runner answered the
        // second run `Saturated` when the caller was resumed before the worker's finally ran.
        val pool = SelectorPool(size = 1, waiting = 0)
        val quick = StubJdbc(quick = true)

        repeat(3) { bounded { pool.run(label, quick.task()) }.shouldBeInstanceOf<SelectorAdmission.Completed>() }

        pool.admitted() shouldBe 0
        pool.abandoned.sum() shouldBe 0L
        quick.discarded shouldBe emptyList()
    }

    @Test
    fun `the admission callback runs once the running slot is held and before the statement starts (#375)`() {
        val pool = SelectorPool(size = 1, waiting = 0)
        val quick = StubJdbc(quick = true)
        val seen = mutableListOf<String>()

        val answer =
            bounded {
                pool.run(label, quick.task()) {
                    seen += "admitted=${pool.admitted()} started=${quick.stubs.size}"
                }
            }

        answer.shouldBeInstanceOf<SelectorAdmission.Completed>()
        seen shouldContainExactly listOf("admitted=1 started=0")
    }

    @Test
    fun `an admission callback that throws returns both permits before the exception propagates (#375)`() {
        val pool = SelectorPool(size = 1, waiting = 0)
        val quick = StubJdbc(quick = true)

        val failed = runCatching { bounded { pool.run(label, quick.task()) { error("the observer broke") } } }

        failed.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
        pool.admitted() shouldBe 0
        quick.stubs.size shouldBe 0
        withClue("the slot came back: the next submission is admitted, not Saturated") {
            bounded { pool.run(label, quick.task()) }.shouldBeInstanceOf<SelectorAdmission.Completed>()
        }
    }

    /**
     * [task] under an evaluate-like deadline ([DEADLINE_MS]), the answer awaited at most
     * [ANSWERED_WITHIN_MS] from the test's side: null when the pool did NOT answer in time — so a pool
     * that waits for its worker fails the assertion instead of hanging the suite.
     */
    private fun underDeadline(
        pool: SelectorPool,
        task: SelectorTask,
    ): Result<SelectorAdmission>? {
        val call = scope.async { runCatching { withTimeout(DEADLINE_MS) { pool.run(label, task) } } }
        return runBlocking { withTimeoutOrNull(ANSWERED_WITHIN_MS) { call.await() } }
    }

    /** Every other wait in this suite: bounded, so a regression fails its assertion instead of hanging the suite. */
    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(COMPLETES_MS) { block() } }

    private fun Result<SelectorAdmission>?.shouldBeTimeout() {
        withClue("the pool answered the deadline (a pool that waited for its worker answers nothing in time)") {
            shouldNotBeNull().exceptionOrNull().shouldBeInstanceOf<TimeoutCancellationException>()
        }
    }

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val DEADLINE_MS = 200L
        const val ANSWERED_WITHIN_MS = 2_000L
        const val AT_ONCE_MS = 500L
        const val COMPLETES_MS = 5_000L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
