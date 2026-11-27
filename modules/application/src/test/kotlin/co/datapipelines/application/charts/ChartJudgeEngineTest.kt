package co.datapipelines.application.charts

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.EngineRenderedStateCheck
import co.datapipelines.visualization.FixtureEvaluation
import co.datapipelines.visualization.RendererConfigValidators
import co.datapipelines.visualization.TemplateContractFacts
import co.datapipelines.visualization.TemplatePin
import co.datapipelines.visualization.TestFixtureEvaluator
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationMechanicalCheck
import co.datapipelines.visualization.VisualizationReader
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The mechanical check's chart cases judged by the REAL engine (js-rendered record §4.12; brief 986 D4, D9): the
 * [VisualizationMechanicalCheck] with [EngineRenderedStateCheck] over [JsRenderedChartEngine] in the product's sandbox.
 * The cases are the chart-value cases `modules/visualization`'s `VisualizationMechanicalCheckTest`,
 * `VisualizationBindingContractTest` and `MechanicalCheckTimingTest` asserted against the old Kotlin model; that module
 * cannot reach the runtime, so they are judged here, every chart VALUE (mark counts, the empty state, the series) with
 * its expectation kept. What changed is the old judge's bookkeeping (D9 (b)): a chart text is a SHOWN text or it fails,
 * and the rendered state is the engine's.
 */
class ChartJudgeEngineTest {
    private val rows =
        mutableListOf<Map<String, Any?>>(
            mapOf("month_labels" to "Jan", "amounts" to 10.5),
            mapOf("month_labels" to "Feb", "amounts" to 12.0),
        )

    private fun check(contract: ScriptContractView = CONTRACT) =
        VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.default(),
            fixtures = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(rows.toList()) },
            templates = TemplateContractFacts { _, _ -> TemplatePin.Transform(PipelineVersionStatus.RELEASED, contract) },
            rendered = EngineRenderedStateCheck(ChartEngines.engine),
        )

    private fun run(tree: ObjectNode) = check().run(WORKSPACE, VisualizationReader().readOrThrow(tree).body)

    /** The spec's worked chart body; the case asserts the two marks the two months draw. */
    private fun spec(vararg extra: String): ObjectNode =
        tree(SPEC).also { tree ->
            val case = (tree.path("tests").path("cases") as ArrayNode)[0] as ObjectNode
            ((case.path("assertions") as ArrayNode)[1] as ObjectNode).put("equals", 2)
            extra.forEach { (case.path("assertions") as ArrayNode).add(ArtifactJson.mapper.readTree(it)) }
        }

    private fun assertion(
        tree: ObjectNode,
        index: Int,
    ): ObjectNode = ((tree.path("tests").path("cases")[0].path("assertions") as ArrayNode)[index] as ObjectNode)

    @Test
    fun `the spec's own body passes every step - the rendered state is the engine's`() {
        val report = run(spec())
        withClue(report.failures) { report.ok shouldBe true }
        report.unjudged.shouldBeEmpty()
        report.cases.getValue("twelve months").rows shouldBe 2
        report.cases.getValue("twelve months").rendered shouldBe "rendered"
    }

    @Test
    fun `mark_count is compared with the engine's marks - and un-falsifies at the matching count`() {
        run(spec("""{"kind":"mark_count","equals":7}""")).failures.single { it.step == "assertions" }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.message shouldContain "draw 2 marks"
            it.path shouldBe "tests.cases[0].assertions[2]"
        }
        run(spec("""{"kind":"mark_count","equals":2}""")).ok shouldBe true
    }

    @Test
    fun `a no_data case with produced rows fails - and passes at zero rows`() {
        run(spec("""{"kind":"no_data"}""")).failures.single { it.step == "assertions" }.message shouldContain "draw 2 marks"
        rows.clear()
        run(spec().also { assertion(it, 1).put("equals", 0) }).let {
            withClue(it.failures) { it.ok shouldBe true }
            it.cases.values
                .single()
                .rendered shouldBe "no-data"
        }
        run(spec("""{"kind":"no_data"}""")).failures.map { it.message } shouldBe
            listOf("The case's rows draw 0 marks; the case asserts 2.")
    }

    @Test
    fun `a chart whose every aggregate is null draws nothing - no_data holds and a mark is disproved`() {
        val direct = tree(DIRECT_NULLS)
        withClue(run(direct).failures) { run(direct).ok shouldBe true }
        // Falsified: one non-null amount draws one mark, and both assertions see it.
        (
            direct
                .path("tests")
                .path("cases")[0]
                .path("fixtures")
                .path("revenue")[1] as ObjectNode
        ).put("amount", 4)
        run(direct).failures.map { it.message } shouldBe
            listOf("The case expects the empty state; its rows draw 1 marks.", "The case's rows draw 1 marks; the case asserts 0.")
    }

    @Test
    fun `text_visible on a chart is a text the chart shows - an absent text fails`() {
        // "Revenue" is the title (and the value axis title); "Feb" a category label.
        listOf("Revenue", "Feb").forEach { text ->
            withClue(text) {
                val report = run(spec("""{"kind":"text_visible","text":"$text"}"""))
                withClue(report.failures) { report.ok shouldBe true }
                report.unjudged.shouldBeEmpty()
            }
        }
        val report = run(spec("""{"kind":"text_visible","text":"NEITHER"}"""))
        report.failures.single().let {
            it.path shouldBe "tests.cases[0].assertions[2]"
            it.message shouldBe "The text is not in any text the chart shows at 640 × 400."
        }
        report.unjudged.shouldBeEmpty()
    }

    @Test
    fun `the record's example - 10 and 20 summed under decimal(2) - shows 30_00, so both texts are seen`() {
        rows.clear()
        rows += listOf(mapOf("month_labels" to "Jan", "amounts" to 10), mapOf("month_labels" to "Jan", "amounts" to 20))
        val document =
            spec("""{"kind":"text_visible","text":"30"}""", """{"kind":"text_visible","text":"30.00"}""").also {
                (it.path("config").path("encoding").path("y") as ObjectNode).put("format", "decimal(2)")
                assertion(it, 1).put("equals", 1)
            }
        val report = run(document)
        withClue(report.failures) { report.ok shouldBe true }
        report.unjudged.shouldBeEmpty()
    }

    @Test
    fun `a series value proves text through the legend - and a missing band x series pair is a gap, not a mark`() {
        val colored = tree(COLORED)
        val report = run(colored)
        withClue(report.failures) { report.ok shouldBe true }
        report.unjudged.shouldBeEmpty()
        // Four cells (two series x two bands), two of them gaps: a third mark is disproved.
        assertion(colored, 1).put("equals", 4)
        run(colored).failures.single().message shouldBe "The case's rows draw 2 marks; the case asserts 4."
    }

    @Test
    fun `a chart whose many categories exceed the frame is still judged - the density refusal is reported, not a crash`() {
        rows.clear()
        rows += (1..1_000).map { mapOf("month_labels" to "row-$it", "amounts" to it.toDouble()) }
        val report = run(spec().also { assertion(it, 1).put("equals", 1_000) })
        // 1,000 categories at 640 × 400 put each bar under a pixel: the engine refuses with
        // chart.rule.category-density, and the judge reports that refusal on both assertions, never a crash.
        report.failures.forEach { failure ->
            failure.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            failure.message shouldContain "chart.rule.category-density"
        }
        report.failures.size shouldBe 2
        report.cases.values.single().rows shouldBe 1_000
    }

    // ---- the measure's types (VisualizationBindingContractTest's chart half) --------------------------

    private fun measure(
        type: LogicalType,
        value: Any?,
        nullable: Boolean = false,
        vararg assertions: String,
    ): VisualizationMechanicalCheck.Report {
        val contract =
            CONTRACT.copy(
                output =
                    ScriptContractView.Output.Table(
                        listOf(
                            ScriptContractView.Column("label", LogicalType.STRING, false),
                            ScriptContractView.Column("amount", type, nullable),
                        ),
                    ),
            )
        rows.clear()
        rows += mapOf("label" to "Jan", "amount" to value)
        val document =
            tree(
                """
                {"name":"finance/visualizations/contract","display_name":"Contract","renderer":{"kind":"chart","version":"1"},
                 "inputs":{"revenue":{"columns":[{"name":"amount","type":"DECIMAL","nullable":false}]}},
                 "transform":{"template":{"name":"finance/scripts/revenue_bars","version":2},"inputs":{"rows":"revenue"}},
                 "config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"label","type":"nominal","sort":null},
                           "y":{"field":"amount","type":"quantitative","aggregate":"sum"}}},
                 "bindings":{"amount":"amount","label":"label"},
                 "tests":{"cases":[{"name":"rows","fixtures":{"revenue":[{"amount":1}]},
                 "assertions":[{"kind":"rendered"}${assertions.joinToString("") { ",$it" }}]}]}}
                """.trimIndent(),
            )
        return check(contract).run(WORKSPACE, VisualizationReader().readOrThrow(document).body)
    }

    @Test
    fun `a chart measure draws INTEGER and DECIMAL numbers and BIG types' exact strings - one mark each`() {
        listOf(LogicalType.INTEGER to 1, LogicalType.DECIMAL to 1, LogicalType.BIGINTEGER to "10", LogicalType.BIGDECIMAL to "10")
            .forEach { (type, value) ->
                withClue(type) {
                    val report = measure(type, value, false, """{"kind":"mark_count","equals":1}""")
                    withClue(report.failures) { report.ok shouldBe true }
                    report.cases.getValue("rows").rendered shouldBe "rendered"
                }
            }
    }

    @Test
    fun `a nullable null measure draws no mark - and its gap never supplies the text null`() {
        measure(LogicalType.DECIMAL, null, true, """{"kind":"mark_count","equals":0}""").let {
            // The only row's measure is null: the engine's empty state, so `rendered` (0 marks over 1 row) fails.
            it.cases.getValue("rows").rendered shouldBe "no-data"
            it.failures.map { failure -> failure.path } shouldBe listOf("tests.cases[0].assertions[0]")
        }
        measure(LogicalType.DECIMAL, null, true, """{"kind":"text_visible","text":"null"}""").failures.map { it.path } shouldBe
            listOf("tests.cases[0].assertions[0]", "tests.cases[0].assertions[1]")
    }

    @Test
    fun `a STRING or BOOLEAN measure that reached the check throws in the engine - one not-rendered case, the sentence kept`() {
        // The save rules refuse both (chart.measure.not_numeric); rows that reach the check anyway make the engine throw
        // the old model's own sentence. An evaluation that throws has no result (record §4.3, §6): the case is not
        // rendered, ONE failure at the case, and its assertions are not judged again.
        listOf(LogicalType.STRING to "Jan", LogicalType.BOOLEAN to true).forEach { (type, value) ->
            withClue(type) {
                val report = measure(type, value, false, """{"kind":"mark_count","equals":1}""")
                withClue(report.failures) { report.failures.size shouldBe 1 }
                report.failures.single().let {
                    it.step shouldBe "assertions"
                    it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
                    it.path shouldBe "tests.cases[0]"
                    it.message shouldContain "A value of 'amount' is not a number in its column's wire form."
                }
                report.cases.getValue("rows").rendered shouldBe "not_rendered"
            }
        }
    }

    companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-00000000098a")

        /** `finance/scripts/revenue_bars@2` as the visualization module's fakes seed it: rows in, month_labels / amounts out. */
        val CONTRACT =
            ScriptContractView(
                mode = ScriptContractView.Mode.TABLE,
                inputs =
                    mapOf(
                        "rows" to
                            ScriptContractView.Input.Table(
                                listOf(
                                    ScriptContractView.Column("month", LogicalType.DATE, false),
                                    ScriptContractView.Column("amount", LogicalType.DECIMAL, false),
                                ),
                            ),
                    ),
                output =
                    ScriptContractView.Output.Table(
                        listOf(
                            ScriptContractView.Column("month_labels", LogicalType.STRING, false),
                            ScriptContractView.Column("amounts", LogicalType.DECIMAL, false),
                        ),
                    ),
                rejects = false,
            )

        fun tree(json: String): ObjectNode = ArtifactJson.mapper.readTree(json) as ObjectNode

        /** The visualization module's `DocumentFixtures.SPEC_VISUALIZATION`, copied (that module's test fixtures are internal). */
        val SPEC =
            """
            {"name":"finance/visualizations/monthly_revenue","display_name":"Monthly revenue","description":"",
             "renderer":{"kind":"chart","version":"1"},
             "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},{"name":"amount","type":"DECIMAL","nullable":false}]}},
             "transform":{"template":{"name":"finance/scripts/revenue_bars","version":2},"inputs":{"rows":"revenue"}},
             "config":{"mark":{"type":"bar"},
                       "encoding":{"x":{"field":"month_labels","type":"nominal","sort":null,"title":"Month"},
                                   "y":{"field":"amounts","type":"quantitative","aggregate":"sum","title":"Revenue","format":"compact"}},
                       "title":{"text":"Revenue"}},
             "bindings":{"amounts":"amounts","month_labels":"month_labels"},
             "tests":{"cases":[{"name":"twelve months","fixtures":{"revenue":[{"month":"2026-01-01","amount":"10.5"}]},
                                "assertions":[{"kind":"rendered"},{"kind":"mark_count","equals":1}]}]}}
            """.trimIndent()

        val DIRECT_NULLS =
            """
            {"name":"finance/visualizations/direct","display_name":"Direct","renderer":{"kind":"chart","version":"1"},
             "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},{"name":"amount","type":"DECIMAL","nullable":true}]}},
             "config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"month","type":"temporal","sort":"ascending"},
                       "y":{"field":"amount","type":"quantitative","aggregate":"sum"}}},
             "bindings":{"amount":"amount","month":"month"},
             "tests":{"cases":[{"name":"gaps","fixtures":{"revenue":[{"month":"2026-01-01","amount":null},{"month":"2026-02-01","amount":null}]},
                                "assertions":[{"kind":"no_data"},{"kind":"mark_count","equals":0}]}]}}
            """.trimIndent()

        val COLORED =
            """
            {"name":"finance/visualizations/direct","display_name":"Direct","renderer":{"kind":"chart","version":"1"},
             "inputs":{"sales":{"columns":[{"name":"region","type":"STRING","nullable":false},{"name":"product","type":"STRING","nullable":false},
                                           {"name":"amount","type":"DECIMAL","nullable":false}]}},
             "config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"region","type":"nominal","sort":null},
                       "y":{"field":"amount","type":"quantitative","aggregate":"sum","stack":"zero"},
                       "color":{"field":"product","type":"nominal","sort":null}}},
             "bindings":{"amount":"amount","product":"product","region":"region"},
             "tests":{"cases":[{"name":"rows","fixtures":{"sales":[{"region":"North","product":"Widget","amount":3},
                                                                   {"region":"South","product":"Gadget","amount":4}]},
                                "assertions":[{"kind":"rendered"},{"kind":"mark_count","equals":2},{"kind":"text_visible","text":"Widget"}]}]}}
            """.trimIndent()
    }
}
