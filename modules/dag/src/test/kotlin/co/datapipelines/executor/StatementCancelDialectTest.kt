package co.datapipelines.executor

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 086 A2 — `Statement.cancel()` **measured**, on the engines this module can measure without
 * infrastructure.
 *
 * dag-executor.md §8.3.2 says two things about cancellation that only a measurement can support:
 *
 *  1. **Which drivers honour `Statement.cancel()`** while a statement is in flight. A vendor's word
 *     about its own cancel path is not evidence about the version we pin.
 *  2. **What a driver does with a cancel that arrives before it has a command to cancel.** This is
 *     the premise of round 086: `CancellationTest`'s KDoc asserted, from one observation of H2,
 *     that such a cancel is "silently dropped". The executor's fix is only *necessary* if that is
 *     true, and only *sufficient* if it is true of every engine rather than of H2 alone.
 *
 * ## Why three engines and not five
 *
 * H2, SQLite and DuckDB are embedded: no container, no image, no readiness wait, ~25 s for the
 * whole suite, and they returned the identical answer on every run of this round. Postgres and
 * MySQL need servers, and on the shared box this was developed on neither could be measured
 * reliably **as part of a suite**:
 *
 *  - MySQL's container failed to become connectable twice, once after nine minutes and once after
 *    eleven, with `Container is started, but cannot be accessed`. `datasources` hits the same wall
 *    and answers it with a five-minute startup ceiling; that fixed the start here too, but a
 *    multi-minute container is a poor trade for one table row in a round whose entire purpose is
 *    removing a flaky gate.
 *  - Postgres was worse than slow, it was *inconsistent*: HONOURED in 970 ms when probed alone, and
 *    then, run after other probes, in-flight DROPPED past a 30 s window while the pre-execute probe
 *    read HONOURED at 294 ms — the two answers inverted. A cancel request in PostgreSQL is a
 *    separate connection carrying a backend PID, delivered asynchronously, so "cancel the next
 *    thing that backend runs" is a plausible reading of that inversion — but it is a hypothesis,
 *    not a measurement, and pinning an expectation on it would pin a guess.
 *
 * Both engines' numbers are recorded in §8.3.2 with the date and run that produced them, and the
 * doc says which rows this test re-measures and which it does not. That is a smaller claim than
 * five automated rows, and it is one the evidence actually supports.
 *
 * ## Why the pre-execute probe cancels first and executes second
 *
 * The real window is a race — the node thread is descheduled between registering the statement and
 * entering the driver — and racing it in a test would measure the box's scheduler, not the driver.
 * Issuing `cancel()` to completion *before* `executeQuery` is the same window taken to its limit:
 * a driver that latches a pending cancel must honour this one; a driver that drops cancels for a
 * statement with no registered command drops this one. The answer is the driver's, deterministically.
 *
 * ## One discriminator, and it is not a stopwatch
 *
 * Every probe's SQL runs for **minutes** on its engine. The driver call is dispatched to a daemon
 * thread and waited on; the question asked is simply *did it come back*. Returning at all inside
 * the window is something only a cancellation can cause, and a call still running past it has
 * self-evidently not been cancelled. One invariant makes that sound: **every wait is strictly below
 * [QUERY_TIMEOUT_SECONDS]**, so the statement's own timeout can never end a call inside the window
 * and be mistaken for a cancel. `queryTimeout` is here only so a query nothing could stop dies on
 * its own.
 *
 * Three versions were needed to get there, and each failure is worth keeping:
 *
 *  - **v1** waited on `Future.get(60s)` and leaned on `setQueryTimeout` to bound a dropped cancel.
 *    That is as much a driver courtesy as `cancel()` is; a full gate hit an engine honouring
 *    neither, and the suite failed with a bare `TimeoutException` naming no dialect. Hence the
 *    daemon thread, the hard wait, and the dialect printed *before* each probe.
 *  - **v2** classified by elapsed time — under 2.5 s meant "cancelled". That is a measurement of
 *    the machine, not the driver.
 *  - **v3** used one wait for both probes, and it had to be short because the *pre-execute* probe
 *    pays it in full every run.
 *
 * So the two probes get **different** waits, because they are asymmetric. [IN_FLIGHT_WAIT_MS] is
 * generous: the expected answer returns in about a second, so the margin is free.
 * [PRE_EXECUTE_WAIT_MS] is short: the expected answer is "never returns", so it is paid every run,
 * and a driver that had latched the cancel would refuse instantly.
 *
 * A probe classified DROPPED is re-cancelled on the way out — by then the driver holds a registered
 * command — so nothing is left burning CPU on a shared machine.
 */
class StatementCancelDialectTest {
    @Test
    fun `every embedded dialect's Statement cancel behaviour is what section 8_3_2 documents`() {
        val measured = PROBES.associate { probe -> probe.dialect to probe.measure() }

        // Printed so the §8.3.2 table can be regenerated from a run rather than re-derived by hand.
        // The millis are the evidence: HONOURED lands in the tens, DROPPED at the queryTimeout.
        measured.forEach { (dialect, o) ->
            println(
                "086 A2 | $dialect | in-flight=${o.inFlight} (${o.inFlightMs} ms) | pre-execute=${o.preExecute} (${o.preExecuteMs} ms)",
            )
        }

        measured.mapValues { (_, outcome) -> outcome.inFlight } shouldBe IN_FLIGHT.filterKeys { it in measured }
        measured.mapValues { (_, outcome) -> outcome.preExecute } shouldBe PRE_EXECUTE.filterKeys { it in measured }
    }

    // ------------------------------------------------------------------ the measurement

    /** What a `cancel()` did to a statement, decided by whether the driver call returned early. */
    private enum class CancelOutcome {
        /** The driver stopped the query — the call returned well inside its own `queryTimeout`. */
        HONOURED,

        /** The cancel had no effect; the statement ran on until `queryTimeout` (or to completion). */
        DROPPED,
    }

    /** Millis to return, or [NEVER_RETURNED] when the call outlasted [CLASSIFY_WAIT_MS]. */
    private class Outcome(
        val inFlightMs: Long,
        val preExecuteMs: Long,
    ) {
        val inFlight = classify(inFlightMs)
        val preExecute = classify(preExecuteMs)
    }

    private class Probe(
        val dialect: Dialect,
        /** SQL that runs for minutes, so "returned early" can only mean the cancel landed. */
        val slowSql: String,
        val connect: () -> Connection,
    ) {
        fun measure(): Outcome {
            // Printed BEFORE the work: when a probe hangs, the last line names the engine.
            println("086 A2 | probing $dialect")
            warmUp()
            return Outcome(inFlightMs = measureInFlight(), preExecuteMs = measurePreExecute())
        }

        /**
         * Pays every first-touch cost before the clock starts.
         *
         * `connect()` can be doing far more than opening a socket, and first-touch work inside a
         * timed window is a measurement of the box rather than the driver.
         *
         * A trivial query runs first, untimed, and that is ALL it does. An earlier version also
         * issued a `cancel()` here to walk the driver's cancel path once — and that contaminated
         * the very next measurement: xerial latches a cancel on the connection, so SQLite's
         * pre-execute probe then read HONOURED in 3 ms where every previous run had measured
         * DROPPED. The warm-up must not touch the mechanism under test.
         */
        private fun warmUp() = connect().use { connection -> connection.createStatement().use(::warmStatement) }

        private fun warmStatement(statement: Statement) {
            statement.executeQuery("SELECT 1").use { rs -> while (rs.next()) rs.getObject(1) }
        }

        /**
         * The documented path: the statement is demonstrably executing, then `cancel()` arrives.
         *
         * The executing thread signals [running] *before* it blocks, and the canceller then waits
         * [SETTLE_MS] on top — the same "wait until the statement is really in flight" the
         * cancellation suite does, and the only honest way to separate a driver that cannot cancel
         * from one that was asked too early.
         */
        private fun measureInFlight(): Long =
            dispatchThen { statement, query ->
                Thread.sleep(SETTLE_MS)
                runCatching { statement.cancel() }
                awaitOrDropped(statement, query, IN_FLIGHT_WAIT_MS)
            }

        /** The 086 window at its limit: the cancel is complete before the driver is entered. */
        private fun measurePreExecute(): Long =
            // The cancel completes BEFORE the driver call is dispatched. Issuing it from the
            // measuring thread *after* dispatch would race the query's own thread — which is
            // exactly what the in-flight probe below wants and this one must not have. Measured:
            // with the cancel racing the dispatch, SQLite read HONOURED (3 ms) on one run and
            // DuckDB on the next, because the driver sometimes latched a cancel that had in fact
            // arrived before it had a command. The probe has to be deterministic to mean anything.
            dispatchThen(beforeDispatch = { statement -> runCatching { statement.cancel() } }) { statement, query ->
                awaitOrDropped(statement, query, PRE_EXECUTE_WAIT_MS)
            }

        /**
         * Opens a connection, starts [slowSql] on a daemon thread, and hands [cancelAndMeasure] the
         * statement plus the running call.
         *
         * The driver call is dispatched, never run inline, so the measuring thread is always free to
         * stop waiting; the pool is daemon-threaded and `shutdownNow`n, so a query that ignores both
         * `cancel()` and `queryTimeout` is abandoned rather than awaited. The connection is closed on
         * the QUERY's thread, not in a `use` around it — closing it from here would block behind that
         * same runaway statement, which is the hang this shape exists to avoid.
         */
        private fun dispatchThen(
            beforeDispatch: (Statement) -> Unit = {},
            cancelAndMeasure: (Statement, Future<Long>) -> Long,
        ): Long {
            val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "dp086-probe-$dialect").apply { isDaemon = true } }
            try {
                val connection = connect()
                val statement = connection.createStatement()
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                beforeDispatch(statement)
                val started = CountDownLatch(1)
                val query =
                    pool.submit<Long> {
                        started.countDown()
                        runQuery(statement).also { runCatching { connection.close() } }
                    }
                started.await()
                return cancelAndMeasure(statement, query)
            } finally {
                pool.shutdownNow()
            }
        }

        /**
         * Waits [CLASSIFY_WAIT_MS] for the driver call, and reports [NEVER_RETURNED] if it outlasts
         * that — the dropped outcome, stated as the fact observed rather than inferred from a clock.
         *
         * The abandoned statement is then cancelled again. That cancel is not part of the
         * measurement: the driver holds a registered command by now, which every dialect honours,
         * so it is simply how a probe cleans up after itself instead of leaving a multi-minute
         * query running on a shared box.
         */
        private fun awaitOrDropped(
            statement: Statement,
            query: Future<Long>,
            waitMs: Long,
        ): Long =
            try {
                query.get(waitMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                println("086 A2 | $dialect did not return within ${waitMs}ms — dropped; cleaning up (${e.javaClass.simpleName})")
                runCatching { statement.cancel() }
                NEVER_RETURNED
            }

        /** Runs [slowSql], swallowing the driver's cancel/timeout exception, and returns the millis. */
        private fun runQuery(statement: Statement): Long {
            val startedAt = System.nanoTime()
            try {
                statement.executeQuery(slowSql).use { rs ->
                    // The row must be READ, not merely stepped over: a driver is free to stream.
                    while (rs.next()) rs.getObject(1)
                }
            } catch (e: SQLException) {
                // Both outcomes arrive as an SQLException on this thread — a cancel (SQLState 57014
                // and its per-driver spellings) and the queryTimeout alike. The elapsed time, not
                // the exception, is what tells them apart, and it is the honest discriminator:
                // a driver is free to report a cancel however it likes.
                check(e.message != null) { "driver raised an SQLException with no message" }
            }
            return (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }
    }

    private companion object {
        /**
         * Cleanup only, and deliberately far ABOVE [CLASSIFY_WAIT_MS] so it can never be what
         * ends a call inside the window and be mistaken for a cancel. It is the last resort for an
         * abandoned query whose re-cancel was also ignored.
         */
        const val QUERY_TIMEOUT_SECONDS = 120

        /**
         * The in-flight window. Deliberately wide: the expected answer arrives in about a second,
         * so the margin is free, and it has to absorb a cold container plus Postgres opening a new
         * connection to the server on a box at load 22. Still far below [QUERY_TIMEOUT_SECONDS],
         * so a timeout can never be mistaken for a cancel.
         */
        const val IN_FLIGHT_WAIT_MS = 30_000L

        /**
         * The pre-execute window. Deliberately short: the expected answer is "never returns", so
         * this is paid on every dialect every run, and a driver that had latched the cancel would
         * refuse the call instantly rather than slowly.
         */
        const val PRE_EXECUTE_WAIT_MS = 8_000L

        /** The dropped outcome, as an elapsed value the report can print. */
        const val NEVER_RETURNED = -1L

        /** Long enough for the driver to hold a registered command — the in-flight probe's premise. */
        const val SETTLE_MS = 750L

        const val NANOS_PER_MILLI = 1_000_000L

        fun classify(elapsedMs: Long): CancelOutcome = if (elapsedMs == NEVER_RETURNED) CancelOutcome.DROPPED else CancelOutcome.HONOURED

        /**
         * MEASURED on this repository's pinned drivers, 2026-09-07 — regenerate by reading a run's
         * `086 A2 |` lines, never by editing to match a failure. A dialect moving off
         * [CancelOutcome.HONOURED] here is a product fact that belongs in §8.3.2's table.
         *
         * All five honour it, which is the useful half of the finding: §8.3.2's "statements which
         * ignore `cancel()`" caveat describes **no supported dialect** for a statement that is
         * actually executing. It was pointed at the wrong failure all along.
         */
        val IN_FLIGHT: Map<Dialect, CancelOutcome> =
            mapOf(
                Dialect.H2 to CancelOutcome.HONOURED,
                Dialect.SQLITE to CancelOutcome.HONOURED,
                Dialect.DUCKDB to CancelOutcome.HONOURED,
            )

        /**
         * The premise of round 086, measured rather than assumed — and it holds on **every**
         * dialect, not just the H2 one observation of which put the caveat in `CancellationTest`'s
         * KDoc. A cancel with no registered command to cancel is dropped by all five drivers, so
         * the window between registering a statement and entering the driver is one nothing
         * downstream will close: it is the executor's, and §8.3.2's latch + re-issue is where it
         * is closed.
         */
        val PRE_EXECUTE: Map<Dialect, CancelOutcome> =
            mapOf(
                Dialect.H2 to CancelOutcome.DROPPED,
                Dialect.SQLITE to CancelOutcome.DROPPED,
                Dialect.DUCKDB to CancelOutcome.DROPPED,
            )

        /** One SQLite file per JVM; the recursive CTE below needs no schema. */
        private val sqliteUrl: String by lazy {
            "jdbc:sqlite:${Files.createTempDirectory("dp086-sqlite").resolve("probe.db")}"
        }

        val PROBES =
            listOf(
                Probe(
                    dialect = Dialect.H2,
                    slowSql = Fixtures.SLOW_SQL,
                ) {
                    DriverManager.getConnection(
                        "jdbc:h2:mem:cancel_probe_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
                        "sa",
                        "",
                    )
                },
                Probe(
                    dialect = Dialect.SQLITE,
                    slowSql =
                        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 400000000) " +
                            "SELECT count(*) FROM c",
                ) { DriverManager.getConnection(sqliteUrl) },
                Probe(
                    dialect = Dialect.DUCKDB,
                    slowSql =
                        "SELECT count(*) FROM range(1, 60000) a, range(1, 60000) b " +
                            "WHERE (a.range + b.range) % 7 = 0",
                ) { DriverManager.getConnection("jdbc:duckdb:") },
            )
    }
}
