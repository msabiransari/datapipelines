package co.datapipelines.executor

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration
import java.time.Instant

/** A node's outcome (dag-executor.md §7.2). `ABORTED` means the node never started. */
enum class NodeStatus {
    SUCCESS,
    FAILED,
    ABORTED,
}

/**
 * The executor's in-flight per-node value (dag-executor.md §7.1) — **internal**: it never crosses
 * the wire; [NodeStats] is its wire-facing projection.
 *
 * [callerResultRef] is a **reference, not data**: the Redis key produced by
 * `ResultStore.materialize` (§6.4.2). By the time a `NodeResult` exists the node's source
 * connection and `ResultSet` are already closed — nothing downstream may hold a JDBC cursor.
 */
data class NodeResult(
    val nodeId: String,
    val status: NodeStatus,
    /** Rows staged / written back / materialized; 0 for DDL. */
    val rowsOut: Long,
    /** Estimated encoded size; -1 when not measurable. */
    val bytesOutEstimate: Long,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMs: Long,
    val callerResultRef: String?,
) {
    companion object {
        /** A successful node's result; the duration is computed from [startedAt] to now. */
        fun of(
            nodeId: String,
            rowsOut: Long,
            startedAt: Instant,
            callerResultRef: String? = null,
            bytesOutEstimate: Long = NOT_MEASURED,
            completedAt: Instant = Instant.now(),
        ): NodeResult =
            NodeResult(
                nodeId = nodeId,
                status = NodeStatus.SUCCESS,
                rowsOut = rowsOut,
                bytesOutEstimate = bytesOutEstimate,
                startedAt = startedAt,
                completedAt = completedAt,
                durationMs = Duration.between(startedAt, completedAt).toMillis(),
                callerResultRef = callerResultRef,
            )

        /** The sentinel for a quantity that was not measured — §7.1's `-1`. */
        const val NOT_MEASURED = -1L
    }
}

/**
 * The wire-facing per-node projection (dag-executor.md §7.2), reported in `pipeline_completed` /
 * `pipeline_failed` / `execution_aborted` payloads and persisted to `pipeline_executions
 * .node_stats_json`.
 *
 * There is **one row per node in the DAG**, including nodes that never produced a [NodeResult]:
 * a failed node is synthesized from its `NodeExecutionException`, and a node that never started
 * (its dependency failed, or the execution was cancelled first) is synthesized as `ABORTED`.
 *
 * `callerResultRef` is deliberately not projected — the result cursor travels in `data_ready`,
 * not in stats.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NodeStats(
    @field:JsonProperty("node_id") @get:JsonProperty("node_id") @param:JsonProperty("node_id")
    val nodeId: String,
    @field:JsonProperty("status") @get:JsonProperty("status") @param:JsonProperty("status")
    val status: NodeStatus,
    @field:JsonProperty("started_at") @get:JsonProperty("started_at") @param:JsonProperty("started_at")
    val startedAt: Instant?,
    @field:JsonProperty("completed_at") @get:JsonProperty("completed_at") @param:JsonProperty("completed_at")
    val completedAt: Instant?,
    @field:JsonProperty("duration_ms") @get:JsonProperty("duration_ms") @param:JsonProperty("duration_ms")
    val durationMs: Long,
    @field:JsonProperty("rows_out") @get:JsonProperty("rows_out") @param:JsonProperty("rows_out")
    val rowsOut: Long,
    @field:JsonProperty("bytes_out") @get:JsonProperty("bytes_out") @param:JsonProperty("bytes_out")
    val bytesOut: Long,
    @field:JsonProperty("error_code") @get:JsonProperty("error_code") @param:JsonProperty("error_code")
    val errorCode: String? = null,
    @field:JsonProperty("error_message") @get:JsonProperty("error_message") @param:JsonProperty("error_message")
    val errorMessage: String? = null,
) {
    companion object {
        /** Projects a succeeded node (§7.2 row 1). */
        fun of(result: NodeResult): NodeStats =
            NodeStats(
                nodeId = result.nodeId,
                status = result.status,
                startedAt = result.startedAt,
                completedAt = result.completedAt,
                durationMs = result.durationMs,
                rowsOut = result.rowsOut,
                bytesOut = result.bytesOutEstimate,
            )

        /** Synthesizes a failed node (§7.2 row 2). */
        fun failed(
            nodeId: String,
            error: MappedError,
            startedAt: Instant?,
            failedAt: Instant,
        ): NodeStats =
            NodeStats(
                nodeId = nodeId,
                status = NodeStatus.FAILED,
                startedAt = startedAt,
                completedAt = failedAt,
                durationMs = startedAt?.let { Duration.between(it, failedAt).toMillis() } ?: 0,
                rowsOut = NodeResult.NOT_MEASURED,
                bytesOut = NodeResult.NOT_MEASURED,
                errorCode = error.code,
                errorMessage = error.message,
            )

        /**
         * Synthesizes a node stopped by the execution's own end, carrying **why** (§7.2 row 3 + F8).
         *
         * The status stays `ABORTED`, deliberately. A node whose statement the executor cancelled —
         * to serve a `DELETE`, a disconnect, or the timeout unwind — did not fail on its own merits,
         * and reporting `FAILED` with `pipeline.node.query_execution_failed` would be the same
         * mislabel at stats level that §8.3 forbids at event level. But the driver's own reason is
         * worth keeping: without it a terminal snapshot shows a bare `ABORTED` and an operator
         * cannot tell a clean interrupt from one that hit something else on the way out.
         */
        fun abortedWithCause(
            nodeId: String,
            error: MappedError,
            startedAt: Instant?,
        ): NodeStats =
            NodeStats(
                nodeId = nodeId,
                status = NodeStatus.ABORTED,
                startedAt = startedAt,
                completedAt = null,
                durationMs = 0,
                rowsOut = NodeResult.NOT_MEASURED,
                bytesOut = NodeResult.NOT_MEASURED,
                errorCode = error.code,
                errorMessage = error.message,
            )

        /**
         * Synthesizes a node that never started (§7.2 row 3) — a dependency failed, or the
         * execution was cancelled before this node ran. No timings, because there are none.
         */
        fun aborted(
            nodeId: String,
            startedAt: Instant? = null,
        ): NodeStats =
            NodeStats(
                nodeId = nodeId,
                status = NodeStatus.ABORTED,
                startedAt = startedAt,
                completedAt = null,
                durationMs = 0,
                rowsOut = NodeResult.NOT_MEASURED,
                bytesOut = NodeResult.NOT_MEASURED,
            )
    }
}
