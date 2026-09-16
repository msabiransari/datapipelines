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
 *   return with no way to tell whether anyone still holds it. This class models open / closing /
 *   closed and the count of outstanding leases explicitly, which is what a close racing an
 *   abandoned JDBC call needs.
 *
 * What remains is small and execution-scoped: one semaphore, one idle stack, one counter set,
 * and no threads of its own. It is not a general pool — no validation queries, no max lifetime,
 * no eviction — because the database it fronts lives exactly as long as this object does.
 *
 * ## Ownership model
 *
 * - A physical connection is either **idle** (on the stack, owned by the pool) or **leased**
 *   (owned by exactly one [lease] call, from checkout to the end of its `finally`). No code path
 *   touches a leased connection from outside its lease — not the reset, not [close].
 * - The bootstrap-handoff connection is the first physical connection and counts toward the cap.
 *   It is not special afterwards: the database lives as long as *any* physical connection is
 *   open (§3.5), and the pool never closes its last usable connection while open — a doomed
 *   connection is replaced before it is closed (see [discard]).
 * - **Metadata only under the lock.** The `synchronized` sections cover counters and the idle
 *   stack; every JDBC call — opening, resetting, closing — runs outside them, on the caller's
 *   thread, so a slow driver cannot stall another lease's bookkeeping.
 *
 * ## Close versus a lease still inside JDBC
 *
 * [close] flips the state to closing, refuses new leases, closes every idle connection and
 * returns — it never waits for an outstanding lease, because the executor's deadline path can
 * legitimately abandon a body that is still blocked in the driver, and waiting for it would be
 * the unbounded shutdown wait §6 forbids. Such a lease is **quarantined**: its return finds the
 * pool closed and closes its own connection, running the deferred cleanup sweep first if it is
 * the last one out and the sweep could not run at close time. So terminal cleanup happens
 * exactly once, on whichever side is last, and an in-flight JDBC call is never reset, reused or
 * closed under its owner. A driver that never returns keeps its one connection — and with it
 * the database — alive; that residual is reported, not hidden (§6).
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
    private var physical = 1
    private var active = 0
    private var peak = 0
    private var opened = 1
    private var discarded = 0
    private var state = State.OPEN
    private var deferredSweep: ((Connection) -> Unit)? = null

    // ---- observability, for tests and measurements; never a decision input ----
    private val waitNanos = AtomicLong()
    private val waiters = AtomicLong()

    /** Leases inside their callback right now. */
    val activeLeases: Int get() = synchronized(lock) { active }

    /** The most leases ever inside their callbacks at one instant — the overlap evidence. */
    val peakActiveLeases: Int get() = synchronized(lock) { peak }

    /** Physical connections currently open (idle + leased). */
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

    /**
     * Runs [block] with exclusive ownership of one physical connection, then returns the
     * connection sanitised per [kind]. Admission suspends when the cap is reached and is
     * cancellable; every exit — value, exception, cancellation, a failed checkout — releases
     * both the permit and the connection.
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
        var connection: Connection? = null
        try {
            connection = checkout()
            return block(connection)
        } finally {
            connection?.let { release(it, kind) }
            admission.release()
        }
    }

    /**
     * Closes the pool: no further leases, every idle connection closed, [sweep] run once on a
     * connection this pool owns when no lease is outstanding — or deferred to the last late
     * return when one is. Idempotent and non-throwing (§3.4).
     */
    fun close(sweep: (Connection) -> Unit = {}): CloseOutcome {
        val toClose: List<Connection>
        val sweepOn: Connection?
        val outstanding: Int
        synchronized(lock) {
            if (state != State.OPEN) return CloseOutcome(leasesOutstanding = active, sweepRan = false, alreadyClosed = true)
            state = State.CLOSING
            outstanding = active
            sweepOn = if (active == 0) idle.firstOrNull() else null
            if (sweepOn == null && active > 0) deferredSweep = sweep
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
        val leasesOutstanding: Int,
        val sweepRan: Boolean,
        val alreadyClosed: Boolean,
    )

    private fun checkout(): Connection {
        val reused =
            synchronized(lock) {
                check(state == State.OPEN) { "staging pool for execution $executionId is closed" }
                val c = idle.removeLastOrNull()
                if (c == null) physical++ // reserved: the open below may still fail
                c
            }
        val connection = reused ?: openReserved()
        synchronized(lock) {
            if (reused == null) opened++
            active++
            if (active > peak) peak = active
        }
        return connection
    }

    /** Opens a connection whose slot [checkout] already reserved; a failed open gives the slot back. */
    private fun openReserved(): Connection {
        var opened = false
        try {
            return opener().also { opened = true }
        } finally {
            if (!opened) synchronized(lock) { physical-- }
        }
    }

    /**
     * The lease's `finally`: sanitise, then recycle — or discard when the connection is closed,
     * the reset failed, or the pool has closed meanwhile. Runs on the holder's thread; every
     * JDBC call here is outside the lock.
     */
    private fun release(
        connection: Connection,
        kind: LeaseKind,
    ) {
        val usable = sanitise(connection, kind)
        val closing: Boolean
        val lastOut: Boolean
        synchronized(lock) {
            active--
            closing = state != State.OPEN
            lastOut = closing && active == 0
            if (usable && !closing) {
                idle.addLast(connection)
                return
            }
        }
        if (!usable) {
            discard(connection)
            return
        }
        // Quarantined by a close that found this lease in flight: finish the deferred cleanup
        // exactly once, on the last connection out, then close it.
        if (lastOut) synchronized(lock) { deferredSweep.also { deferredSweep = null } }?.let { runSweep(connection, it) }
        closePhysical(connection)
        synchronized(lock) { physical-- }
    }

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
        }

    /**
     * Drops a connection the pool must not reuse. If it is the last physical connection while the
     * pool is open, a replacement is opened **first** — closing the last connection would destroy
     * the in-memory database (§3.5) with every staged table on it. The doomed connection is not
     * usable, so the cap on usable connections holds; the process does hold one extra dead
     * session for the length of one open.
     */
    private fun discard(connection: Connection) {
        val needsReplacement = synchronized(lock) { state == State.OPEN && physical == 1 }
        val replacement =
            if (needsReplacement) {
                runCatching(opener)
                    .onFailure {
                        LOG.warn("tempdb replacement connection failed for execution {}: {}", executionId, it.message)
                    }.getOrNull()
            } else {
                null
            }
        closePhysical(connection)
        synchronized(lock) {
            discarded++
            if (replacement != null) {
                opened++
                idle.addLast(replacement)
            } else {
                physical--
            }
        }
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

    private fun closePhysical(connection: Connection) {
        try {
            connection.close()
        } catch (e: SQLException) {
            LOG.warn("tempdb connection close failed for execution {}: {}", executionId, e.message)
        }
    }

    private enum class State { OPEN, CLOSING, CLOSED }

    private companion object {
        val LOG = LoggerFactory.getLogger(H2ConnectionPool::class.java)
    }
}
