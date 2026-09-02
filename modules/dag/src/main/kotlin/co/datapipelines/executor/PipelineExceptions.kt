package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import kotlinx.coroutines.CancellationException

/**
 * The executor's exception hierarchy (dag-executor.md §8.1).
 *
 * §8.1 writes the base as `sealed class PipelineException : RuntimeException`. It extends
 * [DatapipelinesException] here instead, which is a `RuntimeException` — module-structure §4.3
 * makes that the shared base for **every** module's exceptions, and it is what carries the
 * `code` / `details` the unified error response is built from. Extending `RuntimeException`
 * directly would leave the executor as the one module whose failures the global handler cannot
 * render structurally.
 *
 * [ExecutionAbortedException] is deliberately outside this hierarchy: §8.1 requires it to extend
 * `CancellationException` so structured concurrency unwinds normally, and it maps to no error
 * code at all.
 */
sealed class PipelineException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : DatapipelinesException(code, message, details, cause)

/**
 * One node failed. Built at the failure site in the node runner from the mapped error code
 * (§8.2) with the original exception as [cause].
 *
 * `cause` is passed to the base constructor and **not** redeclared as a `val` (§8.1): shadowing
 * it would hide the real cause from stack traces and from every log that prints the cause chain.
 */
class NodeExecutionException(
    val nodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>,
    cause: Throwable,
) : PipelineException(
        code = errorCode,
        message = "Node $nodeId failed ($errorCode): ${cause.message}",
        details = errorDetails + mapOf("node_id" to nodeId),
        cause = cause,
    )

/**
 * The execution aborted because a node failed — the outer handler's translation of a
 * [NodeExecutionException] (§8.1). The three-argument form is the only form.
 */
class PipelineExecutionFailed(
    val failedNodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>,
) : PipelineException(
        code = errorCode,
        message = "Pipeline aborted: node $failedNodeId failed ($errorCode)",
        details = errorDetails + mapOf("failed_node_id" to failedNodeId),
    )

/**
 * `withTimeout(datapipelines.executor.execution-timeout-seconds)` fired (§5.3).
 *
 * Status is `FAILED`, not `ABORTED`: a timeout is a failure. `ABORTED` is reserved for the three
 * cancellation paths of §8.3.
 *
 * @param timedOutNodeId a node that was mid-flight when the timeout fired, or null if none was.
 */
class PipelineTimeoutException(
    val timedOutNodeId: String?,
    val elapsedMs: Long,
) : PipelineException(
        code = PipelineErrorCodes.Execution.TIMEOUT,
        message = "Pipeline timed out after ${elapsedMs}ms",
        details = mapOf("timed_out_node_id" to timedOutNodeId, "elapsed_ms" to elapsedMs),
    )

/** No execution slot was free (§5.3) — per-user or the instance-wide ceiling (050/R2). */
class PipelineConcurrencyLimitException(
    val scope: LimitScope,
    val limit: Int,
) : PipelineException(
        code = PipelineErrorCodes.Execution.CONCURRENCY_LIMIT,
        message = "Execution slot unavailable ($scope): limit $limit reached",
        details = mapOf("scope" to scope.wire, "limit" to limit),
    )

/**
 * Which concurrency limit refused an execution. `GLOBAL` is the INSTANCE-WIDE ceiling (the
 * wire value stays `global` for API stability; the limit it names has always been per JVM —
 * 050/R2).
 */
enum class LimitScope(
    val wire: String,
) {
    PER_USER("per_user"),
    GLOBAL("global"),
}

/**
 * Cancellation, not failure (§8.3). Extends `CancellationException` so cancelling the execution's
 * root job unwinds every node coroutine through ordinary structured concurrency; it maps to no
 * error code — the execution ends `ABORTED` and emits `execution_aborted`.
 */
class ExecutionAbortedException(
    val reason: AbortReason,
) : CancellationException("Execution aborted: ${reason.wire}")

/**
 * Why an execution was cancelled (§8.3). Wire values are the `execution_aborted.reason` strings
 * of [rest-api §6.4.8](../../../../../../../docs/rest-api.md).
 */
enum class AbortReason(
    val wire: String,
) {
    CLIENT_DISCONNECT("client_disconnect"),
    CANCELLED("cancelled"),
    SHUTDOWN("shutdown"),
    ;

    companion object {
        /** Resolves a wire value written to the Redis cancel flag, or null when unknown. */
        fun fromWireOrNull(value: String?): AbortReason? = entries.firstOrNull { it.wire == value }
    }
}
