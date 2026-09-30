package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.TransformOutcome
import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ArtifactRef
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [WebDashboardTransformer] over [TemplateEvaluateService]: the input is shaped by the contract's mode — a ROW transform
 * receives the single table as `rows`, every other mode each table under `inputs[name]` — the output must be a TABLE, and
 * a refusal is an outcome carrying the evaluation's own code. The evaluation is a stub that RECORDS the input it was
 * handed (an effect assertion, not a call count).
 */
class WebDashboardTransformerTest {
    private val workspace = UUID.randomUUID()
    private val template = ArtifactRef("dbr/templates/t", 2)
    private val now = Instant.parse("2026-09-29T10:00:00Z")
    private val evaluate = mockk<TemplateEvaluateService>()
    private val renderer = mockk<TemplateDryRenderer>()
    private val templateService = mockk<TemplateService>()
    private val transformer = WebDashboardTransformer(evaluate, renderer, templateService)
    private val tables = mapOf("orders" to listOf(mapOf<String, Any?>("x" to 1)), "rates" to listOf(mapOf<String, Any?>("r" to 2)))

    private fun contract(mode: TransformContractView.Mode) =
        TransformContractView(
            mode,
            mapOf("orders" to TransformContractView.Input.Table(listOf(TransformContractView.Column("x", LogicalType.INTEGER, false)))),
            TransformContractView.Output.Table(listOf(TransformContractView.Column("x", LogicalType.INTEGER, false))),
            rejects = false,
        )

    private fun evaluated(output: Any?) = TemplateEvaluateService.Evaluation(output, emptyList(), emptyList())

    private fun released() {
        every { templateService.findVersionStatus(workspace, ReadLens.Everything, "dbr/templates/t", 2) } returns
            PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a table-mode transform receives every table under inputs and answers its rows`() {
        released()
        every { renderer.transformContract(workspace, TemplateRef("dbr/templates/t", 2)) } returns
            contract(TransformContractView.Mode.TABLE)
        val seen = slot<TransformTestInput>()
        every { evaluate.evaluate(workspace, "dbr/templates/t", 2, capture(seen), now) } returns evaluated(listOf(mapOf("x" to 9)))

        val outcome = transformer.transform(workspace, template, tables, now)

        outcome shouldBe TransformOutcome.Rows(listOf(mapOf("x" to 9)))
        seen.captured.inputs shouldBe tables
        seen.captured.rows shouldBe null
    }

    @Test
    fun `a row-mode transform receives the one table as rows and no inputs`() {
        released()
        every { renderer.transformContract(workspace, TemplateRef("dbr/templates/t", 2)) } returns contract(TransformContractView.Mode.ROW)
        val seen = slot<TransformTestInput>()
        every { evaluate.evaluate(workspace, "dbr/templates/t", 2, capture(seen), now) } returns evaluated(emptyList<Map<String, Any?>>())

        transformer.transform(workspace, template, mapOf("orders" to tables.getValue("orders")), now)

        seen.captured.rows shouldBe tables.getValue("orders")
        seen.captured.inputs shouldBe emptyMap()
    }

    @Test
    fun `an output that is not a table is refused with the row-shape code, never bound blind`() {
        released()
        every { renderer.transformContract(workspace, TemplateRef("dbr/templates/t", 2)) } returns
            contract(TransformContractView.Mode.TABLE)
        every { evaluate.evaluate(workspace, "dbr/templates/t", 2, any(), now) } returns evaluated(mapOf("scalar" to 1))

        transformer.transform(workspace, template, tables, now) shouldBe
            TransformOutcome.Refused(PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH)
    }

    @Test
    fun `the evaluation's own refusal is carried by code - the message stays behind`() {
        released()
        every { renderer.transformContract(workspace, TemplateRef("dbr/templates/t", 2)) } returns null
        every { evaluate.evaluate(workspace, "dbr/templates/t", 2, any(), now) } throws
            DatapipelinesException(PipelineErrorCodes.Transform.EVALUATION_FAILED, "a row said SECRET")

        transformer.transform(workspace, template, tables, now) shouldBe
            TransformOutcome.Refused(PipelineErrorCodes.Transform.EVALUATION_FAILED)
    }

    @Test
    fun `a transform that is draft discarded or missing at evaluation is refused before execution`() {
        every { renderer.transformContract(workspace, TemplateRef("dbr/templates/t", 2)) } returns null
        every { evaluate.evaluate(workspace, "dbr/templates/t", 2, any(), now) } returns evaluated(emptyList<Map<String, Any?>>())
        listOf(PipelineVersionStatus.DRAFT, PipelineVersionStatus.DISCARDED, null).forEach { status ->
            every { templateService.findVersionStatus(workspace, ReadLens.Everything, "dbr/templates/t", 2) } returns status
            transformer.transform(workspace, template, tables, now) shouldBe
                TransformOutcome.Refused(co.datapipelines.visualization.DashboardErrorCodes.RUNTIME_DEPENDENCY_MISSING)
        }
        io.mockk.verify(exactly = 0) { evaluate.evaluate(any(), any(), any(), any(), any()) }
    }
}
