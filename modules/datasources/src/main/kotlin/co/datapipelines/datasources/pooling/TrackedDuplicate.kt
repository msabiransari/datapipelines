package co.datapipelines.datasources.pooling

import java.sql.Connection
import java.util.concurrent.Executor

/**
 * 152 (R152-2 … R152-7) — a physical duplicate of a LAKE generation's instance, owned by an
 * identifiable actor at every instant until its closure is CONFIRMED or its close attempts are
 * exhausted and reported.
 *
 * HikariCP holds it from `DataSource.getConnection()` on: through its own JDBC setup
 * (`isReadOnly`, auto-commit, validation), bag admission, every lease, and the close or abort
 * at shutdown. All of that passes through here unchanged — this wrapper adds one thing: it
 * keeps the generation's registry truthful about whether the raw connection is still open, and
 * it knows, at every point, WHO owes the next close attempt.
 *
 * ## The protocol (design record: evidence `12-ownership-protocol.md`)
 *
 * ONE per-handle [lock] guards every field below; every exit of every driver call is a
 * transition taken under it; no driver call ever runs under it. The phase is `OPEN` (nobody is
 * inside the driver; closure not confirmed), `CLOSING` (exactly one actor is inside the
 * driver's close) or `CLOSED` (the driver returned from `close()` without throwing — the only
 * thing that counts as closure). The registry forgets the handle only on the transition to
 * `CLOSED` ([Registry.forget]); a handle whose permitted attempts are all refused stays
 * registered, and the owner is told ([Registry.exhausted]) so the residual is counted and
 * logged, never silent.
 *
 * Attempt bound, exactly: every explicit `close()` call makes at most ONE driver attempt of its
 * own, plus at most ONE hand-off retry over the handle's whole life; the generation's release
 * makes at most ONE attempt per handle. The hand-off: if the release finds the handle
 * `CLOSING`, it marks it [transferred] and reports `IN_FLIGHT` — true by construction, because
 * the actor inside the driver is alive, and on failure it decides its retry in the SAME
 * critical section that records the failure, so the release can never outrun it (R152-5).
 * `SQLException` and a nonfatal `RuntimeException` from the driver take the same transitions —
 * an exception is not a closure, and it never leaves the phase at `CLOSING` with its actor gone
 * (R152-6). `abort()` is passed through and never confirms anything: JDBC's abort may be
 * asynchronous, and the pinned DuckDB driver refuses it outright (after which HikariCP falls
 * back to `close()`).
 *
 * `unwrap` still reaches the driver's connection; `isWrapperFor` says so.
 */
internal class TrackedDuplicate(
    private val delegate: Connection,
    private val registry: Registry,
) : Connection by delegate {
    /** What a handle needs from its owner: to be forgotten on confirmed closure, or reported when its attempts are exhausted. */
    interface Registry {
        fun forget(handle: TrackedDuplicate)

        fun exhausted(
            handle: TrackedDuplicate,
            error: Exception,
        )
    }

    internal enum class Phase { OPEN, CLOSING, CLOSED }

    /** What the generation's release found and did for one handle. */
    enum class ReleaseOutcome { CLOSED, ALREADY_CLOSED, IN_FLIGHT, FAILED }

    private val lock = Any()

    // --- all guarded by [lock] ---
    private var phase = Phase.OPEN
    private var released = false
    private var transferred = false
    private var retried = false
    private var attempts = 0
    private var lastFailure: Exception? = null

    /** Driver close attempts so far — diagnostics and tests. */
    val closeAttempts: Int get() = synchronized(lock) { attempts }

    /** The driver's most recent refusal to close this handle — what the residual reports. */
    val lastCloseFailure: Exception? get() = synchronized(lock) { lastFailure }

    /**
     * The holder's close (C1–C3, S, F1–F3). At most one driver attempt of this call's own, plus
     * the one hand-off retry if the generation's release passed while it was inside the driver.
     */
    override fun close() {
        if (!enterClosing()) return
        runAttempts()
    }

    /**
     * The creator's close of a duplicate that was REFUSED at registration (R152-7, protocol A2):
     * the handle is registered for accounting; if the generation has already released, the
     * creator is the holder of last resort and takes the hand-off retry itself.
     */
    fun closeAsRefused(generationAlreadyReleased: Boolean) {
        synchronized(lock) {
            if (generationAlreadyReleased) {
                released = true
                transferred = true
            }
        }
        close()
    }

    /** Passed through; never a closure — see the class KDoc. */
    override fun abort(executor: Executor?) = delegate.abort(executor)

    /**
     * The generation's ONE visit (G0–G4). Runs at most one driver attempt; never throws — a
     * nonfatal driver exception is a `FAILED` outcome, so the release continues with the next
     * handle and the retained owner.
     */
    fun releaseByGeneration(): ReleaseOutcome {
        val driverSaysClosed = runCatching { delegate.isClosed }.getOrDefault(false)
        val found =
            synchronized(lock) {
                released = true
                when {
                    phase == Phase.CLOSED -> {
                        ReleaseOutcome.ALREADY_CLOSED
                    }

                    driverSaysClosed -> {
                        // Closed by something this wrapper never saw (an executor-driven abort, a
                        // driver fault): a closure to confirm, not an attempt to make.
                        phase = Phase.CLOSED
                        ReleaseOutcome.ALREADY_CLOSED
                    }

                    phase == Phase.CLOSING -> {
                        transferred = true
                        ReleaseOutcome.IN_FLIGHT
                    }

                    else -> {
                        phase = Phase.CLOSING
                        attempts++
                        null // the generation's own attempt, below
                    }
                }
            }
        if (found == ReleaseOutcome.ALREADY_CLOSED && driverSaysClosed) registry.forget(this)
        if (found != null) return found
        return try {
            delegate.close()
            confirmClosed()
            ReleaseOutcome.CLOSED
        } catch (
            // SQLException, or a nonfatal RuntimeException the driver surfaces (the module's
            // DS-SEC-6 rule): both are the FAILED transition, never an exit from the release.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            synchronized(lock) {
                phase = Phase.OPEN
                lastFailure = e
            }
            registry.exhausted(this, e)
            ReleaseOutcome.FAILED
        }
    }

    /** C1–C3: true when this call now owns the driver attempt. */
    private fun enterClosing(): Boolean =
        synchronized(lock) {
            if (phase != Phase.OPEN) return false
            phase = Phase.CLOSING
            attempts++
            true
        }

    /**
     * One driver attempt, then — under the lock, together with the failure record — the
     * decision whether this actor owes the hand-off retry (F2) or the failure is final (F1/F3).
     */
    private fun runAttempts() {
        while (true) {
            try {
                delegate.close()
            } catch (
                // SQLException, or a nonfatal RuntimeException the driver surfaces (the module's
                // DS-SEC-6 rule): the same transition either way; the exception still propagates.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                val (retry, exhausted) =
                    synchronized(lock) {
                        lastFailure = e
                        val retry = transferred && !retried
                        if (retry) {
                            retried = true
                            attempts++
                        } else {
                            phase = Phase.OPEN
                        }
                        // Final only when nobody is left: the release has passed (or this IS the
                        // last-resort creator) and no retry remains.
                        retry to (!retry && released)
                    }
                if (retry) continue
                if (exhausted) registry.exhausted(this, e)
                throw e
            }
            confirmClosed()
            return
        }
    }

    private fun confirmClosed() {
        synchronized(lock) { phase = Phase.CLOSED }
        registry.forget(this)
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T =
        if (iface != null && iface.isInstance(delegate)) iface.cast(delegate) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>?): Boolean = (iface != null && iface.isInstance(delegate)) || delegate.isWrapperFor(iface)
}
