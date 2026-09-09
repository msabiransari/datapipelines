package co.datapipelines.executor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The per-instance registry cancellation reaches an in-flight execution through
 * (dag-executor.md §8.3.1).
 *
 * In-memory and instance-local by design; **cross-instance** cancellation travels through the
 * Redis flag [CancellationFlags] writes, which the executing instance polls (§8.3.1 step 2).
 * Only the instance actually running an execution holds its statements, and only it can cancel
 * them — which is precisely why the flag exists.
 */
interface CancellationRegistry {
    /** Registers [executionId] and returns the handle its nodes register statements with. */
    fun register(executionId: UUID): CancellationHandle

    /** Removes [executionId]; called from the executor's `finally` on every path. */
    fun deregister(executionId: UUID)

    /**
     * Cancels [executionId] in the §8.3.2 order: every registered `Statement.cancel()` first,
     * then the root `Job`.
     *
     * @return false when the execution is unknown to this instance or already terminal —
     *   cancelling an already-finished execution is a no-op, not an error.
     */
    fun cancel(
        executionId: UUID,
        reason: AbortReason,
    ): Boolean

    /** Cancels every live execution — the shutdown drain (§8.3). */
    fun cancelAll(reason: AbortReason)

    /**
     * Executions currently running on this instance. The shutdown drain's flush wait polls it:
     * an execution leaves the count only from the executor's `finally`, after its terminal
     * status and events are written — so `0` means the flush is done, not merely that
     * cancellation was requested.
     */
    val liveExecutions: Int
}

/** One execution's cancellation surface (dag-executor.md §8.3.1). */
interface CancellationHandle {
    /** Binds the execution's root `Job`; cancellation cancels it with [ExecutionAbortedException]. */
    fun bind(job: Job)

    /** The reason this execution was cancelled, or null while it is still running. */
    val abortReason: AbortReason?

    /** Statements currently registered — the assertion surface for "a live statement was cancelled". */
    val registeredStatements: Int

    /**
     * Interrupts every registered `Statement` **without** marking the execution aborted.
     *
     * This is the timeout path's half of §8.3.2 step 1 (B4a). `withTimeout` cancels the execution
     * scope, but a node blocked inside a blocking JDBC call observes nothing until that call
     * returns — only `Statement.cancel()` stops the query on the source server, which is what §2
     * principle 7 ("a caller that leaves never keeps a source database busy") actually requires.
     *
     * It deliberately does **not** set [abortReason]: a timeout is a `FAILED` execution and
     * `ABORTED` is reserved for the three cancellation triggers of §8.3 (§5.3). Reusing
     * [CancellationRegistry.cancel] here would relabel every timeout as a cancellation.
     */
    fun cancelStatements()

    /**
     * Registers [stmt] against [nodeId] for the duration of [body], then deregisters it.
     *
     * `body` is **suspending**, where §8.3.1 writes `() -> T`. It has to be: the caller node's
     * drain into the result store runs inside this block (§6.4.2) and is suspending Redis I/O.
     * A non-suspending signature would force a `runBlocking` inside a coroutine — reported to
     * the orchestrator as a signature deviation.
     *
     * @throws ExecutionAbortedException when the execution was already cancelled — a statement
     *   registered after `cancel()` swept the map would otherwise run uninterruptible to
     *   completion on the source database.
     */
    suspend fun <T> withStatement(
        nodeId: String,
        stmt: Statement,
        body: suspend () -> T,
    ): T

    /**
     * Runs [driverCall] — the blocking `execute*` on [stmt] — behind the **cancel latch**, on the
     * node's own thread, at the last instant before the driver is entered (086 A1).
     *
     * ## The window this closes
     *
     * [withStatement] registers the statement and only then does the caller reach the driver.
     * Everything in between — the last few statements of the runner, and any amount of
     * *descheduling* on a loaded box — is a window in which the statement is registered but the
     * driver holds no command yet. A `cancel()` landing there calls `Statement.cancel()` on a
     * statement that is not executing, and **every** bundled driver silently drops it
     * (§8.3.2's table, measured); the query then starts anyway and runs to completion, with the
     * already-cancelled coroutine unable to observe anything until the blocking call returns.
     * That is not a driver caveat — it is a window the executor owns, and this is where it is
     * closed: re-read the latch on the executing thread, and refuse to enter the driver at all.
     *
     * Both halves of "cancelled" are read, because different paths set them:
     * [abortReason] for the registry's own `cancel()` (which sets it *before* cancelling the root
     * job, so there is an instant where only this is true), and the coroutine's own liveness for
     * [cancelStatements] — the timeout, sibling-failure and ancestor paths set no reason at all
     * (§5.3), and there the cancellation is already on the context. The second is what carries
     * this guard onto a composed **child** execution, whose own handle the family's cancel never
     * touches (§8.3, "a descendant stopped by an ancestor").
     *
     * The statement is cancelled and closed before the refusal, so nothing downstream can execute
     * it: a closed statement is inert whatever a later caller does with it.
     *
     * @throws ExecutionAbortedException when this execution was cancelled through the registry.
     * @throws kotlinx.coroutines.CancellationException carrying the ancestor's or the deadline's
     *   own cause when the node's scope was cancelled from outside.
     */
    suspend fun <T> whileExecuting(
        nodeId: String,
        stmt: Statement,
        driverCall: () -> T,
    ): T
}

/** The production [CancellationRegistry]: a concurrent map of live executions. */
class InMemoryCancellationRegistry : CancellationRegistry {
    private val handles = ConcurrentHashMap<UUID, ExecutionCancellationHandle>()

    /** Live executions this instance is running — observability and leak assertions. */
    override val liveExecutions: Int get() = handles.size

    /**
     * Statements currently registered for [executionId]; 0 when it is unknown.
     *
     * The assertion surface for "a cancellation test really had a live statement to cancel" — the
     * difference between exercising `Statement.cancel()` and passing through `withStatement`'s
     * entry guard, which look identical from the outside and are not the same test.
     */
    fun registeredFor(executionId: UUID): Int = handles[executionId]?.registeredStatements ?: 0

    override fun register(executionId: UUID): CancellationHandle =
        ExecutionCancellationHandle(executionId).also { handles[executionId] = it }

    override fun deregister(executionId: UUID) {
        handles.remove(executionId)
    }

    override fun cancel(
        executionId: UUID,
        reason: AbortReason,
    ): Boolean = handles[executionId]?.cancel(reason) ?: false

    override fun cancelAll(reason: AbortReason) {
        handles.keys.toList().forEach { cancel(it, reason) }
    }

    /** The handle for one execution; also the registry's per-execution state. */
    private class ExecutionCancellationHandle(
        private val executionId: UUID,
    ) : CancellationHandle {
        private val statements = ConcurrentHashMap<Long, Statement>()
        private val registrationIds = AtomicLong()
        private val job = AtomicReference<Job?>()
        private val reason = AtomicReference<AbortReason?>()

        /** One re-issue watcher per handle at a time — see [scheduleReissue]. */
        private val reissuing = AtomicBoolean()

        override val abortReason: AbortReason? get() = reason.get()

        override val registeredStatements: Int get() = statements.size

        override fun bind(job: Job) {
            this.job.set(job)
        }

        /** §8.3.2 step 1 without step 2 — see [CancellationHandle.cancelStatements]. */
        override fun cancelStatements() {
            statements.values.forEach(::cancelQuietly)
            scheduleReissue()
        }

        override suspend fun <T> withStatement(
            nodeId: String,
            stmt: Statement,
            body: suspend () -> T,
        ): T {
            reason.get()?.let { throw ExecutionAbortedException(it) }
            val id = registrationIds.incrementAndGet()
            statements[id] = stmt
            // A cancel() that swept the map between the check above and the put would leave this
            // statement unregistered and uninterruptible; re-reading the reason closes that race.
            reason.get()?.let {
                statements.remove(id)
                throw ExecutionAbortedException(it)
            }
            try {
                return body()
            } catch (e: CancellationException) {
                // Already the right shape — passed through untouched, and BEFORE the conversion
                // below. Wrapping it would attach it as the *suppressed cause* of a second
                // `ExecutionAbortedException`, and `PipelineExecutor.recordSuppressedFailure`
                // reads exactly that field to answer "what did this node hit before the abort?".
                // It would then map a cancellation through `ErrorCodeMapper`, whose fallback row
                // is a real code — so a node the cancel latch refused cleanly would report
                // `query_execution_failed` in the abort snapshot, against §8.3's "cancellation
                // carries no error code at all". Only a DRIVER error needs converting.
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // `Statement.cancel()` does its job by making the driver raise on the thread blocked
                // in `executeQuery` — H2 reports SQLState 57014, Postgres 57014, and so on. That
                // exception is the *consequence* of the cancellation, not a node failure, and it
                // reaches the runner first: the coroutine cancellation is only observed at the next
                // suspension point, so without this conversion the driver error wins the race and an
                // ABORTED execution reports `pipeline.node.query_execution_failed` + `node_failed` +
                // `pipeline_failed` instead of `execution_aborted` — flatly against §8.3, which says
                // cancellation carries no error code at all.
                //
                // Converting here rather than at the outer handler is deliberate: this is the
                // narrowest scope that knows both "a statement was registered for cancellation" and
                // "the cancel has since fired", so a genuine query failure that merely *coincides*
                // with an unrelated code path cannot be relabelled.
                // The original is carried as a suppressed exception, not discarded: the *event* is
                // suppressed (§8.3 — an abort has no error code), but `node_stats_json` must still
                // record what the node actually hit, or a terminal snapshot shows a bare ABORTED
                // with no cause (F8). `PipelineExecutor.executeNode` reads it back out.
                reason.get()?.let { throw ExecutionAbortedException(it).apply { addSuppressed(e) } }
                throw e
            } finally {
                statements.remove(id)
            }
        }

        override suspend fun <T> whileExecuting(
            nodeId: String,
            stmt: Statement,
            driverCall: () -> T,
        ): T {
            latch(nodeId, stmt)
            // Read before the call: a driver that failed may have closed the statement, and
            // `getQueryTimeout` on a closed statement throws on most of them.
            val timeoutSeconds = stmt.queryTimeout
            val startedAt = System.nanoTime()
            try {
                return driverCall()
            } catch (e: SQLException) {
                // T202: the statement's own budget has elapsed, so the driver error is the query
                // timeout's consequence — H2 and Postgres say `57014`, DuckDB `INTERRUPT Error`,
                // MySQL a `SQLTimeoutException`, MSSQL `HYT00`; classifying by elapsed time is the
                // one test every driver passes. An abort in flight is not relabelled: `withStatement`
                // converts that one, and a cancelled run must carry no error code (§8.3).
                val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                if (timeoutSeconds > 0 && elapsedMs >= timeoutSeconds * MILLIS_PER_SECOND && reason.get() == null) {
                    throw NodeQueryTimeoutException(timeoutSeconds, elapsedMs, e)
                }
                throw e
            }
        }

        /**
         * The cancel latch, re-read on the executing thread — see [CancellationHandle.whileExecuting].
         *
         * `ensureActive()` is second, not first, and both are needed. [cancel] sets [reason] and
         * only *then* cancels the root job, so between the two the context is still active while
         * the execution is unambiguously cancelled; reading [reason] first is also what carries
         * the right [AbortReason] instead of a bare `JobCancellationException`.
         */
        private suspend fun latch(
            nodeId: String,
            stmt: Statement,
        ) {
            reason.get()?.let {
                LOG.debug("Cancel latch refused the driver call for node {} of execution {}", nodeId, executionId)
                cancelQuietly(stmt)
                closeQuietly(stmt)
                throw ExecutionAbortedException(it)
            }
            currentCoroutineContext().ensureActive()
        }

        /**
         * Re-issues `Statement.cancel()` while any statement is still registered (086 A1).
         *
         * The latch in [whileExecuting] closes the window the executor can see. What it cannot see
         * is the driver's own: `executeQuery` parses and plans before it registers a command with
         * the session, and a thread descheduled inside that prologue leaves a statement that is
         * executing from our side and cancellable from nobody's. One `cancel()` is therefore a
         * *hope*; re-issuing until the statement deregisters is the guarantee. Deregistration is
         * the stop condition precisely because it means the blocking call returned.
         *
         * Bounded at [REISSUE_ATTEMPTS] × [REISSUE_INTERVAL_MS] because it is not the last line of
         * defence: a driver whose `cancel()` is a genuine no-op is stopped by the statement's own
         * `queryTimeout`, which every node sets from
         * `datapipelines.executor.node-query-timeout-seconds`. Nothing here waits on the query.
         *
         * Its own daemon thread, created only when a cancel finds live statements: the callers are
         * an HTTP worker (`DELETE /executions/{id}`), the SSE grace timer and the shutdown hook,
         * and none of them may block — `Statement.cancel()` on Postgres opens a *new* socket to the
         * server, so even the first pass is not free.
         */
        private fun scheduleReissue() {
            if (statements.isEmpty()) return
            if (!reissuing.compareAndSet(false, true)) return
            Thread({ reissueUntilDrained() }, "dag-cancel-reissue-$executionId")
                .apply { isDaemon = true }
                .start()
        }

        private fun reissueUntilDrained() {
            try {
                repeat(REISSUE_ATTEMPTS) {
                    Thread.sleep(REISSUE_INTERVAL_MS)
                    if (statements.isEmpty()) return
                    statements.values.forEach(::cancelQuietly)
                }
                LOG.debug("Cancel re-issue gave up for execution {}; queryTimeout is the backstop", executionId)
            } catch (e: InterruptedException) {
                LOG.debug("Cancel re-issue interrupted for execution {}: {}", executionId, e.message)
                Thread.currentThread().interrupt()
            } finally {
                reissuing.set(false)
            }
        }

        /**
         * §8.3.2 steps 1–2, in this order and for this reason: `Statement.cancel()` runs from the
         * caller's thread **first**, which is what actually interrupts a long-running query on the
         * source database — the driver raises an `SQLException` on the thread blocked in
         * `executeQuery`. Cancelling the coroutine first would only unblock the JVM side and leave
         * the query running on the source server.
         *
         * A statement that ignores `cancel()` is not waited on: it finishes or hits its own
         * `queryTimeout`, and `use` returns its connection to the pool either way.
         */
        fun cancel(reason: AbortReason): Boolean {
            if (!this.reason.compareAndSet(null, reason)) return false
            cancelStatements()
            job.get()?.cancel(ExecutionAbortedException(reason))
            return true
        }

        private fun cancelQuietly(stmt: Statement) {
            try {
                stmt.cancel()
            } catch (e: SQLException) {
                // Cancelling an already-completed statement is a documented no-op case (§12.1),
                // and a driver that refuses is a driver-quality issue, not an executor leak.
                // Either way it must not stop the remaining statements from being cancelled.
                LOG.debug("Statement.cancel() refused for execution {}: {}", executionId, e.message)
            }
        }

        /** Same contract as [cancelQuietly]: a close that refuses must not mask the abort. */
        private fun closeQuietly(stmt: Statement) {
            try {
                stmt.close()
            } catch (e: SQLException) {
                LOG.debug("Statement.close() refused for execution {}: {}", executionId, e.message)
            }
        }

        private companion object {
            val LOG = LoggerFactory.getLogger(ExecutionCancellationHandle::class.java)

            /**
             * The re-issue cadence. Short enough that a dropped cancel costs milliseconds rather
             * than the query's full runtime, long enough that a driver whose `cancel()` opens a
             * connection (Postgres) is not asked to do so in a tight loop.
             */
            const val REISSUE_INTERVAL_MS = 25L

            /** 80 × 25 ms = 2 s of re-issuing; past that, `queryTimeout` owns the problem. */
            const val REISSUE_ATTEMPTS = 80

            const val NANOS_PER_MILLI = 1_000_000L
            const val MILLIS_PER_SECOND = 1_000L
        }
    }
}
