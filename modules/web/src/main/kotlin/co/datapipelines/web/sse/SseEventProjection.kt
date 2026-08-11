package co.datapipelines.web.sse

import co.datapipelines.events.DataReady
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeFailed
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.MappedError
import co.datapipelines.web.api.ApiErrorCatalog
import java.util.UUID

/**
 * Projects an executor [ExecutionEvent] onto its SSE wire payload
 * ([REST API §6.4](../../../../../../../docs/rest-api.md)).
 *
 * The executor's event objects are **not** the wire payloads. dag-executor §10 names three fields
 * that are computed here rather than read, and all three are implemented below:
 *
 *  - `correlation_id` (every event) — the id of the request that started the execution, threaded
 *    in by this class because only `execution_started` carries one on the executor's side.
 *    Normative (observability §3.3): a stream-only client must still be able to quote an id.
 *  - `node_failed.failed_at` — `NodeStats.completedAt`; the executor keeps no separate failure
 *    timestamp.
 *  - `status` on the terminal events — derived from the event *type* (`SUCCESS` / `FAILED` /
 *    `ABORTED`). Deliberately not a field: the type already determines it, and two sources for
 *    one value is one too many.
 *
 * The projection is a pure function of (event, correlation id), which is what makes it testable
 * without a servlet, a Redis or an executor.
 */
class SseEventProjection(
    private val correlationId: UUID,
) {
    /** The `event:` name for [event] — the [co.datapipelines.events.SseEventType] wire value. */
    fun eventName(event: ExecutionEvent): String = event.type.wire

    /**
     * The `data:` payload for [event], with `correlation_id` stamped on **every** type.
     *
     * `when` over the sealed hierarchy with no `else`: a new event type added to `dag` becomes a
     * compile error here rather than an event that silently ships without its payload.
     */
    fun payload(event: ExecutionEvent): Map<String, Any?> {
        val body: Map<String, Any?> =
            when (event) {
                is ExecutionStarted -> startedPayload(event)
                is NodeStarted -> nodeStartedPayload(event)
                is NodeCompleted -> nodeCompletedPayload(event)
                is NodeFailed -> nodeFailedPayload(event)
                is PipelineCompleted -> completedPayload(event)
                is PipelineFailed -> failedPayload(event)
                is ExecutionAborted -> abortedPayload(event)
                is DataReady -> dataReadyPayload(event)
            }
        // Carry-forward #1: stamped last so no branch above can forget it, and so a future event
        // type cannot ship without it.
        return body + (CORRELATION_ID to correlationId)
    }

    private fun startedPayload(event: ExecutionStarted) =
        mapOf(
            EXECUTION_ID to event.executionId,
            PIPELINE_ID to event.pipelineId,
            PIPELINE_VERSION to event.pipelineVersion,
            STARTED_AT to event.startedAt,
            "parameters" to event.parameters,
        )

    private fun nodeStartedPayload(event: NodeStarted) =
        mapOf(
            EXECUTION_ID to event.executionId,
            NODE_ID to event.nodeId,
            STARTED_AT to event.startedAt,
            "attempt" to event.attempt,
        )

    private fun nodeCompletedPayload(event: NodeCompleted) =
        mapOf(
            EXECUTION_ID to event.executionId,
            NODE_ID to event.nodeId,
            STARTED_AT to event.stats.startedAt,
            "completed_at" to event.stats.completedAt,
            DURATION_MS to event.stats.durationMs,
            "rows_out" to event.stats.rowsOut,
            "bytes_out" to event.stats.bytesOut,
        )

    private fun nodeFailedPayload(event: NodeFailed) =
        mapOf(
            EXECUTION_ID to event.executionId,
            NODE_ID to event.nodeId,
            STARTED_AT to event.stats.startedAt,
            // dag-executor §10: `failed_at` IS the stats' completion instant.
            "failed_at" to event.stats.completedAt,
            DURATION_MS to event.stats.durationMs,
            ERROR to errorPayload(event.error),
        )

    private fun completedPayload(event: PipelineCompleted) =
        mapOf(
            EXECUTION_ID to event.executionId,
            PIPELINE_ID to event.pipelineId,
            PIPELINE_VERSION to event.pipelineVersion,
            STARTED_AT to event.startedAt,
            "completed_at" to event.completedAt,
            DURATION_MS to event.durationMs,
            STATUS to ExecutionStatus.SUCCESS.name,
            NODE_STATS to event.nodeStats,
        )

    private fun failedPayload(event: PipelineFailed) =
        mapOf(
            EXECUTION_ID to event.executionId,
            PIPELINE_ID to event.pipelineId,
            PIPELINE_VERSION to event.pipelineVersion,
            STARTED_AT to event.startedAt,
            "failed_at" to event.failedAt,
            DURATION_MS to event.durationMs,
            STATUS to ExecutionStatus.FAILED.name,
            "failed_node_id" to event.failedNodeId,
            ERROR to errorPayload(event.error),
            NODE_STATS to event.nodeStats,
        )

    private fun abortedPayload(event: ExecutionAborted) =
        mapOf(
            EXECUTION_ID to event.executionId,
            PIPELINE_ID to event.pipelineId,
            "aborted_at" to event.abortedAt,
            "reason" to event.reason.wire,
            STATUS to ExecutionStatus.ABORTED.name,
            NODE_STATS to event.nodeStats,
        )

    private fun dataReadyPayload(event: DataReady) =
        mapOf(
            EXECUTION_ID to event.executionId,
            PIPELINE_ID to event.pipelineId,
            "schema" to event.schema,
            "rows" to event.rows,
            "row_count" to event.rowCount,
            "total_rows" to event.totalRows,
            "has_more" to event.hasMore,
            "result_url" to event.resultUrl,
            "expires_at" to event.expiresAt,
            "ttl_seconds" to event.ttlSeconds,
            "warnings" to event.warnings,
        )

    /**
     * The `error` object of `node_failed` / `pipeline_failed` (rest-api §6.4.4).
     *
     * `user_message` and `doc_url` are not on [MappedError] — the executor deals in catalog codes,
     * and the human-facing text is a surface concern. They come from the same catalog the REST
     * error envelope uses, so one code produces one sentence everywhere.
     *
     * `details` passes through untouched: observability §9.3 makes it **normative** that no
     * `jdbc_url`, password or raw connection string appears there, and the executor's
     * `ErrorCodeMapper` already builds it to that rule. Re-filtering here would imply the producer
     * cannot be trusted while doing nothing to make it so.
     */
    private fun errorPayload(error: MappedError): Map<String, Any?> =
        mapOf(
            "code" to error.code,
            "message" to error.message,
            "user_message" to ApiErrorCatalog.userMessageFor(error.code),
            "details" to error.details,
            "doc_url" to ApiErrorCatalog.docUrl(error.code),
        )

    private companion object {
        const val EXECUTION_ID = "execution_id"
        const val PIPELINE_ID = "pipeline_id"
        const val PIPELINE_VERSION = "pipeline_version"
        const val NODE_ID = "node_id"
        const val STARTED_AT = "started_at"
        const val DURATION_MS = "duration_ms"
        const val STATUS = "status"
        const val NODE_STATS = "node_stats"
        const val ERROR = "error"
        const val CORRELATION_ID = "correlation_id"
    }
}
