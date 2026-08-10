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
    return McpToolContext(principal = principal, correlationId = correlationId)
}
