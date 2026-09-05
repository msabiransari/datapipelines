package co.datapipelines.events

import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.MappedError
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.StoredResultView
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.TypeMappingWarning
import java.time.Instant
import java.util.UUID

/**
 * The SSE event vocabulary (enums.md §11).
 *
 * enums.md names **rest-api** as the authoring spec and `dag-executor` as a consumer. That
 * consumption cannot be an import: `web` (which implements rest-api) sits at layer 5 and depends
 * on `dag` at layer 3, never the reverse (module-structure §4.2). The executor is what emits
 * these events and what writes `execution_events.event_type`, so the enum is declared here, at
 * the lowest layer that needs it, and `web` consumes it — the same resolution
 * `staging.StagingEngine` documents for its own cross-module enum.
 */
enum class SseEventType(
    val wire: String,
) {
    EXECUTION_STARTED("execution_started"),
    NODE_STARTED("node_started"),
    NODE_COMPLETED("node_completed"),
    NODE_FAILED("node_failed"),
    PIPELINE_COMPLETED("pipeline_completed"),
    PIPELINE_FAILED("pipeline_failed"),
    EXECUTION_ABORTED("execution_aborted"),
    DATA_READY("data_ready"),
}

/**
 * One execution-stream event (dag-executor.md §10).
 *
 * Wire names, payload keys and ordering guarantees are owned by
 * [REST API §6.4](../../../../../../../docs/rest-api.md); this hierarchy says only *which* event
 * the executor emits *where*, and carries the data each payload needs.
 *
 * The emission rules the executor must honour (§10):
 *  - per node: exactly one `node_started`, then exactly one of `node_completed` **or**
 *    `node_failed` — never both;
 *  - exactly one terminal event per execution: `pipeline_completed` (optionally followed by
 *    `data_ready`), `pipeline_failed`, or `execution_aborted`;
 *  - `data_ready` is built from the **stored** result and is skipped entirely for a pipeline with
 *    no caller node.
 */
sealed class ExecutionEvent {
    abstract val executionId: UUID
    abstract val timestamp: Instant

    /** The SSE `event:` name this payload is published under. */
    abstract val type: SseEventType
}

/** First event, exactly once (rest-api §6.4.1). */
data class ExecutionStarted(
    override val executionId: UUID,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val parameters: Map<String, Any?>,
    val correlationId: UUID? = null,
    val startedAt: Instant = Instant.now(),
) : ExecutionEvent() {
    override val timestamp: Instant get() = startedAt
    override val type: SseEventType get() = SseEventType.EXECUTION_STARTED
}

/** A node began executing, after its dependencies completed (rest-api §6.4.2). */
data class NodeStarted(
    override val executionId: UUID,
    val nodeId: String,
    val startedAt: Instant = Instant.now(),
    /** v1 has no per-node retries (§11.1), so this is always 1; the field is the wire's. */
    val attempt: Int = 1,
) : ExecutionEvent() {
    override val timestamp: Instant get() = startedAt
    override val type: SseEventType get() = SseEventType.NODE_STARTED
}

/** A node finished **successfully** — never emitted for a failed node (rest-api §6.4.3). */
data class NodeCompleted(
    override val executionId: UUID,
    val nodeId: String,
    val stats: NodeStats,
) : ExecutionEvent() {
    override val timestamp: Instant get() = stats.completedAt ?: Instant.now()
    override val type: SseEventType get() = SseEventType.NODE_COMPLETED
}

/** A node failed; the execution then halts fail-fast (rest-api §6.4.4). */
data class NodeFailed(
    override val executionId: UUID,
    val nodeId: String,
    val error: MappedError,
    val stats: NodeStats,
) : ExecutionEvent() {
    override val timestamp: Instant get() = stats.completedAt ?: Instant.now()
    override val type: SseEventType get() = SseEventType.NODE_FAILED
}

/** Terminal success event, emitted immediately before `data_ready` (rest-api §6.4.5). */
data class PipelineCompleted(
    override val executionId: UUID,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMs: Long,
    val nodeStats: List<NodeStats>,
    /**
     * The FULLY RESOLVED Context the nodes saw (calculators design §0.5) — org config, platform
     * keys, parameters, execute-time inputs and every calculator output, as of the end of the run.
     *
     * Carried on the terminal events rather than pushed by the executor, because the executor
     * owns no database: `WebEventEmitter` is what writes `pipeline_executions`, and it writes this
     * in the same terminal UPDATE that records the status. Empty on an execution that never
     * reached the Context — a setup failure — so the row keeps whatever it had.
     */
    val contextSnapshot: Map<String, Any?> = emptyMap(),
) : ExecutionEvent() {
    override val timestamp: Instant get() = completedAt
    override val type: SseEventType get() = SseEventType.PIPELINE_COMPLETED
}

/** Terminal failure event (rest-api §6.4.6). Also the terminal event for an execution timeout. */
data class PipelineFailed(
    override val executionId: UUID,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val startedAt: Instant,
    val failedAt: Instant,
    val durationMs: Long,
    /** Null when the failure was not attributable to a node (e.g. a timeout with none in flight). */
    val failedNodeId: String?,
    val error: MappedError,
    val nodeStats: List<NodeStats>,
    /**
     * The FULLY RESOLVED Context the nodes saw (calculators design §0.5) — org config, platform
     * keys, parameters, execute-time inputs and every calculator output, as of the end of the run.
     *
     * Carried on the terminal events rather than pushed by the executor, because the executor
     * owns no database: `WebEventEmitter` is what writes `pipeline_executions`, and it writes this
     * in the same terminal UPDATE that records the status. Empty on an execution that never
     * reached the Context — a setup failure — so the row keeps whatever it had.
     */
    val contextSnapshot: Map<String, Any?> = emptyMap(),
) : ExecutionEvent() {
    override val timestamp: Instant get() = failedAt
    override val type: SseEventType get() = SseEventType.PIPELINE_FAILED
}

/** Terminal cancellation event — the D7 abort paths (rest-api §6.4.8). Carries no error code. */
data class ExecutionAborted(
    override val executionId: UUID,
    val pipelineId: UUID,
    val reason: AbortReason,
    val abortedAt: Instant,
    val nodeStats: List<NodeStats>,
    /**
     * The FULLY RESOLVED Context the nodes saw (calculators design §0.5) — org config, platform
     * keys, parameters, execute-time inputs and every calculator output, as of the end of the run.
     *
     * Carried on the terminal events rather than pushed by the executor, because the executor
     * owns no database: `WebEventEmitter` is what writes `pipeline_executions`, and it writes this
     * in the same terminal UPDATE that records the status. Empty on an execution that never
     * reached the Context — a setup failure — so the row keeps whatever it had.
     */
    val contextSnapshot: Map<String, Any?> = emptyMap(),
) : ExecutionEvent() {
    override val timestamp: Instant get() = abortedAt
    override val type: SseEventType get() = SseEventType.EXECUTION_ABORTED
}

/**
 * Result stored and ready (rest-api §6.4.7) — emitted **only** when the pipeline has a caller
 * node, and built from the stored result rather than from the ResultSet.
 */
data class DataReady(
    override val executionId: UUID,
    val pipelineId: UUID,
    val schema: List<ColumnSchema>,
    val rows: List<List<Any?>>,
    val totalRows: Long,
    val hasMore: Boolean,
    val resultUrl: String,
    val expiresAt: Instant,
    val ttlSeconds: Long,
    val warnings: List<TypeMappingWarning> = emptyList(),
    val emittedAt: Instant = Instant.now(),
) : ExecutionEvent() {
    /** Rows in this inline first page — `row_count` on the wire. */
    val rowCount: Int get() = rows.size

    override val timestamp: Instant get() = emittedAt
    override val type: SseEventType get() = SseEventType.DATA_READY

    companion object {
        /** Builds the payload from a stored result (§6.4.2). */
        fun from(
            pipelineId: UUID,
            view: StoredResultView,
            resultUrl: String,
            ttlSeconds: Long,
        ): DataReady =
            DataReady(
                executionId = view.executionId,
                pipelineId = pipelineId,
                schema = view.schema,
                rows = view.firstPage,
                totalRows = view.totalRows,
                hasMore = view.hasMore,
                resultUrl = resultUrl,
                expiresAt = view.expiresAt,
                ttlSeconds = ttlSeconds,
                warnings = view.warnings,
            )
    }
}
