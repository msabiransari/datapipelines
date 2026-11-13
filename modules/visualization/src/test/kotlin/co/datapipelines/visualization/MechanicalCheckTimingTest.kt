package co.datapipelines.visualization

import co.datapipelines.visualization.DocumentFixtures.obj
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The mechanical test's WORK BOUNDS, measured (the brief's §B: report the measured time, never hide
 * expensive fixture evaluation behind a timing-only assertion). The run is the worst case the reader
 * caps allow for one case — 1,000 fixture rows through the scripted evaluator — and the assertions are
 * both: a generous CI-safe ceiling AND the measured number printed for the handback's record.
 */
class MechanicalCheckTimingTest {
    @Test
    fun `a max-cased, max-rowed body runs well inside the second scale - measured, printed, and ceiling-asserted`() {
        val rows = (1..1_000).map { index -> mapOf("month_labels" to "row-$index", "amounts" to index.toDouble()) }
        val evaluator = TestFixtureEvaluator { _, _, _ -> FixtureEvaluation.Rows(rows) }
        val facts =
            TemplateContractFacts {
                _,
                _,
                ->
                TemplatePin.Transform(co.datapipelines.pipeline.PipelineVersionStatus.RELEASED, ValidatorFakes.CONTRACT)
            }
        val check =
            VisualizationMechanicalCheck(
                renderers = RendererConfigValidators.default(),
                fixtures = evaluator,
                templates = facts,
            )
        // 1,000 distinct categories: the chart model draws 1,000 marks, and the case asserts exactly that.
        val tree = DocumentFixtures.visualization().also { it.obj("tests.cases[0].assertions[1]").put("equals", 1_000) }
        val body = ValidatorFakes.visualizationDocument(tree).body

        val started = System.nanoTime()
        val report = check.run(UUID.randomUUID(), body)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        report.ok shouldBe true
        report.unjudged shouldBe emptyList()
        report.cases.values
            .single()
            .rows shouldBe 1_000
        // The ceiling is generous for CI noise; the MEASURED value is the record, printed below.
        val ceiling = Duration.ofSeconds(5)
        val measured = "MECHANICAL-CHECK TIMING: single case, 1,000 fixture rows, chart schema + model: ${elapsed.toMillis()} ms"
        println("$measured (ceiling ${ceiling.toSeconds()}s)")
        check(elapsed < ceiling) { measured }
    }
}
