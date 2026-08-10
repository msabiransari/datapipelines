package co.datapipelines.executor

import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
}

/** The production [CancellationRegistry]: a concurrent map of live executions. */
class InMemoryCancellationRegistry : CancellationRegistry {
    private val handles = ConcurrentHashMap<UUID, ExecutionCancellationHandle>()

    /** Live executions this instance is running — observability and leak assertions. */
    val liveExecutions: Int get() = handles.size

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

        override val abortReason: AbortReason? get() = reason.get()

        override val registeredStatements: Int get() = statements.size

        override fun bind(job: Job) {
            this.job.set(job)
        }

        /** §8.3.2 step 1 without step 2 — see [CancellationHandle.cancelStatements]. */
        override fun cancelStatements() {
            statements.values.forEach(::cancelQuietly)
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

        private companion object {
            val LOG = LoggerFactory.getLogger(ExecutionCancellationHandle::class.java)
        }
    }
}
