package co.datapipelines.web.sse

import co.datapipelines.events.SseEventType
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.persistence.BatchSink
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.persistence.FailureKinds
import co.datapipelines.persistence.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

/** One event's bookkeeping, as [WebEventEmitter] hands it over — already projected, id already assigned. */
data class RecordedEvent(
    val executionId: UUID,
    val eventId: Int,
    val type: SseEventType,
    val name: String,
    val timestamp: Instant,
    val payload: Map<String, Any?>,
)

/**
 * Where an execution event's two records go (dag-executor §10): the durable `execution_events`
 * row (`dag`'s repository, 7 days) and the 1-hour Redis replay log (`web`'s [SseEventLog]).
 *
 * [record] returns once both are settled — written, or known not to be — and NEVER throws: a
 * failure is logged at WARN and swallowed, the emitter's failure policy since #3 (a bookkeeping row
 * that cannot be written must not fail an execution whose SQL succeeded). The RUNNING row, the
 * fail-closed rule and the terminal UPDATE are not a recorder's business; they stay in the emitter.
 */
interface ExecutionEventRecorder {
    suspend fun record(event: RecordedEvent)
}

/**
 * One write per event per store, on [dispatcher] — the path every emitter took before #266, and the
 * one `datapipelines.persistence.enabled: false` restores. The emitter's default, so a test that
 * builds an emitter over mocked repositories exercises exactly the calls it always did.
 */
class DirectEventRecorder(
    private val eventRepository: ExecutionEventRepository,
    private val eventLog: SseEventLog,
    private val dispatcher: CoroutineDispatcher,
) : ExecutionEventRecorder {
    private val log = LoggerFactory.getLogger(DirectEventRecorder::class.java)

    override suspend fun record(event: RecordedEvent): Unit =
        withContext(dispatcher) {
            runCatching {
                eventRepository.append(
                    executionId = event.executionId,
                    eventId = event.eventId,
                    type = event.type,
                    timestamp = event.timestamp,
                    // SseJson: the payload carries resolved parameters, so a DATE/TIME pipeline puts
                    // java.time values here. ExecutorJson cannot serialize them and the runCatching
                    // below swallows the failure — which is why this silently dropped execution_started
                    // from the durable record instead of failing loudly. T36, third path.
                    payloadJson = SseJson.mapper.writeValueAsString(event.payload),
                )
            }.onFailure { log.warn("Durable event {} for execution {} not written.", event.name, event.executionId, it) }
            eventLog.append(event.executionId, LoggedSseEvent(event.eventId, event.name, event.payload))
        }
}

/**
 * The batched path (#266): both records go through a [BatchingWriter] — one per store, partitioned
 * by execution id, so one execution's events are written in emit order while N executions share
 * each commit. Still AWAITED: [record] returns only once both writers report the item's outcome,
 * exactly as the direct path returned only once both writes had run. The two stores are waited on
 * concurrently; neither's cadence touches the live stream, which the emitter sends before it
 * records anything.
 *
 * Bounded (dag-executor §10): each writer waits at most `record-max-wait-ms` for its batch and as
 * long again for the direct fallback (run on [directExecutor], the persistence pool — never the
 * executor's dispatcher), so a store that has stopped answering costs the emit a bounded wait and an
 * `indeterminate` count, never a hang — the executor's terminal emit runs under `NonCancellable`.
 */
class BatchedEventRecorder(
    private val rows: BatchingWriter<ExecutionEventRecord>,
    private val replay: BatchingWriter<ReplayLogEntry>,
    private val eventLog: SseEventLog,
    private val directExecutor: Executor,
) : ExecutionEventRecorder {
    private val log = LoggerFactory.getLogger(BatchedEventRecorder::class.java)

    override suspend fun record(event: RecordedEvent): Unit =
        coroutineScope {
            // Serialized here, once, so a payload that cannot be serialized fails BEFORE it is queued
            // (T36: SseJson, never ExecutorJson — the payload carries java.time values).
            val row =
                runCatching {
                    ExecutionEventRecord(
                        event.executionId,
                        event.eventId,
                        event.type.wire,
                        event.timestamp,
                        SseJson.mapper.writeValueAsString(event.payload),
                    )
                }.onFailure { log.warn("Durable event {} for execution {} not written.", event.name, event.executionId, it) }
                    .getOrNull()
            val entry =
                runCatching { eventLog.entry(event.executionId, LoggedSseEvent(event.eventId, event.name, event.payload)) }
                    .onFailure {
                        log.warn(
                            "SSE event log append failed for execution {} (replay will be incomplete).",
                            event.executionId,
                            it,
                        )
                    }.getOrNull()
            val rowOutcome = row?.let { async { rows.recordSuspending(it, directExecutor) } }
            val entryOutcome = entry?.let { async { replay.recordSuspending(it, directExecutor) } }
            rowOutcome?.await()?.let { reportRow(event, it) }
            entryOutcome?.await()?.let { reportReplay(event, it) }
        }

    private fun reportRow(
        event: RecordedEvent,
        outcome: Outcome,
    ) {
        when (outcome) {
            Outcome.Committed -> {}

            // The writer has already logged the cause with the item's ids; this is the emitter's
            // own line, the one operators have always grepped for.
            is Outcome.Failed -> {
                log.warn("Durable event {} for execution {} not written ({}).", event.name, event.executionId, outcome.kind)
            }

            Outcome.Indeterminate -> {
                log.warn(
                    "Durable event {} for execution {}: the store did not answer in time; the row may still land.",
                    event.name,
                    event.executionId,
                )
            }
        }
    }

    private fun reportReplay(
        event: RecordedEvent,
        outcome: Outcome,
    ) {
        if (outcome != Outcome.Committed) {
            log.warn(
                "SSE event log append failed for execution {} (replay will be incomplete): {}.",
                event.executionId,
                (outcome as? Outcome.Failed)?.kind ?: FailureKinds.INDETERMINATE,
            )
        }
    }
}

/**
 * The durable record's side of its writer: [ExecutionEventRepository.appendAll] — one batch, one
 * transaction, retry-safe by its conflict clause. The direct path is the same statement for one
 * row, so a direct write that lands after its caller gave up is a no-op against a re-sent copy.
 */
class ExecutionEventRowSink(
    private val repository: ExecutionEventRepository,
) : BatchSink<ExecutionEventRecord> {
    override fun write(items: List<ExecutionEventRecord>) = repository.appendAll(items)

    override fun partitionKey(item: ExecutionEventRecord): Any = item.executionId

    override fun sizeOf(item: ExecutionEventRecord): Int = item.payloadJson.length + ROW_OVERHEAD_BYTES

    override fun describe(item: ExecutionEventRecord): String =
        "execution=${item.executionId} event_id=${item.eventId} type=${item.eventType}"

    /**
     * A row the database refuses — a taken sequence number with different content, a payload
     * JSONB cannot hold (a NUL character), a foreign key — is `poison`: retrying it cannot help,
     * and it must not take its batch down with it. Anything else is the store being unavailable.
     */
    override fun classify(failure: Throwable): String =
        if (failure is DataIntegrityViolationException) FailureKinds.POISON else FailureKinds.WRITE_FAILED

    private companion object {
        /** The fixed columns and the row header, roughly — the bound is a memory bound, not an accounting. */
        const val ROW_OVERHEAD_BYTES = 96
    }
}

/** The replay log's side of its writer: [SseEventLog.appendAll] — one pipelined `MULTI`/`EXEC` per batch. */
class ReplayLogSink(
    private val eventLog: SseEventLog,
) : BatchSink<ReplayLogEntry> {
    override fun write(items: List<ReplayLogEntry>) = eventLog.appendAll(items)

    override fun partitionKey(item: ReplayLogEntry): Any = item.executionId

    override fun sizeOf(item: ReplayLogEntry): Int = item.json.length

    override fun describe(item: ReplayLogEntry): String = "execution=${item.executionId} event_id=${item.eventId}"
}
