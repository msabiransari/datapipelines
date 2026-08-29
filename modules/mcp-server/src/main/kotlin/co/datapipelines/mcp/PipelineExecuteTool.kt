package co.datapipelines.mcp

import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking

/** §6.2.3 — the `parameters` description, kept off the schema line for length. */
private const val PARAMETERS_DESC =
    "Object whose keys match the pipeline's declared parameters. Values must match the declared types (BIGINTEGER " +
        "and BIGDECIMAL as strings, others as JSON native types)."

/**
 * `pipelines_execute` (mcp-server.md §6.2.3). Scope: `execute`.
 *
 * ## A single blocking call
 *
 * §6.2.3 is explicit: the tool call returns when the execution reaches a terminal state or when
 * `datapipelines.executor.execution-timeout-seconds` elapses. The servlet thread therefore waits
 * inside [runBlocking] — the executor is a coroutine engine and this is the one place in the MCP
 * surface that bridges into it. MCP progress notifications are deliberately not implemented in v1
 * (§6.2.3); progress is the final `node_stats`, and the execution-status resources (§7) are the
 * v1 stand-in.
 *
 * ## The result shape
 *
 * "The result shape mirrors the REST `data_ready` event exactly" (§6.2.3) — schema, inline first
 * page, `total_rows`, `has_more`, `result_url`, `expires_at` — read back from the **stored**
 * result, never from a live ResultSet. A pipeline with no caller node (pipeline-contract §9)
 * returns metadata and `node_stats` with no `schema`/`rows`: success, not an error.
 *
 * ## Deviations reported to the orchestrator
 *
 * - **Abandoned-call cancellation** (§6.2.3's `disconnect-grace-seconds` path) is an SSE-stream
 *   concept the servlet MCP transport gives no hook for: a blocking `POST /mcp` cannot observe
 *   the client going away. Out-of-band cancellation (`DELETE /api/v1/executions/{id}`) still
 *   works and lands here as an abort; the execution timeout remains the backstop.
 *
 * ## Recording (P7)
 * Executions launched here carry `triggered_via = MCP` and are recorded
 * (`pipeline_executions` + `execution_events` + the 1h Redis log) when the assembling layer has
 * supplied an [McpExecutionRunner] — `web` does, through a per-run emitter with no SSE stream.
 * Without one (module slice tests) the shared executor bean runs the request and records
 * nothing; the tool result is unaffected either way.
 */
class PipelineExecuteTool(
    private val pipelines: PipelineRepository,
    private val executor: PipelineExecutor,
    private val resultStore: ResultStore,
    private val resultUrls: ResultUrlFactory,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    /**
     * Only for `ttl_seconds` (§6.2.3 mirrors `data_ready`, rest-api §6.4.7). MCP has no
     * `DP-Result-TTL-Seconds` carrier — that header is a REST execute-call concept — so the
     * effective TTL is always the configured default, resolved through the same clamp the executor
     * used when it wrote the result.
     */
    private val resultConfig: ResultConfig = ResultConfig(),
    /**
     * The recording path (P7). Null outside the assembled application; see [McpExecutionRunner]
     * for what an implementation guarantees.
     */
    private val executionRunner: McpExecutionRunner? = null,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_execute",
            description =
                "Execute a pipeline with the given input parameters. Returns execution events (node start/complete/fail) " +
                    "and the final result data. The result's schema describes column types; BIGINTEGER and BIGDECIMAL " +
                    "columns serialize as JSON strings — preserve them as strings when displaying or persisting to avoid " +
                    "precision loss.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "parameters"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "version": {"type": "integer"},
                    "parameters": {
                      "type": "object",
                      "description": "$PARAMETERS_DESC",
                      "additionalProperties": true
                    }
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspace = ctx.principal.requireWorkspace()
        val id = args.requiredUuid("id")
        val record = pipelines.findById(workspace.id, id) ?: throw McpNotFound.pipeline(id)
        // B1: never clamped — `{version: 0}` is refused, not silently run as version 1.
        val version = args.version() ?: record.currentVersion
        val body = pipelines.findVersionBody(workspace.id, id, version) ?: throw McpNotFound.pipelineVersion(id, version)

        val request =
            ExecuteRequest(
                pipelineId = id,
                pipelineVersion = version,
                pipeline = deserializer.readOrThrow(body),
                userId = ctx.principal.userId,
                // The key's pinned workspace — the record was resolved in it two lines up
                // (025 A5): the execution runs where the pipeline lives.
                workspaceId = workspace.id,
                parameters = parameters(args),
                correlationId = ctx.correlationId,
                triggeredVia = ExecutionTrigger.MCP,
            )
        val result = execute(request, workspace)
        return payload(request, result)
    }

    /** Runs the execution, translating the one cancellation path that is not a [DatapipelinesException]. */
    private fun execute(
        request: ExecuteRequest,
        workspace: WorkspaceContext,
    ): ExecutionResult =
        try {
            runBlocking { executionRunner?.run(request, workspace) ?: executor.execute(request) }
        } catch (e: ExecutionAbortedException) {
            // §8.1: abortion extends CancellationException and maps to no executor error code, so
            // the dispatcher's DatapipelinesException path cannot see it. The agent still has to
            // learn the run ended ABORTED and why, and §13.4 catalogues exactly that code.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.ABORTED,
                message = "Execution aborted: ${e.reason.wire}",
                details = mapOf("reason" to e.reason.wire, "pipeline_id" to request.pipelineId.toString()),
                cause = e,
            )
        }

    /** Each declared parameter value as a `JsonNode`, which is what [ExecuteRequest] binds from. */
    private fun parameters(args: McpArguments): Map<String, JsonNode> =
        args.requiredObject("parameters").mapValues { (_, value) -> ExecutorJson.mapper.valueToTree(value) }

    private fun payload(
        request: ExecuteRequest,
        result: ExecutionResult,
    ): Map<String, Any?> =
        buildMap {
            put("execution_id", result.executionId.toString())
            put("pipeline_id", request.pipelineId.toString())
            put("pipeline_version", request.pipelineVersion)
            put("status", result.status.name)
            put("started_at", result.startedAt)
            put("completed_at", result.completedAt)
            put("duration_ms", result.durationMs)
            put("node_stats", result.nodeStats)
            put("warnings", result.warnings)
            putAll(callerResult(result))
        }

    /**
     * The `data_ready`-shaped half of the payload, or nothing at all for a pipeline with no caller
     * node (§6.2.3). A stored result that has vanished between the executor resolving it and this
     * read is reported as `result.expired` rather than silently dropped — an empty `rows` on a
     * pipeline that returns data is the failure mode D9 exists to prevent.
     */
    private fun callerResult(result: ExecutionResult): Map<String, Any?> {
        val ref = result.resultRef ?: return emptyMap()
        val view =
            resultStore.describe(ref)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Result.EXPIRED,
                    message = "The result of execution ${result.executionId} is no longer available.",
                    details = mapOf("execution_id" to result.executionId.toString()),
                )
        return mapOf(
            "schema" to view.schema,
            "rows" to view.firstPage,
            "row_count" to view.firstPage.size,
            "total_rows" to view.totalRows,
            "has_more" to view.hasMore,
            "result_url" to resultUrls.urlFor(result.executionId),
            "expires_at" to view.expiresAt,
            "ttl_seconds" to resultConfig.effectiveTtlSeconds(null),
        )
    }
}
