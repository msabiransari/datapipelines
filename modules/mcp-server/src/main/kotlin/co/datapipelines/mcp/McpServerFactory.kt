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
     * The idempotency header, spelled exactly as `web`'s `WebHeaders.IDEMPOTENCY_KEY` spells it.
     * Duplicated rather than imported because `mcp-server` must not depend on `web` (§4.2); the
     * two spellings are pinned together by `McpExecuteIdempotencyTest`.
     */
    const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

    /**
     * §5.1's `instructions` field (workspaces design §9): the workspace context every
     * agent needs before its first tool call, so it does not reason about invisible
     * sibling workspaces (mcp-server.md §2).
     */
    const val SERVER_INSTRUCTIONS: String =
        "This server is workspace-scoped: every tool and resource operates inside the workspace " +
            "the API key is pinned to. Pipelines, templates and executions belong to exactly one " +
            "workspace; datasources are either bound to that workspace or global (shared). Content " +
            "in other workspaces does not exist from this connection's point of view — it is not " +
            "hidden, it is absent: another workspace's pipeline id, template id or datasource name " +
            "resolves as not-found. Names are per-workspace for pipelines and templates (a name you " +
            "see is free to reuse only within this workspace), and datasource names are globally " +
            "unique across the whole server."

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
            // The initialize result's `instructions` — the workspace context statement every
            // agent reads first (workspaces design §9): every tool and resource operates
            // inside the API key's pinned workspace; sibling workspaces are invisible, and
            // their pipelines/templates/executions/datasources simply do not exist from
            // this connection's point of view.
            .instructions(SERVER_INSTRUCTIONS)
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

    /**
     * The per-request context the MCP layer reads ([toolContext]). The principal and correlation
     * id are request attributes [McpAuthFilter] wrote; the `Idempotency-Key` is read straight off
     * the HTTP request here, because it needs no authentication and no validation — it is an
     * opaque client string (056/D6, rest-api §3.5's same header on the same POST).
     */
    internal fun transportContext(request: HttpServletRequest): McpTransportContext {
        val values =
            buildMap<String, Any> {
                request.getAttribute(McpTransportKeys.PRINCIPAL)?.let { put(McpTransportKeys.PRINCIPAL, it) }
                request.getAttribute(McpTransportKeys.CORRELATION_ID)?.let { put(McpTransportKeys.CORRELATION_ID, it) }
                request
                    .getHeader(IDEMPOTENCY_KEY_HEADER)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put(McpTransportKeys.IDEMPOTENCY_KEY, it) }
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
