package co.datapipelines.web.visualizations

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.FixtureEvaluation
import co.datapipelines.visualization.RendererConfigValidators
import co.datapipelines.visualization.TemplateContractFacts
import co.datapipelines.visualization.TemplatePin
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationMechanicalCheck
import co.datapipelines.visualization.VisualizationReader
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The authoring path's fixture evaluation over the REAL evaluator's contract (mocked at the service
 * edge): ROW-mode and multi-input shaping follow the pinned contract's mode, the pinned version travels
 * exactly (a DRAFT pin is admitted — authoring), the evaluator's refusals propagate with their own
 * codes, and a non-table output is refused the transformer's shape code. The published runtime's RELEASED-only rule is the
 * runtime's, not this adapter's.
 */
class WebVisualizationFixtureEvaluatorTest {
    private val workspace: UUID = UUID.randomUUID()
    private val pin = ArtifactRef("finance/transforms/revenue_bars", 2)

    private val evaluate = mockk<TemplateEvaluateService>()
    private val contracts = mockk<TemplateDryRenderer>()

    private fun adapter(mode: TransformContractView.Mode?): WebVisualizationFixtureEvaluator {
        val contract = mode?.let { modeView(it) }
        every { contracts.transformContract(any(), any()) } returns contract
        return WebVisualizationFixtureEvaluator(evaluate, contracts)
    }

    /** A minimal TABLE-shaped contract view carrying just the mode the shaping reads. */
    private fun modeView(mode: TransformContractView.Mode): TransformContractView =
        TransformContractView(
            mode = mode,
            inputs = mapOf("rows" to TransformContractView.Input.Table(emptyList())),
            output = TransformContractView.Output.Table(emptyList()),
            rejects = false,
        )

    private fun fixtures(row: Map<String, Any?>) = mapOf("revenue" to listOf(rowNode(row)))

    private fun rowNode(row: Map<String, Any?>): ObjectNode = ObjectMapper().valueToTree(row)

    @Test
    fun `a ROW-mode transform receives the single table as rows - the contract's mode shapes the input`() {
        val input = slot<TransformTestInput>()
        every { evaluate.evaluate(any(), any(), any(), capture(input)) } returns
            TemplateEvaluateService.Evaluation(listOf(mapOf("amounts" to 10.5)), emptyList(), emptyList())
        adapter(TransformContractView.Mode.ROW)
            .evaluate(workspace, pin, fixtures(mapOf("month" to "2026-01-01")))
            .shouldBeInstanceOf<FixtureEvaluation.Rows>()
            .rows shouldBe listOf(mapOf("amounts" to 10.5))
        input.captured.rows shouldBe listOf(mapOf("month" to "2026-01-01"))
        input.captured.inputs shouldBe emptyMap()
    }

    @Test
    fun `a TABLE-mode transform receives each table under its input name - and the pinned version travels`() {
        val input = slot<TransformTestInput>()
        val name = slot<String>()
        val version = slot<Int>()
        every { evaluate.evaluate(any(), capture(name), capture(version), capture(input)) } returns
            TemplateEvaluateService.Evaluation(listOf(mapOf("amounts" to 1)), emptyList(), emptyList())
        adapter(TransformContractView.Mode.TABLE)
            .evaluate(workspace, pin, fixtures(mapOf("amount" to 10.5)))
            .shouldBeInstanceOf<FixtureEvaluation.Rows>()
        name.captured shouldBe pin.name
        version.captured shouldBe pin.version // the EXACT pin, draft or released
        input.captured.inputs shouldBe mapOf("revenue" to listOf(mapOf("amount" to 10.5)))
    }

    @Test
    fun `an evaluator refusal propagates with its own catalogued code`() {
        every { evaluate.evaluate(any(), any(), any(), any()) } throws
            DatapipelinesException("template.type_gate_refused", "row 0 column 'amount' is not a number")
        adapter(TransformContractView.Mode.ROW)
            .evaluate(workspace, pin, fixtures(mapOf("amount" to "ten")))
            .shouldBeInstanceOf<FixtureEvaluation.Refused>()
            .let {
                it.code shouldBe "template.type_gate_refused"
                it.message shouldBe "row 0 column 'amount' is not a number"
            }
    }

    @Test
    fun `a non-table output is refused the transformer's shape code - never bound blind`() {
        every { evaluate.evaluate(any(), any(), any(), any()) } returns
            TemplateEvaluateService.Evaluation("a scalar, not a table", emptyList(), emptyList())
        adapter(TransformContractView.Mode.TABLE)
            .evaluate(workspace, pin, fixtures(mapOf("amount" to 1)))
            .shouldBeInstanceOf<FixtureEvaluation.Refused>()
            .code shouldBe PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH
    }

    @Test
    fun `real adapter keeps explicit nullable null keys through the full mechanical report`() {
        listOf("data[0].y", "data[0].z[0]").forEach { path ->
            val check = mechanicalCheck(nullable = true)
            every { evaluate.evaluate(any(), any(), any(), any()) } returns
                TemplateEvaluateService.Evaluation(listOf(mapOf("amount" to null)), emptyList(), emptyList())
            val report = check.run(workspace, mechanicalBody(path))
            report.ok shouldBe true
            report.failures.shouldBeEmpty()
            report.cases.getValue("rows").let {
                it.ok shouldBe true
                it.rows shouldBe 1
                it.rendered shouldBe "not_available"
            }

            every { evaluate.evaluate(any(), any(), any(), any()) } returns
                TemplateEvaluateService.Evaluation(listOf(emptyMap<String, Any?>()), emptyList(), emptyList())
            val missing = check.run(workspace, mechanicalBody(path))
            missing.ok shouldBe false
            missing.cases.getValue("rows").ok shouldBe false
            missing.failures.single().let {
                it.code shouldBe VisualizationErrorCodes.BINDING_UNBOUND
                it.path shouldBe "tests.cases[0].rows[0].amount"
                it.case shouldBe "rows"
            }
        }
    }

    @Test
    fun `real adapter nonnullable null and evaluator type refusal retain distinct report codes and paths`() {
        val check = mechanicalCheck(nullable = false)
        every { evaluate.evaluate(any(), any(), any(), any()) } returns
            TemplateEvaluateService.Evaluation(listOf(mapOf("amount" to null)), emptyList(), emptyList())
        val invalid = check.run(workspace, mechanicalBody("data[0].y"))
        invalid.ok shouldBe false
        invalid.cases.getValue("rows").ok shouldBe false
        invalid.failures.single().let {
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.path shouldBe "tests.cases[0].rows[0].amount"
        }

        every { evaluate.evaluate(any(), any(), any(), any()) } throws
            DatapipelinesException("template.type_gate_refused", "row 0 column 'amount' is not nullable")
        val refused = check.run(workspace, mechanicalBody("data[0].y"))
        refused.ok shouldBe false
        refused.cases.getValue("rows").ok shouldBe false
        refused.dropped shouldBe 0
        refused.failures.single().let {
            it.step shouldBe "fixtures"
            it.code shouldBe "template.type_gate_refused"
            it.path shouldBe "tests.cases[0]"
            it.case shouldBe "rows"
            it.message shouldBe "row 0 column 'amount' is not nullable"
        }
    }

    private fun mechanicalCheck(nullable: Boolean): VisualizationMechanicalCheck {
        val contract =
            modeView(TransformContractView.Mode.TABLE).copy(
                output = TransformContractView.Output.Table(listOf(TransformContractView.Column("amount", LogicalType.DECIMAL, nullable))),
            )
        every { contracts.transformContract(any(), any()) } returns contract
        return VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.deep(),
            fixtures = WebVisualizationFixtureEvaluator(evaluate, contracts),
            templates = TemplateContractFacts { _, _ -> TemplatePin.Transform(PipelineVersionStatus.RELEASED, contract) },
        )
    }

    private fun mechanicalBody(path: String) =
        VisualizationReader()
            .readOrThrow(
                ObjectMapper().readTree(
                    """
                    {"name":"finance/visualizations/null_contract","display_name":"Nullable output",
                     "renderer":{"kind":"plotly","version":"4"},
                     "inputs":{"revenue":{"columns":[{"name":"amount","type":"DECIMAL","nullable":false}]}},
                     "transform":{"template":{"name":"${pin.name}","version":${pin.version}},"inputs":{"rows":"revenue"}},
                     "config":{"data":[${if (path.endsWith("z[0]")) """{"type":"heatmap","z":[[]]}""" else """{"type":"bar","y":[]}"""}]},
                     "bindings":{"$path":"amount"},
                     "tests":{"cases":[{"name":"rows","fixtures":{"revenue":[{"amount":1}]},"assertions":[{"kind":"rendered"}]}]}}
                    """.trimIndent(),
                ),
            ).body
}
