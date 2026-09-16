package co.datapipelines.staging

import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The bounded pool of **operational** connections behind one execution's [H2Staging]
 * (staging.md §9, #118): at most [maxConnections] physical sessions to the execution's named
 * in-memory database, each leased to exactly one caller at a time.
 *
 * ## Why not H2's own `JdbcConnectionPool`
 *
 * Read against the pinned driver (2.3.232, `org.h2.jdbcx.JdbcConnectionPool`), and each point
 * is the reason a thin adapter over it would have been larger than this class:
 *
 * - **Checkout busy-polls.** An exhausted `getConnection()` spins on `Thread.sleep(1)` for up to
 *   the login timeout, on the executor's bounded dispatcher thread, and then throws `08001`.
 *   Admission here is a coroutine [Semaphore]: a caller past the cap **suspends**, is cancellable
 *   by its node/execution deadline, and frees its thread for the lease holders it is waiting on.
 * - **Its reset is partial and silent.** The pooled handle's `close()` does `rollback()` +
 *   `setAutoCommit(true)` and swallows any `SQLException`, then recycles the connection; schema,
 *   variables, temporary tables and isolation ride along to the next borrower. [H2SessionReset]
 *   restores the supported defaults, and a failed reset discards the connection instead.
 * - **No discard, no ownership.** Nothing marks a connection broken, `connectionErrorOccurred`
 *   is empty, and `dispose()` closes only *idle* connections — an active one is closed on its
 *   return with no way to tell whether anyone still holds it. This class models every session
 *   state explicitly, which is what a close racing an abandoned JDBC call needs.
 *
 * What remains is small and execution-scoped: one semaphore, one idle stack, one counter set,
 * and no threads of its own. It is not a general pool — no validation queries, no max lifetime,
 * no eviction — because the database it fronts lives exactly as long as this object does.
 *
 * ## The ownership invariant (146b)
 *
 * Every physical session is, at every instant, in exactly one of these states, all counted
 * under one metadata lock that never covers JDBC, a callback, a suspension or a wait:
 *
 * - **opening** — a slot reserved by a checkout whose opener is in flight;
 * - **idle** — on the stack, owned by the pool;
 * - **leased** — owned by exactly one [lease] call, from checkout to the end of its `finally`;
 * - **guardian** — a doomed session (its reset failed) that is the database's LAST holder and
 *   therefore stays open until its replacement has been opened — or the pool has been found
 *   closed or lost;
 * - **closing** — its physical close is in progress; it holds nothing for anyone.
 *
 * `physical` counts every session from reservation to the end of its physical close. Three
 * consequences the review's counterexamples demanded:
 *
 * 1. **A reservation is counted before the lock is released**, and every open — checkout or
 *    replacement — is reconciled with the pool's state under the lock *after* it returns and
 *    *before* anything is published or any callback runs. A late open (the pool closed while
 *    the driver was connecting) is physically closed by the opener's own thread and never
 *    handed out; a late checkout raises the same "closed" refusal an early one does.
 * 2. **Retirement decisions are serialized and count only real holders.** A failed reset
 *    closes its session at once only if another *idle*, *leased* or *guardian* session exists —
 *    never on the strength of an in-flight open, which may not have connected yet. Otherwise the
 *    session becomes the guardian, opens its replacement outside the lock, and closes only after
 *    the replacement is adopted. Two concurrent failed resets therefore agree: the first to
 *    decide sees the other still leased and closes; the second sees nobody and guards.
 * 3. **Continuity failure is explicit.** If a guardian's replacement fails to open while nothing
 *    else holds the database, the pool becomes LOST: every later lease is refused with a message
 *    naming the loss, no opener is invoked again, and the guardian is closed. A restricted
 *    opener could not recreate the database anyway; a privileged one must not be allowed to
 *    silently connect to a fresh, empty one.
 *
 * ## Close versus a session still inside JDBC
 *
 * [close] flips the state to closing, refuses new leases, closes every idle connection and
 * returns — it never waits for an outstanding lease, opening or guardian, because the executor's
 * deadline path can legitimately abandon a body that is still blocked in the driver, and waiting
 * would be the unbounded shutdown wait §6 forbids. Every such late session finds the pool closed
 * on its own return path and closes itself; the LAST one out runs the deferred cleanup sweep
 * first when it is usable. So terminal cleanup happens exactly once, on whichever side is last,
 * and an in-flight JDBC call is never reset, reused or closed under its owner. A driver that
 * never returns keeps its one connection — and with it the database — alive; that residual is
 * reported, not hidden (§6).
 */
internal class H2ConnectionPool(
    private val executionId: UUID,
    initial: Connection,
    /** Opens one more restricted operational connection to the same database (§9.5). */
    private val opener: () -> Connection,
    /** The cap on usable physical connections, the bootstrap-handoff one included. */
    val maxConnections: Int,
) {
    private val admission = Semaphore(maxConnections)
    private val defaults = SessionDefaults.capture(initial)
    private val lock = Any()

    // ---- guarded by [lock] ----
    private val idle = ArrayDeque<Connection>().apply { addLast(initial) }
    private var leased = 0
    private var opening = 0
    private var guardians = 0
    private var physical = 1
    private var peak = 0
    private var opened = 1
    private var discarded = 0
    private var state = State.OPEN
    private var lossCause: String? = null
    private var deferredSweep: ((Connection) -> Unit)? = null

    // ---- observability, for tests and measurements; never a decision input ----
    private val waitNanos = AtomicLong()
    private val waiters = AtomicLong()

    /** Leases inside their callback right now. */
    val activeLeases: Int get() = synchronized(lock) { leased }

    /** The most leases ever inside their callbacks at one instant — the overlap evidence. */
    val peakActiveLeases: Int get() = synchronized(lock) { peak }

    /** Physical sessions from reservation to the end of their close (idle, leased, opening, guardian, closing). */
    val physicalConnections: Int get() = synchronized(lock) { physical }

    /** Physical connections ever opened, the bootstrap-handoff one included. */
    val physicalOpened: Int get() = synchronized(lock) { opened }

    /** Connections closed because their reset failed or they were found closed on return. */
    val discardedConnections: Int get() = synchronized(lock) { discarded }

    /** Callers currently suspended in admission. */
    val queuedWaiters: Int get() = waiters.get().toInt()

    /** Total time callers spent suspended in admission — the "waiting for a lease" figure. */
    val leaseWaitNanos: Long get() = waitNanos.get()

    val isClosed: Boolean get() = synchronized(lock) { state == State.CLOSED }

    /** True once the database's continuity could not be preserved (a guardian's replacement failed). */
    val isLost: Boolean get() = synchronized(lock) { state == State.LOST }

    /**
     * Runs [block] with exclusive ownership of one physical connection, then returns the
     * connection sanitised per [kind]. Admission suspends when the cap is reached and is
     * cancellable; every exit — value, exception, cancellation, a failed checkout, a failed
     * return — releases the permit, and a cleanup failure never replaces the block's own.
     *
     * The block must not lease again (at capacity one that deadlocks; above it, it holds a
     * connection idle while waiting for another) and must not let the connection or anything
     * derived from it escape.
     */
    suspend fun <T> lease(
        kind: LeaseKind,
        block: suspend (Connection) -> T,
    ): T {
        val started = System.nanoTime()
        waiters.incrementAndGet()
        try {
            admission.acquire()
        } finally {
            waiters.decrementAndGet()
            waitNanos.addAndGet(System.nanoTime() - started)
        }
        try {
            return leaseWithPermit(kind, block)
        } finally {
            admission.release()
        }
    }

    /** The permit is held by the caller; this owns the connection from checkout to release. */
    private suspend fun <T> leaseWithPermit(
        kind: LeaseKind,
        block: suspend (Connection) -> T,
    ): T {
        val connection = checkout()
        var failure: Throwable? = null
        try {
            return block(connection)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            failure = e
            throw e
        } finally {
            releaseAfter(connection, kind, failure)
        }
    }

    /**
     * The `finally` of a lease. A return that itself fails must not replace the operation's own
     * exception (the node reports what it hit, not what cleanup hit) — it is attached as a
     * suppressed exception; with no original failure, the cleanup failure is the result.
     */
    private fun releaseAfter(
        connection: Connection,
        kind: LeaseKind,
        failure: Throwable?,
    ) {
        try {
            release(connection, kind)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (failure == null) throw e
            failure.addSuppressed(e)
        }
    }

    /**
     * Closes the pool: no further leases, every idle connection closed, [sweep] run once on a
     * connection this pool owns when no session is outstanding — or deferred to the last late
     * arrival (lease return, late open, guardian) when one is. Idempotent and non-throwing (§3.4).
     */
    fun close(sweep: (Connection) -> Unit = {}): CloseOutcome {
        val toClose: List<Connection>
        val sweepOn: Connection?
        val outstanding: Int
        synchronized(lock) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return CloseOutcome(leasesOutstanding = leased, sweepRan = false, alreadyClosed = true)
            }
            state = State.CLOSING
            outstanding = leased + opening + guardians
            sweepOn = if (outstanding == 0) idle.firstOrNull() else null
            if (sweepOn == null && outstanding > 0) deferredSweep = sweep
            toClose = idle.toList()
            idle.clear()
        }
        // Outside the lock: the sweep is JDBC. Leases are refused from here on, so no borrower
        // can race it, and the idle stack is already empty so no one can draw a connection.
        val swept = sweepOn?.let { runSweep(it, sweep) } ?: false
        toClose.forEach { closePhysical(it) }
        synchronized(lock) {
            physical -= toClose.size
            state = State.CLOSED
        }
        return CloseOutcome(leasesOutstanding = outstanding, sweepRan = swept, alreadyClosed = false)
    }

    /** What [close] found and did — the executor logs it; tests assert on it. */
    data class CloseOutcome(
        /** Sessions still owned by someone at close time: leases, opens in flight and guardians. */
        val leasesOutstanding: Int,
        val sweepRan: Boolean,
        val alreadyClosed: Boolean,
    )

    // ------------------------------------------------------------ checkout

    private fun checkout(): Connection {
        synchronized(lock) {
            refuseUnlessOpen()
            val reused = idle.removeLastOrNull()
            if (reused != null) {
                admit()
                return reused
            }
            // Reserved BEFORE the lock is released: close() sees this open in flight and defers.
            opening++
            physical++
        }
        val connection = openReserved()
        val late =
            synchronized(lock) {
                opening--
                opened++
                if (state == State.OPEN) {
                    admit()
                    false
                } else {
                    true
                }
            }
        if (late) {
            // The pool closed while the driver was connecting: never published, never used.
            closeLate(connection, usable = true)
            synchronized(lock) { refuseUnlessOpen() }
        }
        return connection
    }

    private fun admit() {
        leased++
        if (leased > peak) peak = leased
    }

    /** Under the lock. */
    private fun refuseUnlessOpen() {
        when (state) {
            State.OPEN -> Unit
            State.LOST -> error("staging database for execution $executionId was lost: $lossCause")
            State.CLOSING, State.CLOSED -> error("staging pool for execution $executionId is closed")
        }
    }

    /** Opens a connection whose slot [checkout] already reserved; a failed open gives the slot back. */
    private fun openReserved(): Connection {
        var ok = false
        try {
            return opener().also { ok = true }
        } finally {
            if (!ok) {
                synchronized(lock) {
                    opening--
                    physical--
                }
            }
        }
    }

    // ------------------------------------------------------------ release

    /**
     * The lease's `finally`: sanitise, then decide under the lock — recycle, close as a late
     * quarantined return, close because another holder exists, or guard the database until a
     * replacement is open. Every JDBC call here is outside the lock, on the holder's thread.
     */
    private fun release(
        connection: Connection,
        kind: LeaseKind,
    ) {
        val usable = sanitise(connection, kind)
        val decision =
            synchronized(lock) {
                leased--
                when {
                    usable && state == State.OPEN -> {
                        idle.addLast(connection)
                        Decision.RECYCLED
                    }

                    usable -> {
                        Decision.CLOSE_LATE
                    }

                    idle.size + leased + guardians > 0 || state != State.OPEN -> {
                        Decision.CLOSE_NOW
                    }

                    else -> {
                        guardians++
                        Decision.GUARD
                    }
                }
            }
        when (decision) {
            Decision.RECYCLED -> Unit
            Decision.CLOSE_LATE -> closeLate(connection, usable = true)
            Decision.CLOSE_NOW -> discardNow(connection)
            Decision.GUARD -> guardAndReplace(connection)
        }
    }

    private enum class Decision { RECYCLED, CLOSE_LATE, CLOSE_NOW, GUARD }

    private fun sanitise(
        connection: Connection,
        kind: LeaseKind,
    ): Boolean =
        try {
            if (connection.isClosed) {
                false
            } else {
                H2SessionReset.reset(connection, defaults, kind)
                true
            }
        } catch (e: SQLException) {
            LOG.warn("tempdb connection reset failed for execution {} (SQLState {}); discarding it", executionId, e.sqlState)
            false
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // A driver fault of any shape during the reset means the session cannot be trusted;
            // it is discarded like a refused reset, and the accounting below still runs.
            LOG.warn("tempdb connection reset failed for execution {} ({}); discarding it", executionId, e.javaClass.simpleName)
            false
        }

    /** Drops an unusable session that is not the database's last holder. */
    private fun discardNow(connection: Connection) {
        closePhysical(connection)
        synchronized(lock) {
            physical--
            discarded++
        }
    }

    /**
     * The doomed session is the database's LAST holder: open its successor first, adopt it under
     * the lock if the pool is still open, and only then close the guardian. The cap on usable
     * connections holds throughout — the guardian is not usable — and the process carries one
     * extra dead session for the length of one open.
     */
    private fun guardAndReplace(guardian: Connection) {
        val replacement = runCatching(opener)
        val lateReplacement = adoptOrLoseUnderLock(replacement)
        replacement.exceptionOrNull()?.let {
            LOG.warn("tempdb replacement connection failed for execution {}: {}", executionId, it.message)
        }
        if (isLost) {
            LOG.error("tempdb database for execution {} is LOST: its last connection failed and no replacement opened", executionId)
        }
        // The guardian closes AFTER its successor exists (or after the pool decided nothing can
        // hold the database any more) — never before.
        closePhysical(guardian)
        synchronized(lock) { physical-- }
        lateReplacement?.let { closeLate(it, usable = true) }
    }

    /**
     * Reconciles a guardian's replacement with the pool's state. Returns the replacement when it
     * opened after close and must be closed by the caller; null when it was adopted or never
     * opened. Marks the pool LOST when nothing holds the database any more.
     */
    private fun adoptOrLoseUnderLock(replacement: Result<Connection>): Connection? =
        synchronized(lock) {
            guardians--
            discarded++
            val connection = replacement.getOrNull()
            if (connection != null) {
                opened++
                physical++
            }
            when {
                connection != null && state == State.OPEN -> {
                    idle.addLast(connection)
                    null
                }

                connection != null -> {
                    connection
                }

                else -> {
                    if (state == State.OPEN && idle.size + leased + guardians == 0) {
                        state = State.LOST
                        lossCause = replacement.exceptionOrNull()?.message ?: "replacement connection could not be opened"
                    }
                    null
                }
            }
        }

    /**
     * Closes a session the pool no longer wants because it is closing or closed: a quarantined
     * lease's late return, a checkout that connected after close, a guardian's late replacement.
     * The last such session out runs the deferred sweep first, when it can.
     */
    private fun closeLate(
        connection: Connection,
        usable: Boolean,
    ) {
        val sweep =
            synchronized(lock) {
                val last = leased + opening + guardians == 0
                if (last && usable) deferredSweep.also { deferredSweep = null } else null
            }
        sweep?.let { runSweep(connection, it) }
        closePhysical(connection)
        synchronized(lock) { physical-- }
    }

    private fun runSweep(
        connection: Connection,
        sweep: (Connection) -> Unit,
    ): Boolean =
        try {
            sweep(connection)
            true
        } catch (e: SQLException) {
            // pipeline.staging.cleanup_failed — logged, never rethrown from close() (§3.4).
            LOG.warn("tempdb table cleanup failed for execution {}: {}", executionId, e.message)
            false
        }

    /** Never throws: a close that refuses must not skip the accounting that follows it. */
    private fun closePhysical(connection: Connection) {
        try {
            connection.close()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            LOG.warn("tempdb connection close failed for execution {}: {}", executionId, e.message)
        }
    }

    private enum class State { OPEN, LOST, CLOSING, CLOSED }

    private companion object {
        val LOG = LoggerFactory.getLogger(H2ConnectionPool::class.java)
    }
}
