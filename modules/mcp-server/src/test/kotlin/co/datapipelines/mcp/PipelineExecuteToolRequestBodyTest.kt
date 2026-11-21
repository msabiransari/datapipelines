package co.datapipelines.mcp

import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineExecutionSnapshot
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * `pipelines_execute`'s `request_body` argument (#1038; mcp-server §6.2.3): decoded by the same bounded decoder
 * framework execute and the published codec use, handed to the executor beside — never inside — `parameters`, and
 * refused with the execution code, a reason and no value before anything runs.
 */
class PipelineExecuteToolRequestBodyTest {
    private val pipelines = mockk<PipelineRepository>()
    private val executor = mockk<PipelineExecutor>()
    private val tool =
        PipelineExecuteTool(
            McpFixtures.pipelineService(pipelines),
            executor,
            mockk(relaxed = true),
            mockk<ResultStore>(),
            ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" },
            bodyDecoder = StructuredBodyDecoder(StructuredBodyLimits(maxBytes = BOUND.toLong(), maxDepth = 4)),
        )
    private val ctx = McpFixtures.ctx()

    private fun storedPipeline() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findExecutionSnapshot(any(), McpFixtures.PIPELINE_ID, 1) } returns
            PipelineExecutionSnapshot(McpFixtures.pipelineBody(), "snapshot-hash", PipelineVersionStatus.RELEASED, false)
    }

    private fun args(vararg extra: Pair<String, Any?>) =
        McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "parameters" to mapOf("month" to "2026-07")) + extra)

    private fun executed(arguments: McpArguments): ExecuteRequest {
        storedPipeline()
        val request = slot<ExecuteRequest>()
        coEvery { executor.execute(capture(request)) } returns
            ExecutionResult(
                executionId = McpFixtures.EXECUTION_ID,
                status = ExecutionStatus.SUCCESS,
                nodeStats = emptyList(),
                resultRef = null,
                startedAt = Instant.EPOCH,
                completedAt = Instant.EPOCH,
                durationMs = 0,
            )
        tool.call(arguments, ctx)
        return request.captured
    }

    @Test
    fun `the argument reaches the executor as the decoded body, beside the parameters`() {
        val request =
            executed(
                args(
                    "request_body" to
                        listOf(mapOf("id" to "9007199254740993", "qty" to 2, "price" to 1.5), mapOf("note" to emptyMap<String, Any>())),
                ),
            )
        val items = request.requestBody.shouldBeInstanceOf<StructuredBody.ArrayRoot>().items
        items[0] shouldBe mapOf("id" to "9007199254740993", "qty" to 2L, "price" to BigDecimal("1.5"))
        items[1] shouldBe mapOf("note" to emptyMap<String, Any?>())
        request.parameters.keys shouldBe setOf("month")
    }

    @Test
    fun `no argument is absent, and an empty array stays an empty array`() {
        executed(args()).requestBody shouldBe StructuredBody.Absent
        executed(args("request_body" to emptyList<Any>())).requestBody shouldBe StructuredBody.ArrayRoot(emptyList())
    }

    @Test
    fun `the byte bound counts the body's canonical bytes, the budget framework execute applies, not the re-serialized spelling`() {
        // {"n":1,"p":"x…"} canonically: 14 bytes around the padding; the argument as sent spells n with 120 more zeros.
        fun padded(pad: Int) = mapOf("n" to BigDecimal("1." + "0".repeat(TRAILING_ZEROS)), "p" to "x".repeat(pad))
        val atBound = executed(args("request_body" to padded(BOUND - CANONICAL_OVERHEAD))).requestBody
        atBound
            .shouldBeInstanceOf<StructuredBody.ObjectRoot>()
            .canonical()!!
            .toByteArray()
            .size shouldBe BOUND
        val refused =
            shouldThrow<DatapipelinesException> { tool.call(args("request_body" to padded(BOUND - CANONICAL_OVERHEAD + 1)), ctx) }
        refused.details["reason"] shouldBe "too_many_bytes"
    }

    @Test
    fun `a null, scalar, mixed or over-bound argument is refused before the executor runs, naming no value`() {
        storedPipeline()
        val cases =
            listOf(
                args("request_body" to null) to "invalid_root",
                args("request_body" to "secret-text") to "invalid_root",
                args("request_body" to listOf(mapOf("a" to 1), "secret-text")) to "invalid_root",
                args("request_body" to mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to 1)))))) to "too_deep",
                args("request_body" to mapOf("note" to "secret-" + "x".repeat(300))) to "too_many_bytes",
            )
        cases.forEach { (arguments, reason) ->
            withClue(reason) {
                val refused = shouldThrow<DatapipelinesException> { tool.call(arguments, ctx) }
                refused.code shouldBe PipelineErrorCodes.Execution.REQUEST_BODY_INVALID
                refused.details["reason"] shouldBe reason
                refused.message.orEmpty() shouldNotContain "secret"
                refused.details.toString() shouldNotContain "secret"
            }
        }
        coVerify(exactly = 0) { executor.execute(any()) }
    }

    private companion object {
        const val BOUND = 256
        const val CANONICAL_OVERHEAD = 14
        const val TRAILING_ZEROS = 120
    }
}
