package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.typesystem.JsonWireForm
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Complete mechanical reports pin renderer typing and the transform output's presence/nullability contract — a table's
 * and KPI's path bindings, and a chart's field-keyed measure. This module runs the check without the JavaScript runtime
 * (js-rendered record §6): a chart case's rows are checked here, its chart assertions are listed as unjudged, and what
 * the engine draws from those rows is application's `ChartJudgeEngineTest`.
 */
class VisualizationBindingContractTest {
    private val workspace = UUID.randomUUID()

    @Test
    fun `table STRING values pass while a chart's STRING measure is left to the engine`() {
        val table = run(RendererKind.TABLE, "columns[0].values", LogicalType.STRING, row = mapOf("amount" to "Jan"))
        assertPassing(table)
        // The save rules refuse a STRING measure (chart.measure.not_numeric); rows that reached the check anyway are
        // valid in their wire form, and what the engine makes of them is its own state (unjudged without the runtime).
        val chart = run(RendererKind.CHART, "amount", LogicalType.STRING, row = mapOf("amount" to "Jan"))
        assertPassing(chart)
        chart.unjudged.map { it.kind } shouldBe listOf("rendered")
    }

    @Test
    fun `table columns accept every canonical scalar wire type`() {
        LogicalType.entries.forEach { type ->
            withClue(type) {
                val value =
                    when (type.wireForm) {
                        JsonWireForm.NULL -> null
                        JsonWireForm.BOOLEAN -> true
                        JsonWireForm.NUMBER -> 1
                        JsonWireForm.STRING -> "label"
                    }
                assertPassing(run(RendererKind.TABLE, "columns[0].values", type, nullable = true, row = mapOf("amount" to value)))
            }
        }
    }

    @Test
    fun `a chart measure draws INTEGER and DECIMAL as numbers and BIG types as exact strings - KPI keeps BIG wire strings`() {
        listOf(LogicalType.INTEGER, LogicalType.DECIMAL).forEach { type ->
            withClue(type) { assertPassing(run(RendererKind.CHART, "amount", type, row = mapOf("amount" to 1), marks = 1)) }
        }
        listOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL).forEach { type ->
            withClue(type) { assertPassing(run(RendererKind.CHART, "amount", type, row = mapOf("amount" to "10"), marks = 1)) }
        }
        // A boolean measure's rows are valid in their wire form: the engine's state judges them (unjudged here).
        assertPassing(run(RendererKind.CHART, "amount", LogicalType.BOOLEAN, row = mapOf("amount" to true)))
        listOf("value", "comparison.value").forEach { path ->
            listOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL).forEach { type ->
                assertPassing(run(RendererKind.KPI, path, type, row = mapOf("amount" to "10.50")))
            }
        }
    }

    @Test
    fun `nullable transform output null passes at a chart measure while absent keys fail precisely`() {
        assertPassing(run(RendererKind.CHART, "amount", LogicalType.DECIMAL, nullable = true, row = mapOf("amount" to null), marks = 0))
        val missing = run(RendererKind.CHART, "amount", LogicalType.DECIMAL, nullable = true, row = emptyMap())
        assertFailure(missing, "fixtures", VisualizationErrorCodes.BINDING_UNBOUND, "tests.cases[0].rows[0].amount")
        missing.failures.single().message shouldBe "The bound column is missing from the row that amount consumes."
        missing.cases.getValue("rows").ok shouldBe false
    }

    @Test
    fun `nonnullable transform output null is an invalid fixture rather than a missing binding`() {
        val report = run(RendererKind.CHART, "amount", LogicalType.DECIMAL, row = mapOf("amount" to null))
        assertFailure(report, "fixtures", VisualizationErrorCodes.TEST_CASE_INVALID, "tests.cases[0].rows[0].amount")
        report.failures.single().message shouldBe "The column is not nullable."
        report.cases.getValue("rows").ok shouldBe false
    }

    @Test
    fun `nullable null never supplies the literal null for text assertions`() {
        val table =
            run(RendererKind.TABLE, "columns[0].values", LogicalType.DECIMAL, nullable = true, row = mapOf("amount" to null), text = "null")
        assertFailure(table, "assertions", VisualizationErrorCodes.TEST_CASE_INVALID, "tests.cases[0].assertions[1]")
        table.cases.getValue("rows").ok shouldBe true
        // On a chart the text is the engine's to judge (application's ChartJudgeEngineTest): unjudged without the runtime.
        val chart = run(RendererKind.CHART, "amount", LogicalType.DECIMAL, nullable = true, row = mapOf("amount" to null), text = "null")
        assertPassing(chart)
        chart.unjudged.map { it.path } shouldBe listOf("tests.cases[0].assertions[0]", "tests.cases[0].assertions[1]")
    }

    @Test
    fun `nonnullable null failures remain bounded and counted`() {
        val report = run(RendererKind.CHART, "amount", LogicalType.DECIMAL, row = mapOf("amount" to null), count = 150)
        report.ok shouldBe false
        report.failures.size shouldBe VisualizationMechanicalCheck.MAX_FAILURES
        report.dropped shouldBe 50
        report.failures.all { it.code == VisualizationErrorCodes.TEST_CASE_INVALID } shouldBe true
        report.cases.getValue("rows").ok shouldBe false
    }

    private fun run(
        renderer: RendererKind,
        path: String,
        type: LogicalType,
        nullable: Boolean = false,
        row: Map<String, Any?>,
        text: String? = null,
        count: Int = 1,
        marks: Int? = null,
    ): VisualizationMechanicalCheck.Report {
        val config =
            when (renderer) {
                RendererKind.TABLE -> {
                    """{"columns":[{"label":"Amount","values":[]}]}"""
                }

                RendererKind.KPI -> {
                    """{"value":null,"comparison":{"label":"Previous","value":null}}"""
                }

                RendererKind.CHART -> {
                    """{"mark":{"type":"bar"},"encoding":{"x":{"field":"label","type":"nominal","sort":null},""" +
                        """"y":{"field":"$path","type":"quantitative","aggregate":"sum"}}}"""
                }

                RendererKind.HTML, RendererKind.SVG -> {
                    error("Reserved renderer is not a test specimen.")
                }
            }
        val chart = renderer == RendererKind.CHART
        val assertion =
            text?.let { """,{"kind":"text_visible","text":"$it"}""" }.orEmpty() +
                marks?.let { """,{"kind":"mark_count","equals":$it}""" }.orEmpty()
        // A chart's bindings are its derived field map; its contract carries the band column too.
        val bindings = if (chart) """{"$path":"amount","label":"label"}""" else """{"$path":"amount"}"""
        val body =
            ValidatorFakes
                .visualizationDocument(
                    DocumentFixtures.tree(
                        """
                        {"name":"finance/visualizations/contract","display_name":"Contract",
                         "renderer":{"kind":"${renderer.wire}","version":"1"},
                         "inputs":{"revenue":{"columns":[{"name":"amount","type":"DECIMAL","nullable":false}]}},
                         "transform":{"template":{"name":"finance/scripts/revenue_bars","version":2},"inputs":{"rows":"revenue"}},
                         "config":$config,"bindings":$bindings,
                         "tests":{"cases":[{"name":"rows","fixtures":{"revenue":[{"amount":1}]},
                         "assertions":[{"kind":"rendered"}$assertion]}]}}
                        """.trimIndent(),
                    ),
                ).body
        val contract =
            ValidatorFakes.CONTRACT.copy(
                output =
                    ScriptContractView.Output.Table(
                        listOfNotNull(
                            ScriptContractView.Column("label", LogicalType.STRING, false).takeIf { chart },
                            ScriptContractView.Column("amount", type, nullable),
                        ),
                    ),
            )
        val rows = List(count) { if (chart) mapOf("label" to "Jan") + row else row }
        return VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.default(),
            fixtures = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(rows) },
            templates = TemplateContractFacts { _, _ -> TemplatePin.Transform(PipelineVersionStatus.RELEASED, contract) },
        ).run(workspace, body)
    }

    private fun assertPassing(report: VisualizationMechanicalCheck.Report) {
        withClue(report.failures) { report.ok shouldBe true }
        report.failures.shouldBeEmpty()
        report.dropped shouldBe 0
        report.cases.getValue("rows").let {
            it.ok shouldBe true
            it.rows shouldBe 1
            it.rendered shouldBe "not_available"
        }
    }

    private fun assertFailure(
        report: VisualizationMechanicalCheck.Report,
        step: String,
        code: String,
        path: String,
    ) {
        report.ok shouldBe false
        report.dropped shouldBe 0
        report.failures.single().let {
            it.step shouldBe step
            it.code shouldBe code
            it.path shouldBe path
            it.case shouldBe if (step == "bindings") null else "rows"
        }
        report.cases.getValue("rows").rendered shouldBe "not_available"
    }
}
