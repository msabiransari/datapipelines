package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.typesystem.JsonWireForm
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** Complete mechanical reports pin renderer typing and the transform output's presence/nullability contract. */
class VisualizationBindingContractTest {
    private val workspace = UUID.randomUUID()

    @Test
    fun `table STRING values pass while Plotly STRING values remain refused`() {
        val table = run(RendererKind.TABLE, "columns[0].values", LogicalType.STRING, row = mapOf("amount" to "Jan"))
        assertPassing(table)
        val plotly = run(RendererKind.PLOTLY, "data[0].values", LogicalType.STRING, row = mapOf("amount" to "Jan"))
        assertFailure(plotly, "bindings", VisualizationErrorCodes.BINDING_UNBOUND, "bindings.data[0].values")
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
    fun `Plotly numeric leaves accept only INTEGER and DECIMAL while KPI keeps BIG wire strings`() {
        listOf("data[0].y", "data[0].z[0]", "data[0].values").forEach { path ->
            LogicalType.entries.forEach { type ->
                withClue("$path $type") {
                    val report = run(RendererKind.PLOTLY, path, type, row = mapOf("amount" to 1))
                    if (type in setOf(LogicalType.INTEGER, LogicalType.DECIMAL)) {
                        assertPassing(report)
                    } else {
                        assertFailure(report, "bindings", VisualizationErrorCodes.BINDING_UNBOUND, "bindings.$path")
                    }
                }
            }
        }
        listOf("value", "comparison.value").forEach { path ->
            listOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL).forEach { type ->
                assertPassing(run(RendererKind.KPI, path, type, row = mapOf("amount" to "10.50")))
            }
        }
    }

    @Test
    fun `nullable transform output null passes at Plotly y and z while absent keys fail precisely`() {
        listOf("data[0].y", "data[0].z[0]").forEach { path ->
            assertPassing(run(RendererKind.PLOTLY, path, LogicalType.DECIMAL, nullable = true, row = mapOf("amount" to null)))
            val missing = run(RendererKind.PLOTLY, path, LogicalType.DECIMAL, nullable = true, row = emptyMap())
            assertFailure(missing, "fixtures", VisualizationErrorCodes.BINDING_UNBOUND, "tests.cases[0].rows[0].amount")
            missing.failures.single().message shouldBe "The bound column is missing from the row that $path consumes."
            missing.cases.getValue("rows").ok shouldBe false
        }
    }

    @Test
    fun `nonnullable transform output null is an invalid fixture rather than a missing binding`() {
        val report = run(RendererKind.PLOTLY, "data[0].y", LogicalType.DECIMAL, row = mapOf("amount" to null))
        assertFailure(report, "fixtures", VisualizationErrorCodes.TEST_CASE_INVALID, "tests.cases[0].rows[0].amount")
        report.failures.single().message shouldBe "The column is not nullable."
        report.cases.getValue("rows").ok shouldBe false
    }

    @Test
    fun `nullable null never supplies the literal null for text assertions`() {
        val report =
            run(RendererKind.PLOTLY, "data[0].y", LogicalType.DECIMAL, nullable = true, row = mapOf("amount" to null), text = "null")
        assertFailure(report, "assertions", VisualizationErrorCodes.TEST_CASE_INVALID, "tests.cases[0].assertions[1]")
        report.cases.getValue("rows").ok shouldBe true
    }

    @Test
    fun `nonnullable null failures remain bounded and counted`() {
        val report = run(RendererKind.PLOTLY, "data[0].y", LogicalType.DECIMAL, row = mapOf("amount" to null), count = 150)
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
    ): VisualizationMechanicalCheck.Report {
        val config =
            when (renderer) {
                RendererKind.TABLE -> {
                    """{"columns":[{"label":"Amount","values":[]}]}"""
                }

                RendererKind.KPI -> {
                    """{"label":"Total","value":null,"comparison":{"label":"Previous","value":null}}"""
                }

                RendererKind.PLOTLY -> {
                    when (path) {
                        "data[0].values" -> """{"data":[{"type":"pie","values":[]}]}"""
                        "data[0].z[0]" -> """{"data":[{"type":"heatmap","z":[[]]}]}"""
                        else -> """{"data":[{"type":"bar","y":[]}]}"""
                    }
                }

                RendererKind.HTML, RendererKind.SVG -> {
                    error("Reserved renderer is not a test specimen.")
                }
            }
        val assertion = text?.let { """,{"kind":"text_visible","text":"$it"}""" }.orEmpty()
        val body =
            ValidatorFakes
                .visualizationDocument(
                    DocumentFixtures.tree(
                        """
                        {"name":"finance/visualizations/contract","display_name":"Contract",
                         "renderer":{"kind":"${renderer.wire}","version":"1"},
                         "inputs":{"revenue":{"columns":[{"name":"amount","type":"DECIMAL","nullable":false}]}},
                         "transform":{"template":{"name":"finance/transforms/revenue_bars","version":2},"inputs":{"rows":"revenue"}},
                         "config":$config,"bindings":{"$path":"amount"},
                         "tests":{"cases":[{"name":"rows","fixtures":{"revenue":[{"amount":1}]},
                         "assertions":[{"kind":"rendered"}$assertion]}]}}
                        """.trimIndent(),
                    ),
                ).body
        val contract =
            ValidatorFakes.CONTRACT.copy(
                output = TransformContractView.Output.Table(listOf(TransformContractView.Column("amount", type, nullable))),
            )
        return VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.deep(),
            fixtures = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(List(count) { row }) },
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
