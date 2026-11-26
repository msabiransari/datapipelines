package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The mechanical visualization test (the spec's §11.3) over FAKED ports — one passing and one refused
 * specimen per check, then every enforcement line FALSIFIED: flipping the one offending fact back to
 * valid removes exactly that refusal, so the test proves the check runs, not merely that the fixture is
 * bad. The real evaluator adapter's wiring is `web`'s (its focused test there).
 *
 * This module cannot reach the JavaScript runtime (its dependency map), so the check here runs as on a host
 * without it ([RenderedStateCheck.NOT_AVAILABLE], js-rendered record §6): a chart case's `rendered`, `mark_count`,
 * `no_data` and `text_visible` are listed as unjudged, never failures, and every other layer runs. §4.12's table
 * over the port is [ChartJudgeTest]'s; the chart VALUES these cases assert (mark counts, the empty state, the
 * texts) are judged by the real engine in `modules/application`'s `ChartJudgeEngineTest`, expectations kept.
 */
class VisualizationMechanicalCheckTest {
    private object Fakes {
        // The validator fakes' transform: released, TABLE mode, output month_labels (STRING) / amounts (DECIMAL).
        val templateFacts = ValidatorFakes.Fakes().templateFacts

        val evaluatorReturnsRows = mutableListOf<Map<String, Any?>>()
        var evaluatorRefusal: FixtureEvaluation.Refused? = null

        val evaluator =
            TestFixtureEvaluator { _, _, _ ->
                evaluatorRefusal ?: FixtureEvaluation.Rows(evaluatorReturnsRows)
            }

        fun reset() {
            evaluatorReturnsRows.clear()
            evaluatorReturnsRows +=
                listOf(
                    mapOf("month_labels" to "Jan", "amounts" to 10.5),
                    mapOf("month_labels" to "Feb", "amounts" to 12.0),
                )
            evaluatorRefusal = null
        }
    }

    private val check =
        VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.default(),
            fixtures = Fakes.evaluator,
            templates = Fakes.templateFacts,
            rendered = RenderedStateCheck.NOT_AVAILABLE,
        )

    /**
     * The spec's worked chart body, with the transform pin — valid at save, GREEN under these fakes: the evaluator
     * returns two months, so the case's `mark_count` asserts the two marks they draw.
     */
    private fun body(edit: (ObjectNode) -> Unit = {}): VisualizationBody {
        val tree = DocumentFixtures.visualization().also { it.obj("tests.cases[0].assertions[1]").put("equals", 2) }.also(edit)
        return ValidatorFakes.visualizationDocument(tree).body
    }

    /** The worked `table` twin — the renderer whose text assertions are judged against the bound values. */
    private fun table(edit: (ObjectNode) -> Unit = {}): VisualizationBody =
        ValidatorFakes.visualizationDocument(DocumentFixtures.table().also(edit)).body

    init {
        Fakes.reset()
    }

    @Test
    fun `the spec's own body passes every step - and the report records the case rows and not_available`() {
        val report = check.run(WORKSPACE, body())
        report.ok shouldBe true
        report.failures.shouldBeEmpty()
        report.cases["twelve months"]!!.rows shouldBe 2
        report.cases["twelve months"]!!.rendered shouldBe "not_available"
        // Without the runtime the case's two chart assertions are listed, never judged.
        report.unjudged shouldBe
            listOf(
                VisualizationMechanicalCheck.Unjudged("twelve months", "tests.cases[0].assertions[0]", "rendered"),
                VisualizationMechanicalCheck.Unjudged("twelve months", "tests.cases[0].assertions[1]", "mark_count"),
            )
        check.run(WORKSPACE, table()).ok shouldBe true
    }

    // ---- step 1: schema -------------------------------------------------------------------------------

    @Test
    fun `an unknown config attribute fails the chart schema step with the path - and un-falsifies when removed`() {
        val bad = body { it.obj("config").put("bogus_attr", 1) }
        val report = check.run(WORKSPACE, bad)
        report.ok shouldBe false
        report.failures.map { Triple(it.step, it.code, it.path) }.toSet() shouldBe
            setOf(Triple("schema", VisualizationErrorCodes.CONFIG_SCHEMA_INVALID, "config"))
        check.run(WORKSPACE, body()).ok shouldBe true
    }

    @Test
    fun `a mark the bar family does not define fails the schema step under config`() {
        val report = check.run(WORKSPACE, body { it.obj("config.mark").put("type", "not_a_type") })
        report.failures.map { it.step to it.code }.toSet() shouldBe setOf("schema" to VisualizationErrorCodes.CONFIG_SCHEMA_INVALID)
        report.failures.all { it.path.startsWith("config") } shouldBe true
    }

    @Test
    fun `an unknown table key fails the table's own schema step`() {
        val report = check.run(WORKSPACE, table { it.obj("config").put("bogus_attr", 1) })
        report.failures.single().let {
            it.step shouldBe "schema"
            it.code shouldBe VisualizationErrorCodes.CONFIG_SCHEMA_INVALID
            it.path shouldBe "config.bogus_attr"
        }
    }

    // ---- step 2: bindings -----------------------------------------------------------------------------

    @Test
    fun `a binding column outside the output contract fails - and un-falsifies when the contract names it`() {
        val bad = body { it.obj("bindings").put("amounts", "unknown_column") }
        val report = check.run(WORKSPACE, bad)
        val failure = report.failures.single { it.step == "bindings" && it.path == "bindings.amounts" }
        failure.code shouldBe VisualizationErrorCodes.BINDING_UNBOUND
        failure.message shouldContain "not in the output contract"
        // Falsification: `amounts` IS in the faked contract.
        check.run(WORKSPACE, body { it.obj("bindings").put("amounts", "amounts") }).ok shouldBe true
    }

    @Test
    fun `a chart's field-keyed bindings are not config paths - a table's binding path must still resolve`() {
        // `amounts` names no member of a chart's config; the chart is judged by its save rules, never by path resolution.
        check
            .run(WORKSPACE, body())
            .failures
            .filter { it.step == "bindings" }
            .shouldBeEmpty()
        val unresolved = check.run(WORKSPACE, table { it.obj("bindings").put("columns[5].values", "amounts") })
        unresolved.failures.single { it.step == "bindings" }.message shouldContain "Does not resolve"
    }

    @Test
    fun `the per-path type table is pinned - a chart, a table and a KPI bind every type, the reserved kinds none`() {
        LogicalType.entries.forEach { type ->
            withClue(type) {
                BindingTypes.accepts(RendererKind.CHART, "amounts", type) shouldBe true
                BindingTypes.accepts(RendererKind.TABLE, "columns[0].values", type) shouldBe true
                BindingTypes.accepts(RendererKind.KPI, "value", type) shouldBe true
                BindingTypes.accepts(RendererKind.HTML, "x", type) shouldBe false
                BindingTypes.accepts(RendererKind.SVG, "x", type) shouldBe false
            }
        }
    }

    // ---- step 3: the fixture run ----------------------------------------------------------------------

    @Test
    fun `an evaluator refusal fails the case with the evaluator's own code - and un-falsifies on a clean run`() {
        Fakes.evaluatorRefusal = FixtureEvaluation.Refused("template.type_gate_refused", "row 0 column 'amount' is not a number")
        val report = check.run(WORKSPACE, body())
        report.ok shouldBe false
        report.failures.single().let {
            it.step shouldBe "fixtures"
            it.code shouldBe "template.type_gate_refused"
            it.case shouldBe "twelve months"
            it.message shouldContain "not a number"
        }
        Fakes.reset()
        check.run(WORKSPACE, body()).ok shouldBe true
    }

    @Test
    fun `a missing projected bound column fails the case per row - and un-falsifies when present`() {
        Fakes.evaluatorReturnsRows.clear()
        Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan") // amounts missing
        val report = check.run(WORKSPACE, body())
        // The one defect is reported once: a case whose rows failed gets no chart model, so mark_count is not judged too.
        val failure = report.failures.single()
        failure.step shouldBe "fixtures"
        failure.code shouldBe VisualizationErrorCodes.BINDING_UNBOUND
        failure.case shouldBe "twelve months"
        failure.path shouldBe "tests.cases[0].rows[0].amounts"
        Fakes.reset()
        check.run(WORKSPACE, body()).ok shouldBe true
    }

    @Test
    fun `the report keeps the first 100 failures and COUNTS the rest - a per-row explosion is bounded, never built`() {
        Fakes.evaluatorReturnsRows.clear()
        repeat(150) { Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan") } // amounts missing in EVERY row
        val report = check.run(WORKSPACE, body())
        report.ok shouldBe false
        report.failures.size shouldBe VisualizationMechanicalCheck.MAX_FAILURES
        report.dropped shouldBe 50
        report.toJson()["failures_dropped"].intValue() shouldBe 50
        report.toJson()["failures"].size() shouldBe VisualizationMechanicalCheck.MAX_FAILURES
        report.cases.getValue("twelve months").ok shouldBe false
        Fakes.reset()
        check.run(WORKSPACE, body()).let {
            it.ok shouldBe true
            it.dropped shouldBe 0
            it.toJson()["failures_dropped"].intValue() shouldBe 0
        }
    }

    @Test
    fun `a DRAFT transform pin is valid in authoring - published runtime remains RELEASED-only`() {
        val draftFacts =
            TemplateContractFacts { _, ref ->
                ValidatorFakes.CONTRACT.let {
                    TemplatePin.Transform(PipelineVersionStatus.DRAFT, it)
                }
            }
        val draftCheck =
            VisualizationMechanicalCheck(
                renderers = RendererConfigValidators.default(),
                fixtures = Fakes.evaluator,
                templates = draftFacts,
                rendered = RenderedStateCheck.NOT_AVAILABLE,
            )
        withClue("a DRAFT pin passes the fixture run") { draftCheck.run(WORKSPACE, body()).ok shouldBe true }
        // And the same run REFUSES when the pin is gone entirely: the evaluator's refusal names it.
        Fakes.evaluatorRefusal =
            FixtureEvaluation.Refused("template.not_found", "Template 'finance/scripts/revenue_bars' does not exist.")
        draftCheck.run(WORKSPACE, body()).ok shouldBe false
        Fakes.reset()
    }

    @Test
    fun `with no transform the fixture VALUES are judged through the wire-form rules - and un-falsify`() {
        val direct =
            """
            {"name":"finance/visualizations/direct","display_name":"Direct",
             "renderer":{"kind":"chart","version":"1"},
             "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                             {"name":"amount","type":"DECIMAL","nullable":false}]}},
             "config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"month","type":"temporal","sort":"ascending"},
                       "y":{"field":"amount","type":"quantitative","aggregate":"sum"}}},
             "bindings":{"amount":"amount","month":"month"},
             "tests":{"cases":[{"name":"rows","fixtures":{"revenue":[{"month":"2026-01-01","amount":10.5}]},
                                "assertions":[{"kind":"rendered"}]}]}}
            """.trimIndent()
        val good = ValidatorFakes.visualizationDocument(ArtifactJson.mapper.readTree(direct) as ObjectNode).body
        check.run(WORKSPACE, good).ok shouldBe true

        // A DECIMAL column's value in STRING wire form is refused (BIGDECIMAL's grammar, not DECIMAL's).
        val bad = ArtifactJson.mapper.readTree(direct) as ObjectNode
        bad.obj("tests.cases[0].fixtures.revenue[0]").put("amount", "10.5")
        val report = check.run(WORKSPACE, ValidatorFakes.visualizationDocument(bad).body)
        val failure = report.failures.single()
        failure.step shouldBe "fixtures"
        failure.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
        failure.path shouldBe "tests.cases[0].fixtures[revenue][0].amount"
    }

    // ---- step 4: assertion feasibility ----------------------------------------------------------------

    @Test
    fun `without the runtime a chart's mark_count is unjudged, never a failure - and a table's mark_count is refused`() {
        val bad = check.run(WORKSPACE, body { objAssertion(it, 0, """{"kind":"mark_count","equals":7}""") })
        bad.ok shouldBe true
        bad.failures.shouldBeEmpty()
        bad.unjudged.map { it.path to it.kind } shouldBe
            listOf(
                "tests.cases[0].assertions[0]" to "rendered",
                "tests.cases[0].assertions[1]" to "mark_count",
                "tests.cases[0].assertions[2]" to "mark_count",
            )
        // mark_count is a chart's: on a table it is refused.
        check
            .run(WORKSPACE, table { objAssertion(it, 0, """{"kind":"mark_count","equals":2}""") })
            .failures
            .single()
            .message shouldBe
            "mark_count needs a chart document."
    }

    @Test
    fun `without the runtime a chart's no_data is unjudged at any row count - a table keeps the row rule`() {
        val noData = body { objAssertion(it, 0, """{"kind":"no_data"}""") }
        listOf(2, 0).forEach { rows ->
            withClue("$rows rows") {
                if (rows == 0) Fakes.evaluatorReturnsRows.clear()
                val report = check.run(WORKSPACE, noData)
                report.ok shouldBe true
                report.unjudged.map { it.kind } shouldBe listOf("rendered", "mark_count", "no_data")
                report.cases.values
                    .single()
                    .rendered shouldBe "not_available"
            }
        }
        // A table keeps the row rule.
        Fakes.reset()
        check
            .run(WORKSPACE, table { objAssertion(it, 0, """{"kind":"no_data"}""") })
            .failures
            .single { it.step == "assertions" }
            .message shouldContain "produced 2 rows"
    }

    @Test
    fun `without the runtime a chart whose every aggregate is null is unjudged - its rows are still checked`() {
        val direct =
            DocumentFixtures.tree(
                """
                {"name":"finance/visualizations/direct","display_name":"Direct",
                 "renderer":{"kind":"chart","version":"1"},
                 "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                                 {"name":"amount","type":"DECIMAL","nullable":true}]}},
                 "config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"month","type":"temporal","sort":"ascending"},
                           "y":{"field":"amount","type":"quantitative","aggregate":"sum"}}},
                 "bindings":{"amount":"amount","month":"month"},
                 "tests":{"cases":[{"name":"gaps","fixtures":{"revenue":[{"month":"2026-01-01","amount":null},
                                                                         {"month":"2026-02-01","amount":null}]},
                                    "assertions":[{"kind":"no_data"},{"kind":"mark_count","equals":0}]}]}}
                """.trimIndent(),
            )
        listOf(null, 4).forEach { amount ->
            withClue("amount $amount") {
                direct.obj("tests.cases[0].fixtures.revenue[1]").put("amount", amount)
                val report = check.run(WORKSPACE, ValidatorFakes.visualizationDocument(direct).body)
                report.ok shouldBe true
                report.unjudged.map { it.kind } shouldBe listOf("no_data", "mark_count")
            }
        }
        // The fixture layer still runs: a string where the DECIMAL column wants a number fails the case.
        direct.obj("tests.cases[0].fixtures.revenue[1]").put("amount", true)
        check
            .run(WORKSPACE, ValidatorFakes.visualizationDocument(direct).body)
            .failures
            .single()
            .step shouldBe "fixtures"
    }

    @Test
    fun `without the runtime a chart's text_visible is unjudged whatever the text - a table's is refused`() {
        listOf("Revenue", "Feb", "NEITHER").forEach { text ->
            withClue(text) {
                val report = check.run(WORKSPACE, body { objAssertion(it, 0, """{"kind":"text_visible","text":"$text"}""") })
                report.ok shouldBe true
                report.failures.shouldBeEmpty()
                report.unjudged.last() shouldBe
                    VisualizationMechanicalCheck.Unjudged("twelve months", "tests.cases[0].assertions[2]", "text_visible")
            }
        }
        val report = check.run(WORKSPACE, body { objAssertion(it, 0, """{"kind":"text_visible","text":"NEITHER"}""") })
        report.toJson()["unjudged"].last().let {
            it["case"].asText() shouldBe "twelve months"
            it["path"].asText() shouldBe "tests.cases[0].assertions[2]"
            it["kind"].asText() shouldBe "text_visible"
        }
        // The same text on a table IS refused: the unjudged rule is the chart's alone.
        check.run(WORKSPACE, table { objAssertion(it, 0, """{"kind":"text_visible","text":"NEITHER"}""") }).ok shouldBe false
    }

    @Test
    fun `without the runtime the record's example - 10 and 20 summed under decimal(2) - is unjudged, its texts too`() {
        Fakes.evaluatorReturnsRows.clear()
        Fakes.evaluatorReturnsRows +=
            listOf(mapOf("month_labels" to "Jan", "amounts" to 10), mapOf("month_labels" to "Jan", "amounts" to 20))
        val document =
            body {
                it.obj("config.encoding.y").put("format", "decimal(2)")
                it.obj("tests.cases[0].assertions[1]").put("equals", 1)
                objAssertion(it, 0, """{"kind":"text_visible","text":"30"}""")
                objAssertion(it, 0, """{"kind":"text_visible","text":"30.00"}""")
            }
        val report = check.run(WORKSPACE, document)
        report.ok shouldBe true
        report.unjudged.map { it.path } shouldBe (0..3).map { "tests.cases[0].assertions[$it]" }
        Fakes.reset()
    }

    @Test
    fun `without the runtime a colored chart's assertions are unjudged at any asserted count`() {
        val colored =
            DocumentFixtures.tree(
                """
                {"name":"finance/visualizations/direct","display_name":"Direct",
                 "renderer":{"kind":"chart","version":"1"},
                 "inputs":{"sales":{"columns":[{"name":"region","type":"STRING","nullable":false},
                                               {"name":"product","type":"STRING","nullable":false},
                                               {"name":"amount","type":"DECIMAL","nullable":false}]}},
                 "config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"region","type":"nominal","sort":null},
                           "y":{"field":"amount","type":"quantitative","aggregate":"sum","stack":"zero"},
                           "color":{"field":"product","type":"nominal","sort":null}}},
                 "bindings":{"amount":"amount","product":"product","region":"region"},
                 "tests":{"cases":[{"name":"rows","fixtures":{"sales":[{"region":"North","product":"Widget","amount":3},
                                                                       {"region":"South","product":"Gadget","amount":4}]},
                                    "assertions":[{"kind":"rendered"},{"kind":"mark_count","equals":2},
                                                  {"kind":"text_visible","text":"Widget"}]}]}}
                """.trimIndent(),
            )
        listOf(2, 4).forEach { marks ->
            withClue("$marks marks") {
                colored.obj("tests.cases[0].assertions[1]").put("equals", marks)
                val report = check.run(WORKSPACE, ValidatorFakes.visualizationDocument(colored).body)
                report.ok shouldBe true
                report.unjudged.map { it.kind } shouldBe listOf("rendered", "mark_count", "text_visible")
            }
        }
    }

    @Test
    fun `text_visible on a table must appear in the configuration or the bound values - and un-falsifies`() {
        val absent = table { objAssertion(it, 0, """{"kind":"text_visible","text":"NEITHER"}""") }
        check
            .run(WORKSPACE, absent)
            .failures
            .single { it.step == "assertions" }
            .message shouldContain "appears neither"
        // "Feb" is a bound value of the case's evaluation; "Revenue" is a column label of the configuration.
        check.run(WORKSPACE, table { objAssertion(it, 0, """{"kind":"text_visible","text":"Feb"}""") }).ok shouldBe true
        check.run(WORKSPACE, table { objAssertion(it, 0, """{"kind":"text_visible","text":"Revenue"}""") }).ok shouldBe true
    }

    @Test
    fun `an INTEGER KPI bound fixture makes 42 feasible without configuration text`() {
        val candidate = directKpi("INTEGER", "42", "42")
        assertFixtureScalar(candidate, "Integer", "42")
        assertFeasible(candidate, "rows")
    }

    @Test
    fun `a direct DECIMAL fixture makes 10 point 5 feasible for both text assertion kinds`() {
        listOf("value_visible", "text_visible").forEach { kind ->
            val candidate = directKpi("DECIMAL", "10.5", "10.5", kind)
            assertFixtureScalar(candidate, "Double", "10.5")
            assertFeasible(candidate, "rows")
        }
    }

    @Test
    fun `transformed Double and BigDecimal preserve their scalar spelling and substring matching`() {
        val specimens =
            listOf(
                10.5 to "10.5",
                0 to "0",
                -42 to "-42",
                BigDecimal("10.50") to "10.50",
                BigDecimal("1E+8") to "1E+8",
                1.0e20 to "1.0E20",
            )
        specimens.forEach { (value, text) ->
            withClue("${value.javaClass.simpleName}: $text") {
                value.toString() shouldBe text
                Fakes.evaluatorReturnsRows.clear()
                Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan", "amounts" to value)
                listOf("value_visible", "text_visible").forEach { kind ->
                    assertFeasible(table { objAssertion(it, 0, """{"kind":"$kind","text":"$text"}""") }, "twelve months")
                }
            }
        }
        Fakes.evaluatorReturnsRows.clear()
        Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan", "amounts" to BigDecimal("10.50"))
        assertFeasible(table { objAssertion(it, 0, """{"kind":"value_visible","text":"0.50"}""") }, "twelve months")
        Fakes.reset()
    }

    @Test
    fun `absent numeric text and numeric text from only an unbound column are refused on both paths`() {
        assertTextRefused(directKpi("INTEGER", "42", "987654"), "rows", 1)
        val direct = directKpi("INTEGER", "42", "987654")
        val fixture =
            direct.tests
                ?.cases
                ?.single()
                ?.fixtures
                ?.getValue("revenue")
                ?.single()
        requireNotNull(fixture).put("unbound", 987654)
        assertTextRefused(direct, "rows", 1)

        Fakes.evaluatorReturnsRows.clear()
        Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan", "amounts" to 10.5, "unbound" to 987654)
        listOf("987654", "876543").forEach { text ->
            assertTextRefused(table { objAssertion(it, 0, """{"kind":"value_visible","text":"$text"}""") }, "twelve months", 2)
        }
        Fakes.reset()
    }

    @Test
    fun `bound booleans containers and arbitrary objects are not assertion text`() {
        val specimens =
            listOf(
                true to "true",
                listOf("container-only") to "container-only",
                mapOf("key" to "object-only") to "object-only",
                object {
                    override fun toString(): String = "arbitrary-only"
                } to "arbitrary-only",
            )
        specimens.forEach { (value, text) ->
            Fakes.evaluatorReturnsRows.clear()
            Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan", "amounts" to value)
            assertTextRefused(table { objAssertion(it, 0, """{"kind":"value_visible","text":"$text"}""") }, "twelve months", 2)
        }
        Fakes.reset()
    }

    @Test
    fun `a nullable null supplies no text while a missing required fixture column still fails`() {
        assertTextRefused(directKpi("INTEGER", "null", "null", nullable = true), "rows", 1)
        val missing = directKpi("INTEGER", "42", "42")
        requireNotNull(missing.tests)
            .cases
            .single()
            .fixtures
            .getValue("revenue")
            .single()
            .remove("amount")
        val report = check.run(WORKSPACE, missing)
        report.ok shouldBe false
        report.cases.getValue("rows").ok shouldBe false
        report.failures.single { it.step == "fixtures" }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.path shouldBe "tests.cases[0].fixtures[revenue][0].amount"
            it.case shouldBe "rows"
        }
    }

    @Test
    fun `BIGDECIMAL string wire values keep trailing zeros and are not normalized`() {
        assertFeasible(directKpi("BIGDECIMAL", "\"10.50\"", "10.50"), "rows")
        assertTextRefused(directKpi("BIGDECIMAL", "\"10.5\"", "10.50"), "rows", 1)
    }

    private fun directKpi(
        type: String,
        value: String,
        text: String,
        kind: String = "value_visible",
        nullable: Boolean = false,
    ): VisualizationBody =
        ValidatorFakes
            .visualizationDocument(
                DocumentFixtures.tree(
                    """
                    {"name":"finance/visualizations/direct","display_name":"Direct",
                     "renderer":{"kind":"kpi","version":"1"},
                     "inputs":{"revenue":{"columns":[{"name":"amount","type":"$type","nullable":$nullable}]}},
                     "config":{"value":"$.amount"},"bindings":{"value":"amount"},
                     "tests":{"cases":[{"name":"rows","fixtures":{"revenue":[{"amount":$value}]},
                                        "assertions":[{"kind":"rendered"},{"kind":"$kind","text":"$text"}]}]}}
                    """.trimIndent(),
                ),
            ).body

    private fun assertFixtureScalar(
        candidate: VisualizationBody,
        className: String,
        text: String,
    ) {
        // Inspect the same Jackson conversion the direct-fixture path uses, without changing its mapper.
        val row =
            requireNotNull(candidate.tests)
                .cases
                .single()
                .fixtures
                .getValue("revenue")
                .single()
        val converted: Map<String, Any?> =
            ArtifactJson.mapper.convertValue(row, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {})
        val value = requireNotNull(converted["amount"])
        value.javaClass.simpleName shouldBe className
        value.toString() shouldBe text
    }

    private fun assertFeasible(
        candidate: VisualizationBody,
        case: String,
    ) {
        val report = check.run(WORKSPACE, candidate)
        withClue(report.failures) { report.ok shouldBe true }
        report.failures.shouldBeEmpty()
        report.cases.getValue(case).ok shouldBe true
        report.cases.getValue(case).rows shouldBe 1
        report.cases.getValue(case).rendered shouldBe "not_available"
    }

    private fun assertTextRefused(
        candidate: VisualizationBody,
        case: String,
        assertionIndex: Int,
    ) {
        val report = check.run(WORKSPACE, candidate)
        report.ok shouldBe false
        // Case ok describes the fixture run; assertion refusals live on the overall report.
        report.cases.getValue(case).ok shouldBe true
        report.cases.getValue(case).rendered shouldBe "not_available"
        report.failures.single().let {
            it.step shouldBe "assertions"
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.path shouldBe "tests.cases[0].assertions[$assertionIndex]"
            it.case shouldBe case
            it.message shouldBe "The text appears neither in the configuration nor in the bound values."
        }
    }

    // ---- step 5: rendered state -----------------------------------------------------------------------

    @Test
    fun `the default rendered state records not_available - never success, never a browser claim`() {
        val input = ChartInput(ChartType.BAR, DocumentFixtures.visualization(), emptyMap(), emptyMap())
        RenderedStateCheck.NOT_AVAILABLE.render(input) shouldBe CanonicalRender.NotAvailable
        CanonicalRender.NotAvailable.state shouldBe "not_available"
        val report = check.run(WORKSPACE, body())
        report.cases.values
            .single()
            .rendered shouldBe "not_available"
    }

    @Test
    fun `every step runs - one body accumulates failures from three steps at once`() {
        Fakes.evaluatorReturnsRows.clear()
        Fakes.evaluatorReturnsRows += mapOf("month_labels" to "Jan") // fixtures step fails (amounts missing)
        val report =
            check.run(
                WORKSPACE,
                table {
                    it.obj("config").put("bogus", true) // schema step fails
                    objAssertion(it, 0, """{"kind":"text_visible","text":"NEITHER"}""") // assertions step fails
                },
            )
        report.failures.map { it.step }.distinct() shouldContainExactlyInAnyOrder listOf("schema", "fixtures", "assertions")
        Fakes.reset()
    }

    private fun objAssertion(
        tree: ObjectNode,
        caseIndex: Int,
        assertion: String,
    ) {
        val cases = tree.obj("tests").get("cases") as com.fasterxml.jackson.databind.node.ArrayNode
        (cases.get(caseIndex).get("assertions") as com.fasterxml.jackson.databind.node.ArrayNode)
            .add(ArtifactJson.mapper.readTree(assertion))
    }

    companion object {
        val WORKSPACE: UUID = ValidatorFakes.WORKSPACE
    }
}
