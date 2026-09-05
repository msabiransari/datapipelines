package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import io.modelcontextprotocol.spec.ProtocolVersions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import reactor.core.publisher.Mono

/**
 * The §3/§5 transport and capability wiring, and the §6.1 tool surface as the server actually
 * advertises it.
 */
class McpServerWiringTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val datasources = mockk<DatasourceRegistry>()
    private val executions = mockk<ExecutionRepository>()
    private val events = mockk<ExecutionEventRepository>()
    private val executor = mockk<PipelineExecutor>()
    private val resultStore = mockk<ResultStore>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val resultUrls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" }
    private val authoringGuard = co.datapipelines.pipeline.AuthoringGuard(true)

    private fun tools(): List<McpTool> {
        val validator = mockk<PipelineValidator>()
        val templateValidator = mockk<TemplateValidator>()
        val engines = mockk<WorkspaceTemplateEngines>()
        val introspector = mockk<SchemaIntrospector>()
        val usage = co.datapipelines.templates.TemplateUsageService(templates, pipelines)
        val service = McpFixtures.pipelineService(pipelines, validator, authoringGuard)
        return listOf(
            PipelinesListTool(service),
            PipelinesGetTool(service, usage),
            PipelineExecuteTool(service, executor, executions, resultStore, resultUrls),
            PipelinesExecuteNodeTool(
                co.datapipelines.templates.NodeSqlResolver(pipelines, templates, engines),
                datasources,
                co.datapipelines.datasources.SqlRunner(datasources),
            ),
            PipelinesCreateTool(service),
            PipelinesUpdateTool(service),
            TemplatesListTool(templates),
            TemplatesGetTool(templates),
            TemplatesUsedByTool(usage),
            TemplatesCreateTool(templates, authoringGuard, templateValidator),
            TemplatesRenderTool(templates, engines),
            DatasourcesListTool(datasources),
            DatasourcesGetTool(datasources),
            DatasourcesTestTool(datasources),
            DatasourcesGetSchemasTool(introspector, datasources),
            DatasourcesGetTablesTool(introspector, datasources),
            DatasourcesGetColumnsTool(introspector, datasources),
            DatasourcesPreviewRowsTool(datasources, co.datapipelines.datasources.SqlRunner(datasources)),
            DatasourcesCreateTool(mockk<co.datapipelines.application.datasources.DatasourceCreateService>()),
            ExecutionsListTool(executions),
            ExecutionsGetTool(executions),
            ExecutionsGetResultTool(executions, resultStore, resultUrls, ResultConfig()),
            CalculatorsListTool(),
            CalculatorsGetTool(),
        )
    }

    /**
     * The §6.1 surface and the auth §7.6 matrix are the same 24 names, in both directions. A tool
     * without a matrix row is refused at dispatch (fail-closed); a matrix row without a tool is a
     * documented capability that does not exist.
     */
    @Test
    fun `the tool surface is exactly the 24 tools the scope matrix knows`() {
        val dispatcher = McpToolDispatcher(tools(), auditLogger)

        assertAll(
            { dispatcher.toolNames().size shouldBe McpToolCatalog.NAMES.size },
            { dispatcher.toolNames() shouldContainExactlyInAnyOrder ScopeMatrix.MCP_TOOL_MIN_SCOPE.keys },
        )
    }

    @Test
    fun `the server builds with all 24 tools and all three prompts registered`() {
        val transport = McpServerFactory.transport()
        val server =
            McpServerFactory.server(
                transport,
                McpToolDispatcher(tools(), auditLogger),
                McpPromptCatalog(),
                McpResourceCatalog(pipelines, templates, datasources, executions),
                McpResourceReader(pipelines, templates, datasources, executions, events),
                version = "1.0.0",
            )

        assertAll(
            { server.listTools().size shouldBe McpToolCatalog.NAMES.size },
            {
                server.listPrompts().map { it.name() } shouldContainExactlyInAnyOrder
                    listOf("analyze_pipeline", "create_pipeline_for_question", "debug_failed_execution")
            },
            { server.serverInfo.name() shouldBe "datapipelines" },
            { server.serverInfo.version() shouldBe "1.0.0" },
        )
        server.close()
    }

    @Test
    fun `capabilities advertise no listChanged and no subscriptions`() {
        val capabilities = McpServerFactory.capabilities()

        assertAll(
            { capabilities.tools().listChanged() shouldBe false },
            { capabilities.resources().listChanged() shouldBe false },
            { capabilities.resources().subscribe() shouldBe false },
            { capabilities.prompts().listChanged() shouldBe false },
            // B4: no `logging` — the stateless transport 405s GET /mcp and this module emits no
            // notifications/message, so advertising it would promise progress that never arrives.
            { capabilities.logging() shouldBe null },
        )
    }

    @Test
    fun `the advertised protocol version is pinned to the one the spec documents`() {
        val recorder = RecordingTransport()
        PinnedTransport(recorder) { it }.protocolVersions() shouldBe listOf(ProtocolVersions.MCP_2025_06_18)
        McpServerFactory.PROTOCOL_VERSION shouldBe "2025-06-18"
    }

    @Test
    fun `the resource handler wraps the SDK's own handler rather than replacing it`() {
        val recorder = RecordingTransport()
        val delegate = mockk<McpStatelessServerHandler>(relaxed = true)

        PinnedTransport(recorder) { inner -> McpResourceRequestHandler(inner, catalog(), reader()) }.setMcpHandler(delegate)

        (recorder.handler is McpResourceRequestHandler) shouldBe true
    }

    @Test
    fun `resources are served here and every other method is delegated`() {
        val delegate = mockk<McpStatelessServerHandler>()
        every { pipelines.findAll(any(), null) } returns emptyList()
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { delegate.handleRequest(any(), any()) } returns
            Mono.just(McpSchema.JSONRPCResponse.result("1", mapOf("delegated" to true)))

        val handler = McpResourceRequestHandler(delegate, catalog(), reader())
        val context = context()

        val listed = handler.handleRequest(context, request(McpSchema.METHOD_RESOURCES_LIST, emptyMap())).block()!!
        val delegated = handler.handleRequest(context, request(McpSchema.METHOD_TOOLS_LIST, emptyMap())).block()!!

        assertAll(
            {
                (listed.result() as McpSchema.ListResourcesResult).resources().map { it.uri() } shouldBe
                    listOf("datapipelines://datasources")
            },
            { (listed.result() as McpSchema.ListResourcesResult).nextCursor() shouldBe null },
            { (delegated.result() as Map<*, *>)["delegated"] shouldBe true },
        )
    }

    @Test
    fun `an undecodable cursor comes back as invalid params, not as an empty page`() {
        val handler = McpResourceRequestHandler(mockk(relaxed = true), catalog(), reader())

        val response = handler.handleRequest(context(), request(McpSchema.METHOD_RESOURCES_LIST, mapOf("cursor" to "@@@"))).block()!!

        assertAll(
            { response.result() shouldBe null },
            { response.error().code() shouldBe McpArguments.INVALID_PARAMS },
        )
    }

    @Test
    fun `resources_read without a uri is invalid params`() {
        val handler = McpResourceRequestHandler(mockk(relaxed = true), catalog(), reader())

        val response = handler.handleRequest(context(), request(McpSchema.METHOD_RESOURCES_READ, emptyMap())).block()!!

        response.error().code() shouldBe McpArguments.INVALID_PARAMS
    }

    private fun catalog() = McpResourceCatalog(pipelines, templates, datasources, executions)

    private fun reader() = McpResourceReader(pipelines, templates, datasources, executions, events)

    private fun context(): McpTransportContext =
        McpTransportContext.create(
            mapOf(
                McpTransportKeys.PRINCIPAL to McpFixtures.principal(Scope.READ),
                McpTransportKeys.CORRELATION_ID to McpFixtures.CORRELATION_ID,
            ),
        )

    private fun request(
        method: String,
        params: Map<String, Any?>,
    ) = McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, "1", params)

    /** Captures what the SDK (or [PinnedTransport]) installs. */
    private class RecordingTransport : McpStatelessServerTransport {
        var handler: McpStatelessServerHandler? = null

        override fun setMcpHandler(handler: McpStatelessServerHandler) {
            this.handler = handler
        }

        override fun closeGracefully(): Mono<Void> = Mono.empty()
    }
}
