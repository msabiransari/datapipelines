package co.datapipelines.templates

import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A race FORCED, never timed (MISTAKES.md: "a coverage floor that depends on winning a race"): the
 * winner's statement runs on a raw connection whose transaction stays OPEN; the contender starts on
 * the pool; the helper waits until Postgres itself reports the contender BLOCKED by the winner
 * (`pg_blocking_pids`), then commits the winner and hands back the contender's outcome.
 *
 * The wait polls `pg_stat_activity` with a FRESH autocommit query each time, on its own connection:
 * read inside one open transaction it is a snapshot frozen at that transaction's start and would
 * watch nothing (the MISTAKES.md corollary). The poll is bounded; its bound is a failure, never a pass.
 *
 * The templates twin of `modules/parameters`' `ForcedRace` (#276): test source sets do not cross
 * modules, and the parameter-set repository copies this module's draft shape, so the two helpers
 * guard the same race on the two tables.
 */
internal object ForcedRace {
    private const val MAX_WAIT_MS = 30_000L
    private const val POLL_MS = 20L

    fun holdingThenCommitting(
        hold: (Connection) -> Unit,
        contender: () -> Any?,
    ): Result<Any?> =
        rawConnection().use { winner ->
            winner.autoCommit = false
            hold(winner)
            val winnerPid =
                winner.createStatement().use { s ->
                    s.executeQuery("SELECT pg_backend_pid()").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val outcome = executor.submit(Callable { runCatching { contender() } })
                awaitBlockedBy(winnerPid)
                winner.commit()
                outcome.get(MAX_WAIT_MS, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }
        }

    private fun rawConnection(): Connection =
        SharedPostgres.postgres.let { DriverManager.getConnection(it.jdbcUrl, it.username, it.password) }

    private fun awaitBlockedBy(pid: Int) {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        rawConnection().use { observer ->
            observer.autoCommit = true
            while (System.currentTimeMillis() < deadline) {
                if (blockedBy(observer, pid) > 0) return
                Thread.sleep(POLL_MS)
            }
        }
        error("the contender never blocked on the winner (pid $pid) within ${MAX_WAIT_MS}ms — the race was not forced")
    }
}

/** Sessions blocked by [pid] right now — a fresh autocommit statement each time, so never a frozen snapshot. */
private fun blockedBy(
    observer: Connection,
    pid: Int,
): Int =
    observer.prepareStatement("SELECT COUNT(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))").use { s ->
        s.setInt(1, pid)
        s.executeQuery().use {
            it.next()
            it.getInt(1)
        }
    }

/** The failure a forced race's contender ended with, as [T]. */
internal inline fun <reified T : Throwable> Result<Any?>.shouldBeFailure(): T = exceptionOrNull().shouldBeInstanceOf<T>()
