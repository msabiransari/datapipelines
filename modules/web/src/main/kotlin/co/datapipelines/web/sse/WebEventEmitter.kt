package co.datapipelines.web.sse

import co.datapipelines.events.DataReady
import co.datapipelines.events.EventEmitter
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything one execution needs recorded that the executor does not know (rest-api §10.2,
 * metadata-db §4.6).
 *
 * `triggered_via` and `triggered_by` are **not** on the event wire — `ExecuteRequest` carries them
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
 * ## Dispatching
 * Steps 3 and 4 are blocking JDBC and Redis calls, and `emit` is invoked **on the executor's own
 * bounded dispatcher** (dag-executor §15.2 — a pool sized for SQL work, not for the surface's
 * bookkeeping). They therefore run under [persistenceDispatcher], a dispatcher `web` owns. Each
 * `emit` awaits its own persistence before returning, which is what preserves event order: the
 * executor calls `emit` sequentially, so an awaited hand-off cannot reorder. Fire-and-forget would
 * be faster and would let `data_ready` land in the table before `pipeline_completed`.
 *
 * ## Failure policy
 * A persistence failure is logged and **swallowed** — deliberately, and only here. dag-executor
 * §10 requires the emitter never to throw: an exception raised inside `emit` propagates into the
 * executor's coroutine and would fail an execution whose SQL all succeeded, because a bookkeeping
 * row could not be written. The live stream and the durable row are independent for the same
 * reason: losing one must not cost the other.
 */
class WebEventEmitter(
    private val context: ExecutionContext,
    private val stream: ExecutionStream?,
    private val streams: ExecutionStreamRegistry,
    private val eventLog: SseEventLog,
    private val eventRepository: ExecutionEventRepository,
    private val executionRepository: ExecutionRepository,
    private val persistenceDispatcher: CoroutineDispatcher,
    /**
     * Invoked with the executor-minted execution id the moment `execution_started` is persisted —
     * the first point any code outside the executor learns it. The execute launcher uses this to
     * rebind the idempotency reservation (which had to be claimed *before* the id existed) onto the
     * real id; see `ExecutionStreamLauncher`.
     */
    private val onExecutionStarted: (UUID) -> Unit = {},
) : EventEmitter {
    private val log = LoggerFactory.getLogger(WebEventEmitter::class.java)
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
            // The hook runs BEFORE the stream lookup: it is where the launcher registers this
            // execution's stream (the executor mints the id, so registration cannot happen
            // earlier), and `execution_started` itself must already reach the client.
            executionId.set(event.executionId)
            runCatching { onExecutionStarted(event.executionId) }
                .onFailure { log.warn("onExecutionStarted hook failed for execution {}.", event.executionId, it) }
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

        withContext(persistenceDispatcher) {
            persist(event, eventId, name, payload)
        }
    }

    private fun persist(
        event: ExecutionEvent,
        eventId: Int,
        name: String,
        payload: Map<String, Any?>,
    ) {
        // The execution row must exist before any event row: execution_events.execution_id is a
        // foreign key onto pipeline_executions (metadata-db §4.7).
        if (event is ExecutionStarted) createExecutionRow(event)

        runCatching {
            eventRepository.append(
                executionId = event.executionId,
                eventId = eventId,
                type = event.type,
                timestamp = event.timestamp,
                // SseJson: the payload carries resolved parameters, so a DATE/TIME pipeline puts
                // java.time values here. ExecutorJson cannot serialize them and the runCatching
                // below swallows the failure — which is why this silently dropped execution_started
                // from the durable record instead of failing loudly. T36, third path.
                payloadJson = SseJson.mapper.writeValueAsString(payload),
            )
        }.onFailure { log.warn("Durable event {} for execution {} not written.", name, event.executionId, it) }

        eventLog.append(event.executionId, LoggedSseEvent(eventId, name, payload))

        completeExecutionRow(event)
    }

    private fun createExecutionRow(event: ExecutionStarted) {
        runCatching {
            executionRepository.create(
                ExecutionRecord(
                    executionId = event.executionId,
                    pipelineId = context.pipelineId,
                    pipelineVersion = context.pipelineVersion,
                    status = ExecutionStatus.RUNNING,
                    parametersJson = context.parametersJson,
                    triggeredBy = context.userId,
                    triggeredVia = context.triggeredVia,
                    correlationId = context.correlationId,
                    startedAt = event.startedAt,
                    parentExecutionId = context.parentExecutionId,
                    parentNodeId = context.parentNodeId,
                    rootExecutionId = context.rootExecutionId,
                ),
            )
        }.onFailure { log.error("pipeline_executions row for execution {} not created.", event.executionId, it) }
    }

    /**
     * The single terminal UPDATE (metadata-db §4.6).
     *
     * `data_ready` follows `pipeline_completed` and is not itself terminal, so the row is completed
     * on `pipeline_completed` and the result columns are filled by the caller afterwards from
     * `ExecutionResult` — the event carries no size, and inventing one from the inline page would
     * be wrong for any result larger than a page.
     */
    private fun completeExecutionRow(event: ExecutionEvent) {
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
        runCatching {
            executionRepository.complete(
                executionId = event.executionId,
                status = status,
                completedAt = event.timestamp,
                durationMs = durationMsOf(event),
                nodeStatsJson = SseJson.mapper.writeValueAsString(nodeStats),
                failedNodeId = failedNodeId,
                errorJson = errorJson,
            )
        }.onFailure { log.error("pipeline_executions row for execution {} not completed.", event.executionId, it) }
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
        }.getOrNull() ?: 0

    private fun ExecutionEvent.isTerminalOnTheWire(): Boolean =
        this is PipelineCompleted || this is PipelineFailed || this is ExecutionAborted || this is DataReady
}
