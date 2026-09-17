package co.datapipelines.datasources.pooling

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * 152 (R152-2/3/4) — a physical duplicate of a LAKE generation's instance, registered with the
 * [LakeInstanceOwner] that created it until its closure is CONFIRMED.
 *
 * HikariCP holds it from `DataSource.getConnection()` on: through its own JDBC setup
 * (`isReadOnly`, auto-commit, validation), bag admission, every lease, and the close or abort
 * at shutdown. All of that passes through here unchanged — this wrapper adds one thing: it
 * keeps the generation's registry truthful about whether the raw connection is still open.
 *
 * ## Ownership state
 *
 * A handle is [State.OPEN], [State.CLOSING] (exactly one thread is inside the driver's close)
 * or [State.CLOSED] (the driver returned from `close()` without throwing — the only thing that
 * counts as closure). Unregistering happens ONLY on that transition to CLOSED, inside the
 * owner's registry monitor ([Registry.forget]). A driver that throws puts the handle back to
 * OPEN, still registered: the generation's release will try it again, and its counts say what
 * actually closed. `abort()` is passed through and never unregisters — JDBC's abort is allowed
 * to be asynchronous (and the pinned DuckDB driver refuses it outright, after which HikariCP
 * falls back to `close()`); only a confirmed close moves the state.
 *
 * ## The one hand-off
 *
 * If the generation's release finds a handle CLOSING — a borrower's close is mid-flight on
 * another thread — it does not double-close and it does not wait; it marks the handle
 * [transferred], and the in-flight closer, should its driver call fail, retries ONCE more before
 * giving up, because after the release nobody else will. Bounded: one extra attempt, no lock
 * held across driver work, no coordination beyond an atomic flag.
 *
 * `unwrap` still reaches the driver's connection (the identity tests and any driver-specific
 * caller see the real thing); `isWrapperFor` says so.
 */
internal class TrackedDuplicate(
    private val delegate: Connection,
    private val registry: Registry,
) : Connection by delegate {
    /** What a [TrackedDuplicate] needs from its owner: to be forgotten once closure is confirmed. */
    fun interface Registry {
        fun forget(handle: TrackedDuplicate)
    }

    internal enum class State { OPEN, CLOSING, CLOSED }

    private val state = AtomicReference(State.OPEN)

    /** Set by the generation's release when it found this handle mid-close and left it to that closer. */
    @Volatile
    private var transferred = false

    /** The driver's most recent refusal to close this handle — what the owner's release reports for the residual. */
    @Volatile
    var lastCloseFailure: SQLException? = null
        private set

    override fun close() = closeOnce(finalAttempt = false)

    /**
     * JDBC's abort is asynchronous by contract, so nothing here is treated as closure; whatever
     * the driver does, the handle stays registered until a `close()` confirms it, or the
     * generation's release observes `isClosed` and forgets it.
     */
    override fun abort(executor: Executor?) = delegate.abort(executor)

    /**
     * The generation's release: closes the handle if it is OPEN; if it is CLOSING on another
     * thread, hands the last responsibility to that thread (see the class KDoc) and reports
     * [ReleaseOutcome.IN_FLIGHT]; if it is CLOSED already, nothing to do.
     */
    fun releaseByGeneration(): ReleaseOutcome {
        if (state.get() == State.CLOSED) return ReleaseOutcome.ALREADY_CLOSED
        if (runCatching { delegate.isClosed }.getOrDefault(false)) {
            // Closed by something this wrapper did not see (an executor-driven abort, a driver
            // fault): confirm and forget — that is a closure, not an attempt.
            confirmClosedByOthers()
            return ReleaseOutcome.ALREADY_CLOSED
        }
        return when {
            state.compareAndSet(State.OPEN, State.CLOSING) -> {
                try {
                    delegate.close()
                    markClosed()
                    ReleaseOutcome.CLOSED
                } catch (e: SQLException) {
                    lastCloseFailure = e
                    state.set(State.OPEN)
                    ReleaseOutcome.FAILED
                }
            }

            else -> {
                transferred = true
                // Re-check after the flag: if the closer finished between the CAS and the flag,
                // the outcome is already final and the flag is harmless.
                if (state.get() == State.CLOSED) ReleaseOutcome.ALREADY_CLOSED else ReleaseOutcome.IN_FLIGHT
            }
        }
    }

    enum class ReleaseOutcome { CLOSED, ALREADY_CLOSED, IN_FLIGHT, FAILED }

    /**
     * Exactly one thread runs the driver's close: the CAS loser returns — CLOSED means a
     * second close is the no-op the driver's own would be, CLOSING means the other thread owns
     * the outcome and this one must not report a closure it did not confirm. A driver failure
     * puts the handle back to OPEN, still registered; if the generation has meanwhile handed
     * this thread the last word ([transferred]), one more attempt is made, then the failure is
     * the caller's.
     */
    private fun closeOnce(finalAttempt: Boolean) {
        if (!state.compareAndSet(State.OPEN, State.CLOSING)) return
        try {
            delegate.close()
        } catch (e: SQLException) {
            lastCloseFailure = e
            state.set(State.OPEN)
            if (transferred && !finalAttempt) {
                closeOnce(finalAttempt = true)
                return
            }
            throw e
        }
        markClosed()
    }

    private fun confirmClosedByOthers() {
        if (state.getAndSet(State.CLOSED) != State.CLOSED) registry.forget(this)
    }

    private fun markClosed() {
        state.set(State.CLOSED)
        registry.forget(this)
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T =
        if (iface != null && iface.isInstance(delegate)) iface.cast(delegate) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>?): Boolean = (iface != null && iface.isInstance(delegate)) || delegate.isWrapperFor(iface)
}
