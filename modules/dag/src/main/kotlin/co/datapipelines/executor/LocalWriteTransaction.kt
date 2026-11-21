package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LocalWriteQualification
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * One execution family's local write transaction (pipeline-write-transactions §3.3, §6): the
 * sole participant connection every read and write to ONE registration uses "until the outer
 * execution settles", created lazily "before the first access to the participating
 * datasource" and committed/rolled back only by the ROOT executor.
 *
 * ## Identity (§6)
 *
 * The lease belongs to one (execution family, workspace, registration name). Enlist resolves
 * the registration through workspace visibility ONCE and snapshots the resolved entry; every
 * later span uses that snapshot's pool, so a rename/repoint/recredential mid-run can never
 * switch the active transaction onto a newly resolved connection — the run finishes on the
 * lease it began on (or rolls back), and the replacement destination receives zero writes.
 *
 * ## Serialization (§3.3)
 *
 * All statement/cursor use of the connection is serialized: every [withParticipant] span
 * holds the transaction's mutex, so parallel siblings targeting the registration queue
 * FIFO without overlapping JDBC calls, while the DAG still orders them by dependency. A span
 * opened inside another span of the SAME coroutine reuses the connection directly (a
 * same-registration write-back consumes its cursor inside the read's span); a DIFFERENT
 * coroutine never inherits that right — the span marker lives in the opening coroutine's
 * context, so a concurrent sibling still waits on the mutex. Do not launch concurrent
 * transaction work from inside a span: the marker makes the child believe it holds the span.
 *
 * ## Lifetime
 *
 * `IDLE → ENLISTED → SETTLED → CLOSED`. Commit and rollback run under [NonCancellable] on
 * the executor's dispatcher — terminal bookkeeping must survive the scope being unwound —
 * and each settles the transaction exactly once. Close returns the connection to the pool on
 * every path (HikariCP resets `autoCommit` and rolls back any uncommitted work on return,
 * which backstops a rollback that could not complete). Telemetry (§7) records start,
 * confirmed commit, confirmed rollback and unknown per execution, by dialect, and nothing
 * else — no SQL, no row data, no credentials.
 *
 * The outcome-unknown STATUS (§4's lost-ack rows) is the executor's (#1035); this class records
 * the honest outcome, the durable commit boundary ([CommitLedger]) and throws the catalogued commit failure.
 */
class LocalWriteTransaction(
    /** The family this lease belongs to — the root execution's id. */
    val rootExecutionId: UUID,
    val workspaceId: UUID,
    /** The one registration every eligible write of the plan targets. */
    val registration: String,
    private val registry: DatasourceRegistry,
    private val metrics: ExecutorMetrics,
    private val dispatcher: ExecutorDispatcher,
    /**
     * The execution's wall-clock deadline (R7): the run's `withTimeout` bound. Enlist refuses a
     * connection acquisition that would begin — or return — after this instant, so a late
     * acquisition cannot resurrect a transaction the root already settled. Null keeps the
     * pre-R7 tests (which construct this class directly) on the ordinary path.
     */
    private val deadline: Instant? = null,
    /**
     * (#1035) The durable commit boundary: stamped before the driver's commit is called and again
     * once it confirmed, so a stale sweep on another instance can tell "never attempted" from "may
     * have committed". [CommitLedger.NONE] keeps direct constructions (tests) on the old path.
     */
    private val ledger: CommitLedger = CommitLedger.NONE,
) {
    private val mutex = Mutex()

    /**
     * (#1035) True once [commit] has been entered past its durable attempt stamp: from here on the
     * customer database MAY hold the family's writes, so no terminal path may claim a rollback.
     * Volatile — the root's terminal handlers read it from whichever coroutine unwinds.
     */
    @Volatile
    var commitAttempted: Boolean = false
        private set

    /**
     * (#1035) True once the driver CONFIRMED the commit. The writes are durable whatever happens
     * to result delivery afterwards: a late cancellation, deadline or delivery failure settles as
     * SUCCESS, never as ABORTED or FAILED.
     */
    @Volatile
    var commitConfirmed: Boolean = false
        private set

    /**
     * (#1035 R3) True once the wait for the driver's commit answer was abandoned at the execution budget:
     * the connection then belongs to the commit thread ([lateReturn]) and [close] must not touch it.
     */
    @Volatile
    private var commitAbandoned: Boolean = false

    /**
     * Guards [state], [connection] and [snapshot] as one unit. [enlistedConnection]'s blocking
     * pool lease runs OUTSIDE this lock (a Hikari acquire can block for `connectionTimeout`), but
     * the enlist decision — "still IDLE, so take this connection" — is atomic with [close]'s
     * "nothing enlisted, so mark CLOSED". That atomicity is the whole of R7: whichever runs
     * second sees the other's transition, so a lease acquired after the root settled is returned
     * exactly once instead of resurrecting the transaction.
     */
    private val stateLock = Any()
    private var connection: Connection? = null
    private var snapshot: Datasource? = null
    private var state: State = State.IDLE

    /** True once the first span leased the connection — the §9 "zero leases" read counts these. */
    val enlisted: Boolean
        get() = synchronized(stateLock) { state != State.IDLE && state != State.CLOSED }

    /** The registration snapshot the enlisted connection belongs to; null before enlist. */
    val datasource: Datasource? get() = synchronized(stateLock) { snapshot }

    /** The dialect of the enlisted connection's snapshot, for SQL built for that connection. */
    val dialect: co.datapipelines.typesystem.Dialect?
        get() = synchronized(stateLock) { snapshot?.dialect }

    /**
     * Runs [block] as one serialized statement/cursor span on the participant connection —
     * enlisting lazily on the first span (§3.3). See the class KDoc for the reentrancy rule.
     */
    suspend fun <T> withParticipant(block: suspend (Connection) -> T): T {
        coroutineContext[Span]?.let { return block(it.connection) }
        return mutex.withLock {
            val conn = enlistedConnection()
            withContext(Span(conn)) { block(conn) }
        }
    }

    /**
     * True when the calling coroutine is already inside a participant span — i.e. the cursor a
     * caller is about to hand us was opened on THIS transaction's one connection. That is the
     * §3.3 signal the write-back runner uses to decide whether a same-registration source must
     * be drained (closed) before any write statement reuses the connection: a fresh span means
     * the source lives on a different connection (tempdb, or another datasource) and no drain is
     * needed; a reentrant span means the source cursor and the target write share one connection.
     */
    suspend fun inParticipantSpan(): Boolean = coroutineContext[Span] != null

    /**
     * The root's commit (§3.5): called only after every required node — including children —
     * succeeded and the caller result was prepared. No-op when nothing ever enlisted: a plan
     * that enrolled but never touched the registration held no transaction, so there is
     * nothing to confirm and zero leases were taken (§9).
     *
     * @throws DatapipelinesException `pipeline.execution.local_write_commit_failed` when the
     *   driver refuses the commit. A refusal here may or may not have committed — §4's lost-ack
     *   rows — so telemetry records `unknown` and the transaction is SETTLED terminal-unknown:
     *   the root's later rollback attempt is a no-op (no `rolled_back` evidence may follow an
     *   outcome that may have landed). The same code ends a commit the driver never answered within
     *   the execution's remaining budget (#1035 R3, reason `commit_unanswered_within_budget`): the wait
     *   is abandoned, the connection aborted, and a late answer is discarded.
     */
    suspend fun commit() {
        val conn = enlistedOrNull() ?: return
        withContext(NonCancellable + dispatcher.context) {
            recordAttemptOrRefuse()
            commitAttempted = true
            val answered = driverCommit(conn)
            if (answered == null) {
                abandonUnansweredCommit(conn)
                throw commitFailed("commit_unanswered_within_budget", null)
            }
            val failure = answered.exceptionOrNull()
            when (failure) {
                null -> {
                    synchronized(stateLock) { state = State.SETTLED }
                    commitConfirmed = true
                    metrics.localWriteTransaction(OUTCOME_COMMITTED, dialectForTelemetry())
                    recordConfirmationBestEffort()
                }

                else -> {
                    // R4: a refused commit is TERMINAL and its outcome unknown — the write may have
                    // landed, so the root's subsequent rollback attempt must not record `rolled_back`
                    // evidence that implies the committed write was undone. Settle here (no more
                    // settling) so `rollbackQuietly`'s ENLISTED branch can never run after this.
                    synchronized(stateLock) { state = State.SETTLED }
                    metrics.localWriteTransaction(OUTCOME_UNKNOWN, dialectForTelemetry())
                    LOG.error(
                        "Local write transaction {} on '{}' refused at commit; outcome unknown (SQLState {}, vendor code {})",
                        rootExecutionId,
                        registration,
                        (failure as? SQLException)?.sqlState ?: failure.javaClass.simpleName,
                        (failure as? SQLException)?.errorCode,
                    )
                    throw commitFailed("commit_refused", failure)
                }
            }
        }
    }

    /**
     * (#1035 R3) Asks the driver to commit WITHOUT lending it the executor's thread or an unbounded wait.
     *
     * The blocking `commit()` runs on its own daemon thread ([COMMIT_THREADS]); this coroutine suspends on
     * its answer for at most the execution's REMAINING budget (the existing [deadline] — no new setting).
     * [CommitDecision] is the single arbiter between the two sides: whichever side moves it off `PENDING`
     * first owns the outcome, so a driver that answers in the instant the budget expires is believed, and a
     * driver that answers after the wait was abandoned changes nothing observable ([lateReturn]).
     *
     * @return the driver's answer, or null when the budget expired first — the commit was attempted, its
     *   outcome is unknown, and the caller must [abandonUnansweredCommit].
     */
    private suspend fun driverCommit(conn: Connection): Result<Unit>? {
        val answer = CompletableDeferred<Result<Unit>>()
        val decision = AtomicReference(CommitDecision.PENDING)
        COMMIT_THREADS.execute {
            val result = runCatching { conn.commit() }
            if (decision.compareAndSet(CommitDecision.PENDING, CommitDecision.ANSWERED)) {
                answer.complete(result)
            } else {
                lateReturn(conn, result)
            }
        }
        val remaining = remainingBudget() ?: return answer.await()
        withTimeoutOrNull(remaining.toMillis()) { answer.await() }?.let { return it }
        // The budget is spent. Abandon the wait unless the driver answered at this very moment.
        return if (decision.compareAndSet(CommitDecision.PENDING, CommitDecision.ABANDONED)) null else answer.await()
    }

    /**
     * (#1035 R3) The budget expired inside the commit: the writes MAY have landed, so this records the unknown
     * outcome (never a rollback, never a success) and lets go of the connection without waiting on it. The
     * session is aborted so the customer database is not left holding a transaction for a driver call nobody
     * is waiting for any more; the blocked thread then ends with whatever the driver reports, which
     * [lateReturn] discards. [close] leaves the connection to that thread — closing it here would queue a
     * rollback behind the very call that may be committing.
     */
    private fun abandonUnansweredCommit(conn: Connection) {
        synchronized(stateLock) {
            state = State.SETTLED
            commitAbandoned = true
        }
        metrics.localWriteTransaction(OUTCOME_UNKNOWN, dialectForTelemetry())
        LOG.error(
            "Local write transaction {} on '{}' got no commit answer within the execution budget; outcome unknown",
            rootExecutionId,
            registration,
        )
        try {
            conn.abort(COMMIT_THREADS)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            LOG.warn(
                "Aborting the unanswered commit connection of {} refused ({})",
                rootExecutionId,
                e.javaClass.simpleName,
            )
        }
    }

    /**
     * (#1035 R3) The driver answered AFTER the wait was abandoned. Nothing about the execution changes: no
     * confirmation stamp, no metric, no event, no replay — the outcome the root already recorded stands. The
     * only action is returning the connection, which this thread owns once [abandonUnansweredCommit] ran.
     */
    private fun lateReturn(
        conn: Connection,
        result: Result<Unit>,
    ) {
        LOG.warn(
            "Late answer of the abandoned commit of {} ({}); ignored, the recorded outcome stands",
            rootExecutionId,
            if (result.isSuccess) "returned" else result.exceptionOrNull()?.javaClass?.simpleName,
        )
        returnLeaseQuietly(conn)
        synchronized(stateLock) { state = State.CLOSED }
    }

    private fun remainingBudget(): Duration? {
        val deadline = this.deadline ?: return null
        return Duration.between(Instant.now(), deadline).takeIf { !it.isNegative } ?: Duration.ZERO
    }

    private fun commitFailed(
        reason: String,
        cause: Throwable?,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED,
        message = "The pipeline's local write transaction could not be committed; its outcome is unknown",
        details = mapOf("registration" to registration, "execution_id" to rootExecutionId.toString(), "reason" to reason),
        cause = cause,
    )

    /**
     * (#1035) Persists the attempt boundary BEFORE the driver's commit. When the stamp is not
     * durable the commit is NOT called — the work is still uncommitted (state stays ENLISTED), so
     * the root's rollback stays honest — and the run ends FAILED with the catalogued refusal. No
     * metadata transaction is held across the customer commit: this is one autocommit statement.
     */
    private fun recordAttemptOrRefuse() {
        val durable =
            try {
                ledger.recordAttempt(rootExecutionId)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                LOG.error(
                    "Commit attempt of local write transaction {} could not be recorded ({})",
                    rootExecutionId,
                    e.javaClass.simpleName,
                )
                false
            }
        if (!durable) {
            throw refused(
                "commit_evidence_unavailable",
                "The commit boundary could not be recorded, so the commit was not attempted and the writes were rolled back",
            )
        }
    }

    /**
     * (#1035) Records that the confirmed commit's result could not be delivered. Best effort and
     * never throws: the status stays SUCCESS either way.
     */
    fun markResultUnavailable() {
        try {
            ledger.recordResultUnavailable(rootExecutionId)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            LOG.warn("Result-unavailable mark of execution {} could not be recorded ({})", rootExecutionId, e.javaClass.simpleName)
        }
    }

    /** The confirmation stamp is best effort: the root's terminal write settles the row either way. */
    private fun recordConfirmationBestEffort() {
        try {
            ledger.recordConfirmed(rootExecutionId)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            LOG.warn(
                "Commit confirmation of local write transaction {} could not be recorded ({})",
                rootExecutionId,
                e.javaClass.simpleName,
            )
        }
    }

    /**
     * Best-effort rollback on every non-commit terminal path (§4: cancellation, deadline,
     * node failure). "Best-effort" is the design's word: a blocked database or a lost
     * acknowledgement cannot justify CLAIMING rollback, so a refusal is logged and recorded
     * as `unknown` — the pool's reset on close remains the backstop.
     */
    suspend fun rollbackQuietly() {
        val conn = enlistedOrNull() ?: return
        withContext(NonCancellable + dispatcher.context) {
            val enlisted = synchronized(stateLock) { state == State.ENLISTED }
            if (!enlisted) return@withContext
            try {
                conn.rollback()
                synchronized(stateLock) { state = State.SETTLED }
                metrics.localWriteTransaction(OUTCOME_ROLLED_BACK, dialectForTelemetry())
            } catch (e: SQLException) {
                synchronized(stateLock) { state = State.SETTLED }
                metrics.localWriteTransaction(OUTCOME_UNKNOWN, dialectForTelemetry())
                LOG.error(
                    "Local write transaction {} on '{}' refused at rollback; outcome unknown (SQLState {}, vendor code {})",
                    rootExecutionId,
                    registration,
                    e.sqlState,
                    e.errorCode,
                )
            }
        }
    }

    /**
     * Returns the connection to the pool on every path. Never commits; unsettled work is
     * rolled back by the explicit attempt in the terminal handlers and, failing that, by the
     * pool's own reset. Idempotent.
     *
     * R7: the CLOSED decision is atomic with [enlistedConnection]'s enlist decision — see
     * [stateLock]. When the root settles (deadline, cancellation, a sibling's failure) while a
     * detached node body is still blocked in its first pool acquire, this observes "nothing
     * enlisted" and marks CLOSED; the body's later acquire then sees CLOSED and returns its lease
     * instead of enlisting behind the settlement.
     */
    suspend fun close() {
        val conn =
            synchronized(stateLock) {
                when (state) {
                    State.IDLE -> {
                        state = State.CLOSED
                        null
                    }

                    State.CLOSED -> {
                        null
                    }

                    State.ENLISTED, State.SETTLED -> {
                        // An abandoned commit's connection is the commit thread's to return (R3).
                        if (commitAbandoned) null else connection
                    }
                }
            } ?: return
        withContext(NonCancellable + dispatcher.context) {
            try {
                conn.close()
            } catch (e: SQLException) {
                logReturnFailure(e)
            } finally {
                synchronized(stateLock) { state = State.CLOSED }
            }
        }
    }

    /** The enlisted connection, or null when no span ever enlisted (and none may settle). */
    private fun enlistedOrNull(): Connection? =
        synchronized(stateLock) {
            when (state) {
                State.IDLE, State.CLOSED -> null
                State.ENLISTED, State.SETTLED -> connection
            }
        }

    /**
     * Enlists on first access: resolve the registration through workspace visibility, snapshot
     * it, lease from its pool and begin the transaction. Called under the mutex, so the
     * lazy acquisition is exactly-once even with parallel siblings racing to the first span.
     *
     * A registration that vanished, or was repointed to an engine excluded from this feature
     * after planning, refuses here (§4 row 1's run-admission recheck) rather than leasing a
     * connection the plan never admitted. Pool waits and setup failures surface as the
     * driver's own exception (§4 row 2) and keep the executor's existing mapping.
     *
     * ## R7 — late acquisition cannot resurrect a settled transaction
     *
     * `registry.poolFor(resolved).leaseConnection()` blocks for up to the pool's
     * `connectionTimeout`. While it blocks, the root can settle the run (its deadline fired, a
     * sibling failed, or the execution was cancelled) and [close] marks the transaction CLOSED.
     * The enlist below is therefore ATOMIC with that mark ([stateLock]): if the state is no
     * longer IDLE — or the execution deadline passed while we were blocked — the acquired lease
     * is returned exactly once and nothing enlists, records a `started` outcome or writes a row.
     * The deadline bound is what keeps an acquisition from outliving the execution's own budget
     * even when the pool's wait is longer (the "remaining budget" half of §4 row 2).
     */
    private fun enlistedConnection(): Connection {
        synchronized(stateLock) {
            connection?.let { return it }
            check(state == State.IDLE) { "local write transaction for '$registration' is already ${state.name.lowercase()}" }
        }
        val resolved =
            registry.getVisible(registration, workspaceId)
                ?: throw refused("registration_ineligible", "Registration '$registration' is no longer visible to the workspace")
        // #1036: a repoint between planning and first use onto a covered but UNQUALIFIED engine
        // is refused exactly like one onto an excluded engine — never enlisted without evidence.
        if (resolved.dialect !in LocalWritePlanner.ELIGIBLE_DIALECTS || !LocalWriteQualification.transactionQualified(resolved.dialect)) {
            throw refused(
                "registration_ineligible",
                "Registration '$registration' now points at ${resolved.dialect.wire}, which the single-database transaction " +
                    "does not cover or has not qualified",
            )
        }
        return enlistAcquired(resolved)
    }

    /**
     * The acquire + enlist half, kept separate from [enlistedConnection]'s refusal throws. The
     * pool lease blocks for up to `connectionTimeout`; while it does, the root can settle the run
     * and [close] marks the transaction CLOSED. The enlist below is ATOMIC with that mark
     * ([stateLock]): if the state is no longer IDLE — or the execution deadline passed while we
     * were blocked — the acquired lease is returned exactly once and nothing enlists, records a
     * `started` outcome or writes a row (R7).
     */
    private fun enlistAcquired(resolved: Datasource): Connection {
        if (deadlinePassed()) throw settledBeforeAcquire()
        val conn = registry.poolFor(resolved).leaseConnection()
        beginTransaction(conn)
        val enlisted =
            synchronized(stateLock) {
                if (state == State.IDLE && !deadlinePassed()) {
                    connection = conn
                    snapshot = resolved
                    state = State.ENLISTED
                    true
                } else {
                    false
                }
            }
        if (!enlisted) {
            returnLeaseQuietly(conn)
            throw settledBeforeAcquire()
        }
        metrics.localWriteTransaction(OUTCOME_STARTED, resolved.dialect.wire)
        return conn
    }

    /**
     * R3: own the lease through setup. `autoCommit` is a setup step (§4 row 2), not part of the
     * span; a driver that refuses the setter must still see its connection returned exactly once,
     * with no started/committed outcome recorded — otherwise the connection is leaked (state IDLE,
     * nothing records it, neither rollback nor close can reach it). Kept in its own function so
     * [enlistedConnection]'s refusal throws stay within the ThrowsCount bound.
     */
    private fun beginTransaction(conn: Connection) {
        try {
            conn.autoCommit = false
        } catch (e: SQLException) {
            try {
                conn.close()
            } catch (closeFailure: SQLException) {
                e.addSuppressed(closeFailure)
            }
            throw e
        }
    }

    private fun refused(
        reason: String,
        message: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED,
            message = message,
            details = mapOf("reason" to reason, "registration" to registration),
        )

    /** True once the execution's deadline (if any) has passed — the R7 acquisition bound. */
    private fun deadlinePassed(): Boolean {
        val deadline = this.deadline ?: return false
        return !Instant.now().isBefore(deadline)
    }

    /**
     * The refusal for an acquisition that began or returned after the run settled (§4 row 4's
     * "explicit cancellation or execution deadline" — the run is already terminal, so this throw
     * is the detached body's own unwinding and changes no outcome): no enlist, no `started`.
     */
    private fun settledBeforeAcquire(): DatapipelinesException =
        refused(
            "transaction_settled",
            "The local write transaction for '$registration' settled before its connection could be " +
                "acquired; the acquired lease is returned and nothing enlisted.",
        )

    /** Returns a freshly-acquired lease that the enlist refused to take — best effort, never throws. */
    private fun returnLeaseQuietly(conn: Connection) {
        try {
            conn.close()
        } catch (e: SQLException) {
            logReturnFailure(e)
        }
    }

    /** Transaction diagnostics carry JDBC metadata, never the driver's text or exception chain. */
    private fun logReturnFailure(failure: SQLException) {
        LOG.warn(
            "Returning local write transaction {} connection for '{}' to the pool refused (SQLState {}, vendor code {})",
            rootExecutionId,
            registration,
            failure.sqlState,
            failure.errorCode,
        )
    }

    private fun dialectForTelemetry(): String = synchronized(stateLock) { snapshot?.dialect?.wire ?: "unknown" }

    /** The span marker: present in exactly the coroutines running inside a participant span. */
    private class Span(
        val connection: Connection,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Span>
    }

    /** Who owns the in-flight commit's outcome: nobody yet, the driver's answer, or the budget's expiry (R3). */
    private enum class CommitDecision {
        PENDING,
        ANSWERED,
        ABANDONED,
    }

    private enum class State {
        IDLE,
        ENLISTED,
        SETTLED,
        CLOSED,
    }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(LocalWriteTransaction::class.java)

        const val OUTCOME_STARTED = "started"
        const val OUTCOME_COMMITTED = "committed"
        const val OUTCOME_ROLLED_BACK = "rolled_back"
        const val OUTCOME_UNKNOWN = "unknown"

        /**
         * The threads blocked in a driver's commit (R3). Their own pool, not the executor's bounded
         * dispatcher: an unanswered commit pins its thread until the driver returns, and pinning one of the
         * threads that serve every other execution's SQL is how one hung database would starve the instance.
         * One thread per in-flight commit, bounded by the execution slots that can reach a commit.
         */
        val COMMIT_THREADS: java.util.concurrent.ExecutorService =
            Executors.newCachedThreadPool(
                object : ThreadFactory {
                    private val counter = AtomicInteger()

                    override fun newThread(runnable: Runnable): Thread =
                        Thread(runnable, "dag-commit-${counter.incrementAndGet()}").apply { isDaemon = true }
                },
            )
    }
}
