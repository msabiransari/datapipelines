package co.datapipelines.staging

import kotlinx.coroutines.sync.Semaphore
import java.sql.Connection
import java.util.UUID

/**
 * The leases of one DuckDB staging database (staging.md §8.5, §9).
 *
 * Unlike H2's pool there is nothing to recycle: a lease is a fresh `duplicate()` of the locked root
 * connection, closed when the lease returns. A duplicate shares the root's database and inherits its
 * locked configuration (measured, lane #795 probe 12), and its close takes every piece of session
 * state with it — temporary tables, prepared statements, `search_path`, an open transaction (rolled
 * back) — so "session reset per lease" (§9.2) is a property of the connection's lifetime, not a list
 * of statements to replay. A duplicate costs about 12 µs to open and close (probe 16).
 *
 * Admission is a coroutine [Semaphore] of [maxConnections] permits: a caller past the cap suspends
 * and is cancellable, as on H2.
 *
 * ## Close and the last lease out
 *
 * [close] refuses new leases at once. If no lease is outstanding it runs [finalize] — close the root,
 * delete the files, release the reservation — before returning; otherwise the LAST lease to return
 * runs it. `close()` therefore never waits for a lease still inside the driver (§6). The driver can
 * stop one: `Statement.cancel()` interrupts the lease's connection while that statement executes
 * (measured, lane #795 round two: a runaway aggregate stopped within milliseconds), and its query
 * timeout does the same on the driver's own timer. The pool holds no statement; [DuckDbStaging]
 * cancels the ones it created before it closes the pool. `DuckDBConnection.abort` stays unsupported.
 * [finalize] runs exactly once.
 */
internal class DuckDbLeasePool(
    val executionId: UUID,
    val maxConnections: Int,
    /** Opens one lease's connection: a `duplicate()` of the locked root. */
    private val open: () -> Connection,
    /** Runs exactly once, when the pool is closed and no lease is outstanding. Must not throw. */
    private val finalize: () -> Unit,
) {
    private val admission = Semaphore(maxConnections)
    private val lock = Any()
    private var state = State.OPEN
    private var outstanding = 0
    private var peak = 0

    /** The most leases ever outstanding at once — a measurement for tests, never a decision input. */
    val peakActiveLeases: Int get() = synchronized(lock) { peak }

    /** True once [close] ran and the last lease returned, i.e. once [finalize] ran. */
    val isFinalized: Boolean get() = synchronized(lock) { state == State.FINALIZED }

    /**
     * Runs [block] on a connection of its own, then closes it. [wrap] decorates what the block sees
     * (the author gate) and never what is closed. Every exit releases the permit; a failed close of
     * the duplicate never replaces the block's own failure.
     */
    suspend fun <T> lease(
        wrap: (Connection) -> Connection,
        block: suspend (Connection) -> T,
    ): T {
        synchronized(lock) { refuseUnlessOpen() }
        admission.acquire()
        try {
            val connection = checkout()
            var failure: Throwable? = null
            try {
                return block(wrap(connection))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                failure = e
                throw e
            } finally {
                checkin(connection, failure)
            }
        } finally {
            admission.release()
        }
    }

    /**
     * Refuses new leases; finalizes now when none is outstanding, else on the last return.
     *
     * @return the leases still outstanding when it ran (0 means [finalize] ran on this thread).
     */
    fun close(): Int {
        val runNow: Boolean
        val stillOut: Int
        synchronized(lock) {
            if (state != State.OPEN) return 0
            stillOut = outstanding
            runNow = outstanding == 0
            state = if (runNow) State.FINALIZED else State.CLOSING
        }
        if (runNow) finalize()
        return stillOut
    }

    /** Reserves a slot under the lock, opens outside it, and re-checks the state the open raced. */
    private fun checkout(): Connection {
        synchronized(lock) {
            refuseUnlessOpen()
            outstanding++
            if (outstanding > peak) peak = outstanding
        }
        val connection =
            try {
                open()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                giveBack()
                throw e
            }
        val closedMeanwhile = synchronized(lock) { state != State.OPEN }
        if (closedMeanwhile) {
            checkin(connection, failure = null, rethrow = false)
            error(closedMessage())
        }
        return connection
    }

    private fun checkin(
        connection: Connection,
        failure: Throwable?,
        rethrow: Boolean = true,
    ) {
        try {
            connection.close()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The block's own failure is what the node reports; a failed return rides along.
            if (failure != null) {
                failure.addSuppressed(e)
            } else if (rethrow) {
                throw e
            }
        } finally {
            giveBack()
        }
    }

    /** Returns a slot; the last return after [close] runs [finalize]. */
    private fun giveBack() {
        val runNow =
            synchronized(lock) {
                outstanding--
                if (state == State.CLOSING && outstanding == 0) {
                    state = State.FINALIZED
                    true
                } else {
                    false
                }
            }
        if (runNow) finalize()
    }

    /** Under the lock. */
    private fun refuseUnlessOpen() {
        if (state != State.OPEN) error(closedMessage())
    }

    private fun closedMessage() = "staging database for execution $executionId is closed"

    private enum class State { OPEN, CLOSING, FINALIZED }
}
