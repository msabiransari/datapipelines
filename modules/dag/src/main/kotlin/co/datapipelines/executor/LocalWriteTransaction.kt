package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
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
 * The outcome-unknown STATUS (§4's lost-ack rows) is #1035's; this class only records the
 * honest outcome and throws the catalogued commit failure.
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
) {
    private val mutex = Mutex()
    private var connection: Connection? = null
    private var snapshot: Datasource? = null
    private var state: State = State.IDLE

    /** True once the first span leased the connection — the §9 "zero leases" read counts these. */
    val enlisted: Boolean get() = state != State.IDLE && state != State.CLOSED

    /** The registration snapshot the enlisted connection belongs to; null before enlist. */
    val datasource: Datasource? get() = snapshot

    /** The dialect of the enlisted connection's snapshot, for SQL built for that connection. */
    val dialect: co.datapipelines.typesystem.Dialect?
        get() = snapshot?.dialect

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
     *   outcome that may have landed).
     */
    suspend fun commit() {
        val conn = enlistedOrNull() ?: return
        withContext(NonCancellable + dispatcher.context) {
            try {
                conn.commit()
                state = State.SETTLED
                metrics.localWriteTransaction(OUTCOME_COMMITTED, dialectForTelemetry())
            } catch (e: SQLException) {
                // R4: a refused commit is TERMINAL and its outcome unknown — the write may have
                // landed, so the root's subsequent rollback attempt must not record `rolled_back`
                // evidence that implies the committed write was undone. Settle here (no more
                // settling) so `rollbackQuietly`'s ENLISTED branch can never run after this.
                state = State.SETTLED
                metrics.localWriteTransaction(OUTCOME_UNKNOWN, dialectForTelemetry())
                LOG.error("Local write transaction {} on '{}' refused at commit; outcome unknown", rootExecutionId, registration, e)
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED,
                    message =
                        "The pipeline's local write transaction could not be committed: " +
                            "${e.message?.take(COMMIT_ERROR_MESSAGE_LIMIT)}",
                    details = mapOf("registration" to registration, "execution_id" to rootExecutionId.toString()),
                    cause = e,
                )
            }
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
            when (state) {
                State.ENLISTED -> {
                    try {
                        conn.rollback()
                        state = State.SETTLED
                        metrics.localWriteTransaction(OUTCOME_ROLLED_BACK, dialectForTelemetry())
                    } catch (e: SQLException) {
                        state = State.SETTLED
                        metrics.localWriteTransaction(OUTCOME_UNKNOWN, dialectForTelemetry())
                        LOG.error(
                            "Local write transaction {} on '{}' refused at rollback; outcome unknown",
                            rootExecutionId,
                            registration,
                            e,
                        )
                    }
                }

                else -> {
                    Unit
                }
            }
        }
    }

    /**
     * Returns the connection to the pool on every path. Never commits; unsettled work is
     * rolled back by the explicit attempt in the terminal handlers and, failing that, by the
     * pool's own reset. Idempotent.
     */
    suspend fun close() {
        val conn =
            enlistedOrNull() ?: run {
                state = State.CLOSED
                return
            }
        withContext(NonCancellable + dispatcher.context) {
            try {
                conn.close()
            } catch (e: SQLException) {
                LOG.warn("Returning local write transaction {} connection for '{}' to the pool refused", rootExecutionId, registration, e)
            } finally {
                state = State.CLOSED
            }
        }
    }

    /** The enlisted connection, or null when no span ever enlisted (and none may settle). */
    private fun enlistedOrNull(): Connection? =
        when (state) {
            State.IDLE, State.CLOSED -> null
            State.ENLISTED, State.SETTLED -> connection
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
     */
    private fun enlistedConnection(): Connection {
        connection?.let { return it }
        check(state == State.IDLE) { "local write transaction for '$registration' is already ${state.name.lowercase()}" }
        val resolved =
            registry.getVisible(registration, workspaceId)
                ?: throw refused("registration_ineligible", "Registration '$registration' is no longer visible to the workspace")
        if (resolved.dialect !in LocalWritePlanner.ELIGIBLE_DIALECTS) {
            throw refused(
                "registration_ineligible",
                "Registration '$registration' now points at ${resolved.dialect.wire}, which the single-database transaction does not cover",
            )
        }
        val conn = registry.poolFor(resolved).leaseConnection()
        beginTransaction(conn)
        connection = conn
        snapshot = resolved
        state = State.ENLISTED
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

    private fun dialectForTelemetry(): String = snapshot?.dialect?.wire ?: "unknown"

    /** The span marker: present in exactly the coroutines running inside a participant span. */
    private class Span(
        val connection: Connection,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Span>
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

        /** Driver text carried into the commit-failure message — bounded, and a named constant. */
        const val COMMIT_ERROR_MESSAGE_LIMIT = 200
    }
}
