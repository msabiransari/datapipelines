package co.datapipelines.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Serves `resources/list` and `resources/read` from [McpResourceCatalog] / [McpResourceReader],
 * delegating every other JSON-RPC method to the SDK's own handler.
 *
 * ## Why this exists
 *
 * The SDK answers `resources/list` from the resources **statically registered** on the server: one
 * global set, no cursor, no per-caller filtering. mcp-server.md §7.3 requires the opposite on all
 * three counts — a page of 100 with an opaque cursor, an enumeration that changes as pipelines and
 * executions are created, and a listing "filtered to what the calling key may read" (which the §13
 * security checklist calls out separately from pagination). Registering resources with the SDK
 * therefore cannot implement §7, so this module owns both resource methods and registers none.
 *
 * `McpStatelessServerTransport.setMcpHandler` is the SDK's own composition point for exactly this,
 * so nothing here reaches around the library: the default handler is wrapped, not replaced.
 */
class McpResourceRequestHandler(
    private val delegate: McpStatelessServerHandler,
    private val catalog: McpResourceCatalog,
    private val reader: McpResourceReader,
) : McpStatelessServerHandler {
    private val log = LoggerFactory.getLogger(McpResourceRequestHandler::class.java)

    override fun handleRequest(
        context: McpTransportContext,
        request: McpSchema.JSONRPCRequest,
    ): Mono<McpSchema.JSONRPCResponse> =
        when (request.method()) {
            McpSchema.METHOD_RESOURCES_LIST -> Mono.fromCallable { answer(context, request) { list(it, request) } }
            McpSchema.METHOD_RESOURCES_READ -> Mono.fromCallable { answer(context, request) { read(it, request) } }
            else -> delegate.handleRequest(context, request)
        }

    override fun handleNotification(
        context: McpTransportContext,
        notification: McpSchema.JSONRPCNotification,
    ): Mono<Void> = delegate.handleNotification(context, notification)

    /**
     * Runs [body] and shapes the JSON-RPC answer.
     *
     * A [McpError] (bad cursor, unknown URI, scope refusal) becomes the JSON-RPC error it carries.
     * Anything else — a Redis outage, a metadata-DB failure — is **sanitized**: without this catch
     * the SDK would return `-32603` carrying `getMessage()` verbatim, putting internal hostnames
     * into an agent's LLM context, which §13 forbids. The real cause is logged server-side against
     * the correlation id; the agent gets that id and nothing else. Failing loudly is still the
     * rule (§2 principle 5) — an error is returned, never a plausible-looking empty page.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun answer(
        context: McpTransportContext,
        request: McpSchema.JSONRPCRequest,
        body: (McpToolContext) -> Any,
    ): McpSchema.JSONRPCResponse {
        val ctx = context.toolContext()
        return try {
            McpSchema.JSONRPCResponse.result(request.id(), body(ctx))
        } catch (e: McpError) {
            log.debug("MCP {} rejected: {}", request.method(), e.jsonRpcError.message())
            McpSchema.JSONRPCResponse.error(request.id(), e.jsonRpcError)
        } catch (e: Exception) {
            log.error("MCP {} failed with an uncatalogued fault correlation_id={}", request.method(), ctx.correlationId, e)
            McpSchema.JSONRPCResponse.error(
                request.id(),
                McpSchema.JSONRPCResponse.JSONRPCError(
                    McpArguments.INTERNAL_ERROR,
                    "Internal error. Quote correlation id ${ctx.correlationId} to an operator.",
                ),
            )
        }
    }

    private fun list(
        ctx: McpToolContext,
        request: McpSchema.JSONRPCRequest,
    ): McpSchema.ListResourcesResult {
        val page = catalog.list(ctx, param(request, "cursor"))
        return McpSchema.ListResourcesResult(page.resources, page.nextCursor, null)
    }

    private fun read(
        ctx: McpToolContext,
        request: McpSchema.JSONRPCRequest,
    ): McpSchema.ReadResourceResult {
        val uri = param(request, "uri") ?: throw McpArguments.invalidParams("Missing required field 'uri'.")
        return reader.read(uri, ctx)
    }

    private fun param(
        request: McpSchema.JSONRPCRequest,
        name: String,
    ): String? = (request.params() as? Map<*, *>)?.get(name) as? String
}
