package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.sql.SQLException

/**
 * One node's failure resolved to a catalog code plus the structured detail that travels with it.
 *
 * The three 057 fields are the **failure record** — facts attached at the failure site
 * ([NodeRunner] decorates an escaping signal with [NodeErrorContext] and the rendered SQL)
 * and completed where the failure is recorded ([PipelineExecutor.failNode] adds the
 * [ExceptionDetail]). `node_failed`, the terminal `pipeline_failed` and `error_json` then
 * carry the SAME object unchanged; nulls are omitted on the wire, not serialized.
 *
 * @property node which node failed and against what (datasource, dialect, pinned template).
 * @property sql the rendered SQL in `:name` form, when the failure is at or after RENDER.
 *   Never the positional form, never a bound value (042's contract).
 * @property exception the original failure's class, message, capped frames and bounded
 *   `caused_by` chain — present only under [ErrorDetail.FULL].
 */
data class MappedError(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val node: NodeErrorContext? = null,
    val sql: String? = null,
    val exception: ExceptionDetail? = null,
)

/**
 * The phase a node was in when it failed.
 *
 * §8.2's table distinguishes "`SQLException` during connection acquire" from "`SQLException`
 * during query execution" — the driver exception is the same class in both, so the *only* thing
 * that can tell them apart is where the executor was. Passing the phase makes that explicit
 * instead of guessing from a message.
 */
enum class NodePhase {
    RENDER,
    CONNECT,
    EXECUTE,
    STAGE,
    MATERIALIZE,
    WRITEBACK,
}

/**
 * Resolves an internal exception to a code from the single catalog
 * ([pipeline-contract §13](../../../../../../../docs/pipeline-contract.md)) per
 * dag-executor.md §8.2. This module defines **no codes of its own**.
 *
 * Any [DatapipelinesException] already carries a catalog code chosen by the module that raised
 * it — the template engine's `template_not_found` vs `template_render_failed` split (templates
 * §8.2), staging's `value_overflow` / `memory_limit_exceeded` / `invalid_column_name`, the
 * result store's `too_large` / `storage_unavailable`. Re-deriving a code from the exception's
 * type here would silently override those with a coarser one, so the carried code always wins.
 */
object ErrorCodeMapper {
    /** Maps [error] raised during [phase] on [nodeId]. */
    fun map(
        error: Throwable,
        phase: NodePhase,
        nodeId: String,
    ): MappedError {
        val details = mapOf("node_id" to nodeId, "phase" to phase.name.lowercase())
        return when (error) {
            is DatapipelinesException -> {
                // Bounded too (B1): a collaborator's exception can quote driver text of its own —
                // staging's `value_overflow` is built from H2's message, for one.
                MappedError(error.code, error.message?.take(MAX_MESSAGE_CHARS) ?: error.code, error.details + details)
            }

            is SQLException -> {
                MappedError(sqlCode(phase), describe(error), details + sqlDetails(error))
            }

            else -> {
                MappedError(fallbackCode(phase), describe(error), details)
            }
        }
    }

    /** The §8.2 row for a raw driver exception, chosen by the phase it surfaced in. */
    private fun sqlCode(phase: NodePhase): String =
        when (phase) {
            NodePhase.CONNECT -> {
                PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
            }

            NodePhase.STAGE -> {
                PipelineErrorCodes.Node.STAGING_FAILED
            }

            NodePhase.WRITEBACK -> {
                PipelineErrorCodes.Node.WRITEBACK_FAILED
            }

            // A driver fault while draining the caller cursor is still a query failure: the
            // result store's own faults arrive as DatapipelinesException and never reach here.
            NodePhase.RENDER, NodePhase.EXECUTE, NodePhase.MATERIALIZE -> {
                PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
            }
        }

    /**
     * The §8.2 row for anything else. `pipeline.execution.aborted` is the catalog's
     * "executor-internal failure with no more specific code"; the phase-specific rows above it
     * are preferred wherever the phase names one.
     */
    private fun fallbackCode(phase: NodePhase): String =
        when (phase) {
            NodePhase.RENDER -> PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED
            NodePhase.CONNECT -> PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
            NodePhase.STAGE -> PipelineErrorCodes.Node.STAGING_FAILED
            NodePhase.WRITEBACK -> PipelineErrorCodes.Node.WRITEBACK_FAILED
            NodePhase.EXECUTE, NodePhase.MATERIALIZE -> PipelineErrorCodes.Execution.ABORTED
        }

    private fun sqlDetails(error: SQLException): Map<String, Any?> = mapOf("sql_state" to error.sqlState, "vendor_code" to error.errorCode)

    /**
     * The human-readable half of a [MappedError] — **bounded** (B1).
     *
     * The content is spec-sanctioned: rest-api §4.2 puts a driver message in the error envelope on
     * purpose, because a SQL author needs to see what the database said. The *length* was the
     * defect. H2, MSSQL and Oracle append the entire failing statement to `SQLException.message`,
     * and the rendered SQL that produced it is bounded only by the 64M-character engine backstop —
     * so one invalid multi-megabyte statement produced a multi-megabyte message that was then
     * copied into `MappedError`, into `NodeStats.errorMessage`, into the `node_failed` and
     * `pipeline_failed` SSE payloads, into `error_json` and `node_stats_json` in Postgres, and into
     * every log line that printed it — once per node, across up to
     * `max-concurrent-executions-per-instance` executions (050/R2 name).
     *
     * 2000 characters keeps the diagnostic (the offending statement's head is where the error is)
     * and removes the amplifier. Same discipline, same reason, as
     * `RedisIdempotencyStore.MAX_ECHOED_KEY`.
     */
    private fun describe(error: Throwable): String = error.message?.take(MAX_MESSAGE_CHARS) ?: error.javaClass.simpleName

    /** Upper bound on reflected driver text in any error carrier. */
    const val MAX_MESSAGE_CHARS = 2000
}
