package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ChartJudge.Verdict
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * §4.12's table over the [ChartEngine] port (js-rendered record §4.12; brief 986 D4, A.2): each assertion kind judged
 * from the field the table names, green and red per `state`, over a FAKE engine that answers a scripted output — the
 * real engine's answers for the same cases are application's `ChartJudgeEngineTest`. Then the seam: the canonical
 * input (640 × 400, the case's rows as columns typed by the output contract, the document without its tests), an
 * engine failure as ONE case failure that is not judged again, and the host without the runtime.
 */
class ChartJudgeTest {
    private fun output(
        state: String,
        marks: Int = 0,
        texts: List<Pair<String, String?>> = emptyList(),
        refusal: String? = null,
    ): CanonicalRender.Drawn {
        val json = ArtifactJson.mapper.createObjectNode()
        json.put("state", state)
        json.putObject("summary").apply {
            put("marks", marks)
            refusal?.let { putObject("refusal").put("code", it) }
        }
        val primitives = json.putArray("primitives")
        primitives.addObject().put("k", "rect")
        texts.forEach { (text, shown) ->
            primitives.addObject().apply {
                put("k", "text")
                put("text", text)
                shown?.let { put("shown", it) }
            }
        }
        return CanonicalRender.Drawn(ChartOutput(json))
    }

    // ---- the table, kind by kind ----------------------------------------------------------------------

    @Test
    fun `rendered holds on a rendered state with a mark, or on no-data over zero rows - and fails otherwise`() {
        ChartJudge.rendered(output(ChartOutput.RENDERED, marks = 1), rows = 3) shouldBe Verdict.Holds
        ChartJudge.rendered(output(ChartOutput.NO_DATA), rows = 0) shouldBe Verdict.Holds
        (ChartJudge.rendered(output(ChartOutput.NO_DATA), rows = 2) as Verdict.Fails).message shouldBe
            "The chart drew no mark at 640 × 400 (state no-data) over the case's 2 rows."
        (ChartJudge.rendered(output(ChartOutput.RENDERED, marks = 0), rows = 2) as Verdict.Fails).message shouldBe
            "The chart was not drawn at 640 × 400 (state rendered)."
        (ChartJudge.rendered(output(ChartOutput.REFUSED, refusal = "too_many_series"), rows = 9) as Verdict.Fails).message shouldBe
            "The chart was not drawn at 640 × 400 (state refused, too_many_series)."
        (ChartJudge.rendered(output(ChartOutput.TOO_SMALL), rows = 1) as Verdict.Fails).message shouldContain "state too-small"
    }

    @Test
    fun `mark_count reads summary marks on rendered and no-data - refused and too-small fail it`() {
        ChartJudge.markCount(output(ChartOutput.RENDERED, marks = 2), equals = 2) shouldBe Verdict.Holds
        ChartJudge.markCount(output(ChartOutput.NO_DATA), equals = 0) shouldBe Verdict.Holds
        (ChartJudge.markCount(output(ChartOutput.RENDERED, marks = 2), equals = 7) as Verdict.Fails).message shouldBe
            "The case's rows draw 2 marks; the case asserts 7."
        (ChartJudge.markCount(output(ChartOutput.NO_DATA), equals = 2) as Verdict.Fails).message shouldBe
            "The case's rows draw 0 marks; the case asserts 2."
        listOf(ChartOutput.REFUSED, ChartOutput.TOO_SMALL).forEach { state ->
            withClue(state) {
                (ChartJudge.markCount(output(state, marks = 3), equals = 3) as Verdict.Fails).message shouldContain "was not drawn"
            }
        }
    }

    @Test
    fun `no_data holds only on the no-data state`() {
        ChartJudge.noData(output(ChartOutput.NO_DATA)) shouldBe Verdict.Holds
        (ChartJudge.noData(output(ChartOutput.RENDERED, marks = 1)) as Verdict.Fails).message shouldBe
            "The case expects the empty state; its rows draw 1 marks."
        listOf(ChartOutput.REFUSED, ChartOutput.TOO_SMALL).forEach { state ->
            withClue(state) { (ChartJudge.noData(output(state)) as Verdict.Fails).message shouldContain "was not drawn at 640 × 400" }
        }
    }

    @Test
    fun `text_visible reads the SHOWN text - a cut label shows its cut, not its full text`() {
        val drawn = output(ChartOutput.RENDERED, marks = 1, texts = listOf("Revenue" to null, "Northern region" to "Northern…"))
        ChartJudge.textVisible(drawn, "Revenue") shouldBe Verdict.Holds
        ChartJudge.textVisible(drawn, "venu") shouldBe Verdict.Holds
        ChartJudge.textVisible(drawn, "Northern") shouldBe Verdict.Holds
        (ChartJudge.textVisible(drawn, "region") as Verdict.Fails).message shouldBe
            "The text is not in any text the chart shows at 640 × 400."
        (ChartJudge.textVisible(output(ChartOutput.NO_DATA), "Revenue")).let { it is Verdict.Fails } shouldBe true
    }

    @Test
    fun `no render is unjudged - a failed render or a case whose rows failed is not judged again`() {
        val judges =
            listOf<(CanonicalRender?) -> Verdict>(
                { ChartJudge.rendered(it, 1) },
                { ChartJudge.markCount(it, 1) },
                { ChartJudge.noData(it) },
                { ChartJudge.textVisible(it, "x") },
            )
        judges.forEachIndexed { index, judge ->
            withClue("judge $index") {
                judge(CanonicalRender.NotAvailable) shouldBe Verdict.Unjudged
                judge(CanonicalRender.Failed("statements", "the statement limit")) shouldBe Verdict.AlreadyFailed
                judge(null) shouldBe Verdict.AlreadyFailed
            }
        }
    }

    // ---- the seam, through the check ------------------------------------------------------------------

    /** A fake engine that records its inputs and answers [answer]. */
    private class FakeEngine(
        var answer: (ChartInput) -> ChartOutput,
    ) : ChartEngine {
        val calls = mutableListOf<Pair<ChartInput, ChartFrame>>()

        override fun render(
            input: ChartInput,
            frame: ChartFrame,
        ): ChartOutput {
            calls += input to frame
            return answer(input)
        }

        override fun picture(
            input: ChartInput,
            frame: ChartFrame,
            theme: ChartTheme,
        ): ChartPicture = error("the judge never asks for a picture")
    }

    private val rows =
        listOf(
            mapOf("month_labels" to "Jan", "amounts" to 10.5),
            mapOf("month_labels" to "Feb", "amounts" to 12.0),
        )

    private fun check(engine: ChartEngine) =
        VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.default(),
            fixtures = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(rows) },
            templates = TemplateContractFacts { _, _ -> TemplatePin.Transform(PipelineVersionStatus.RELEASED, ValidatorFakes.CONTRACT) },
            rendered = EngineRenderedStateCheck(engine),
        )

    private fun body(vararg assertions: String): VisualizationBody {
        val tree = DocumentFixtures.visualization()
        val list = tree.obj("tests.cases[0]").get("assertions") as ArrayNode
        list.removeAll()
        assertions.forEach { list.add(ArtifactJson.mapper.readTree(it)) }
        return ValidatorFakes.visualizationDocument(tree).body
    }

    @Test
    fun `the engine gets the canonical input - 640 x 400, the rows as typed columns, the document without its tests`() {
        val engine = FakeEngine { output(ChartOutput.RENDERED, marks = 2).output }
        val report = check(engine).run(ValidatorFakes.WORKSPACE, body("""{"kind":"rendered"}""", """{"kind":"mark_count","equals":2}"""))
        withClue(report.failures) { report.ok shouldBe true }
        report.unjudged.shouldBeEmpty()
        report.cases.values
            .single()
            .rendered shouldBe "rendered"
        val (input, frame) = engine.calls.single()
        frame shouldBe ChartFrame.CANONICAL
        input.type shouldBe ChartType.BAR
        input.columns shouldBe mapOf("amounts" to listOf(10.5, 12.0), "month_labels" to listOf("Jan", "Feb"))
        input.types shouldBe mapOf("amounts" to LogicalType.DECIMAL, "month_labels" to LogicalType.STRING)
        (input.document as ObjectNode).has("tests") shouldBe false
        input.document
            .path("config")
            .path("mark")
            .path("type")
            .asText() shouldBe "bar"
    }

    @Test
    fun `each verdict lands on its assertion's path - a red count fails the case, the state is the engine's`() {
        val engine = FakeEngine { output(ChartOutput.RENDERED, marks = 2, texts = listOf("Revenue" to null)).output }
        val report =
            check(engine).run(
                ValidatorFakes.WORKSPACE,
                body(
                    """{"kind":"rendered"}""",
                    """{"kind":"mark_count","equals":7}""",
                    """{"kind":"text_visible","text":"Revenue"}""",
                    """{"kind":"text_visible","text":"NEITHER"}""",
                    """{"kind":"no_data"}""",
                ),
            )
        report.failures.map { it.path to it.message } shouldBe
            listOf(
                "tests.cases[0].assertions[1]" to "The case's rows draw 2 marks; the case asserts 7.",
                "tests.cases[0].assertions[3]" to "The text is not in any text the chart shows at 640 × 400.",
                "tests.cases[0].assertions[4]" to "The case expects the empty state; its rows draw 2 marks.",
            )
        report.failures.all { it.step == "assertions" && it.code == VisualizationErrorCodes.TEST_CASE_INVALID } shouldBe true
        report.unjudged.shouldBeEmpty()
        engine.calls.size shouldBe 1
    }

    @Test
    fun `an engine failure is ONE case failure naming its limit - its assertions are not judged again`() {
        val engine = FakeEngine { throw ChartEngineFailure("statements", "the statement limit") }
        val report =
            check(engine).run(
                ValidatorFakes.WORKSPACE,
                body("""{"kind":"rendered"}""", """{"kind":"mark_count","equals":2}""", """{"kind":"no_data"}"""),
            )
        report.failures.single().let {
            it.step shouldBe "assertions"
            it.code shouldBe VisualizationErrorCodes.TEST_CASE_INVALID
            it.path shouldBe "tests.cases[0]"
            it.message shouldContain "the statements limit"
        }
        report.unjudged.shouldBeEmpty()
        report.cases.values
            .single()
            .rendered shouldBe "not_rendered"
    }

    @Test
    fun `a case whose rows failed is not drawn - its state is not_rendered and the engine is never asked`() {
        val engine = FakeEngine { output(ChartOutput.RENDERED, marks = 2).output }
        val failing =
            VisualizationMechanicalCheck(
                renderers = RendererConfigValidators.default(),
                fixtures = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(listOf(mapOf("month_labels" to "Jan"))) },
                templates =
                    TemplateContractFacts { _, _ -> TemplatePin.Transform(PipelineVersionStatus.RELEASED, ValidatorFakes.CONTRACT) },
                rendered = EngineRenderedStateCheck(engine),
            )
        val report = failing.run(ValidatorFakes.WORKSPACE, body("""{"kind":"rendered"}"""))
        report.failures.single().step shouldBe "fixtures"
        report.cases.values
            .single()
            .rendered shouldBe "not_rendered"
        engine.calls.shouldBeEmpty()
    }

    @Test
    fun `without the runtime nothing is rendered - the four chart kinds are unjudged and the state is not_available`() {
        val report =
            VisualizationMechanicalCheck(
                renderers = RendererConfigValidators.default(),
                fixtures = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(rows) },
                templates =
                    TemplateContractFacts { _, _ -> TemplatePin.Transform(PipelineVersionStatus.RELEASED, ValidatorFakes.CONTRACT) },
            ).run(
                ValidatorFakes.WORKSPACE,
                body(
                    """{"kind":"rendered"}""",
                    """{"kind":"mark_count","equals":7}""",
                    """{"kind":"no_data"}""",
                    """{"kind":"text_visible","text":"NEITHER"}""",
                ),
            )
        report.ok shouldBe true
        report.failures.shouldBeEmpty()
        report.unjudged.map { it.kind } shouldBe listOf("rendered", "mark_count", "no_data", "text_visible")
        report.cases.values
            .single()
            .rendered shouldBe "not_available"
        RenderedStateCheck.NOT_AVAILABLE.available shouldBe false
        EngineRenderedStateCheck(FakeEngine { error("unused") }).available shouldBe true
    }
}
