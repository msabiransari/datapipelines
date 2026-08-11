package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import reactor.core.publisher.Mono

/**
 * Drives the **real SDK request pipeline** in process — the module-structure §5.8 "integration
 * tests using an in-process MCP client", without an HTTP container.
 *
 * The handler the server installs on its transport is exactly the one `POST /mcp` feeds, so a
 * JSON-RPC message handled here travels the same code path a Claude Desktop call would: SDK
 * decode → SDK method dispatch → this module's dispatcher/catalog → SDK encode.
 */
class McpProtocolIntegrationTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>(relaxed = true)
    private val datasources = mockk<DatasourceRegistry>(relaxed = true)
    private val executions = mockk<ExecutionRepository>(relaxed = true)
    private val events = mockk<ExecutionEventRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    private val transport = CapturingTransport()

    init {
        McpServerFactory.server(
            transport = transport,
            dispatcher = McpToolDispatcher(listOf(PipelinesGetTool(pipelines), PipelinesListTool(pipelines)), auditLogger),
            prompts = McpPromptCatalog(),
            catalog = McpResourceCatalog(pipelines, templates, datasources, executions),
            reader = McpResourceReader(pipelines, templates, datasources, executions, events),
            version = "1.0.0",
        )
    }

    private fun call(
        method: String,
        params: Any?,
        scope: Scope = Scope.READ,
    ): McpSchema.JSONRPCResponse =
        transport.handler!!
            .handleRequest(
                McpTransportContext.create(
                    mapOf(
                        McpTransportKeys.PRINCIPAL to McpFixtures.principal(scope),
                        McpTransportKeys.CORRELATION_ID to McpFixtures.CORRELATION_ID,
                    ),
                ),
                McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, "1", params),
            ).block()!!

    @Test
    fun `initialize advertises the pinned protocol version, the server info and the capabilities`() {
        val response =
            call(
                McpSchema.METHOD_INITIALIZE,
                mapOf(
                    "protocolVersion" to McpServerFactory.PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "test-agent", "version" to "1.0.0"),
                ),
            )
        val result = response.result() as McpSchema.InitializeResult

        assertAll(
            { result.protocolVersion() shouldBe "2025-06-18" },
            { result.serverInfo().name() shouldBe "datapipelines" },
            { result.capabilities().tools().listChanged() shouldBe false },
            { result.capabilities().resources().subscribe() shouldBe false },
        )
    }

    @Test
    fun `a client offering an unsupported protocol version is negotiated down to ours`() {
        val response =
            call(
                McpSchema.METHOD_INITIALIZE,
                mapOf(
                    "protocolVersion" to "2099-01-01",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "test-agent", "version" to "1.0.0"),
                ),
            )

        (response.result() as McpSchema.InitializeResult).protocolVersion() shouldBe McpServerFactory.PROTOCOL_VERSION
    }

    @Test
    fun `tools list is served over the protocol`() {
        val result = call(McpSchema.METHOD_TOOLS_LIST, emptyMap<String, Any>()).result() as McpSchema.ListToolsResult

        result.tools().map { it.name() } shouldContain "pipelines_get"
    }

    @Test
    fun `a tool call reaches the tool and comes back in the §6_3 envelope`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()

        val response =
            call(
                McpSchema.METHOD_TOOLS_CALL,
                mapOf("name" to "pipelines_get", "arguments" to mapOf("id" to McpFixtures.PIPELINE_ID.toString())),
            )
        val result = response.result() as McpSchema.CallToolResult

        // §6.3 on the wire, not just in the object graph: the envelope an agent receives must carry
        // the correlation id under `_meta`, which is the SDK's serialized name for `meta()`.
        val wire = McpJsonDefaults.getMapper().writeValueAsString(result)

        assertAll(
            { result.isError() shouldBe false },
            { (result.content().single() as McpSchema.TextContent).text() shouldContain "monthly_revenue" },
            { result.meta()[McpToolResults.META_CORRELATION_ID] shouldBe McpFixtures.CORRELATION_ID.toString() },
            { wire shouldContain """"_meta":{"correlation_id":"${McpFixtures.CORRELATION_ID}"}""" },
            { wire shouldContain """"isError":false""" },
        )
    }

    @Test
    fun `resources list is served by this module, not by the SDK's empty registry`() {
        every { pipelines.findAll(null) } returns listOf(McpFixtures.pipelineRecord())
        every { templates.list(any(), any(), any(), any()) } returns emptyList()
        every { datasources.list(null) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val result = call(McpSchema.METHOD_RESOURCES_LIST, emptyMap<String, Any>()).result() as McpSchema.ListResourcesResult

        result.resources().map { it.uri() } shouldContain "datapipelines://pipelines/${McpFixtures.PIPELINE_ID}"
    }

    @Test
    fun `prompts get is served over the protocol`() {
        val response =
            call(
                McpSchema.METHOD_PROMPT_GET,
                mapOf("name" to "analyze_pipeline", "arguments" to mapOf("pipeline_id" to McpFixtures.PIPELINE_ID.toString())),
            )
        val result = response.result() as McpSchema.GetPromptResult

        (result.messages().single().content() as McpSchema.TextContent).text() shouldContain "pipelines_get"
    }

    @Test
    fun `an uncatalogued fault reaches the agent sanitized, over the real protocol`() {
        every { pipelines.findById(any()) } throws IllegalStateException("Redis down at redis-master.internal:6379")

        val response =
            call(
                McpSchema.METHOD_TOOLS_CALL,
                mapOf("name" to "pipelines_get", "arguments" to mapOf("id" to McpFixtures.PIPELINE_ID.toString())),
            )

        assertAll(
            { response.error().code() shouldBe McpArguments.INTERNAL_ERROR },
            { response.error().message() shouldNotContain "redis-master.internal" },
            { response.error().message() shouldContain McpFixtures.CORRELATION_ID.toString() },
        )
    }

    @Test
    fun `the handshake does not promise logging notifications this server cannot send`() {
        val result =
            call(
                McpSchema.METHOD_INITIALIZE,
                mapOf(
                    "protocolVersion" to McpServerFactory.PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "test-agent", "version" to "1.0.0"),
                ),
            ).result() as McpSchema.InitializeResult

        result.capabilities().logging() shouldBe null
    }

    /** A transport that only captures the handler the server installs. */
    private class CapturingTransport : McpStatelessServerTransport {
        var handler: McpStatelessServerHandler? = null

        override fun setMcpHandler(handler: McpStatelessServerHandler) {
            this.handler = handler
        }

        override fun protocolVersions(): List<String> = listOf(McpServerFactory.PROTOCOL_VERSION)

        override fun closeGracefully(): Mono<Void> = Mono.empty()
    }
}
