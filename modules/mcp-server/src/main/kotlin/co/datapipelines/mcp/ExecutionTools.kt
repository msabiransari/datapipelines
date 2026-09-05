package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineErrorCodes
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema

/**
 * The `read` floor every resource path enforces explicitly (mcp-server.md §7.3, §13 checklist:
 * "`resources/list` filtered by the caller's scope").
 *
 * The tools get this from [ScopeMatrix] via the dispatcher; the resource methods have no matrix row
 * of their own, and relying on `Scope.READ` being ordinal 0 would make the guarantee accidental —
 * an operator who set `datapipelines.auth.api-keys.default-scopes` to something empty or exotic
 * would mint a key that reads every pipeline body and template through the `resources` methods while every
 * tool refuses it. So the floor is asserted, not assumed.
 *
 * @throws McpError `-32003` when the caller cannot read. the `resources` methods have no `isError` content
 *   channel, so a refusal can only be a JSON-RPC error ([McpArguments.FORBIDDEN]).
 */
internal fun requireReadScope(ctx: McpToolContext) {
    if (!Scope.satisfies(ctx.principal.scopes, Scope.READ)) {
        throw McpArguments.forbidden(
            "${PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT}: this key holds no scope that grants read.",
        )
    }
}

/**
 * Execution **ownership** (mcp-server.md §13 security checklist): a valid `read` key cannot read
 * another user's executions; `admin` may read any.
 *
 * A non-owned execution is reported as *not found* rather than *forbidden*: telling a caller that
 * an execution it may not read exists is an information disclosure, and §13.10 catalogues no
 * "not yours" code. The distinction is invisible to a legitimate caller and mirrors the
 * `result.execution_not_found` row of §6.2.15's error table.
 */
internal fun ExecutionRecord.visibleTo(ctx: McpToolContext): Boolean =
    triggeredBy == ctx.principal.userId || Scope.satisfies(ctx.principal.scopes, Scope.ADMIN)

/** The §6.2.14 execution projection — metadata only, never rows. */
internal fun ExecutionRecord.toMcpMetadata(): Map<String, Any?> =
    buildMap {
        put("execution_id", executionId.toString())
        put("pipeline_id", pipelineId.toString())
        put("pipeline_version", pipelineVersion)
        put("status", status.name)
        put("triggered_by", triggeredBy.toString())
        put("triggered_via", triggeredVia.name)
        put("correlation_id", correlationId?.toString())
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("duration_ms", durationMs)
        put("failed_node_id", failedNodeId)
        // 072: both of these now carry the calculator story, with no projection change here.
        // `parameters` is the FULLY RESOLVED Context after the run — org keys, platform keys,
        // parameters and every calculator output (§0.5) — and each CALCULATOR node's entry in
        // `node_stats` carries `context_key` / `context_value`, which is where an agent looks
        // when a computed value is not what it expected.
        put("parameters", McpTools.readTree(parametersJson))
        put("node_stats", nodeStatsJson?.let { McpTools.readTree(it) })
        put("error", errorJson?.let { McpTools.readTree(it) })
        put("result_row_count", resultRowCount)
        put("result_size_bytes", resultSizeBytes)
    }

/**
 * `executions_list` (mcp-server.md §6.2.13). Scope: `read`.
 *
 * ## Reported gap
 *
 * `ExecutionRepository` exposes `findByUser` and `findByPipeline` only — there is no "all
 * executions" query — so an `admin` key sees its own executions plus, when `pipeline_id` is
 * given, that pipeline's executions from every user. A cross-user unfiltered admin listing needs
 * a repository method in `dag`; reported to the orchestrator rather than worked around by
 * scanning, and no caller ever sees another user's execution through this tool today.
 */
class ExecutionsListTool(
    private val executions: ExecutionRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "executions_list",
            description = "List recent pipeline executions of the key's pinned workspace, optionally filtered by pipeline or status.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "pipeline_id": {"type": "string", "format": "uuid"},
                    "status": {"type": "string", "enum": ["RUNNING", "SUCCESS", "FAILED", "ABORTED"]},
                    "limit": {"type": "integer", "default": 50, "maximum": 200}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val limit = args.int("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)
        val status = args.enumString("status", ExecutionStatus.entries.map { it.name }.toSet())
        val pipelineId = args.uuid("pipeline_id")

        val candidates =
            if (pipelineId == null) {
                executions.findByUser(workspaceId, ctx.principal.userId, limit = limit)
            } else {
                executions.findByPipeline(workspaceId, pipelineId, limit = limit)
            }
        return candidates
            .filter { it.visibleTo(ctx) }
            .filter { status == null || it.status.name == status }
            .map { it.toMcpMetadata() }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/** `executions_get` (mcp-server.md §6.2.14). Scope: `read` + ownership. */
class ExecutionsGetTool(
    private val executions: ExecutionRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "executions_get",
            description =
                "Get metadata for a specific execution: status, timing, node_stats, parameters used. On a FAILED " +
                    "execution, error carries the full failure record: code, message, correlation_id, node context " +
                    "(datasource, dialect, pinned template), the rendered SQL (:name form, no bound values) and the " +
                    "exception chain with stack frames — read error.code first, then error.exception.caused_by (root " +
                    "cause LAST), then error.sql; quote error.correlation_id when escalating. To get the result " +
                    "rows, use executions_get_result.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["execution_id"],
                  "properties": {
                    "execution_id": {"type": "string", "format": "uuid"}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("execution_id")
        val record = executions.findById(workspaceId, id)?.takeIf { it.visibleTo(ctx) } ?: throw McpNotFound.execution(id)
        return record.toMcpMetadata()
    }
}
