package co.datapipelines.staging

import io.kotest.assertions.fail
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The two bounds on a statement [H2Staging] creates (#815; staging.md §3.3): `execute` sets the
 * configured query timeout, as `withQuery` does, and `close()` cancels a statement `execute` or
 * `withQuery` still has running, so its lease comes back — and the database dies — without waiting
 * the timeout out. The H2 twin of `DuckDbStagingStopTest`.
 *
 * The runaway is a count over a 10^10-row cross join: minutes of work, and it never ends by itself
 * inside a test's bound. The pinned H2 (2.3.232) stops it about 10 ms after its timeout fires and a
 * few ms after a cross-thread `cancel()`, with SQLState 57014 (lane #815 probe: 1,012 ms for a 1 s
 * timeout, 1,002 ms for a cancel issued at 1 s). The stated slack is [SLACK_SECONDS].
 */
class H2StagingStopTest {
    private val runner = Executors.newSingleThreadExecutor()

    @AfterEach
    fun stopRunner() {
        runner.shutdownNow()
    }

    @Test
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `a runaway statement through execute stops at the configured query timeout`() {
        val staging = open(SHORT_TIMEOUT_SECONDS)
        try {
            val started = System.nanoTime()
            val outcome = runner.submit<Result<Long>> { runCatching { runBlocking { staging.execute(RUNAWAY_EXECUTE) } } }

            val result =
                awaitStop(staging, outcome, SHORT_TIMEOUT_SECONDS + SLACK_SECONDS, "with a ${SHORT_TIMEOUT_SECONDS}s query timeout")
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI

            println("runaway execute: stopped after ${elapsedMs}ms (timeout ${SHORT_TIMEOUT_SECONDS}s, slack ${SLACK_SECONDS}s)")
            result.exceptionOrNull().shouldBeInstanceOf<SQLException>().sqlState shouldBe CANCELLED_SQLSTATE
            // The timeout ended it, not an early refusal of some other kind.
            elapsedMs shouldBeGreaterThanOrEqual SHORT_TIMEOUT_SECONDS * MILLIS_PER_SECOND - EARLY_TOLERANCE_MS
        } finally {
            staging.close()
        }
    }

    @Test
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `close during a runaway execute returns at once, and the returning lease destroys the database`() {
        val executionId = UUID.randomUUID()
        val staging = open(LONG_TIMEOUT_SECONDS, executionId)
        val outcome = runner.submit<Result<Long>> { runCatching { runBlocking { staging.execute(RUNAWAY_EXECUTE) } } }
        awaitInFlight(staging)
        // Non-vacuity: the cancel pass has a statement to cancel.
        println("close during runaway execute: inFlightStatements=${staging.inFlightStatements} before close()")
        // H2's cancel is a no-op until the statement has entered the driver (class KDoc); registration
        // precedes that entry by microseconds, and this margin covers it.
        Thread.sleep(EXECUTE_ENTRY_MARGIN_MS)

        val closeMs = timedClose(staging)

        closeMs shouldBeLessThan CLOSE_RETURNS_WITHIN_MS
        val result = awaitStop(staging, outcome, SLACK_SECONDS, "after close() with a ${LONG_TIMEOUT_SECONDS}s query timeout")
        println("close during runaway execute: close() returned in ${closeMs}ms; the statement stopped within ${SLACK_SECONDS}s")
        result.exceptionOrNull().shouldBeInstanceOf<SQLException>().sqlState shouldBe CANCELLED_SQLSTATE
        // The lease returned before execute rethrew, and the last return destroyed the database: the
        // restricted user the factory creates exists only while it lives (H2StagingLifecycleTest).
        DriverManager.getConnection(stagingUrl(executionId, H2StagingProperties()), "sa", "").use { fresh ->
            execUserCount(fresh) shouldBe 0L
            tableCount(fresh) shouldBe 0
        }
    }

    @Test
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `close during a runaway withQuery execution returns at once and stops the statement`() {
        val staging = open(LONG_TIMEOUT_SECONDS)
        // Readiness that does not depend on registration: with registration removed, polling for an
        // in-flight statement would fail before close() is ever exercised.
        val submitted = CountDownLatch(1)
        val outcome =
            runner.submit<Result<Long>> {
                runCatching {
                    submitted.countDown()
                    runBlocking { staging.withQuery(RUNAWAY_SELECT) { rs -> rs.next().let { rs.getLong(1) } } }
                }
            }
        submitted.await(SLACK_SECONDS, TimeUnit.SECONDS) shouldBe true
        // The SELECT's work happens inside executeQuery, before the callback; this margin puts close()
        // well inside it.
        Thread.sleep(EXECUTE_ENTRY_MARGIN_MS)
        println("close during runaway withQuery: inFlightStatements=${staging.inFlightStatements} before close()")

        val closeMs = timedClose(staging)

        closeMs shouldBeLessThan CLOSE_RETURNS_WITHIN_MS
        val what = "after close() during withQuery with a ${LONG_TIMEOUT_SECONDS}s query timeout"
        val result = awaitStop(staging, outcome, SLACK_SECONDS, what)
        println("close during runaway withQuery: close() returned in ${closeMs}ms; the statement stopped within ${SLACK_SECONDS}s")
        result.exceptionOrNull().shouldBeInstanceOf<SQLException>().sqlState shouldBe CANCELLED_SQLSTATE
    }

    @Test
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `a withQuery statement stays registered while its cursor is read and is released when the block returns`() {
        val staging = open(LONG_TIMEOUT_SECONDS)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val outcome =
                runner.submit<Result<Long>> {
                    runCatching {
                        runBlocking {
                            staging.withQuery("SELECT 1") { rs ->
                                entered.countDown()
                                release.await(SLACK_SECONDS, TimeUnit.SECONDS)
                                rs.next().let { rs.getLong(1) }
                            }
                        }
                    }
                }
            entered.await(SLACK_SECONDS, TimeUnit.SECONDS) shouldBe true

            staging.inFlightStatements shouldBe 1
            release.countDown()
            outcome.get(SLACK_SECONDS, TimeUnit.SECONDS).getOrThrow() shouldBe 1L
            staging.inFlightStatements shouldBe 0
        } finally {
            release.countDown()
            staging.close()
        }
    }

    private fun open(
        queryTimeoutSeconds: Int,
        executionId: UUID = UUID.randomUUID(),
    ): H2Staging = H2StagingFactory(H2StagingProperties(queryTimeoutSeconds = queryTimeoutSeconds)).create(executionId) as H2Staging

    private fun timedClose(staging: H2Staging): Long {
        val started = System.nanoTime()
        staging.close()
        return (System.nanoTime() - started) / NANOS_PER_MILLI
    }

    /** Polls until the runaway's statement is registered in flight, bounded. */
    private fun awaitInFlight(staging: H2Staging) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SLACK_SECONDS)
        while (staging.inFlightStatements == 0) {
            if (System.nanoTime() > deadline) fail("the runaway statement never registered in flight")
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * The statement's outcome when it stops within [seconds]; otherwise a failure naming [what] —
     * after closing [staging] (whose cancel pass stops a statement the bound under test missed, when
     * that pass is intact) and letting the statement run out its own timeout, so a red run leaves no
     * thread behind.
     */
    private fun awaitStop(
        staging: H2Staging,
        outcome: Future<Result<Long>>,
        seconds: Long,
        what: String,
    ): Result<Long> =
        try {
            outcome.get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            staging.close()
            runCatching { outcome.get(LONG_TIMEOUT_SECONDS + SLACK_SECONDS, TimeUnit.SECONDS) }
            fail("the runaway statement was still running ${seconds}s later, $what")
        }

    /** 1 while the database lives, 0 once destroyed — the user's name is folded, hence UPPER (H2StagingLifecycleTest). */
    private fun execUserCount(connection: Connection): Long =
        scalarLong(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE UPPER(USER_NAME) = 'STAGING_EXEC'")

    private companion object {
        /** A count over a 10^10-row cross join: never finishes inside any bound here. */
        const val RUNAWAY_SELECT =
            "SELECT COUNT(*) AS n FROM SYSTEM_RANGE(1, 100000) a, SYSTEM_RANGE(1, 100000) b WHERE MOD(a.\"X\" + b.\"X\", 7) = 0"
        const val RUNAWAY_EXECUTE = "CREATE TABLE stg_runaway AS $RUNAWAY_SELECT"

        /** H2's "statement was canceled or the session timed out", for a timeout and a cancel alike (probe). */
        const val CANCELLED_SQLSTATE = "57014"

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
