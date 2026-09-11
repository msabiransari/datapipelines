package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * **No credential travels through an agent** (094 ruling 4) — the standing rule, asserted on the
 * surface that has to keep it.
 *
 * 068 shipped `datasources_create` with the hazard written into the tool's own description: a
 * password passed through an agent transits its context, its transcript and whatever the client
 * logs. This round decided that hazard is not documentable away. People add datasources in the
 * UI; an operator uses `POST /api/v1/datasources` or the bootstrap file; agents use datasources
 * BY NAME. `datasources_test`, the introspection tools, `datasources_preview_rows` and
 * `datasources_list`/`_get` all stay — none of them accepts a credential.
 *
 * ## Why the last case drives the SDK
 *
 * P32: `POST /mcp` is a SERVLET, so the `ScopeInterceptor` that guards every MVC handler never
 * sees it — a fact 074 learned the expensive way, when a scopeless endpoint key returned the
 * whole tool catalogue. A removal asserted only against [McpToolCatalog] would prove the list
 * changed, not that the SERVER refuses the call. So the last case pushes a real
 * `tools/call` JSON-RPC request through the SDK handler the servlet feeds, over the REAL shipped
 * tool bean, and demands the protocol-level "unknown tool" refusal.
 */
class DatasourcesCreateRemovedTest {
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    @Test
    fun `the shipped tool bean no longer builds a datasources_create tool`() {
        realShippedTools().map { it.name } shouldNotContain TOOL
    }

    @Test
    fun `the catalog, the scope matrix and the count all agree that it is gone`() {
        assertAll(
            { McpToolCatalog.NAMES shouldNotContain TOOL },
            { McpToolCatalog.MUTATING shouldNotContain TOOL },
            { ScopeMatrix.MCP_TOOL_MIN_SCOPE.keys shouldNotContain TOOL },
            // 31 → 30 (089's three lake_tables_* tools landed first), 30 → 34 (107's four
            // probe/cancel/purge tools), 34 → 35 (117's `templates_update`), 35 → 38 (118's three semantics_* tools). The site
            // renders NAMES.size, so this is also what the marketing page says.
            { McpToolCatalog.NAMES.size shouldBe 38 },
            { ScopeMatrix.MCP_TOOL_MIN_SCOPE.size shouldBe 38 },
        )
    }

    @Test
    fun `every datasource tool that remains is a READ - none of them accepts a credential`() {
        val datasourceTools = McpToolCatalog.ENTRIES.filter { it.name.startsWith("datasources_") }

        assertAll(
            // 7 → 8 with 107's `datasources_get_table_stats` (a read; the rule below still holds).
            { datasourceTools.map { it.name }.size shouldBe 8 },
            // The rule made structural: a mutating datasource tool cannot exist on this surface,
            // because the only datasource mutation there is takes a credential.
            { datasourceTools.none { it.mutating } shouldBe true },
        )
    }

    @Test
    fun `calling datasources_create over the mcp servlet path is an unknown tool`() {
        val transport = CapturingTransport()
        McpServerFactory.server(
            transport = transport,
            dispatcher = McpToolDispatcher(realShippedTools(), auditLogger),
            prompts = McpPromptCatalog(),
            catalog = McpResourceCatalog(pipelines, templates, datasources, executions),
            reader = McpResourceReader(McpFixtures.pipelineService(pipelines), templates, datasources, executions, events),
            version = "1.0.0",
        )

        val response =
            transport.handler!!
                .handleRequest(
                    McpTransportContext.create(
                        mapOf(
                            McpTransportKeys.PRINCIPAL to McpFixtures.principal(Scope.ADMIN),
                            McpTransportKeys.CORRELATION_ID to McpFixtures.CORRELATION_ID,
                        ),
                    ),
                    McpSchema.JSONRPCRequest(
                        McpSchema.JSONRPC_VERSION,
                        McpSchema.METHOD_TOOLS_CALL,
                        "1",
                        mapOf(
                            "name" to TOOL,
                            "arguments" to
                                mapOf(
                                    "name" to "pg_agent",
                                    "dialect" to "POSTGRES",
                                    "jdbc_url" to "jdbc:postgresql://db:5432/app",
                                    "username" to "readonly",
                                    // The exact thing that must never reach this surface.
                                    "password" to "s3cret",
                                ),
                        ),
                    ),
                ).block()!!

        // An ADMIN key, the highest scope there is, and it still cannot reach a tool that does
        // not exist: the refusal is the PROTOCOL's, not a permission check that could be widened.
        // The message is the SDK's own ("Unknown tool: invalid_tool_name") and deliberately does
        // not echo the requested name — this asserts the refusal, not the wording.
        val error = requireNotNull(response.error()) { "expected a JSON-RPC error for an unknown tool" }
        error.message() shouldContain "Unknown tool"
        response.result() shouldBe null
    }

    @Test
    fun `tools list over the protocol does not offer it either`() {
        val transport = CapturingTransport()
        McpServerFactory.server(
            transport = transport,
            dispatcher = McpToolDispatcher(realShippedTools(), auditLogger),
            prompts = McpPromptCatalog(),
            catalog = McpResourceCatalog(pipelines, templates, datasources, executions),
            reader = McpResourceReader(McpFixtures.pipelineService(pipelines), templates, datasources, executions, events),
            version = "1.0.0",
        )

        val result =
            transport.handler!!
                .handleRequest(
                    McpTransportContext.create(
                        mapOf(
                            McpTransportKeys.PRINCIPAL to McpFixtures.principal(Scope.ADMIN),
                            McpTransportKeys.CORRELATION_ID to McpFixtures.CORRELATION_ID,
                        ),
                    ),
                    McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_LIST, "1", emptyMap<String, Any>()),
                ).block()!!
                .result() as McpSchema.ListToolsResult

        result.tools().map { it.name() } shouldNotContain TOOL
        result.tools().size shouldBe 38
    }

    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>(relaxed = true)
    private val datasources = mockk<DatasourceRegistry>(relaxed = true)
    private val executions = mockk<ExecutionRepository>(relaxed = true)
    private val events = mockk<ExecutionEventRepository>(relaxed = true)

    private companion object {
        const val TOOL = "datasources_create"
    }
}
