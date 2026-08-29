package co.datapipelines.executor

import co.datapipelines.pipeline.Pipeline
import co.datapipelines.typesystem.TypeMappingWarning
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** The whole-pipeline outcome (enums.md §10, authored by rest-api; see [SseEventType] for the layering note). */
@Suppress("KDocUnresolvedReference")
enum class ExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    ABORTED,
}

/**
 * One execution request (dag-executor.md §5.1 step 1).
 *
 * The [pipeline] body is a **snapshot** taken by the caller: deleting or editing the pipeline
 * mid-execution does not affect the run (§12.1). [pipelineId] and [pipelineVersion] identify that
 * snapshot for the events and for `pipeline_executions`; they are not on the `Pipeline` body,
 * which deliberately carries no server-assigned fields.
 *
 * The executor is handed an **already-authenticated** principal — [userId] — and never consults
 * `auth` (module-structure §4.2); authorization happened at the surface that built this request.
 *
 * @param parameters the raw request `parameters` object; binding, defaults and coercion are
 *   `ParameterBinder`'s job (pipeline-contract §7.1), run at execution start.
 * @param resultTtlSeconds the client's `DP-Result-TTL-Seconds`, clamped by [ResultConfig].
 * @param triggeredVia how the surface that built this request was reached — `UI` for the editor's
 *   Run button, `REST` for a programmatic API call, `MCP` for an agent's tool invocation
 *   (enums.md §18). Only the surface can know this, so only the surface may set it; it is carried
 *   here purely so the execution recorder can write `pipeline_executions.triggered_via`
 *   ([ExecutionRecord.triggeredVia], metadata-db §4.6) without a second channel. The executor
 *   itself never branches on it, and it is **not** part of the `execution_started` wire payload
 *   (rest-api §6.4.1 does not carry it).
 *
 *   The default is [ExecutionTrigger.REST] — the catalogued value for a direct programmatic call,
 *   which is what an in-process construction is. It exists so callers predating this field still
 *   compile, not as a value a surface should rely on: `web` and `mcp-server` pass theirs.
 * @param directSink the `direct` delivery target (design §4.2) — set only on a CHILD execution
 *   spawned by a PIPELINE node through the internal execution service. When present, the caller
 *   node's result streams here and never reaches the [ResultStore]. Null for root executions.
 * @param parentExecutionId the execution whose PIPELINE node spawned this one; null for roots
 *   (metadata-db §4.6, design §5).
 * @param parentNodeId the PIPELINE node id in the parent that spawned this execution.
 * @param rootExecutionId the family's top ancestor. Non-null marks this request as a child: it
 *   takes no concurrency slot (design §4.4 correction) and honours the root's cancellation flag
 *   in addition to its own (design §4.3, D8).
 * @param compositionDepth how many PIPELINE-node hops sit above this execution; the runtime
 *   depth backstop refuses beyond `datapipelines.pipelines.max-composition-depth`.
 */
data class ExecuteRequest(
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val pipeline: Pipeline,
    val userId: UUID,
    /**
     * The workspace the pipeline belongs to — the execution runs IN it (design §5.3, 025
     * A5). Supplied by the surface that resolved the pipeline (its workspace IS the
     * execution's workspace) and carried onto [NodeExecutionContext.workspaceId], where
     * runtime datasource resolution is scoped by it: a datasource the workspace cannot see
     * is `datasource_not_found` at execution time, exactly as it would be at save time —
     * closing the gap where a pipeline saved while a datasource was global kept executing
     * against it after a re-bound. A PIPELINE node's child request carries it verbatim:
     * composition inherits the parent's workspace.
     */
    val workspaceId: UUID,
    val parameters: Map<String, JsonNode> = emptyMap(),
    val idempotencyKey: String? = null,
    val resultTtlSeconds: Long? = null,
    val correlationId: UUID? = null,
    val triggeredVia: ExecutionTrigger = ExecutionTrigger.REST,
    val executionId: UUID? = null,
    val directSink: DirectResultSink? = null,
    val parentExecutionId: UUID? = null,
    val parentNodeId: String? = null,
    val rootExecutionId: UUID? = null,
    val compositionDepth: Int = 0,
)

/**
 * What one execution produced (§5.2).
 *
 * [resultRef] is the Redis **key** of the stored caller result, or null for a pipeline with no
 * caller node — never data, never a cursor.
 *
 * [warnings] carries the non-fatal type-mapping warnings drained out of every `StageResult`
 * (staging §8.2) and out of the caller result's schema mapping. They never fail an execution.
 */
data class ExecutionResult(
    val executionId: UUID,
    val status: ExecutionStatus,
    val nodeStats: List<NodeStats>,
    val resultRef: String?,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMs: Long,
    val warnings: List<TypeMappingWarning> = emptyList(),
)

/**
 * Builds the `result_url` that `data_ready` carries (rest-api §6.4.7).
 *
 * The executor has no idea what host it is reachable on — and must not guess from a request
 * header (the same rule `datapipelines.auth.base-url` exists for). So the URL is injected: `web`
 * supplies an absolute builder from its configured base URL, and the default here is the path
 * alone, which is correct, safe, and obviously relative.
 *
 * (An addition to §5.2's constructor list: the spec's `DataReady.from(...)` sketch does not say
 * where the host comes from. Reported to the orchestrator.)
 */
fun interface ResultUrlFactory {
    /** The `result_url` for [executionId]. */
    fun urlFor(executionId: UUID): String

    companion object {
        /** `/api/v1/executions/{id}/result` — the path rest-api §7.2 defines, without a host. */
        val RELATIVE = ResultUrlFactory { "/api/v1/executions/$it/result" }
    }
}
