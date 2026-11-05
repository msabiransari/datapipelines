package co.datapipelines.datasources.pooling

/**
 * #818d (dp-lake record §10 RULING 4 amended, item 6) — who closes a pool the reaper's pass has taken off its
 * retiring queue. The pass DECIDES (a clock comparison, the queue walk) and hands each close here; it never waits
 * on one, so neither a slow Hikari shutdown nor a stuck driver close can delay the next pass, and the lifetime rule
 * needs no close-time term for the pass.
 *
 * [SYNCHRONOUS] closes on the caller's thread: the library default, for callers that wire no closer (this module's
 * own tests, a plain library caller). The assembling layer injects one that runs each close on a dedicated thread
 * under [DEADLINE_SECONDS] (`DeadlinePoolCloser`, the `dp-pool-close` threads). Whatever the closer, the close
 * itself is [ConnectionPool.close]: for a LAKE generation that is interrupt-first ([PoolInstanceOwner.interruptReaders]),
 * then Hikari's shutdown, then the owner release — so a reader stops on the interrupt, never behind Hikari's waits.
 *
 * A `fun interface` so a test can pass a lambda; [ConnectionPoolManager] takes it BEFORE its last parameter on
 * purpose (see that parameter's KDoc).
 */
fun interface PoolCloser {
    /** Closes [pool], now or on another thread. Never throws: [ConnectionPool.close] contains its own failures. */
    fun close(pool: ConnectionPool)

    companion object {
        /** Closes on the caller's thread — the library default; application shutdown's path regardless of the closer. */
        val SYNCHRONOUS: PoolCloser = PoolCloser { it.close() }

        /**
         * HikariCP 6.3.3's fixed shutdown waits, each 10 s: `HikariPool.shutdown()` loops `abortActiveConnections`
         * while connections remain and less than 10 s have elapsed, then awaits the assassin executor 10 s, then the
         * close-connection executor 10 s (`javap -c -p com.zaxxer.hikari.pool.HikariPool` on the pinned jar:
         * `ldc2_w 10L` at offsets 188, 203 and 277; offset 236 is the finally twin of 203). There is no public
         * constant; this one carries the citation. `ReaperDelayDerivationTest` recomputes [DEADLINE_SECONDS] from it.
         */
        const val HIKARI_SHUTDOWN_WAIT_SECONDS = 10L

        /** How many [HIKARI_SHUTDOWN_WAIT_SECONDS] waits one `HikariPool.shutdown()` can run in sequence (above). */
        const val HIKARI_SHUTDOWN_WAITS = 3

        /**
         * The owner release's share of the deadline. By the time it runs, retire has refused every new duplicate and
         * interrupt-first has put every tracked handle into its own close, so the release waits on nothing: each
         * handle it finds is `ALREADY_CLOSED` or `IN_FLIGHT` (handed off, `TrackedDuplicate.releaseByGeneration`),
         * and the retained owner connection runs no statement, so its close is one native disconnect. Five seconds
         * covers that disconnect and the scheduling of a loaded box with a wide margin; a WARN past it is the signal
         * this margin exists to make meaningful, not a failure of the lifetime bound (the reader already stopped).
         */
        const val OWNER_RELEASE_MARGIN_SECONDS = 5L

        /**
         * The per-close deadline: Hikari's own bound for the shipped pool settings — `connectionTimeout` (30 s, a
         * fresh `HikariConfig`'s default, which bounds `shutdown()`'s first wait: `addConnectionExecutor
         * .awaitTermination(loginTimeout)`, the login timeout Hikari sets from `connectionTimeout`) plus the
         * [HIKARI_SHUTDOWN_WAITS] × [HIKARI_SHUTDOWN_WAIT_SECONDS] — plus [OWNER_RELEASE_MARGIN_SECONDS]:
         * `30 + 3 × 10 + 5 = 65`. A close still running past it logs one WARN `datasource.pool_close_stuck` and
         * counts `datapipelines.datasource.pool.close_stuck`; the thread is left to finish. A datasource whose own
         * `connectionTimeout` is longer can legitimately run past it and be reported: the deadline is a monitor on
         * the close, never on the reader, which interrupt-first stopped before Hikari's first wait.
         */
        const val DEADLINE_SECONDS = 65L
    }
}
