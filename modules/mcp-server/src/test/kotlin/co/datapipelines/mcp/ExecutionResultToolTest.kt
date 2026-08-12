package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant

/** §6.2.15: identical semantics to the REST cursor, plus the 1 MB inline cap. */
class ExecutionResultToolTest {
    private val executions = mockk<ExecutionRepository>()
    private val resultStore = mockk<ResultStore>()
    private val resultUrls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" }
    private val ctx = McpFixtures.ctx(Scope.READ)

    private val tool = ExecutionsGetResultTool(executions, resultStore, resultUrls, ResultConfig())

    init {
        // P7: the tool resolves the Redis key through dag's public `ResultStore.keyFor`;
        // the mock reproduces RedisResultStore's `dp:result:{id}` layout so the `page`
        // stubs below keep matching the same keys the production store would serve.
        every { resultStore.keyFor(any()) } answers { "dp:result:${firstArg<java.util.UUID>()}" }
    }

    private fun page(rows: List<List<Any?>>) =
        ResultPage(
            executionId = McpFixtures.EXECUTION_ID,
            schema = listOf(ColumnSchema(name = "total", type = LogicalType.STRING)),
            rows = rows,
            offset = 0,
            limit = 1000,
            totalRows = rows.size.toLong() + 1,
            expiresAt = Instant.parse("2026-08-09T12:05:00Z"),
        )

    private fun args(vararg pairs: Pair<String, Any?>) = McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString()) + pairs)

    @Test
    fun `a json page carries the REST cursor body`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        every { resultStore.page("dp:result:${McpFixtures.EXECUTION_ID}", 0, 1000) } returns page(listOf(listOf("1")))

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args(), ctx) as Map<String, Any?>

        assertAll(
            { payload["execution_id"] shouldBe McpFixtures.EXECUTION_ID.toString() },
            { payload["row_count"] shouldBe 1 },
            { payload["offset"] shouldBe 0L },
            { payload["limit"] shouldBe 1000 },
            { payload["total_rows"] shouldBe 2L },
            { payload["has_more"] shouldBe true },
            { payload["expires_at"] shouldBe Instant.parse("2026-08-09T12:05:00Z") },
        )
    }

    @Test
    fun `offset and limit map one-to-one onto the cursor's parameters`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        val offset = slot<Long>()
        val limit = slot<Int>()
        every { resultStore.page(any(), capture(offset), capture(limit)) } returns page(emptyList())

        tool.call(args("offset" to 500, "limit" to 250), ctx)

        assertAll(
            { offset.captured shouldBe 500L },
            { limit.captured shouldBe 250 },
        )
    }

    @Test
    fun `a limit above the server maximum is clamped, not rejected`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        val limit = slot<Int>()
        every { resultStore.page(any(), any(), capture(limit)) } returns page(emptyList())

        tool.call(args("limit" to 10_000_000), ctx)

        limit.captured shouldBe ResultConfig().pageMaxRows
    }

    @Test
    fun `a running execution is incomplete, a failed one has no result`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(status = ExecutionStatus.RUNNING, resultRowCount = null)

        shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }.code shouldBe
            PipelineErrorCodes.Result.EXECUTION_INCOMPLETE

        every { executions.findById(McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(status = ExecutionStatus.FAILED, resultRowCount = null)

        shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }.code shouldBe
            PipelineErrorCodes.Result.EXECUTION_FAILED
    }

    @Test
    fun `an expired result is unrecoverable and says so`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        every { resultStore.page(any(), any(), any()) } returns null

        shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }.code shouldBe PipelineErrorCodes.Result.EXPIRED
    }

    @Test
    fun `an unknown format is a tool error, not a protocol error`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()

        shouldThrow<DatapipelinesException> { tool.call(args("format" to "parquet"), ctx) }.code shouldBe
            PipelineErrorCodes.Result.FORMAT_UNSUPPORTED
    }

    @Test
    fun `another user's result is invisible`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(triggeredBy = McpFixtures.OTHER_USER)

        shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }.code shouldBe
            PipelineErrorCodes.Result.EXECUTION_NOT_FOUND
    }

    @Test
    fun `arrow and csv are never inlined - the cursor URL is returned instead`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        every { resultStore.page(any(), any(), any()) } returns page(listOf(listOf("1")))

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args("format" to "csv"), ctx) as Map<String, Any?>

        assertAll(
            { payload["result_url"] shouldBe "https://dp.test/api/v1/executions/${McpFixtures.EXECUTION_ID}/result" },
            { payload["format"] shouldBe "csv" },
            { payload["reason"] shouldBe "payload_exceeds_inline_cap" },
            { payload.containsKey("rows") shouldBe false },
        )
    }

    @Test
    fun `a json payload over 1 MB is replaced by the cursor URL`() {
        val wide = List(600) { listOf("x".repeat(2_000)) }
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        every { resultStore.page(any(), any(), any()) } returns page(wide)

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args(), ctx) as Map<String, Any?>

        assertAll(
            { payload["reason"] shouldBe "payload_exceeds_inline_cap" },
            { payload.containsKey("rows") shouldBe false },
        )
    }

    /**
     * B3: a `csv`/`arrow` request never inlines anything, so pulling the page first meant reading
     * up to 100 MB out of Redis to discard it. Only the one-row probe may be read.
     */
    @Test
    fun `a non-json format never materializes the page`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord(resultRowCount = 5_000_000)
        val limits = mutableListOf<Int>()
        every { resultStore.page(any(), any(), capture(limits)) } returns page(listOf(listOf("1")))

        tool.call(args("format" to "csv"), ctx)

        assertAll(
            { limits shouldContainExactly listOf(1) },
            { limits.none { it > 1 } shouldBe true },
        )
    }

    /** B3: the stored `result_size_bytes` already proves this page cannot be inlined. */
    @Test
    fun `a json page the stored metadata says is over the cap is not materialized either`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(resultRowCount = 100_000).copy(resultSizeBytes = 500L * 1024 * 1024)
        val limits = mutableListOf<Int>()
        every { resultStore.page(any(), any(), capture(limits)) } returns page(listOf(listOf("1")))

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args(), ctx) as Map<String, Any?>

        assertAll(
            { limits shouldContainExactly listOf(1) },
            { payload["reason"] shouldBe "payload_exceeds_inline_cap" },
            { payload["total_rows"] shouldBe 2L },
        )
    }

    @Test
    fun `a small json page is still fetched and inlined`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        val limits = mutableListOf<Int>()
        every { resultStore.page(any(), any(), capture(limits)) } returns page(listOf(listOf("1")))

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args(), ctx) as Map<String, Any?>

        assertAll(
            { limits shouldContainExactly listOf(ResultConfig().pageSizeRows) },
            { payload["row_count"] shouldBe 1 },
        )
    }

    @Test
    fun `an offset past the end is an empty page, not an error`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        every { resultStore.page(any(), 9_000L, any()) } returns
            ResultPage(
                executionId = McpFixtures.EXECUTION_ID,
                schema = listOf(ColumnSchema(name = "total", type = LogicalType.STRING)),
                rows = emptyList(),
                offset = 9_000,
                limit = 1000,
                totalRows = 3,
                expiresAt = Instant.parse("2026-08-09T12:05:00Z"),
            )

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args("offset" to 9_000), ctx) as Map<String, Any?>

        assertAll(
            { payload["row_count"] shouldBe 0 },
            { payload["has_more"] shouldBe false },
            { payload["total_rows"] shouldBe 3L },
        )
    }

    @Test
    fun `a successful pipeline with no caller result returns an empty page, not an error`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord(resultRowCount = null)

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args(), ctx) as Map<String, Any?>

        assertAll(
            { payload["total_rows"] shouldBe 0 },
            { payload["has_more"] shouldBe false },
            { (payload["rows"] as List<*>).size shouldBe 0 },
        )
    }
}
