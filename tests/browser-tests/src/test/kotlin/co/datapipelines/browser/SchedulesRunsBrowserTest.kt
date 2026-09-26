package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.PARAMETER
import co.datapipelines.browser.ScheduleFixtures.YEARLY
import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.etag
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2, A.4 — a schedule's runs (ui-screens.md §4.20) in a real browser against the real
 * dispatcher: Run now records a manual run the list follows to its end, and the run's Messages
 * merge the scheduler's own trail with the execution's durable events in ONE time order, each line
 * labelled by its source (the record's R10).
 */
class SchedulesRunsBrowserTest : SchedulesBrowserSuite() {
    // ------------------------------------------------------------------ A.4 Run now and the merged trail

    @Test
    fun `Run now - a run appears and finishes, and its Messages merge the scheduler's trail with the pipeline's events`() {
        startTrace()
        val root = ready("schrun")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)
        openSchedules("?id=$id")
        detail().waitFor()

        page.waitForResponse({ it.url().endsWith("/api/v1/schedules/$id/run") && it.status() == 202 }) {
            page.locator("[data-verb='schedule-run']").click()
        }
        val row = page.locator("#schedule-detail .sch-runrow").first()
        row.waitFor()
        row.locator("[data-slot='origin']").textContent() shouldBe "manual"
        // The deep link revealed the schedule's leaf in the tree, selected.
        page.locator("#schedule-tree-pane [data-leaf-name='$root/nightly/rows'][aria-selected='true']").waitFor()
        // The dispatcher runs it as the system identity; the list follows it to its end.
        page.waitForFunction(
            "() => (document.querySelector('#schedule-detail .sch-runrow') || {}).getAttribute && " +
                "document.querySelector('#schedule-detail .sch-runrow').getAttribute('data-state') === 'succeeded'",
            null,
            Page.WaitForFunctionOptions().setTimeout(60_000.0),
        )
        row.locator("[data-slot='execution']").getAttribute("href")!! shouldContain "/executions/"

        row.locator("[data-sch-action='open-run']").click()
        val dlg = page.locator("#sch-dialog .sch-run-dialog")
        dlg.waitFor()
        page.waitForFunction("() => document.querySelectorAll('#sch-dialog [data-slot=messages] tr[data-source=pipeline]').length > 0")
        val sources =
            dlg
                .locator(
                    "[data-slot='messages'] tr",
                ).evaluateAll("rs => rs.map(r => r.getAttribute('data-source') + ':' + r.querySelector('[data-slot=what]').textContent)")

        @Suppress("UNCHECKED_CAST")
        val lines = sources as List<String>
        withClue(lines.joinToString("\n")) {
            lines.first() shouldBe "scheduler:recorded"
            lines.contains("scheduler:execution_started") shouldBe true
            lines.contains("pipeline:execution_started") shouldBe true
            lines.contains("pipeline:pipeline_completed") shouldBe true
            lines.last() shouldBe "scheduler:finished"
        }
        // ONE time order across both logs (R10): every line at or after the one above it.
        val times = dlg.locator("[data-slot='messages'] time").evaluateAll("ts => ts.map(t => Date.parse(t.getAttribute('datetime')))")

        @Suppress("UNCHECKED_CAST")
        val instants = (times as List<Number>).map { it.toLong() }
        instants shouldBe instants.sorted()
        page.url() shouldContain "run="
        page.keyboard().press("Escape")
        dlg.waitFor(
            com.microsoft.playwright.Locator
                .WaitForOptions()
                .setState(WaitForSelectorState.DETACHED),
        )
    }
}
