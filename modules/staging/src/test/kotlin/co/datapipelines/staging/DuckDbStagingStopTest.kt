package co.datapipelines.staging

import io.kotest.assertions.fail
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The two bounds on a statement [DuckDbStaging] creates (#795 round two, review F4; staging.md §8.5):
 * `execute` sets the configured query timeout, as `withQuery` does, and `close()` cancels a statement
 * still running, so the last lease comes back — and deletes the database — without waiting the
 * timeout out.
 *
 * The runaway is one aggregate over a trillion-row range: minutes of work on two threads, and it
 * never ends by itself inside a test's bound. The driver stops it about 1 s after its timeout task or
 * a cross-thread `cancel()` fires (round-two probe: 1,004 ms for a 1 s timeout, 1,001 ms for a cancel
 * issued at 1 s). The stated slack is [SLACK_SECONDS].
 */
class DuckDbStagingStopTest {
    @TempDir
    lateinit var scratch: Path

    private val runner = Executors.newSingleThreadExecutor()

    @AfterEach
    fun stopRunner() {
        runner.shutdownNow()
    }

    @Test
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `a runaway statement through execute stops at the configured query timeout`() {
        val staging = DuckDbStagingFactory(duckProps(scratch).copy(queryTimeoutSeconds = SHORT_TIMEOUT_SECONDS)).open()
        try {
            val started = System.nanoTime()
            val outcome = runner.submit<Result<Long>> { runCatching { runBlocking { staging.execute(RUNAWAY) } } }

            val result = awaitStop(outcome, SHORT_TIMEOUT_SECONDS + SLACK_SECONDS, "with a ${SHORT_TIMEOUT_SECONDS}s query timeout")
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI

            println("runaway execute: stopped after ${elapsedMs}ms (timeout ${SHORT_TIMEOUT_SECONDS}s, slack ${SLACK_SECONDS}s)")
            result.exceptionOrNull().shouldBeInstanceOf<SQLException>().message shouldContain "Interrupted"
            // The timeout ended it, not an early refusal of some other kind.
            elapsedMs shouldBeGreaterThanOrEqual SHORT_TIMEOUT_SECONDS * MILLIS_PER_SECOND - EARLY_TOLERANCE_MS
        } finally {
            staging.close()
        }
    }

    @Test
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `close during a runaway execute returns at once, and the returning lease deletes the database`() {
        val factory = DuckDbStagingFactory(duckProps(scratch).copy(queryTimeoutSeconds = LONG_TIMEOUT_SECONDS))
        val staging = factory.open()
        val executionId = staging.executionId
        val outcome = runner.submit<Result<Long>> { runCatching { runBlocking { staging.execute(RUNAWAY) } } }
        awaitInFlight(staging)
        // The driver's cancel is a no-op until the statement has entered its execute (DuckDbLeasePool
        // KDoc); registration precedes that entry by microseconds, and this margin covers it.
        Thread.sleep(EXECUTE_ENTRY_MARGIN_MS)

        val closeStarted = System.nanoTime()
        staging.close()
        val closeMs = (System.nanoTime() - closeStarted) / NANOS_PER_MILLI

        closeMs shouldBeLessThan CLOSE_RETURNS_WITHIN_MS
        val result = awaitStop(outcome, SLACK_SECONDS, "after close() with a ${LONG_TIMEOUT_SECONDS}s query timeout")
        println("close during runaway: close() returned in ${closeMs}ms; the statement stopped within ${SLACK_SECONDS}s")
        result.exceptionOrNull().shouldBeInstanceOf<SQLException>().message shouldContain "Interrupted"
        // The lease returned before execute rethrew, and the last return finalized the database.
        pathsOf(scratch, executionId).shouldBeEmpty()
        factory.budget.reservedBytes shouldBe 0L
    }

    /** Polls until the runaway's statement is registered in flight, bounded. */
    private fun awaitInFlight(staging: DuckDbStaging) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SLACK_SECONDS)
        while (staging.inFlightStatements == 0) {
            if (System.nanoTime() > deadline) fail("the runaway statement never registered in flight")
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * The statement's outcome when it stops within [seconds]; otherwise a failure naming [what] —
     * after letting the statement run out its own timeout, so a red run leaves no thread behind.
     */
    private fun awaitStop(
        outcome: Future<Result<Long>>,
        seconds: Long,
        what: String,
    ): Result<Long> =
        try {
            outcome.get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            runCatching { outcome.get(LONG_TIMEOUT_SECONDS + SLACK_SECONDS, TimeUnit.SECONDS) }
            fail("the runaway statement was still running ${seconds}s later, $what")
        }

    private companion object {
        /** One aggregate over 10^12 rows: never finishes inside any bound here. */
        const val RUNAWAY = "CREATE TABLE stg_runaway AS SELECT sum(range) AS s FROM range(1000000000000)"

        const val SHORT_TIMEOUT_SECONDS = 1
        const val LONG_TIMEOUT_SECONDS = 30
        const val SLACK_SECONDS = 5L
        const val TEST_BOUND_SECONDS = 90L
        const val EXECUTE_ENTRY_MARGIN_MS = 500L
        const val CLOSE_RETURNS_WITHIN_MS = 1_000L
        const val EARLY_TOLERANCE_MS = 100L
        const val POLL_MS = 10L
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
