package co.datapipelines.mcp

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory

/**
 * The single entry point for `tools/call` (mcp-server.md §6).
 *
 * Three things happen here and nowhere else:
 *
 * 1. **The per-tool authorization gate.** The minimum scope comes from
 *    [ScopeMatrix.requiredScopeForTool] — the auth module's projection of the
 *    [auth.md §7.6 matrix](../../../../../../../docs/auth.md), which is authoritative on any
 *    conflict with §6.2's restated values. A tool whose requirement the matrix does not know is
 *    **refused**, never executed: fail-closed is the only safe reading of "no tool runs without
 *    its documented minimum". Scopes are hierarchical, so the check is [Scope.satisfies], not set
 *    membership (§7.5).
 * 2. **The §6.3 result envelope**, including the `_meta.correlation_id` echo.
 * 3. **Error mapping** (§9.2): every catalogued domain failure becomes a tool result with
 *    `isError: true`; only protocol faults ([McpError]) travel as JSON-RPC errors (§9.1).
 *
 * Every call is written to the audit log — tool name, caller, target entity, outcome, correlation
 * id (§13 security checklist) — as `mcp.tool.called`. On top of that (052, ruling R4): every call
 * to a tool the catalog declares [mutating][McpToolCatalog.isMutating] writes ONE
 * `mcp.tool.write` event, node runs included — the trace that an `author`-scoped key altered
 * stored definitions or customer data, which a node run otherwise leaves nowhere (§6.2.20's
 * no-history ratification covered executions, not traces). The write event is emitted HERE, at
 * the single dispatch choke point, after the tool returns on success and failure alike — a tool
 * that forgets is the failure mode, and a choke point cannot forget. A refused call (the §7.6
 * gate) never invoked the tool, so it writes the refusal into `mcp.tool.called` and no write
 * event: nothing happened to trace.
 */
class McpToolDispatcher(
    tools: List<McpTool>,
    private val auditSink: AuditEventSink,
) {
    private val log = LoggerFactory.getLogger(McpToolDispatcher::class.java)
    private val byName: Map<String, McpTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "Duplicate MCP tool names: ${tools.map { it.name }.sorted()}" }
    }

    /** The registered tools' definitions, in `tools/list` order (§6.1). */
    fun definitions(): List<McpSchema.Tool> = byName.values.map { it.definition }

    /** The registered tool names — the surface `ScopeMatrix.MCP_TOOL_MIN_SCOPE` must cover exactly. */
    fun toolNames(): Set<String> = byName.keys

    /**
     * Authorizes and runs one tool call.
     *
     * @throws McpError for protocol-level faults only (unknown tool, malformed arguments) — §9.1.
     */
    fun call(
        request: McpSchema.CallToolRequest,
        ctx: McpToolContext,
    ): McpSchema.CallToolResult {
        val tool = byName[request.name()] ?: throw McpArguments.invalidParams("Unknown tool '${request.name()}'.")
        val startedAt = System.nanoTime()
        val refusal = scopeRefusal(tool.name, ctx)
        if (refusal != null) {
            audit(tool.name, request, ctx, outcome = "scope_refused", code = refusal.code, startedAt = startedAt, invoked = false)
            return McpToolResults.error(refusal, ctx.correlationId)
        }
        return runTool(tool, request, ctx, startedAt)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runTool(
        tool: McpTool,
        request: McpSchema.CallToolRequest,
        ctx: McpToolContext,
        startedAt: Long,
    ): McpSchema.CallToolResult =
        try {
            val payload = tool.call(McpArguments(request.arguments() ?: emptyMap()), ctx)
            audit(tool.name, request, ctx, outcome = "success", startedAt = startedAt, invoked = true)
            McpToolResults.success(payload, ctx.correlationId)
        } catch (e: DatapipelinesException) {
            // A catalogued domain failure is CONTENT, not a protocol error (§9.2).
            audit(tool.name, request, ctx, outcome = "error", code = e.code, startedAt = startedAt, invoked = true)
            log.info("MCP tool {} failed code={} correlation_id={}", tool.name, e.code, ctx.correlationId)
            McpToolResults.error(McpErrorPayload.of(e), ctx.correlationId)
        } catch (e: McpError) {
            audit(tool.name, request, ctx, outcome = "invalid_params", startedAt = startedAt, invoked = true)
            throw e
        } catch (e: Exception) {
            throw internalError(tool.name, request, ctx, e, startedAt)
        }

    /**
     * The catch-all an uncatalogued fault lands in — a Redis outage, a metadata-DB connection
     * failure, a driver `RuntimeException`.
     *
     * Without it the SDK maps the throwable to JSON-RPC `-32603` carrying `getMessage()`
     * **verbatim**, which puts strings like `…redis-master.internal:6379` straight into an agent's
     * LLM context: §13 forbids internal network topology in error messages, and that path also
     * wrote no audit row. So the real cause is logged **server-side** with the correlation id, an
     * audit row records `internal_error`, and the agent gets the §9.1 internal-error code with a
     * message that carries nothing but the correlation id to quote.
     *
     * It is the protocol channel rather than an `isError` content result deliberately: §9.2's
     * content shape is the REST §4.2 error object, whose `code` must come from the
     * [pipeline-contract §13](../../../../../../../docs/pipeline-contract.md) catalog — and §13 has
     * no generic server-fault code. Minting one here would put an uncatalogued code on the wire.
     */
    private fun internalError(
        toolName: String,
        request: McpSchema.CallToolRequest,
        ctx: McpToolContext,
        cause: Exception,
        startedAt: Long,
    ): McpError {
        audit(toolName, request, ctx, outcome = "internal_error", startedAt = startedAt, invoked = true)
        log.error("MCP tool {} failed with an uncatalogued fault correlation_id={}", toolName, ctx.correlationId, cause)
        return McpError
            .builder(McpArguments.INTERNAL_ERROR)
            .message("Internal error. Quote correlation id ${ctx.correlationId} to an operator.")
            .build()
    }

    /**
     * The §7.6 gate. Returns the refusal payload when the principal may not call [toolName], or
     * null when it may.
     */
    private fun scopeRefusal(
        toolName: String,
        ctx: McpToolContext,
    ): McpErrorPayload? {
        val required = ScopeMatrix.requiredScopeForTool(toolName)
        if (required == null) {
            // Fail closed: an implemented tool with no documented minimum must not run.
            log.error("MCP tool {} has no scope in the auth §7.6 matrix; refusing the call", toolName)
            return McpErrorPayload(
                code = PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT,
                message = "Tool '$toolName' has no documented scope requirement and cannot be called.",
                userMessage = "You do not have permission to perform this action.",
                details = mapOf("tool" to toolName),
            )
        }
        if (Scope.satisfies(ctx.principal.scopes, required)) return null
        return McpErrorPayload(
            code = PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT,
            message = "Principal lacks required scope for this operation",
            userMessage = "You do not have permission to perform this action.",
            details =
                mapOf(
                    "tool" to toolName,
                    "required" to required.wire,
                    "held" to
                        ctx.principal.scopes
                            .map { it.wire }
                            .sorted(),
                ),
        )
    }

    /**
     * The single audit choke point. Every call site flows through here, so no outcome can
     * dodge its row — and the `mcp.tool.write` emission rides the same choke point, which
     * is what makes "a tool that forgets" impossible (052/A).
     *
     * Emission is AFTER the tool returned, on success and failure alike, and a sink
     * failure is logged and swallowed: the customer's call must not change outcome because
     * bookkeeping did. The [AuditEventSink]'s own boundary already swallows DB failures;
     * this catch is the belt for anything else the sink might throw.
     *
     * [invoked] distinguishes "the tool ran and this is its verdict" from the §7.6
     * refusal, which never touched the tool: the write event records exercised write
     * paths, and a refusal exercised none.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun audit(
        toolName: String,
        request: McpSchema.CallToolRequest,
        ctx: McpToolContext,
        outcome: String,
        code: String? = null,
        startedAt: Long,
        invoked: Boolean,
    ) {
        val details =
            buildMap {
                put("tool", toolName)
                put("outcome", outcome)
                put("correlation_id", ctx.correlationId.toString())
                targetOf(request)?.let { put("target", it) }
                identifierExtras(request)
                code?.let { put("code", it) }
                put("elapsed_ms", (System.nanoTime() - startedAt) / NANOS_PER_MILLI)
            }
        try {
            auditSink.log(
                event = AUDIT_EVENT,
                userId = ctx.principal.userId,
                keyId = ctx.principal.keyId,
                details = details,
            )
            if (invoked && McpToolCatalog.isMutating(toolName)) {
                auditSink.log(
                    event = WRITE_EVENT,
                    userId = ctx.principal.userId,
                    keyId = ctx.principal.keyId,
                    details = details,
                )
            }
        } catch (e: Exception) {
            log.warn("MCP audit emission failed tool={} correlation_id={}", toolName, ctx.correlationId, e)
        }
    }

    /**
     * The entity a call targeted, for the audit row. Only identifier-shaped arguments are
     * recorded — never the whole argument map, which can carry a pipeline body or a render
     * context (Observability §redaction: audit rows are not a copy of the request).
     */
    private fun targetOf(request: McpSchema.CallToolRequest): String? {
        val args = request.arguments() ?: return null
        return TARGET_KEYS.firstNotNullOfOrNull { key -> (args[key] as? String)?.takeIf { it.isNotBlank() } }
    }

    /**
     * The rest of the ruling's target discipline (052): the version a version-aware tool
     * was pointed at, and the node id for node runs — identifiers, never values. The
     * `parameters` map is deliberately NOT read: parameter values are customer data.
     */
    private fun MutableMap<String, Any?>.identifierExtras(request: McpSchema.CallToolRequest) {
        val args = request.arguments() ?: return
        (args["node_id"] as? String)?.takeIf { it.isNotBlank() }?.let { put("node_id", it) }
        (args["version"] as? Number)?.toInt()?.let { put("version", it) }
    }

    private companion object {
        const val AUDIT_EVENT = "mcp.tool.called"
        const val WRITE_EVENT = "mcp.tool.write"
        const val NANOS_PER_MILLI = 1_000_000L
        val TARGET_KEYS = listOf("execution_id", "pipeline_id", "id", "name")
    }
}
