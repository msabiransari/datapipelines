package co.datapipelines.datasources.pooling

import java.time.Duration

/**
 * #818d (dp-lake record §10 RULING 4 amended, item 6) — who closes a pool the reaper's pass has taken off its
 * retiring queue. The pass DECIDES (a clock comparison, the queue walk) and hands each close here; it never waits
 * on one, so neither a slow Hikari shutdown nor a stuck driver close can delay the next pass, and the lifetime rule
 * needs no close-time term for the pass.
 *
 * [SYNCHRONOUS] closes on the caller's thread: the library default, for callers that wire no closer (this module's
 * own tests, a plain library caller). The assembling layer injects one that runs each close on a dedicated thread
 * under that pool's own deadline, [deadlineFor] (`DeadlinePoolCloser`, the `dp-pool-close` threads; #840). Whatever the closer, the close
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
         * The documented DEFAULT per-close deadline: [deadlineFor] at a fresh `HikariConfig`'s `connectionTimeout`
         * (30 s) for a pool with no reader pins, `30 + 3 × 10 + 5 = 65`. Since #840 the closer reads each pool's own
         * deadline, never this constant; `ReaperDelayDerivationTest` keeps pinning that the default's derivation
         * still lands here.
         */
        const val DEADLINE_SECONDS = 65L

        /**
         * #840 — the deadline terms that do not depend on the pool: [HIKARI_SHUTDOWN_WAITS] ×
         * [HIKARI_SHUTDOWN_WAIT_SECONDS] plus [OWNER_RELEASE_MARGIN_SECONDS], `3 × 10 + 5 = 35` s.
         */
        val FIXED_CLOSE_TERMS: Duration =
            Duration.ofSeconds(HIKARI_SHUTDOWN_WAITS * HIKARI_SHUTDOWN_WAIT_SECONDS + OWNER_RELEASE_MARGIN_SECONDS)

        /**
         * #840 — one pool's close deadline, from its own [ConnectionPool.connectionTimeout]: Hikari's bound for THAT
         * pool's settings (`shutdown()`'s first wait is `addConnectionExecutor.awaitTermination(loginTimeout)`, the
         * login timeout Hikari sets from `connectionTimeout`), plus [fixedTerms] ([FIXED_CLOSE_TERMS]), plus
         * [releaseBound]: what else the close runs inside the same deadline after the owner release. `65` s at 30 s,
         * `155` s at 120 s, so a datasource configured with a longer `connectionTimeout` is no longer reported stuck
         * while Hikari is still inside a wait its own settings admit.
         *
         * [releaseBound], the fourth term, is a MANAGED generation's reader-pin release. Its close is wrapped
         * (`ConnectionPoolManager.buildProtected`): `pool.close()` and then, once no physical handle remains,
         * `ManagedGenerationPins.unpin`, which in production (`ReaderLeaseKeeper.unpin` → `LakeLeases.release`) checks a
         * connection out of the METADATA pool (up to that pool's `connectionTimeout`) and runs one DELETE bounded at
         * `LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS` (statement timeout and socket timeout). That is not
         * [OWNER_RELEASE_MARGIN_SECONDS]'s five seconds, which cover one native disconnect; it is a named term, derived
         * by the assembling layer from the metadata pool it actually runs on. Zero for every other pool, which unpins
         * nothing.
         */
        fun deadlineFor(
            connectionTimeout: Duration,
            releaseBound: Duration = Duration.ZERO,
            fixedTerms: Duration = FIXED_CLOSE_TERMS,
        ): Duration = connectionTimeout.plus(fixedTerms).plus(releaseBound)
    }
}
