package co.datapipelines.mcp

import co.datapipelines.application.ExecutionLaunch
import co.datapipelines.application.ExecutionLauncher
import co.datapipelines.application.LaunchDecision
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking
import java.util.UUID

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
 * ## Idempotency (056/D6)
 *
 * Until 056 this tool had **no idempotency support at all** while `POST /pipelines/{id}/execute`
 * did — the one behavioural divergence in the audit's eight-item drift list, and the reason the
 * service-layer work started with the pipeline aggregate. Both surfaces now go through
 * [ExecutionLauncher]: the key is claimed with `SET NX` before anything runs, and a retry
 * carrying the same key returns the ORIGINAL execution instead of running a second one.
 *
 * The key rides the `Idempotency-Key` HTTP header of the `POST /mcp` request — the same header
 * REST uses — so the **tool schema is unchanged** and the wire surface
 * `McpToolSurfaceSpecDriftTest` freezes stays byte-identical. A retry's payload is rebuilt from
 * the stored execution row and its stored result, which is the closest a blocking call can come
 * to REST's "follow the original's event log": same execution id, same status, same result.
 *
 * ## Recording (P7)
 * Executions launched here carry `triggered_via = MCP` and are recorded
 * (`pipeline_executions` + `execution_events` + the 1h Redis log) when the assembling layer has
 * supplied an [McpExecutionRunner] — `web` does, through a per-run emitter with no SSE stream.
 * Without one (module slice tests) the shared executor bean runs the request and records
 * nothing; the tool result is unaffected either way.
 */
class PipelineExecuteTool(
    private val pipelines: PipelineService,
    private val executor: PipelineExecutor,
    private val executions: ExecutionRepository,
    private val resultStore: ResultStore,
    private val resultUrls: ResultUrlFactory,
    /**
     * The shared launch decision (056/D6): binds the parameters, then settles the idempotency
     * reservation. Null only outside the assembled application — a module-slice context has no
     * reservation store — where the tool behaves exactly as it did before 056 and every call
     * runs. `web` publishes the bean the assembled application injects.
     */
    private val launcher: ExecutionLauncher? = null,
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
                    "precision loss. When the execution FAILS, the error result carries the full failure record " +
                    "(node context, rendered SQL, exception chain with the root cause last in caused_by) — the same " +
                    "object executions_get returns; quote its correlation_id when escalating.",
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
        val record = pipelines.findRecord(workspace.id, id) ?: throw McpNotFound.pipeline(id)
        // B1: never clamped — `{version: 0}` is refused, not silently run as version 1.
        val version = args.version() ?: record.currentVersion
        // D6: the version resolution is the aggregate's, shared with the REST execute path.
        val executable =
            pipelines.findExecutable(workspace.id, record, version) ?: throw McpNotFound.pipelineVersion(id, version)

        val parameters = parameters(args)
        val request =
            ExecuteRequest(
                pipelineId = id,
                pipelineVersion = version,
                pipeline = executable.pipeline,
                userId = ctx.principal.userId,
                // The key's pinned workspace — the record was resolved in it two lines up
                // (025 A5): the execution runs where the pipeline lives.
                workspaceId = workspace.id,
                parameters = parameters,
                idempotencyKey = ctx.idempotencyKey,
                correlationId = ctx.correlationId,
                triggeredVia = ExecutionTrigger.MCP,
            )
        return when (val decision = decide(request, parameters, ctx)) {
            is LaunchDecision.Attach -> {
                attachedPayload(request, workspace, decision.executionId)
            }

            is LaunchDecision.Start -> {
                val started = request.copy(executionId = decision.executionId)
                payload(started, execute(started, workspace))
            }
        }
    }

    /**
     * The shared decision, or an unconditional [LaunchDecision.Start] where no launcher is wired
     * (a module-slice context — see the constructor).
     */
    private fun decide(
        request: ExecuteRequest,
        parameters: Map<String, JsonNode>,
        ctx: McpToolContext,
    ): LaunchDecision =
        launcher?.decide(
            ExecutionLaunch(
                pipelineId = request.pipelineId,
                pipelineVersion = request.pipelineVersion,
                pipeline = request.pipeline,
                userId = request.userId,
                parameters = parameters,
                parametersJson = ExecutorJson.write(parameters),
                idempotencyKey = ctx.idempotencyKey,
            ),
        ) ?: LaunchDecision.Start(null)

    /**
     * A retry that resolved to an existing reservation (§3.5): the ORIGINAL execution's payload,
     * rebuilt from its stored row and stored result rather than by running anything.
     *
     * `node_stats` comes out of `pipeline_executions.node_stats_json`, which the emitter wrote on
     * the terminal event, so the shape matches a fresh call's. An original that is still RUNNING
     * reports its live status with no result block — the honest answer for a blocking call that
     * must not start a second execution, and the execution-status resources (§7) are how an agent
     * follows it from there.
     */
    private fun attachedPayload(
        request: ExecuteRequest,
        workspace: WorkspaceContext,
        executionId: UUID,
    ): Map<String, Any?> {
        val record =
            executions.findById(workspace.id, executionId)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Result.EXECUTION_NOT_FOUND,
                    message = "The execution this idempotency key reserved ($executionId) is no longer recorded.",
                    details = mapOf("execution_id" to executionId.toString()),
                )
        return buildMap {
            put("execution_id", record.executionId.toString())
            put("pipeline_id", request.pipelineId.toString())
            put("pipeline_version", record.pipelineVersion)
            put("status", record.status.name)
            put("started_at", record.startedAt)
            put("completed_at", record.completedAt)
            put("duration_ms", record.durationMs)
            put("node_stats", record.nodeStatsJson?.let(McpTools::readTree) ?: emptyList<Any?>())
            put("warnings", emptyList<Any?>())
            putAll(storedResult(record))
        }
    }

    /** The `data_ready`-shaped half of an attached payload, read back from the stored result. */
    private fun storedResult(record: ExecutionRecord): Map<String, Any?> {
        if (record.resultRowCount == null) return emptyMap()
        val view = resultStore.describe(resultStore.keyFor(record.executionId)) ?: return emptyMap()
        return mapOf(
            "schema" to view.schema,
            "rows" to view.firstPage,
            "row_count" to view.firstPage.size,
            "total_rows" to view.totalRows,
            "has_more" to view.hasMore,
            "result_url" to resultUrls.urlFor(record.executionId),
            "expires_at" to view.expiresAt,
            "ttl_seconds" to resultConfig.effectiveTtlSeconds(null),
        )
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
