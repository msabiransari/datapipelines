package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
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
        val drafts = co.datapipelines.templates.TemplateDraftService(templates, authoringGuard, mockk(relaxed = true))
        val semantics = mockk<co.datapipelines.application.semantics.SemanticsService>()
        return listOf(
            PipelinesListTool(service, McpFixtures.EVERYTHING_LENS),
            PipelinesGetTool(service, usage, McpFixtures.EVERYTHING_LENS),
            PipelineExecuteTool(service, executor, executions, resultStore, resultUrls),
            PipelinesExecuteNodeTool(
                co.datapipelines.templates.NodeSqlResolver(pipelines, templates, engines),
                datasources,
                co.datapipelines.datasources.SqlRunner(datasources),
            ),
            PipelinesCreateTool(service, pipelines),
            PipelinesUpdateTool(service),
            TemplatesListTool(McpFixtures.templateService(templates), McpFixtures.EVERYTHING_LENS),
            TemplatesGetTool(McpFixtures.templateService(templates), McpFixtures.EVERYTHING_LENS),
            TemplatesUsedByTool(usage, McpFixtures.EVERYTHING_LENS),
            McpFixtures.createTool(templates, authoringGuard, templateValidator),
            TemplatesUpdateTool(templates, drafts, templateValidator),
            TemplatesRenderTool(templates, engines),
            // 7b — the transform evaluator, appended the way the shipped bean does.
            TemplatesEvaluateTool(mockk<co.datapipelines.application.templates.TemplateEvaluateService>()),
            TemplatesPurgeDraftTool(templates, usage, authoringGuard),
            DatasourcesListTool(datasources, lens = McpFixtures.EVERYTHING_LENS),
            DatasourcesGetTool(datasources, lens = McpFixtures.EVERYTHING_LENS),
            DatasourcesTestTool(datasources),
            DatasourcesGetSchemasTool(introspector, datasources),
            DatasourcesGetTablesTool(introspector, datasources),
            DatasourcesGetColumnsTool(introspector, datasources),
            DatasourcesGetTableStatsTool(introspector, datasources),
            DatasourcesPreviewRowsTool(datasources, co.datapipelines.datasources.SqlRunner(datasources), introspector),
            SqlProbeTool(datasources, co.datapipelines.datasources.SqlProbe(datasources)),
            ExecutionsListTool(executions),
            ExecutionsGetTool(executions),
            ExecutionsGetResultTool(executions, resultStore, resultUrls, ResultConfig()),
            ExecutionsCancelTool(executions, mockk(), mockk()),
            CalculatorsListTool(),
            CalculatorsGetTool(),
            // 074 — the four published-endpoint tools, appended the way the shipped bean does.
        ) +
            EndpointsTools.all(
                mockk<co.datapipelines.application.endpoints.EndpointPublishService>(),
                mockk<co.datapipelines.pipeline.PipelineRepository>(),
            ) +
            // 089 §A — the three dp-lake catalog tools, appended after the endpoint tools.
            LakeTableTools.all(
                datasources,
                mockk<co.datapipelines.application.datasources.LakeTableRegistryService>(),
            ) +
            // 118 — the three learned-semantics tools, appended after the lake tools.
            SemanticsTools.all(datasources, semantics, introspector, McpFixtures.EVERYTHING_LENS) +
            // 120 — the two docs tools, appended after the semantics tools.
            DocsTools.all { DocSetTestSupport.minimalDocSet() } +
            // 140 — the release-check run, appended after the docs tools.
            listOf(PipelineRunChecksTool(service, mockk()))
    }

    /**
     * The §6.1 surface and the auth §7.6 matrix are the same 41 names, in both directions. A tool
     * without a matrix row is refused at dispatch (fail-closed); a matrix row without a tool is a
     * documented capability that does not exist. (28 → 27 with 094 removing
     * `datasources_create`; 30 → 34 with 107's probe/cancel/purge four; 34 → 35 with 117's `templates_update`; 35 → 38 with 118's
     * `semantics_*` three; 38 → 40 with 120's `docs_*` two; 40 → 41 with 140's `pipelines_run_checks`.)
     */
    @Test
    fun `the tool surface is exactly the 41 tools the scope matrix knows`() {
        val dispatcher = McpToolDispatcher(tools(), auditLogger)

        assertAll(
            { dispatcher.toolNames().size shouldBe McpToolCatalog.NAMES.size },
            { dispatcher.toolNames() shouldContainExactlyInAnyOrder McpToolCatalog.NAMES },
        )
    }

    @Test
    fun `the server builds with all 41 tools and all three prompts registered`() {
        val transport = McpServerFactory.transport()
        val server =
            McpServerFactory.server(
                transport,
                McpToolDispatcher(tools(), auditLogger),
                McpPromptCatalog(),
                McpResourceCatalog(
                    McpFixtures.pipelineService(pipelines),
                    McpFixtures.templateService(templates),
                    datasources,
                    executions,
                    McpFixtures.EVERYTHING_LENS,
                    docSet = DocSetTestSupport.minimalDocSet(),
                ),
                McpResourceReader(
                    McpFixtures.pipelineService(pipelines),
                    McpFixtures.templateService(templates),
                    datasources,
                    executions,
                    events,
                    auditLogger,
                    McpFixtures.EVERYTHING_LENS,
                    DocSetTestSupport.minimalDocSet(),
                ),
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
                // The docs rows (095) lead every listing; the datasource collection URI is the
                // only entity row an empty instance has.
                (listed.result() as McpSchema.ListResourcesResult).resources().map { it.uri() } shouldBe
                    listOf(McpResourceUri.skill()) +
                    DocSetTestSupport
                        .minimalDocSet()
                        .docs
                        .filter { it.name != "core" }
                        .map { McpResourceUri.skillReference(it.name) } +
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

    private fun catalog() =
        McpResourceCatalog(
            McpFixtures.pipelineService(pipelines),
            McpFixtures.templateService(templates),
            datasources,
            executions,
            McpFixtures.EVERYTHING_LENS,
            docSet = DocSetTestSupport.minimalDocSet(),
        )

    private fun reader() =
        McpResourceReader(
            McpFixtures.pipelineService(pipelines),
            McpFixtures.templateService(templates),
            datasources,
            executions,
            events,
            auditLogger,
            McpFixtures.EVERYTHING_LENS,
            DocSetTestSupport.minimalDocSet(),
        )

    private fun context(): McpTransportContext =
        McpTransportContext.create(
            mapOf(
                McpTransportKeys.PRINCIPAL to McpFixtures.principal(),
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
