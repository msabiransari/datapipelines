package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import kotlinx.coroutines.CancellationException
import java.sql.SQLException

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
 *
 * [errorRecord] (057) is the completed failure record the executor already emitted on
 * `node_failed` — carried so the terminal `pipeline_failed`, `error_json` and the MCP failure
 * envelope ship the SAME object rather than a rebuild that drops the node context, the SQL and
 * the exception chain. Null only on paths that never passed a recording site (the
 * vanished-result throw in `PipelineExecutor.resolveStoredResult`).
 */
class NodeExecutionException(
    val nodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>,
    cause: Throwable,
    val errorRecord: MappedError? = null,
) : PipelineException(
        code = errorCode,
        message = "Node $nodeId failed ($errorCode): ${cause.message}",
        details = errorDetails + mapOf("node_id" to nodeId),
        cause = cause,
    )

/**
 * The execution aborted because a node failed — the outer handler's translation of a
 * [NodeExecutionException] (§8.1). The three-argument form is the only form.
 *
 * [errorRecord] (057): the failure record, when the caller holds one — the MCP surface reads it
 * so `pipelines_execute`'s error result carries what `executions_get` would return.
 */
class PipelineExecutionFailed(
    val failedNodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>,
    val errorRecord: MappedError? = null,
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

/**
 * A node's statement outlived its JDBC query timeout and the driver cancelled it (§8.2, T202).
 *
 * Raised by the cancellation handle, the one place that holds both the statement (and so the
 * timeout it was armed with) and the clock around the driver call: a driver error that arrives
 * after the statement's own budget has elapsed is the timeout's *consequence*, however the
 * driver spells it. Carries the catalog code, so [ErrorCodeMapper] passes it through unchanged.
 */
class NodeQueryTimeoutException(
    val timeoutSeconds: Int,
    val elapsedMs: Long,
    cause: SQLException,
) : PipelineException(
        code = PipelineErrorCodes.Node.QUERY_TIMEOUT,
        message = "Query exceeded its timeout of ${timeoutSeconds}s and was cancelled after ${elapsedMs}ms",
        details = mapOf("timeout_seconds" to timeoutSeconds, "elapsed_ms" to elapsedMs, "sql_state" to cause.sqlState),
        cause = cause,
    )

/**
 * The node outlived its WALL-CLOCK deadline and the executor stopped it (§5.3, 108) —
 * `pipeline.node.timeout`, HTTP 504.
 *
 * The sibling of [NodeQueryTimeoutException] one level up: that one is a STATEMENT's budget,
 * noticed when the driver raises; this one is the NODE's, enforced by the executor whatever the
 * driver does. Which is the whole point — the measurement this exists for found a Postgres node
 * stopped cleanly at its statement budget while H2 tempdb nodes in the same pipeline ran minutes
 * past it, because nothing above the statement had a deadline at all.
 *
 * @param phase where the budget went, or null when the node had not entered a phase yet (it was
 *   still waiting on a dependency or a parallelism permit) — reported as `unknown` rather than
 *   invented.
 */
class NodeTimeoutException(
    val timeoutSeconds: Long,
    val elapsedMs: Long,
    val phase: NodePhase?,
) : PipelineException(
        code = PipelineErrorCodes.Node.TIMEOUT,
        message =
            "Node exceeded its wall-clock timeout of ${timeoutSeconds}s and was stopped after ${elapsedMs}ms " +
                "(phase: ${phase?.name?.lowercase() ?: "unknown"})",
        details =
            mapOf(
                "timeout_seconds" to timeoutSeconds,
                "elapsed_ms" to elapsedMs,
                "phase" to (phase?.name?.lowercase() ?: "unknown"),
            ),
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
