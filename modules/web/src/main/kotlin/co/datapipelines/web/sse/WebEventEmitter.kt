package co.datapipelines.web.sse

import co.datapipelines.events.DataReady
import co.datapipelines.events.EventEmitter
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.executor.ExecutedByKeyKind
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.persistence.FailureShape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Everything one execution needs recorded that the executor does not know (rest-api §10.2,
 * metadata-db §4.6).
 *
 * `triggered_via`, `executed_by` and `executed_by_key_kind` are **not** on the event wire — `ExecuteRequest` carries them
 * and only the surface that built it knows them — so they are captured here, per execution, and
 * used when the `RUNNING` row is inserted.
 */
data class ExecutionContext(
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val userId: UUID,
    val correlationId: UUID,
    val triggeredVia: ExecutionTrigger,
    val parametersJson: String,
    /**
     * The workspace this execution runs in — its pipeline's workspace (design §5.3). Carried
     * explicitly because the emitter only ever sees EVENTS, never the request or the node
     * context (the `EventEmitter` port is a single `emit(event)`), so the `workspaceId` the
     * request/context types carry since 025 A5 cannot reach it. It scopes the execution-row
     * read that derives an aborted execution's duration (`findById` is workspace-scoped).
     */
    val workspaceId: UUID,
    /**
     * Composition lineage (metadata-db §4.6, V3): set only on a child execution spawned by a
     * PIPELINE node — null on roots, which persist `root_execution_id = execution_id`.
     */
    val parentExecutionId: UUID? = null,
    val parentNodeId: String? = null,
    val rootExecutionId: UUID? = null,
    /** D11 — the credential kind behind [userId] when a key started the run; null for a session. */
    val executedByKeyKind: ExecutedByKeyKind? = null,
)

/**
 * The hub every execution event passes through (dag-executor.md §10).
 *
 * One instance per execution. It does four things, in this order, for every event:
 *
 * 1. **Projects** the executor event onto its wire payload, stamping `correlation_id` on all of
 *    them ([SseEventProjection] — carry-forward #1).
 * 2. **Streams** it to the live SSE consumer, if one is still attached. Never blocks on the
 *    reader and never throws for "nobody is listening" (dag-executor §10).
 * 3. **Persists** it: the durable `execution_events` row (7 days, `dag`'s repository) and the
 *    1-hour Redis replay log (`web`'s, §5.9).
 * 4. **Drives the execution record**: `ExecutionRepository.create` on `execution_started` —
 *    which is where the execution id first becomes known, since `PipelineExecutor.execute` mints
 *    it internally — and `ExecutionRepository.complete` on the terminal event.
 *
 * For `execution_started` alone the record's RUNNING row (step 4's first half) moves IN FRONT of
 * the live send (step 2): an id must never reach a client before the id resolves (#306). The
 * started hook still runs before all of it — it is where the launcher registers the stream, so
 * `execution_started` itself reaches the client. #306: a client that cancels or reads on the id
 * the moment the first frame lands is answered 204 / 200, never `404 result.execution_not_found`
 * — the row is committed before the frame that carries the id exists.
 *
 * ## Dispatching
 * Steps 3 and 4 are blocking JDBC and Redis calls, and `emit` is invoked **on the executor's own
 * bounded dispatcher** (dag-executor §15.2 — a pool sized for SQL work, not for the surface's
 * bookkeeping). The execution row's writes run under [persistenceDispatcher], a pool `web` owns;
 * the event's row and replay-log entry go through [eventRecorder] — since #266 the batching
 * writers, which share one commit across concurrent executions and run their direct fallback on
 * that same pool. Each `emit` still AWAITS its own persistence before returning, which is what
 * preserves event order: the executor calls `emit` sequentially, the writers are FIFO per
 * execution, so an awaited hand-off cannot reorder. Fire-and-forget would be faster and would let
 * `data_ready` land in the table before `pipeline_completed` (the #266 ruling keeps it awaited).
 *
 * Persistence that has STARTED runs to its end even if the execution is cancelled meanwhile, and
 * the cancellation is then rethrown — the shape the single blocking hop always had. It is bounded:
 * the recorder waits at most twice `record-max-wait-ms` per store (dag-executor §10).
 *
 * ## The lifecycle writes' own bound (#311)
 * The RUNNING insert and the terminal UPDATE are single direct statements, and a statement timeout
 * cannot reach a server that never answers (measured: pgjdbc's `queryTimeout` timer cannot deliver
 * its cancel to a paused Postgres). Both writes are therefore bounded at the CALLER by
 * [lifecycleWriteTimeout] — `emit` hands the statement to [persistenceDispatcher] and waits at most
 * the bound; past it the caller returns, the statement keeps running on the pool thread and may
 * still land when the database recovers (the #266 direct-fallback precedent). The statements also
 * carry a JDBC `queryTimeout` of the same bound (`ExecutionRepository`) so the common case — a
 * database that answers slowly — is cancelled at the statement and frees its thread. What each
 * timeout means is stated where it is caught: a RUNNING insert past the bound is `recorded = false`
 * (fail-closed for scheduled runs, WARN-and-continue for interactive ones), a terminal UPDATE past
 * it leaves the row RUNNING for the stale sweep and is counted
 * (`datapipelines.executions.lifecycle_write_failed`) — never a fabricated COMPLETED.
 *
 * ## Failure policy
 * A persistence failure is logged and **swallowed** — deliberately, and only here. dag-executor
 * §10 requires the emitter never to throw: an exception raised inside `emit` propagates into the
 * executor's coroutine and would fail an execution whose SQL all succeeded, because a bookkeeping
 * row could not be written. The live stream and the durable row are independent for the same
 * reason: losing one must not cost the other. The one exception is the fail-closed rule below.
 */
class WebEventEmitter(
    private val context: ExecutionContext,
    private val stream: ExecutionStream?,
    private val streams: ExecutionStreamRegistry,
    eventLog: SseEventLog,
    eventRepository: ExecutionEventRepository,
    private val executionRepository: ExecutionRepository,
    private val persistenceDispatcher: CoroutineDispatcher,
    /**
     * #311 — the bound on the two direct lifecycle writes (the RUNNING insert, the terminal
     * UPDATE), from `datapipelines.executor.lifecycle-write-timeout-seconds`. `emit` waits at most
     * this long for each; past it the caller returns and the statement keeps running on
     * [persistenceDispatcher] (see the class KDoc). [Duration.ZERO] — the default every direct
     * construction gets — means unbounded, the pre-#311 shape; the application passes the key's
     * value through [co.datapipelines.executor.ExecutorConfig].
     */
    private val lifecycleWriteTimeout: Duration = Duration.ZERO,
    /**
     * #311 — counts `datapipelines.executions.lifecycle_write_failed` when the terminal UPDATE
     * fails or does not return within [lifecycleWriteTimeout]: the row is left RUNNING for the
     * stale sweep, and the counter is how an operator tells that apart from silence. Null in
     * direct constructions that carry no metrics; the application passes `ExecutorMetrics`.
     */
    private val metrics: ExecutorMetrics? = null,
    /**
     * #9 A14 — the scheduled path's FAIL-CLOSED rule (scheduler design revision §2.1). When true, a
     * `pipeline_executions` RUNNING row that cannot be written stops the execution before its
     * first node: [emit] of `execution_started` throws [ExecutionRecordUnwritableException] into
     * the executor, which unwinds before staging is even created. That is what lets the scheduler
     * treat "no execution row" as "no node ran". False (every interactive surface): the insert
     * failure is logged and the run goes on, exactly as §10's never-throw policy says.
     */
    private val failClosedOnRecord: Boolean = false,
    /**
     * #266 — where the event's durable row and replay-log entry go. Defaults to the direct path —
     * one write per event per store on [persistenceDispatcher], the shape before #266 and the one
     * `datapipelines.persistence.enabled: false` restores; the application passes the batched
     * recorder. Not function-typed, and placed before the two hooks, so [onExecutionStarted] stays
     * the last parameter (see its note).
     */
    eventRecorder: ExecutionEventRecorder? = null,
    /**
     * #9 — invoked with the execution id once its RUNNING row is durably written (after
     * [onExecutionStarted], which runs BEFORE persistence). The scheduler's adapter waits on it to
     * report "started" only for a recorded execution. A failing hook is logged, never thrown.
     */
    private val onRecorded: (UUID) -> Unit = {},
    /**
     * Invoked with the executor-minted execution id the moment `execution_started` is seen — the
     * first point any code outside the executor learns it, before the RUNNING row (#306) and
     * before the send. The execute launcher uses this to register the live stream and rebind the
     * idempotency reservation (which had to be claimed *before* the id existed) onto the real id;
     * see `ExecutionStreamLauncher`.
     *
     * Keep it the LAST parameter: callers pass it as a trailing lambda, and Kotlin binds a trailing
     * lambda to whichever function-typed parameter is last. #9 once appended [onRecorded] after it,
     * which silently moved the launcher's stream registration after persistence — the live stream
     * lost `execution_started` (`WebEventEmitterTest` pins the order now).
     */
    private val onExecutionStarted: (UUID) -> Unit = {},
) : EventEmitter {
    private val log = LoggerFactory.getLogger(WebEventEmitter::class.java)
    private val eventRecorder: ExecutionEventRecorder =
        eventRecorder ?: DirectEventRecorder(eventRepository, eventLog, persistenceDispatcher)
    private val projection = SseEventProjection(context.correlationId)
    private val executionId = AtomicReference<UUID?>(null)
    private val nextEventId =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val emitted =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** The execution id, once `execution_started` has been seen. Null before that. */
    fun executionIdOrNull(): UUID? = executionId.get()

    /**
     * True once any event has passed through [emit]. The launcher reads this to decide whether a
     * failure escaping `execute()` can still become an HTTP error response (nothing sent → the
     * stream's response is uncommitted) or must simply close the stream (events already flowed).
     */
    fun emittedAny(): Boolean = emitted.get()

    override suspend fun emit(event: ExecutionEvent) {
        emitted.set(true)
        if (event is ExecutionStarted) {
            // The hook runs BEFORE everything: it is where the launcher registers this execution's
            // stream (the executor mints the id, so registration cannot happen earlier).
            executionId.set(event.executionId)
            runCatching { onExecutionStarted(event.executionId) }
                .onFailure { log.warn("onExecutionStarted hook failed for execution {}.", event.executionId, it) }
            // #306 — the RUNNING row is committed BEFORE the first frame: the event's own payload
            // carries the execution id, and a client that cancels or reads on that id the moment the
            // frame lands must be answered 204/200, never `404 result.execution_not_found`. The
            // scheduler's start barrier waits on `onRecorded`, which still fires right after the
            // insert — the row now also precedes every event row AND every frame.
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val recorded = createExecutionRow(event)
                if (!recorded && failClosedOnRecord) throw ExecutionRecordUnwritableException(event.executionId)
                if (recorded) {
                    runCatching { onRecorded(event.executionId) }
                        .onFailure { log.warn("onRecorded hook failed for execution {}.", event.executionId, it) }
                }
            }
            currentCoroutineContext().ensureActive()
        }
        val target = stream ?: streams.find(event.executionId)
        // The counter lives on the emitter, not on the stream: `event_id` is monotonic **per
        // execution** (§6.3/§6.7) and the durable record's UNIQUE (execution_id, event_id)
        // constraint says so too. Events keep flowing while no consumer is attached (§10), so a
        // counter owned by a stream would restart — or vanish — exactly when nobody is watching.
        val eventId = nextEventId.incrementAndGet()
        val name = projection.eventName(event)
        val payload = projection.payload(event)

        if (target != null) {
            target.send(name, eventId, payload)
            if (event.isTerminalOnTheWire()) target.markTerminal(name)
        }

        // The refusal point the single `withContext(persistenceDispatcher)` hop always had: an emit on
        // an already-cancelled job persists nothing (PipelineExecutor.emitTerminal's NonCancellable is
        // what keeps the terminal emit out of this case).
        currentCoroutineContext().ensureActive()
        // Once started, the writes finish — as the blocking hop's did — and a cancellation that
        // landed meanwhile is rethrown afterwards, as the hop's completion rethrew it.
        withContext(NonCancellable) { persist(event, eventId, name, payload) }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun persist(
        event: ExecutionEvent,
        eventId: Int,
        name: String,
        payload: Map<String, Any?>,
    ) {
        // The execution row already exists: for `execution_started` it was persisted before the
        // send (#306), and every other event arrives after it — `execution_events.execution_id` is
        // a foreign key onto pipeline_executions (metadata-db §4.7).
        eventRecorder.record(RecordedEvent(event.executionId, eventId, event.type, name, event.timestamp, payload))

        // The terminal UPDATE after the terminal event's own row, as always — direct, never batched.
        if (event.completesTheRow()) completeExecutionRow(event)
    }

    /**
     * Inserts the RUNNING row; false when the insert failed or did not return within
     * [lifecycleWriteTimeout] (logged either way — the false drives the fail-closed rule).
     */
    private suspend fun createExecutionRow(event: ExecutionStarted): Boolean =
        runCatching {
            withinLifecycleBound {
                executionRepository.create(recordFor(event))
            }
        }.onFailure { reportLifecycleWriteFailure(event.executionId, "not created", "not written yet", it) }
            .isSuccess

    private fun recordFor(event: ExecutionStarted) =
        ExecutionRecord(
            executionId = event.executionId,
            pipelineId = context.pipelineId,
            pipelineVersion = context.pipelineVersion,
            status = ExecutionStatus.RUNNING,
            parametersJson = context.parametersJson,
            executedBy = context.userId,
            executedByKeyKind = context.executedByKeyKind,
            triggeredVia = context.triggeredVia,
            correlationId = context.correlationId,
            startedAt = event.startedAt,
            parentExecutionId = context.parentExecutionId,
            parentNodeId = context.parentNodeId,
            rootExecutionId = context.rootExecutionId,
        )

    /**
     * The two lifecycle writes' caller-side bound (#311): hands [block] to [persistenceDispatcher]
     * and waits at most [lifecycleWriteTimeout]. Past the bound the WAIT returns — the statement
     * keeps running on the pool thread and may still land when the database recovers — and the
     * caller is told via [LifecycleWriteUnconfirmedException] so it can state the outcome honestly.
     * Unbounded ([Duration.ZERO]) keeps the plain blocking hop, the pre-#311 shape every direct
     * construction gets. The statements themselves carry the same bound as a JDBC `queryTimeout`
     * (`ExecutionRepository`), so a database that answers slowly is cancelled at the statement and
     * frees its thread; one that never answers is what this caller-side bound is for — measured,
     * a statement timeout cannot reach a paused Postgres.
     */
    private suspend fun <T> withinLifecycleBound(block: () -> T): T {
        if (lifecycleWriteTimeout.isZero()) return withContext(persistenceDispatcher) { block() }
        val done = CompletableDeferred<T>()
        persistenceDispatcher.dispatch(EmptyCoroutineContext) {
            runCatching { block() }.fold(onSuccess = done::complete, onFailure = done::completeExceptionally)
        }
        return withTimeoutOrNull(lifecycleWriteTimeout.toMillis()) { done.await() }
            ?: throw LifecycleWriteUnconfirmedException(lifecycleWriteTimeout)
    }

    /**
     * The single terminal UPDATE (metadata-db §4.6), bounded (#311).
     *
     * `data_ready` follows `pipeline_completed` and is not itself terminal, so the row is completed
     * on `pipeline_completed` and the result columns are filled by the caller afterwards from
     * `ExecutionResult` — the event carries no size, and inventing one from the inline page would
     * be wrong for any result larger than a page.
     *
     * A failure — or a write that outlives [lifecycleWriteTimeout] — is counted
     * (`datapipelines.executions.lifecycle_write_failed`) and logged with the execution id; the row
     * is left RUNNING for the stale sweep to reap. Never a fabricated COMPLETED: an unconfirmed
     * write is reported as unconfirmed, which is also why nothing retries it here (a retry would
     * double the wait and park a second pool thread on the same hung database).
     */
    private suspend fun completeExecutionRow(event: ExecutionEvent) {
        val (status, failedNodeId, errorJson) =
            when (event) {
                is PipelineCompleted -> {
                    Triple(ExecutionStatus.SUCCESS, null, null)
                }

                is PipelineFailed -> {
                    // 057: error_json is the SAME error object the wire carried — the projected
                    // map, not a bare serialization of the executor's record — so `GET
                    // /executions/{id}`, the detail page and MCP `executions_get` all read what
                    // the live stream showed, `user_message`/`doc_url`/`correlation_id` included.
                    val errorJson = SseJson.mapper.writeValueAsString(projection.errorPayload(event.error))
                    Triple(ExecutionStatus.FAILED, event.failedNodeId, errorJson)
                }

                is ExecutionAborted -> {
                    Triple(ExecutionStatus.ABORTED, null, null)
                }

                else -> {
                    return
                }
            }
        val nodeStats =
            when (event) {
                is PipelineCompleted -> event.nodeStats
                is PipelineFailed -> event.nodeStats
                is ExecutionAborted -> event.nodeStats
            }
        // 072 §0.5: the row's `parameters_json` becomes the FULLY RESOLVED Context the nodes saw —
        // org config, platform keys, parameters, inputs and every calculator output. SseJson's
        // mapper, not ExecutorJson's: the Context holds java.time values, and the one that cannot
        // serialize them is exactly how execution_started went missing from the durable record
        // once before (T36). A serialization failure leaves the insert-time value in place rather
        // than losing the terminal UPDATE with it.
        val contextJson =
            when (event) {
                is PipelineCompleted -> event.contextSnapshot
                is PipelineFailed -> event.contextSnapshot
                is ExecutionAborted -> event.contextSnapshot
            }.takeIf { it.isNotEmpty() }?.let { serializedContext(event.executionId, it) }
        runCatching {
            withinLifecycleBound {
                executionRepository.complete(
                    executionId = event.executionId,
                    status = status,
                    completedAt = event.timestamp,
                    durationMs = durationMsOf(event),
                    nodeStatsJson = SseJson.mapper.writeValueAsString(nodeStats),
                    failedNodeId = failedNodeId,
                    errorJson = errorJson,
                    contextJson = contextJson,
                )
            }
        }.onSuccess { updated -> if (!updated) reportTerminalRowMissing(event.executionId) }
            .onFailure {
                metrics?.lifecycleWriteFailed()
                reportLifecycleWriteFailure(event.executionId, "not completed", "left RUNNING for the stale sweep", it)
            }
    }

    /**
     * The UPDATE matched no row: the RUNNING insert was abandoned past its bound or never landed (the
     * composition case, #325). Counted like a failed terminal write — the row, if it lands late, is RUNNING
     * and the stale sweep reaps it; nothing here fabricates a COMPLETED.
     */
    private fun reportTerminalRowMissing(executionId: UUID) {
        metrics?.lifecycleWriteFailed()
        log.warn(
            "pipeline_executions row for execution {} not found at completion (0 rows updated) — " +
                "a late RUNNING insert is left for the stale sweep.",
            executionId,
        )
    }

    /** Class and SQLState only (observability §3.4G): the driver's message quotes the statement. */
    private fun reportLifecycleWriteFailure(
        executionId: UUID,
        what: String,
        pastBound: String,
        failure: Throwable,
    ) {
        if (failure is LifecycleWriteUnconfirmedException) {
            log.warn(
                "pipeline_executions row for execution {} did not return within {} — {}.",
                executionId,
                lifecycleWriteTimeout,
                pastBound,
            )
        } else {
            log.warn(
                "pipeline_executions row for execution {} {}: error={} sql_state={}",
                executionId,
                what,
                failure.javaClass.simpleName,
                FailureShape.sqlState(failure),
            )
        }
    }

    private fun durationMsOf(event: ExecutionEvent): Long =
        when (event) {
            is PipelineCompleted -> event.durationMs

            is PipelineFailed -> event.durationMs

            // `execution_aborted` carries no duration; derive it from the row's own start instant
            // rather than writing null — metadata-db §8.3 (F1) requires every terminal row to
            // carry one, so a consumer never has to special-case the aborted shape.
            else -> abortedDurationMs(event.executionId, event)
        }

    /**
     * The context snapshot serialized for the terminal row (#336 D5): a failure is one WARN —
     * the designed degradation (the row keeps its insert-time value) still says so, because a
     * terminal record that silently kept stale parameters looked exactly like a fresh one.
     */
    private fun serializedContext(
        executionId: UUID,
        snapshot: Map<String, Any?>,
    ): String? =
        runCatching { SseJson.mapper.writeValueAsString(snapshot) }
            .onFailure {
                log.warn(
                    "execution {} context snapshot could not be serialized — the row keeps its" +
                        " insert-time parameters: error={} sql_state={}",
                    executionId,
                    FailureShape.cause(it),
                    FailureShape.sqlState(it),
                )
            }.getOrNull()

    private fun abortedDurationMs(
        executionId: UUID,
        event: ExecutionEvent,
    ): Long =
        runCatching {
            executionRepository.findById(context.workspaceId, executionId)?.let {
                java.time.Duration
                    .between(it.startedAt, event.timestamp)
                    .toMillis()
            }
        }.onFailure {
            // #336 D5: metadata-db §8.3 (F1) requires every terminal row to carry a duration,
            // so the read failure records the 0 sentinel — but the record must SAY the duration
            // is unknown, never present it as measured.
            log.warn(
                "execution {} aborted duration could not be read — recorded 0 (unknown): error={} sql_state={}",
                executionId,
                FailureShape.cause(it),
                FailureShape.sqlState(it),
            )
        }.getOrNull() ?: 0

    private fun ExecutionEvent.completesTheRow(): Boolean = this is PipelineCompleted || this is PipelineFailed || this is ExecutionAborted

    private fun ExecutionEvent.isTerminalOnTheWire(): Boolean =
        this is PipelineCompleted || this is PipelineFailed || this is ExecutionAborted || this is DataReady
}

/**
 * #9 A14: a scheduled execution's RUNNING row could not be written, so it stops before its first
 * node (the scheduler then records the run `not_started` / `record_unwritable` — no row, no node).
 * Thrown only by an emitter built with `failClosedOnRecord = true`.
 */
class ExecutionRecordUnwritableException(
    val executionId: UUID,
) : IllegalStateException("The execution record of $executionId could not be written; the scheduled run stops before its first node.")

/**
 * #311: a lifecycle write (the RUNNING insert, the terminal UPDATE) did not return within the
 * emitter's bound. "Not confirmed" — the statement may still be running on the pool thread and
 * may land when the database recovers — so the callers report the outcome honestly: the insert's
 * false drives the fail-closed rule, the terminal UPDATE leaves the row RUNNING for the stale
 * sweep and is counted. Never thrown as a CancellationException subtype: the await's timeout is
 * converted here, inside the `NonCancellable` block, so no caller can mistake it for the job's
 * own cancellation.
 */
private class LifecycleWriteUnconfirmedException(
    val bound: Duration,
) : RuntimeException("The lifecycle write did not return within $bound; its outcome is unconfirmed.")
