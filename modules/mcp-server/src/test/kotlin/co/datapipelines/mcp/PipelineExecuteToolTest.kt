package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.PipelineExecutionFailed
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.StoredResultView
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

class PipelineExecuteToolTest {
    private val pipelines = mockk<PipelineRepository>()
    private val executor = mockk<PipelineExecutor>()
    private val resultStore = mockk<ResultStore>()
    private val resultUrls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" }
    private val ctx = McpFixtures.ctx(Scope.EXECUTE)

    private val tool = PipelineExecuteTool(pipelines, executor, resultStore, resultUrls)

    private val args =
        McpArguments(
            mapOf(
                "id" to McpFixtures.PIPELINE_ID.toString(),
                "parameters" to mapOf("month" to "2026-07"),
            ),
        )

    private fun storedPipeline() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()
    }

    private fun result(
        status: ExecutionStatus = ExecutionStatus.SUCCESS,
        resultRef: String? = "dp:result:${McpFixtures.EXECUTION_ID}",
    ) = ExecutionResult(
        executionId = McpFixtures.EXECUTION_ID,
        status = status,
        nodeStats = emptyList(),
        resultRef = resultRef,
        startedAt = Instant.parse("2026-08-09T12:00:00Z"),
        completedAt = Instant.parse("2026-08-09T12:00:03Z"),
        durationMs = 3_000,
    )

    private fun view() =
        StoredResultView(
            key = "dp:result:${McpFixtures.EXECUTION_ID}",
            executionId = McpFixtures.EXECUTION_ID,
            schema = listOf(ColumnSchema(name = "total", type = LogicalType.BIGDECIMAL, scale = 2, nullable = false)),
            firstPage = listOf(listOf("12345.67")),
            totalRows = 42,
            bytes = 1_024,
            expiresAt = Instant.parse("2026-08-09T12:05:00Z"),
        )

    @Test
    fun `the result mirrors data_ready - schema, first page, totals, url and expiry`() {
        storedPipeline()
        coEvery { executor.execute(any()) } returns result()
        every { resultStore.describe("dp:result:${McpFixtures.EXECUTION_ID}") } returns view()

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args, ctx) as Map<String, Any?>

        assertAll(
            { payload["execution_id"] shouldBe McpFixtures.EXECUTION_ID.toString() },
            { payload["status"] shouldBe "SUCCESS" },
            { payload["duration_ms"] shouldBe 3_000L },
            { payload["total_rows"] shouldBe 42L },
            { payload["has_more"] shouldBe true },
            { payload["row_count"] shouldBe 1 },
            { payload["result_url"] shouldBe "https://dp.test/api/v1/executions/${McpFixtures.EXECUTION_ID}/result" },
            { payload["expires_at"] shouldBe Instant.parse("2026-08-09T12:05:00Z") },
        )
    }

    @Test
    fun `the executor is handed the pipeline snapshot, the caller and the correlation id`() {
        storedPipeline()
        val request = slot<ExecuteRequest>()
        coEvery { executor.execute(capture(request)) } returns result(resultRef = null)

        tool.call(args, ctx)

        assertAll(
            { request.captured.pipelineId shouldBe McpFixtures.PIPELINE_ID },
            { request.captured.pipelineVersion shouldBe 1 },
            { request.captured.userId shouldBe McpFixtures.USER },
            { request.captured.correlationId shouldBe McpFixtures.CORRELATION_ID },
            { request.captured.parameters["month"]?.asText() shouldBe "2026-07" },
            {
                request.captured.pipeline.nodes
                    .single()
                    .id shouldBe "fetch"
            },
        )
    }

    @Test
    fun `a pipeline with no caller node returns metadata and no schema - success, not an error`() {
        storedPipeline()
        coEvery { executor.execute(any()) } returns result(resultRef = null)

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args, ctx) as Map<String, Any?>

        assertAll(
            { payload["status"] shouldBe "SUCCESS" },
            { payload.containsKey("schema") shouldBe false },
            { payload.containsKey("rows") shouldBe false },
            { payload.containsKey("node_stats") shouldBe true },
        )
    }

    @Test
    fun `a vanished stored result fails loudly rather than returning an empty page`() {
        storedPipeline()
        coEvery { executor.execute(any()) } returns result()
        every { resultStore.describe(any()) } returns null

        shouldThrow<DatapipelinesException> { tool.call(args, ctx) }.code shouldBe PipelineErrorCodes.Result.EXPIRED
    }

    @Test
    fun `a node failure surfaces with the node's catalogued code`() {
        storedPipeline()
        coEvery { executor.execute(any()) } throws
            PipelineExecutionFailed("fetch", PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED, mapOf("sql_state" to "42P01"))

        val error = shouldThrow<DatapipelinesException> { tool.call(args, ctx) }
        assertAll(
            { error.code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED },
            { error.details["failed_node_id"] shouldBe "fetch" },
        )
    }

    @Test
    fun `a cancelled execution is reported as aborted with its reason`() {
        storedPipeline()
        coEvery { executor.execute(any()) } throws ExecutionAbortedException(AbortReason.CANCELLED)

        val error = shouldThrow<DatapipelinesException> { tool.call(args, ctx) }
        assertAll(
            { error.code shouldBe PipelineErrorCodes.Execution.ABORTED },
            { error.details["reason"] shouldBe "cancelled" },
        )
    }

    @Test
    fun `a version below 1 is refused and the executor is never called`() {
        storedPipeline()

        assertAll(
            {
                shouldThrow<McpError> {
                    tool.call(McpArguments(args.rawMap() + mapOf("version" to 0)), ctx)
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
            {
                shouldThrow<McpError> {
                    tool.call(McpArguments(args.rawMap() + mapOf("version" to -3)), ctx)
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
        )
        // The whole point: `{version: 0}` must NOT silently execute version 1.
        coVerify(exactly = 0) { executor.execute(any()) }
    }

    @Test
    fun `an explicit version executes that version's stored snapshot`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord(version = 7)
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 3) } returns McpFixtures.pipelineBody(name = "v3_snapshot")
        val request = slot<ExecuteRequest>()
        coEvery { executor.execute(capture(request)) } returns result(resultRef = null)

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(McpArguments(args.rawMap() + mapOf("version" to 3)), ctx) as Map<String, Any?>

        assertAll(
            { request.captured.pipelineVersion shouldBe 3 },
            { request.captured.pipeline.name shouldBe "v3_snapshot" },
            { payload["pipeline_version"] shouldBe 3 },
        )
    }

    @Test
    fun `the result carries ttl_seconds so an agent knows its paging window`() {
        storedPipeline()
        coEvery { executor.execute(any()) } returns result()
        every { resultStore.describe(any()) } returns view()

        @Suppress("UNCHECKED_CAST")
        val payload =
            PipelineExecuteTool(pipelines, executor, resultStore, resultUrls, resultConfig = ResultConfig(ttlDefaultSeconds = 900))
                .call(args, ctx) as Map<String, Any?>

        payload["ttl_seconds"] shouldBe 900L
    }

    @Test
    fun `an unknown pipeline never reaches the executor`() {
        every { pipelines.findById(any(), any()) } returns null

        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("id" to UUID.randomUUID().toString(), "parameters" to emptyMap<String, Any?>())), ctx)
        }.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `the request is stamped triggered via MCP`() {
        storedPipeline()
        val request = slot<ExecuteRequest>()
        coEvery { executor.execute(capture(request)) } returns result(resultRef = null)

        tool.call(args, ctx)

        request.captured.triggeredVia shouldBe ExecutionTrigger.MCP
    }

    @Test
    fun `a supplied execution runner takes the call and the shared executor is not used`() {
        // P7: in the assembled application `web` supplies the runner that records the
        // execution (triggered_via = MCP); the NONE-emitter executor bean must stay unused.
        storedPipeline()
        val runner = mockk<McpExecutionRunner>()
        val request = slot<ExecuteRequest>()
        coEvery { runner.run(capture(request), any()) } returns result(resultRef = null)
        val withRunner =
            PipelineExecuteTool(pipelines, executor, resultStore, resultUrls, executionRunner = runner)

        @Suppress("UNCHECKED_CAST")
        val payload = withRunner.call(args, ctx) as Map<String, Any?>

        assertAll(
            { payload["status"] shouldBe "SUCCESS" },
            { request.captured.triggeredVia shouldBe ExecutionTrigger.MCP },
            { request.captured.pipelineId shouldBe McpFixtures.PIPELINE_ID },
        )
        coVerify(exactly = 0) { executor.execute(any()) }
    }
}
