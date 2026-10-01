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
import java.util.UUID

/**
 * The mechanical visualization test (the spec's §11.3) over FAKED ports — one passing and one refused
 * specimen per check, then every enforcement line FALSIFIED: flipping the one offending fact back to
 * valid removes exactly that refusal, so the test proves the check runs, not merely that the fixture is
 * bad. The real evaluator adapter's wiring is `web`'s (its focused test there); the rendered-state
 * default records `not_available` and is pinned here too.
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
            renderers = RendererConfigValidators.deep(),
            fixtures = Fakes.evaluator,
            templates = Fakes.templateFacts,
            rendered = RenderedStateCheck.NOT_AVAILABLE,
        )

    /** The spec's worked body, with the transform pin — valid at save, GREEN under these fakes. */
    private fun body(edit: (ObjectNode) -> Unit = {}): VisualizationBody {
        val tree = DocumentFixtures.visualization().also(edit)
        return ValidatorFakes.visualizationDocument(tree).body
    }

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
    }

    // ---- step 1: schema -------------------------------------------------------------------------------

    @Test
    fun `an unknown config attribute fails the schema step with the path - and un-falsifies when removed`() {
        val bad =
            body { it.obj("config.data[0]").put("bogus_attr", 1) }
        val report = check.run(WORKSPACE, bad)
        report.ok shouldBe false
        report.failures.single().let {
            it.step shouldBe "schema"
            it.code shouldBe VisualizationErrorCodes.CONFIG_SCHEMA_INVALID
            it.path shouldBe "config.data[0].bogus_attr"
        }
        check.run(WORKSPACE, body()).ok shouldBe true
    }

    @Test
    fun `an unsupported trace type fails the schema step`() {
        val report = check.run(WORKSPACE, body { it.obj("config.data[0]").put("type", "treemap") })
        report.failures.single().let {
            it.code shouldBe VisualizationErrorCodes.CONFIG_SCHEMA_INVALID
            it.path shouldBe "config.data[0].type"
        }
    }

    // ---- step 2: bindings -----------------------------------------------------------------------------

    @Test
    fun `a binding column outside the output contract fails - and un-falsifies when the contract names it`() {
        val bad = body { it.obj("bindings").put("data[0].x", "unknown_column") }
        val report = check.run(WORKSPACE, bad)
        val failure = report.failures.single { it.step == "bindings" && it.path == "bindings.data[0].x" }
        failure.code shouldBe VisualizationErrorCodes.BINDING_UNBOUND
        failure.message shouldContain "not in the output contract"
        // Falsification: `month_labels` IS in the faked contract.
        check.run(WORKSPACE, body { it.obj("bindings").put("data[0].x", "month_labels") }).ok shouldBe true
    }

    @Test
    fun `a numeric path bound to a text column fails the per-path type table - the table is pinned`() {
        // y is a numeric leaf (BindingTypes); `month_labels` is STRING.
        val bad = body { it.obj("bindings").put("data[0].y", "month_labels") }
        val report = check.run(WORKSPACE, bad)
        val failure = report.failures.single { it.path == "bindings.data[0].y" }
        failure.code shouldBe VisualizationErrorCodes.BINDING_UNBOUND
        failure.message shouldContain "not accepted at this path"
        // Falsification: the DECIMAL column binds y; and the table itself is the pinned one.
        check.run(WORKSPACE, body()).ok shouldBe true
        BindingTypes.accepts("data[0].y", LogicalType.INTEGER) shouldBe true
        BindingTypes.accepts("data[0].y", LogicalType.BIGDECIMAL) shouldBe false // wire STRING, renders as text
        BindingTypes.accepts("layout.title.text", LogicalType.STRING) shouldBe true // not a numeric leaf
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
    fun `a DRAFT transform pin is valid here - the authoring path, not the runtime's RELEASED-only rule`() {
        val draftFacts =
            TemplateContractFacts { _, ref ->
                ValidatorFakes.CONTRACT.let {
                    TemplatePin.Transform(PipelineVersionStatus.DRAFT, it)
                }
            }
        val draftCheck =
            VisualizationMechanicalCheck(
                renderers = RendererConfigValidators.deep(),
                fixtures = Fakes.evaluator,
                templates = draftFacts,
                rendered = RenderedStateCheck.NOT_AVAILABLE,
            )
        withClue("a DRAFT pin passes the fixture run") { draftCheck.run(WORKSPACE, body()).ok shouldBe true }
        // And the same run REFUSES when the pin is gone entirely: the evaluator's refusal names it.
        Fakes.evaluatorRefusal =
            FixtureEvaluation.Refused("template.not_found", "Template 'finance/transforms/revenue_bars' does not exist.")
        draftCheck.run(WORKSPACE, body()).ok shouldBe false
        Fakes.reset()
    }

    @Test
    fun `with no transform the fixture VALUES are judged through the wire-form rules - and un-falsify`() {
        val direct =
            """
            {"name":"finance/visualizations/direct","display_name":"Direct",
             "renderer":{"kind":"plotly","version":"4"},
             "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                             {"name":"amount","type":"DECIMAL","nullable":false}]}},
             "config":{"data":[{"type":"bar","x":"$.month","y":"$.amount"}]},
             "bindings":{"data[0].x":"month","data[0].y":"amount"},
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
    fun `trace_count is compared with config data length - and un-falsifies at the matching count`() {
        val bad = body { objAssertion(it, 0, """{"kind":"trace_count","equals":7}""") }
        check.run(WORKSPACE, bad).failures.single { it.step == "assertions" }.let {
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.message shouldContain "renders 1 traces"
        }
        check.run(WORKSPACE, body { objAssertion(it, 0, """{"kind":"trace_count","equals":1}""") }).ok shouldBe true
    }

    @Test
    fun `a no_data case with produced rows fails - and passes at zero rows`() {
        val noData = body { objAssertion(it, 0, """{"kind":"no_data"}""") }
        check
            .run(WORKSPACE, noData)
            .failures
            .single { it.step == "assertions" }
            .message shouldContain "produced 2 rows"
        Fakes.evaluatorReturnsRows.clear()
        check
            .run(WORKSPACE, noData)
            .failures
            .filter { it.step == "assertions" }
            .shouldBeEmpty()
        Fakes.reset()
    }

    @Test
    fun `text_visible must appear in the configuration or the bound values - and un-falsifies`() {
        val absent = body { objAssertion(it, 0, """{"kind":"text_visible","text":"NEITHER"}""") }
        check
            .run(WORKSPACE, absent)
            .failures
            .single { it.step == "assertions" }
            .message shouldContain "appears neither"
        // "Feb" is a bound value of the case's evaluation; "Revenue" is the layout title text.
        check.run(WORKSPACE, body { objAssertion(it, 0, """{"kind":"text_visible","text":"Feb"}""") }).ok shouldBe true
        check.run(WORKSPACE, body { objAssertion(it, 0, """{"kind":"text_visible","text":"Revenue"}""") }).ok shouldBe true
    }

    // ---- step 5: rendered state -----------------------------------------------------------------------

    @Test
    fun `the default rendered state records not_available - never success, never a browser claim`() {
        RenderedStateCheck.NOT_AVAILABLE.state(WORKSPACE, body(), "twelve months") shouldBe "not_available"
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
                body {
                    it.obj("config.data[0]").put("bogus", true) // schema step fails
                    objAssertion(it, 0, """{"kind":"trace_count","equals":9}""") // assertions step fails
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
