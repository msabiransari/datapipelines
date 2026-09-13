package co.datapipelines.executor

import co.datapipelines.calculators.CalculatorExample
import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorOutput
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.typesystem.LogicalType
import java.time.LocalDate

/**
 * The throwaway multi-output fixture kind (121) — the executor-side twin of pipeline-contract's
 * `Fixtures.WINDOW_KIND`: two DATE outputs, `start` and `end`, computed deterministically as
 * the calendar-month bounds of the input date so a test's expectation never depends on the day
 * it runs. It lives here and not in `CalculatorRegistry` because the catalog is additive
 * forever and documented per kind — the two real period kinds land in 121 commit C with their
 * own tests and docs.
 *
 * Deliberately named without the `Test` suffix so the module's `verifyTestsExecuted` guard
 * counts only real test classes — the convention `ExecutorHarness` and `Fixtures` follow.
 */
internal object WindowKindFixture : CalculatorKind {
    override val kind = "period_window"
    override val displayName = "Period window"
    override val description = "The first and last day of the month containing a date."
    override val phrases = listOf("this month")
    override val inputs = listOf(CalculatorInput("date", LogicalType.DATE, "The date whose month to bound."))
    override val output: LogicalType? = null
    override val outputs =
        listOf(
            CalculatorOutput("start", LogicalType.DATE, "The month's first day."),
            CalculatorOutput("end", LogicalType.DATE, "The month's last day."),
        )
    override val example = CalculatorExample(mapOf("date" to "2026-08-14"), "start=2026-08-01, end=2026-08-31")

    override fun evaluate(values: Map<String, Any?>): Any {
        val date = values["date"] as LocalDate
        return mapOf("start" to date.withDayOfMonth(1), "end" to date.withDayOfMonth(date.lengthOfMonth()))
    }

    /** The registry plus this fixture — the lookup a test hands to the executor's kind seam. */
    val lookup: (String) -> CalculatorKind? = { name -> if (name == kind) this else CalculatorRegistry.find(name) }
}
