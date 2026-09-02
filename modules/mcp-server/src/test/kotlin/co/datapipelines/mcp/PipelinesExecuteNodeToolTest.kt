package co.datapipelines.mcp

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.QueryRows
import co.datapipelines.datasources.SqlRunner
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.NodeSqlResolution
import co.datapipelines.templates.NodeSqlResolver
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * `pipelines_execute_node` (§6.2.20) — the E2 refusal ladder (all before anything runs), the
 * E4 readonly refusal for DML/DDL, and the E5 version/status contract. The resolution states
 * themselves are pinned in `NodeSqlResolverTest`; this suite owns the TOOL's mapping of each
 * state to its catalogued outcome.
 */
class PipelinesExecuteNodeToolTest {
    // The resolver is mocked: its six states are pinned in NodeSqlResolverTest; this suite
    // owns the TOOL's mapping of each state to its catalogued outcome.
    private val resolver = mockk<NodeSqlResolver>()
    private val datasources = mockk<DatasourceRegistry>()
    private val runner = mockk<SqlRunner>()
    private val tool = PipelinesExecuteNodeTool(resolver, datasources, runner)
    private val ctx = McpFixtures.ctx(co.datapipelines.auth.Scope.AUTHOR)

    private val pipelineId = McpFixtures.PIPELINE_ID
    private val draft =
        PipelineVersionDetail(
            pipelineId = pipelineId,
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "hash-2",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = McpFixtures.USER,
        )
    private val datasource =
        Datasource(
            name = "sample-trips",
            displayName = "Sample Trips",
            description = "",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://x/y",
            username = "u",
            password = null,
        )



    private fun rendered(
        nodeType: NodeType = NodeType.DQL,
        source: String = "sample-trips",
        sql: String = "SELECT * FROM trips WHERE d = :start_date",
    ) = NodeSqlResolution.Rendered(
        version = draft,
        node =
            Node(
                id = "fetch",
                description = "",
                type = nodeType,
                source = source,
                template = TemplateRef("fetch.sql", 1),
                output = null,
                dependsOn = emptyList(),
            ),
        templateId = "fetch.sql",
        templateVersion = 1,
        dialect = Dialect.POSTGRES,
        sql = sql,
        positionalSql = "SELECT * FROM trips WHERE d = ?",
        bindValues = listOf(java.time.LocalDate.parse("2026-09-01")),
        contextValues = mapOf("start_date" to java.time.LocalDate.parse("2026-09-01")),
        sampledParameters = listOf("start_date"),
    )

    private fun resolverReturns(resolution: NodeSqlResolution) {
        every {
            resolver.resolve(McpFixtures.WORKSPACE_ID, pipelineId, "fetch", null, null)
        } returns resolution
    }

    @Test
    fun `a DQL run returns rows, the sql, the sampled parameters and the version and status it ran`() {
        resolverReturns(rendered())
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns datasource
        every { runner.select(datasource, "SELECT * FROM trips WHERE d = ?", any(), 50) } returns
            QueryRows(
                co.datapipelines.datasources.ResultSchema(listOf(ColumnSchema("city", LogicalType.STRING)), emptyList()),
                listOf(mapOf("city" to "acme")),
                truncated = false,
            )

        val payload =
            payloadOf(tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx))

        assertAll(
            { payload["node_type"] shouldBe "DQL" },
            { payload["datasource"] shouldBe "sample-trips" },
            { payload["version"] shouldBe 2 },
            { payload["status"] shouldBe "DRAFT" },
            { payload["sql"] shouldBe "SELECT * FROM trips WHERE d = :start_date" },
            { payload["sampled_parameters"] shouldBe listOf("start_date") },
            { payload["row_count"] shouldBe 1 },
            { payload["truncated"] shouldBe false },
            { (payload["elapsed_ms"] as Long) >= 0 },
        )
    }

    @Test
    fun `a DML node executes for real and reports affected rows`() {
        resolverReturns(rendered(nodeType = NodeType.DML, sql = "DELETE FROM trips WHERE d = :start_date"))
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns datasource
        every { runner.executeUpdate(datasource, "SELECT * FROM trips WHERE d = ?", any()) } returns 3L

        val payload =
            payloadOf(tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx))

        payload["affected_rows"] shouldBe 3L
        payload["node_type"] shouldBe "DML"
    }

    @Test
    fun `an unknown node is the new not_found code, naming the version searched`() {
        resolverReturns(NodeSqlResolution.NodeMissing(draft, "fetch"))

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
            }

        assertAll(
            { thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.NOT_FOUND },
            { thrown.details["node_id"] shouldBe "fetch" },
            { thrown.details["version"] shouldBe 2 },
        )
        verify(exactly = 0) { runner.select(any(), any(), any(), any()) }
    }

    @Test
    fun `a tempdb source and a PIPELINE node are the standalone refusal with the reason named`() {
        resolverReturns(rendered(source = "tempdb"))

        val tempdb =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
            }
        assertAll(
            { tempdb.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED },
            { tempdb.details["reason"] shouldBe "tempdb_source" },
            { (tempdb.message ?: "") shouldContain "pipelines_execute" },
        )

        resolverReturns(NodeSqlResolution.ChildPipeline(draft, "child_pipe", 3))
        val pipelineNode =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
            }
        pipelineNode.details["reason"] shouldBe "pipeline_node"

        verify(exactly = 0) { runner.select(any(), any(), any(), any()) }
        verify(exactly = 0) { datasources.getVisible(any(), any()) }
    }

    @Test
    fun `a rejected override and a missing template and a render failure map to their catalogued codes`() {
        resolverReturns(
            NodeSqlResolution.ParameterRejected(
                draft,
                listOf(NodeSqlResolution.ParameterRejected.RejectedParameter("limit", "Parameter 'limit': expected NUMBER.")),
            ),
        )
        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE

        resolverReturns(NodeSqlResolution.TemplateMissing(draft, "fetch.sql", 1))
        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND

        resolverReturns(NodeSqlResolution.RenderFailed(draft, "undefined variable: nope"))
        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED

        verify(exactly = 0) { runner.select(any(), any(), any(), any()) }
    }

    @Test
    fun `a DML or DDL node on a readonly datasource is refused - a DQL node on it runs`() {
        val readonly = datasource.copy(isReadonly = true)
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns readonly

        resolverReturns(rendered(nodeType = NodeType.DML))
        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.DATASOURCE_READONLY

        resolverReturns(rendered(nodeType = NodeType.DDL))
        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.DATASOURCE_READONLY

        resolverReturns(rendered(nodeType = NodeType.DQL))
        every { runner.select(readonly, any(), any(), any()) } returns
            QueryRows(
                co.datapipelines.datasources.ResultSchema(emptyList(), emptyList()),
                emptyList(),
                truncated = false,
            )
        tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        verify(exactly = 1) { runner.select(readonly, any(), any(), any()) }
    }

    @Test
    fun `an unknown pipeline maps to the execution not-found code`() {
        every { resolver.resolve(McpFixtures.WORKSPACE_ID, pipelineId, "fetch", null, null) } throws
            NoSuchElementException("Pipeline $pipelineId not found")

        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Execution.NOT_FOUND
    }


}

    @Suppress("UNCHECKED_CAST")
    private fun payloadOf(call: Any?): Map<String, Any?> = call as Map<String, Any?>
