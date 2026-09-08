package co.datapipelines.mcp

import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import reactor.core.publisher.Mono

/**
 * A transport that keeps the handler the SDK installs on it, so a test can push a JSON-RPC
 * request through the REAL request pipeline in process (module-structure §5.8) without an HTTP
 * container.
 *
 * The handler captured here is exactly the one `POST /mcp` feeds, which is why it is the honest
 * place to assert a P32 fact: `/mcp` is a SERVLET, so the `ScopeInterceptor` that guards every
 * MVC handler never sees it, and a claim about what that endpoint answers has to be made against
 * this pipeline rather than against a catalog constant.
 *
 * Shared rather than private to one suite since 094: two suites drive it, and a second copy is
 * how the two would quietly start testing different pipelines.
 */
internal class CapturingTransport : McpStatelessServerTransport {
    var handler: McpStatelessServerHandler? = null

    override fun setMcpHandler(handler: McpStatelessServerHandler) {
        this.handler = handler
    }

    override fun protocolVersions(): List<String> = listOf(McpServerFactory.PROTOCOL_VERSION)

    override fun closeGracefully(): Mono<Void> = Mono.empty()
}
