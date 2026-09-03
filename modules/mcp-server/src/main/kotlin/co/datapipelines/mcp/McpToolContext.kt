package co.datapipelines.mcp

import co.datapipelines.auth.AuthenticatedPrincipal
import io.modelcontextprotocol.common.McpTransportContext
import java.util.UUID

/**
 * Everything a tool, resource read or prompt needs about *who is asking* and *under which
 * correlation id* (mcp-server.md §6.3, Observability §9).
 *
 * The principal is resolved once per HTTP request by [McpAuthFilter] and carried into the MCP
 * layer through the transport context ([McpTransportKeys]); nothing below this type re-reads a
 * header or re-validates a credential.
 */
data class McpToolContext(
    val principal: AuthenticatedPrincipal,
    val correlationId: UUID,
    /**
     * The request's `Idempotency-Key` header, trimmed; null when absent or blank (rest-api §3.5).
     *
     * 056/D6: `pipelines_execute` had **no idempotency support at all** while the REST execute
     * path did — a behavioural divergence the audit called "a bug wearing a duplication costume".
     * The carrier is deliberately the HTTP header REST already uses on the same `POST /mcp`
     * request, NOT a new tool argument: adding a property to the `pipelines_execute` input schema
     * would change the wire surface `McpToolSurfaceSpecDriftTest` freezes against mcp-server.md
     * §6.2.3, and this round moves code without changing what the surface DOES on the wire.
     */
    val idempotencyKey: String? = null,
)

/**
 * Keys shared by the servlet layer and the MCP layer.
 *
 * The servlet request attributes are written by [McpAuthFilter]; the transport-context keys are
 * written by the `contextExtractor` the transport is built with ([McpServerFactory]) and read
 * back by every handler through [toolContext].
 */
object McpTransportKeys {
    /** Request attribute + transport-context key holding the [AuthenticatedPrincipal]. */
    const val PRINCIPAL: String = "co.datapipelines.mcp.principal"

    /** Request attribute + transport-context key holding the request's correlation id. */
    const val CORRELATION_ID: String = "co.datapipelines.mcp.correlation_id"

    /** Transport-context key holding the request's `Idempotency-Key` header (056/D6). */
    const val IDEMPOTENCY_KEY: String = "co.datapipelines.mcp.idempotency_key"
}

/**
 * The calling context carried by this transport context.
 *
 * @throws IllegalStateException when no principal is present — which cannot happen through the
 *   servlet path ([McpAuthFilter] answers `401` before the transport is ever reached) and is a
 *   wiring bug anywhere else. Failing loudly is deliberate (§2 principle 5): a tool that ran
 *   without a principal would be an unauthenticated execution.
 */
fun McpTransportContext.toolContext(): McpToolContext {
    val principal =
        get(McpTransportKeys.PRINCIPAL) as? AuthenticatedPrincipal
            ?: error("No authenticated principal in the MCP transport context; McpAuthFilter did not run")
    val correlationId = get(McpTransportKeys.CORRELATION_ID) as? UUID ?: UUID.randomUUID()
    return McpToolContext(
        principal = principal,
        correlationId = correlationId,
        idempotencyKey = get(McpTransportKeys.IDEMPOTENCY_KEY) as? String,
    )
}
