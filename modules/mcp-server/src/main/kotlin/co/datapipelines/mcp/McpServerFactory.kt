package co.datapipelines.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import io.modelcontextprotocol.spec.ProtocolVersions
import jakarta.servlet.http.HttpServletRequest
import reactor.core.publisher.Mono

/**
 * Builds the MCP server and its Streamable HTTP transport (mcp-server.md §3, §5).
 *
 * ## §3.1 implementation gate — closed here
 *
 * - **Protocol version:** `2025-06-18` ([ProtocolVersions.MCP_2025_06_18]), the value §3.2/§5.1
 *   write and one of the four the pinned SDK 2.0.0 compiles in. It is advertised as the *only*
 *   supported version ([PinnedTransport.protocolVersions]) so the handshake matches the documented
 *   contract rather than silently negotiating a version this spec was not written against.
 * - **Transport:** `HttpServletStatelessServerTransport` on `POST /mcp` — the stateless variant,
 *   which is what §3.3 asks for ("stateless by default; each request carries full auth context").
 * - **`GET /mcp`:** the stateless transport answers `405`. A server-initiated SSE stream is
 *   optional in the protocol, and the only v1 feature that would use it is the §10 logging
 *   notification, which §10 itself calls advisory ("nothing in the execution contract depends on
 *   them"). Choosing the session-based provider instead would have bought that stream at the cost
 *   of §7.3's paginated, scope-filtered `resources/list` — see [McpResourceRequestHandler].
 *   Reported to the orchestrator as the one deliberate §3/§10 deviation.
 *
 * ## Capabilities (§5.1)
 *
 * `listChanged` is `false` everywhere and `resources.subscribe` is `false`: the v1 tool surface is
 * static, and the server sends no change notifications a client could rely on.
 */
object McpServerFactory {
    /** §5.1 — `serverInfo.name`. */
    const val SERVER_NAME: String = "datapipelines"

    /** §3.1 gate — the pinned protocol version, advertised and accepted. */
    const val PROTOCOL_VERSION: String = ProtocolVersions.MCP_2025_06_18

    /** §3.2 — the single MCP endpoint. */
    const val ENDPOINT: String = "/mcp"

    /**
     * Builds the transport servlet. Register it at [ENDPOINT]; [McpAuthFilter] must run in front
     * of it.
     */
    fun transport(): HttpServletStatelessServerTransport =
        HttpServletStatelessServerTransport
            .builder()
            .messageEndpoint(ENDPOINT)
            .contextExtractor { request: HttpServletRequest -> transportContext(request) }
            .build()

    /**
     * Builds the server over [transport] and installs the resource handler.
     *
     * The parameter is the [McpStatelessServerTransport] interface rather than the servlet class so
     * the same wiring can be driven in-process by a test harness (module-structure §5.8's
     * "integration tests using an in-process MCP client") without an HTTP container.
     *
     * @param version `serverInfo.version` — the datapipelines.co release version (§5.1).
     */
    fun server(
        transport: McpStatelessServerTransport,
        dispatcher: McpToolDispatcher,
        prompts: McpPromptCatalog,
        catalog: McpResourceCatalog,
        reader: McpResourceReader,
        version: String,
    ): McpStatelessSyncServer =
        McpServer
            .sync(PinnedTransport(transport) { McpResourceRequestHandler(it, catalog, reader) })
            .serverInfo(SERVER_NAME, version)
            .capabilities(capabilities())
            .tools(toolSpecifications(dispatcher))
            .prompts(promptSpecifications(prompts))
            .build()

    /**
     * §5.1 — tools, resources and prompts, all with `listChanged: false`.
     *
     * **No `logging` capability.** §5.1's sketch advertises one, but this server emits
     * `notifications/message` nowhere and the stateless transport answers `GET /mcp` with `405`, so
     * there is no stream to deliver one on. Advertising it would tell an agent that live per-node
     * progress is available during a blocking `pipelines_execute` (§6.2.3 points at exactly that as
     * the v1 stand-in) and leave it waiting for messages that can never arrive. The authoritative
     * per-node record is the `node_stats` array in the tool's final result.
     */
    fun capabilities(): McpSchema.ServerCapabilities =
        McpSchema.ServerCapabilities
            .builder()
            .tools(false)
            .resources(false, false)
            .prompts(false)
            .build()

    private fun toolSpecifications(dispatcher: McpToolDispatcher): List<McpStatelessServerFeatures.SyncToolSpecification> =
        dispatcher.definitions().map { tool ->
            McpStatelessServerFeatures.SyncToolSpecification(tool) { context, request ->
                dispatcher.call(request, context.toolContext())
            }
        }

    private fun promptSpecifications(prompts: McpPromptCatalog): List<McpStatelessServerFeatures.SyncPromptSpecification> =
        prompts.prompts.map { prompt ->
            McpStatelessServerFeatures.SyncPromptSpecification(prompt) { _, request ->
                prompts.get(request.name(), request.arguments() ?: emptyMap())
                    ?: throw McpArguments.invalidParams("Unknown prompt '${request.name()}'.")
            }
        }

    /** The per-request context the MCP layer reads ([toolContext]), populated by [McpAuthFilter]. */
    private fun transportContext(request: HttpServletRequest): McpTransportContext {
        val values =
            buildMap<String, Any> {
                request.getAttribute(McpTransportKeys.PRINCIPAL)?.let { put(McpTransportKeys.PRINCIPAL, it) }
                request.getAttribute(McpTransportKeys.CORRELATION_ID)?.let { put(McpTransportKeys.CORRELATION_ID, it) }
            }
        return McpTransportContext.create(values)
    }
}

/**
 * A transport decorator that pins the advertised protocol version and wraps the handler the SDK
 * installs.
 *
 * Both hooks are the SDK's own: `McpStatelessAsyncServer` reads [protocolVersions] to build the
 * `initialize` handshake and calls [setMcpHandler] with its default request handler. Wrapping at
 * that seam is what lets §7.3's resource semantics live in this module without forking the server
 * (see [McpResourceRequestHandler]).
 */
internal class PinnedTransport(
    private val delegate: McpStatelessServerTransport,
    private val wrap: (McpStatelessServerHandler) -> McpStatelessServerHandler,
) : McpStatelessServerTransport {
    override fun setMcpHandler(handler: McpStatelessServerHandler) = delegate.setMcpHandler(wrap(handler))

    override fun protocolVersions(): List<String> = listOf(McpServerFactory.PROTOCOL_VERSION)

    override fun closeGracefully(): Mono<Void> = delegate.closeGracefully()

    override fun close() = delegate.close()
}
