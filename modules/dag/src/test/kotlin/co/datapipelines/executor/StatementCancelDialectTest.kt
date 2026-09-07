package co.datapipelines.executor

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 086 A2 — `Statement.cancel()` **measured**, per dialect, on the pinned drivers.
 *
 * dag-executor.md §8.3.2 says two things about cancellation that only a measurement can support,
 * and both were previously taken on trust:
 *
 *  1. **Which drivers honour `Statement.cancel()` at all** while a statement is in flight. The
 *     doc's table is generated from [IN_FLIGHT] here, not from any driver's documentation — a
 *     vendor's word about its own cancel path is not evidence about the version we pin.
 *  2. **What a driver does with a cancel that arrives before it has a command to cancel.** This is
 *     the whole premise of round 086: `CancellationTest`'s KDoc asserted, from one observation of
 *     H2, that such a cancel is "silently dropped". [PRE_EXECUTE] measures it on every dialect,
 *     because the executor's fix is only necessary if the premise is true, and only *sufficient*
 *     if it is true everywhere rather than on H2 alone.
 *
 * ## Why the pre-execute probe cancels first and executes second
 *
 * The real window is a race — the node thread is descheduled between registering the statement and
 * entering the driver — and racing it in a test would measure the box's scheduler, not the driver.
 * Issuing `cancel()` to completion *before* `executeQuery` is the same window taken to its limit:
 * if a driver latches a pending cancel, it must honour this one; if it drops cancels for a
 * statement with no registered command, it drops this one. Either way the answer is the driver's,
 * and it is deterministic.
 *
 * ## Bounding a "not honoured" outcome
 *
 * Every probe's SQL runs far longer than [QUERY_TIMEOUT_SECONDS], and every statement carries that
 * as its `queryTimeout` — the same backstop production sets from
 * `datapipelines.executor.node-query-timeout-seconds`. So a dropped cancel costs the timeout, not
 * the query, and the two outcomes are told apart by *when* the call returned, not by waiting for a
 * query that may never end.
 */
class StatementCancelDialectTest {
    @Test
    fun `every supported dialect's Statement cancel behaviour is what section 8_3_2 documents`() {
        val measured = PROBES.associate { probe -> probe.dialect to probe.measure() }

        // Printed so the §8.3.2 table can be regenerated from a run rather than re-derived by hand.
        // The millis are the evidence: HONOURED lands in the tens, DROPPED at the queryTimeout.
        measured.forEach { (dialect, o) ->
            println(
                "086 A2 | $dialect | in-flight=${o.inFlight} (${o.inFlightMs} ms) | pre-execute=${o.preExecute} (${o.preExecuteMs} ms)",
            )
        }

        measured.mapValues { (_, outcome) -> outcome.inFlight } shouldBe IN_FLIGHT
        measured.mapValues { (_, outcome) -> outcome.preExecute } shouldBe PRE_EXECUTE
    }

    // ------------------------------------------------------------------ the measurement

    /** What a `cancel()` did to a statement, decided by whether the driver call returned early. */
    private enum class CancelOutcome {
        /** The driver stopped the query — the call returned well inside its own `queryTimeout`. */
        HONOURED,

        /** The cancel had no effect; the statement ran on until `queryTimeout` (or to completion). */
        DROPPED,
    }

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
        fun measure(): Outcome = Outcome(inFlightMs = measureInFlight(), preExecuteMs = measurePreExecute())

        /**
         * The documented path: the statement is demonstrably executing, then `cancel()` arrives.
         *
         * The executing thread signals [running] *before* it blocks, and the canceller then waits
         * [SETTLE_MS] on top — the same "wait until the statement is really in flight" the
         * cancellation suite does, and the only honest way to separate a driver that cannot cancel
         * from one that was asked too early.
         */
        private fun measureInFlight(): Long {
            val pool = Executors.newSingleThreadExecutor()
            try {
                connect().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                        val running = CountDownLatch(1)
                        val query =
                            pool.submit<Long> {
                                running.countDown()
                                runQuery(statement)
                            }
                        running.await()
                        Thread.sleep(SETTLE_MS)
                        runCatching { statement.cancel() }
                        return query.get(RESULT_WAIT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }

        /** The 086 window at its limit: the cancel is complete before the driver is entered. */
        private fun measurePreExecute(): Long =
            connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                    runCatching { statement.cancel() }
                    runQuery(statement)
                }
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
         * The bound on a dropped cancel. Deliberately short: it is the only thing standing between
         * this suite and a query that runs for minutes on five engines.
         */
        const val QUERY_TIMEOUT_SECONDS = 5

        /** Comfortably above [QUERY_TIMEOUT_SECONDS], so a *hung* probe fails rather than hangs. */
        const val RESULT_WAIT_SECONDS = 60L

        /** Long enough for the driver to hold a registered command — the in-flight probe's premise. */
        const val SETTLE_MS = 750L

        /**
         * Halfway between "cancelled instantly" and "ran to its `queryTimeout`". A driver that
         * honoured the cancel returns in tens of milliseconds; one that dropped it returns at
         * 5 000 ms. Nothing measured lands anywhere near this line.
         */
        const val EARLY_RETURN_MS = 2_500L

        const val NANOS_PER_MILLI = 1_000_000L

        fun classify(elapsedMs: Long): CancelOutcome = if (elapsedMs < EARLY_RETURN_MS) CancelOutcome.HONOURED else CancelOutcome.DROPPED

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
                Dialect.POSTGRES to CancelOutcome.HONOURED,
                Dialect.MYSQL to CancelOutcome.HONOURED,
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
                Dialect.POSTGRES to CancelOutcome.DROPPED,
                Dialect.MYSQL to CancelOutcome.DROPPED,
                Dialect.SQLITE to CancelOutcome.DROPPED,
                Dialect.DUCKDB to CancelOutcome.DROPPED,
            )

        private val mysql: MySQLContainer<*> by lazy {
            MySQLContainer<Nothing>("mysql:8.4").apply {
                withReuse(true)
                start()
            }
        }

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
                    dialect = Dialect.POSTGRES,
                    slowSql =
                        "SELECT count(*) FROM generate_series(1, 40000) a, generate_series(1, 40000) b " +
                            "WHERE (a + b) % 7 = 0",
                ) { SharedPostgres.postgres.let { DriverManager.getConnection(it.jdbcUrl, it.username, it.password) } },
                Probe(
                    dialect = Dialect.MYSQL,
                    // BENCHMARK is MySQL's own long-running scalar; a recursive CTE would need
                    // cte_max_recursion_depth raised, which is a server setting this must not touch.
                    slowSql = "SELECT BENCHMARK(200000000, SHA1('086'))",
                ) { mysql.let { DriverManager.getConnection(it.jdbcUrl, it.username, it.password) } },
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

        @JvmStatic
        @AfterAll
        fun stopContainers() {
            // Reuse keeps it alive when the developer opted in; otherwise Ryuk reaps it at exit.
            if (!mysql.isShouldBeReused) mysql.stop()
        }
    }
}
