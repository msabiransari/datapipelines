package co.datapipelines.mcp

import co.datapipelines.application.mcp.McpCallAudit
import co.datapipelines.auth.Scope
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

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
internal fun ExecutionRecord.visibleTo(ctx: McpToolContext): Boolean = triggeredBy == ctx.principal.userId || ctx.principal.isWorkspaceAdmin

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

/**
 * `executions_cancel` (107). Scope: `execute`. Mutating.
 *
 * The MCP twin of `DELETE /api/v1/executions/{id}` (rest-api §10.4) with one rule REST does not
 * have: **the same-credential rule**. A key cancels only an execution its OWN MCP calls started
 * — `triggered_via = MCP`, `triggered_by` = the key's owner, and an `mcp.tool.called` audit row
 * pairing THIS key id with the execution's correlation id ([McpCallAudit]; the audit row is the
 * join because `pipeline_executions` carries the owner USER id, never the key id). An execution
 * started over REST, the UI, a PIPELINE node or a published endpoint — or by a DIFFERENT key of
 * the same user — is refused with a static message naming why; an execution the caller may not
 * see is the same not-found every read path answers.
 *
 * The cancellation itself is [ExecutionCancellationService]'s, untouched: 086's Redis-flag-first,
 * local-registry-second semantics (the flag reaches the executing instance within about one poll
 * interval; the common same-instance case cancels immediately) come from the service, and this
 * tool deliberately does not reimplement them.
 */
class ExecutionsCancelTool(
    private val executions: ExecutionRepository,
    private val cancellation: ExecutionCancellationService,
    private val mcpCalls: McpCallAudit,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "executions_cancel",
            description =
                "Request cancellation of a RUNNING execution. This key can cancel ONLY an execution its own MCP " +
                    "calls started: the execution must have been triggered via MCP by this key's user, and an " +
                    "audit row must pair this key with the execution's correlation id — an execution started over " +
                    "REST, the UI, a pipeline node, a published endpoint, or another key of the same user is " +
                    "refused, and the refusal names which rule fired. A non-RUNNING execution is refused with its " +
                    "current status. Cancellation is requested, not awaited: the flag reaches the executing " +
                    "instance within about one poll interval (immediately when same-instance); poll " +
                    "executions_get for the terminal ABORTED status. Mutating.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["execution_id"],
                  "additionalProperties": false,
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
        // Ownership was checked before anything else, exactly as the REST verb's guard order:
        // a non-owned execution is not-found, never a disclosure (§10.2/§10.4).
        val record = executions.findById(workspaceId, id)?.takeIf { it.visibleTo(ctx) } ?: throw McpNotFound.execution(id)
        if (record.status != ExecutionStatus.RUNNING) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.NOT_RUNNING,
                message = "Execution '$id' is already ${record.status.name}.",
                details = mapOf("execution_id" to id.toString(), "status" to record.status.name),
            )
        }
        if (record.triggeredVia != ExecutionTrigger.MCP) {
            throw sameCredentialRefusal(
                id,
                "started_outside_mcp",
                "Execution '$id' was started outside MCP; a key cancels only executions its own MCP calls started.",
            )
        }
        val keyId = ctx.principal.keyId
        val correlationId = record.correlationId
        val auditProvesThisKey =
            keyId != null && correlationId != null &&
                mcpCalls.calledByKey(keyId, correlationId)
        if (record.triggeredBy != ctx.principal.userId || !auditProvesThisKey) {
            throw sameCredentialRefusal(
                id,
                "different_credential",
                "Execution '$id' was started by a different credential; a key cancels only executions its own MCP calls started.",
            )
        }
        cancellation.cancel(id, AbortReason.CANCELLED)
        return mapOf("execution_id" to id.toString(), "status" to "cancellation_requested")
    }

    /**
     * The same-credential refusals: `auth.scope.insufficient` is the catalog's one authorization
     * refusal code (§13.7) — the catalog has no "not yours" code and this surface invents none.
     * `details.reason` names which rule fired; the messages are static and leak nothing beyond
     * what the caller's own visibility already established.
     */
    private fun sameCredentialRefusal(
        id: UUID,
        reason: String,
        message: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT,
            message = message,
            details = mapOf("execution_id" to id.toString(), "reason" to reason),
        )
}
