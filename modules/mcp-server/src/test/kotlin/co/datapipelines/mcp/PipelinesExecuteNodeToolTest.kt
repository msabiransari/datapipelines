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
    private val ctx = McpFixtures.ctx()

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
            secret = null,
        )

    private fun rendered(
        nodeType: NodeType = NodeType.DQL,
        source: String = "sample-trips",
        sql: String = "SELECT * FROM trips WHERE d = :start_date",
        shape: (Node) -> Node = { it },
    ) = NodeSqlResolution.Rendered(
        version = draft,
        node =
            shape(
                Node(
                    id = "fetch",
                    description = "",
                    type = nodeType,
                    source = source,
                    template = TemplateRef("test/fetch.sql", 1),
                    output = null,
                    dependsOn = emptyList(),
                ),
            ),
        templateId = "test/fetch.sql",
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
            resolver.resolve(McpFixtures.WORKSPACE_ID, pipelineId, "fetch", null, null, any(), any())
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
        resolverReturns(rendered(nodeType = NodeType.DML, sql = "DELETE FROM trips WHERE d = :start_date", shape = DELETE_SHAPE))
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns datasource
        every { runner.executeUpdate(datasource, "SELECT * FROM trips WHERE d = ?", any()) } returns 3L

        val payload =
            payloadOf(tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx))

        payload["affected_rows"] shouldBe 3L
        payload["node_type"] shouldBe "DML"
    }

    @Test
    fun `a DML insert reading a staged table is the standalone refusal, before the datasource and any SQL (#1034)`() {
        resolverReturns(rendered(nodeType = NodeType.DML, sql = "INSERT INTO trips (city) VALUES (:city)", shape = STAGED_INSERT))

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
            }

        assertAll(
            { thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED },
            { thrown.details["reason"] shouldBe "staged_input" },
            { (thrown.message ?: "") shouldContain "pipelines_execute" },
        )
        verify(exactly = 0) { runner.executeUpdate(any(), any(), any()) }
        verify(exactly = 0) { datasources.getVisible(any(), any()) }
    }

    /**
     * #1034 "Roles": the single-node tool's ORIGINAL role gate (`pipeline.execute_node` — author,
     * workspace admin, super admin), exercised through the real dispatcher on a node of the NEW
     * schema — a scalar-input DML insert. A viewer's or promoter's key is refused before the tool
     * runs (no datasource read, no statement); an author's key runs it and keeps the debugger's
     * single-node contract: the statement executes once and reports its affected rows.
     */
    @Test
    fun `the role gate admits an author and refuses a viewer and a promoter for a new-schema DML insert`() {
        resolverReturns(rendered(nodeType = NodeType.DML, sql = "INSERT INTO trips (city) VALUES (:start_date)", shape = SCALAR_INSERT))
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns datasource
        every { runner.executeUpdate(datasource, "SELECT * FROM trips WHERE d = ?", any()) } returns 1L
        val dispatcher = McpToolDispatcher(listOf(tool), mockk<co.datapipelines.auth.AuditEventSink>(relaxed = true))
        val request =
            McpFixtures.request("pipelines_execute_node", mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch"))

        fun callAs(role: co.datapipelines.auth.WorkspaceRole) =
            dispatcher.call(
                request,
                McpFixtures.ctx(workspace = co.datapipelines.auth.WorkspaceContext(McpFixtures.WORKSPACE_ID, "acme", role)),
            )

        listOf(co.datapipelines.auth.WorkspaceRole.VIEWER, co.datapipelines.auth.WorkspaceRole.PROMOTER).forEach { role ->
            val refused = callAs(role)
            val error = McpFixtures.payloadOf(refused)["error"]
            assertAll(
                { refused.isError() shouldBe true },
                { error["code"].asText() shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Auth.KEY_ISSUER_ROLE_LOST },
                { error["details"]["required"].asText() shouldBe "pipeline.execute_node" },
            )
        }
        verify(exactly = 0) { datasources.getVisible(any(), any()) }
        verify(exactly = 0) { runner.executeUpdate(any(), any(), any()) }

        val admitted = callAs(co.datapipelines.auth.WorkspaceRole.AUTHOR)

        admitted.isError() shouldBe false
        McpFixtures.payloadOf(admitted)["affected_rows"].asLong() shouldBe 1L
        verify(exactly = 1) { runner.executeUpdate(datasource, "SELECT * FROM trips WHERE d = ?", any()) }
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
    fun `a SCRIPT node is the standalone refusal pointing at templates_evaluate`() {
        // 7c (#7): a SCRIPT is a script over staged data, not SQL — the tool's one refusal
        // code, the reason named, and `use` naming the verb that DOES evaluate it.
        resolverReturns(rendered(nodeType = NodeType.SCRIPT, source = ""))

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
            }

        assertAll(
            { thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED },
            { thrown.details["reason"] shouldBe "script_node" },
            { thrown.details["use"] shouldBe "templates_evaluate" },
        )
        verify(exactly = 0) { runner.select(any(), any(), any(), any()) }
        verify(exactly = 0) { datasources.getVisible(any(), any()) }
    }

    @Test
    fun `a script template's no-dialect resolver refusal maps to the same standalone refusal`() {
        // The REAL resolution path: a script template declares no dialect, so the resolver
        // refuses its render with this exact sentence before the tool's `when` can fire. The
        // sentence is the pinned contract of that branch — if the resolver rewords it, this
        // test and the refusal need rewording together.
        resolverReturns(
            NodeSqlResolution.RenderFailed(
                draft,
                "Template 'test/shape.jsonata@3' has type 'jsonata' and no dialect; " +
                    "only type 'sql' templates can render node SQL.",
            ),
        )

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
            }

        assertAll(
            { thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED },
            { thrown.details["reason"] shouldBe "script_node" },
            { thrown.details["use"] shouldBe "templates_evaluate" },
        )
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

        resolverReturns(NodeSqlResolution.TemplateMissing(draft, "test/fetch.sql", 1))
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
        every { resolver.resolve(McpFixtures.WORKSPACE_ID, pipelineId, "fetch", null, null, any(), any()) } throws
            NoSuchElementException("Pipeline $pipelineId not found")

        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("pipeline_id" to pipelineId.toString(), "node_id" to "fetch")), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Execution.NOT_FOUND
    }
}

@Suppress("UNCHECKED_CAST")
private fun payloadOf(call: Any?): Map<String, Any?> = call as Map<String, Any?>

/** The #1034 DML node shapes the tool sees: a delete, a scalar-input insert and a staged-input insert. */
private val DELETE_SHAPE: (Node) -> Node = { it.copy(operation = "delete", output = co.datapipelines.pipeline.NodeOutput.Discard) }

private val SCALAR_INSERT: (Node) -> Node = {
    it.copy(
        operation = "insert",
        key = co.datapipelines.pipeline.DmlKey("trip_id", "BIGINTEGER"),
        input = co.datapipelines.pipeline.DmlInput(parameters = listOf("start_date")),
        output = co.datapipelines.pipeline.NodeOutput.Caller,
    )
}

private val STAGED_INSERT: (Node) -> Node = {
    SCALAR_INSERT(it).copy(input = co.datapipelines.pipeline.DmlInput(table = "stg_trips"))
}
