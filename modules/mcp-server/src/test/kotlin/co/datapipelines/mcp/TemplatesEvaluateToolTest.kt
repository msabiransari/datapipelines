package co.datapipelines.mcp

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInput
import co.datapipelines.templates.TransformMode
import co.datapipelines.templates.TransformOutput
import co.datapipelines.templates.TransformTestRunner
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private class EvaluateRecordingAuditSink : co.datapipelines.auth.AuditEventSink {
    data class Row(
        val event: String,
        val details: Map<String, Any?>,
    )

    val rows = mutableListOf<Row>()

    override fun log(
        event: String,
        userId: java.util.UUID?,
        keyId: String?,
        sourceIp: String?,
        userAgent: String?,
        details: Map<String, Any?>,
    ) {
        rows += Row(event, details)
    }

    fun calls() = rows.filter { it.event == "mcp.tool.called" }
}

/**
 * `templates_evaluate` through the real dispatcher with a key principal (record §9.1 / §9.5):
 * the tool resolves the working version over the mocked repository, evaluates through the
 * real runner/engine/pool, and the `mcp.tool.called` row carries `tool`, `template`,
 * `version` and `outcome` — never the input object or the output.
 */
class TemplatesEvaluateToolTest {
    @Test
    fun `caller shape failures use invalid params with safe prose and never evaluate`() {
        val service = mockk<TemplateEvaluateService>()
        val sink = EvaluateRecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(TemplatesEvaluateTool(service)), sink)
        val longKey = "bad\n\t" + "x".repeat(200)
        val inputs = listOf(mapOf("now" to 987654321), mapOf("rows" to true), mapOf(longKey to "sentinel987654321"))
        inputs.forEachIndexed { index, input ->
            val wrapper =
                io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                    co.datapipelines.templates.TransformBlocks.mapper.convertValue(
                        input,
                        co.datapipelines.templates.TransformTestInput::class.java,
                    )
                }
            println("381 mapper wrapper=${wrapper.javaClass.name} cause=${wrapper.cause?.javaClass?.name}")
            (wrapper.cause is com.fasterxml.jackson.databind.JsonMappingException) shouldBe true
            val error =
                io.kotest.assertions.throwables
                    .shouldThrow<io.modelcontextprotocol.spec.McpError> {
                        dispatcher.call(
                            McpFixtures.request("templates_evaluate", mapOf("id" to "test/xform.jsonata", "input" to input)),
                            McpFixtures.ctx(),
                        )
                    }.jsonRpcError
            println("381 public code=${error.code()} message=${error.message()}")
            error.code() shouldBe McpArguments.INVALID_PARAMS
            error.data() shouldBe null
            error.message().contains("987654321") shouldBe false
            error.message().any { it.isISOControl() } shouldBe false
            error.message().contains(longKey) shouldBe false
            when (index) {
                0 -> {
                    error.message().contains("'now' must be a string") shouldBe true
                }

                1 -> {
                    error.message().contains("'rows' must be an array") shouldBe true
                }

                else -> {
                    val reflected = error.message().substringAfter(": '").substringBefore("' is not")
                    reflected.length shouldBe 161
                    println("381 evaluate reflected key length=${reflected.length}, controls=${reflected.any { it.isISOControl() }}")
                }
            }
        }
        io.mockk.verify(exactly = 0) { service.evaluate(any(), any(), any(), any(), any()) }
        sink.calls().map { it.details["outcome"] } shouldBe List(3) { "invalid_params" }
    }

    @Test
    fun `optional input members stay absent and unrelated service faults stay internal errors`() {
        val service = mockk<TemplateEvaluateService>()
        val input = co.datapipelines.templates.TransformTestInput(rows = emptyList())
        every { service.evaluate(any(), any(), any(), input, null) } returns
            TemplateEvaluateService.Evaluation(output = emptyMap<String, Any>(), rejects = emptyList(), invariants = emptyList())
        val sink = EvaluateRecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(TemplatesEvaluateTool(service)), sink)
        val request =
            McpFixtures.request(
                "templates_evaluate",
                mapOf(
                    "id" to "test/xform.jsonata",
                    "input" to mapOf("rows" to emptyList<Any>()),
                ),
            )
        dispatcher.call(request, McpFixtures.ctx()).isError() shouldBe false
        every { service.evaluate(any(), any(), any(), input, null) } throws IllegalArgumentException("sentinel987654321")
        val error =
            io.kotest.assertions.throwables
                .shouldThrow<io.modelcontextprotocol.spec.McpError> {
                    dispatcher.call(request, McpFixtures.ctx())
                }.jsonRpcError
        error.code() shouldBe McpArguments.INTERNAL_ERROR
        error.message().contains("987654321") shouldBe false
        io.mockk.verify(exactly = 2) { service.evaluate(any(), any(), any(), input, null) }
        sink.calls().map { it.details["outcome"] } shouldBe listOf("success", "internal_error")
    }

    private val templates = mockk<TemplateRepository>()
    private val contract =
        TransformContract(
            mode = TransformMode.ROW,
            inputs =
                mapOf(
                    "orders" to TransformInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
                ),
            output = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
        )

    private val transformTemplate =
        Template(
            id = "test/xform.jsonata",
            version = 1,
            engine = Template.NONE_ENGINE,
            type = TemplateType.JSONATA,
            dialect = null,
            displayName = "X",
            description = "d",
            body = "rows",
            createdAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
            contract = contract,
            invariants = emptyList(),
            tests = emptyList(),
        )

    private fun tool(): TemplatesEvaluateTool {
        val service = mockk<TemplateEvaluateService>()
        every { service.evaluate(any(), any(), any(), any(), any()) } returns
            TemplateEvaluateService.Evaluation(
                output = mapOf("rows" to listOf(mapOf("order_id" to 7))),
                rejects = emptyList(),
                invariants = emptyList(),
            )
        return TemplatesEvaluateTool(service)
    }

    @Test
    fun `the tool through the real dispatcher evaluates and audits tool, template, version, outcome`() {
        every { templates.findWorking(any(), "test/xform.jsonata") } returns transformTemplate
        val sink = EvaluateRecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(tool()), sink)
        val ctx = McpFixtures.ctx()

        // The direct return maps the service result verbatim; the dispatcher ride proves the
        // wiring and writes the audit row.
        val direct =
            tool().call(
                McpArguments(
                    mapOf(
                        "id" to "test/xform.jsonata",
                        "input" to mapOf("rows" to listOf(mapOf("order_id" to 7)), "inputs" to emptyMap<String, Any>()),
                    ),
                ),
                ctx,
            )
        dispatcher.call(
            McpFixtures.request(
                "templates_evaluate",
                mapOf(
                    "id" to "test/xform.jsonata",
                    "input" to mapOf("rows" to listOf(mapOf("order_id" to 7)), "inputs" to emptyMap<String, Any>()),
                ),
            ),
            ctx,
        )

        assertSoftly {
            (direct as Map<*, *>)["output"] shouldBe mapOf("rows" to listOf(mapOf("order_id" to 7)))
            val row = sink.calls().single { it.details["tool"] == "templates_evaluate" }
            row.details["template"] shouldBe "test/xform.jsonata"
            row.details["outcome"] shouldBe "success"
            row.details.toString().contains("order_id") shouldBe false
        }
    }

    @Test
    fun `templates_render on a transform type is render_not_applicable pointing at evaluate`() {
        every { templates.findWorking(any(), "test/xform.jsonata") } returns transformTemplate
        every { templates.lookupVersion(any(), "test/xform.jsonata", 1) } returns
            mockk<co.datapipelines.templates.TemplateVersion> {
                every { type } returns TemplateType.JSONATA
            }
        val render = TemplatesRenderTool(templates, mockk())
        val thrown =
            io.kotest.assertions.throwables.shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                render.call(
                    McpArguments(mapOf("id" to "test/xform.jsonata", "version" to 1, "context" to emptyMap<String, Any>())),
                    McpFixtures.ctx(),
                )
            }
        thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Template.RENDER_NOT_APPLICABLE
        thrown.details["use"] shouldBe "templates_evaluate"
    }
}
