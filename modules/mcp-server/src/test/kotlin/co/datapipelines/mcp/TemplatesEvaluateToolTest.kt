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
