package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.DATE_PARAMETER
import co.datapipelines.browser.ScheduleFixtures.PARAMETER
import co.datapipelines.browser.ScheduleFixtures.createBoundSchedule
import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.releasedDatePipeline
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * #9 slice 3 — the bindings in a real browser (ui-screens.md §4.20): a DATE parameter's field
 * offers Fixed value / Today / Yesterday (other types keep the plain field), a chosen Today
 * round-trips through `payload.parameter_bindings` (edit re-opens with the preset selected),
 * a run's detail shows what its bindings resolved to beside the frozen parameters, and the new
 * surfaces hold light/dark at the three widths. Every write goes through the page's own REST
 * path; the run is executed by the context's real dispatcher.
 */
class SchedulesBindingsBrowserTest : SchedulesBrowserSuite() {
    private fun source(name: String) = page.locator("[data-param-name='$name'] [data-param-source]")

    @Test
    fun `a DATE parameter offers the presets, Today hides the fixed field, and the choice round-trips`() {
        val root = ready("schbind")
        val pipeline = "$root/jobs/daily"
        releasedDatePipeline(page, pipeline)
        openSchedules()

        page.locator("[data-verb='schedule-create']").first().click()
        val form = page.locator("#sch-dialog [data-sch-form]")
        form.waitFor()
        page.fill("#sch-f-name", "$root/nightly/daily")
        page.fill("#sch-f-pipeline", pipeline)
        page.locator("#sch-f-param-$DATE_PARAMETER").waitFor()
        // The DATE field's source selector: Fixed value is the default, the exact allowlist behind it.
        source(DATE_PARAMETER).waitFor()
        source(DATE_PARAMETER).inputValue() shouldBe ""
        source(DATE_PARAMETER)
            .locator("option")
            .evaluateAll("os => os.map(o => o.textContent)") shouldBe listOf("Fixed value", "Today", "Yesterday")

        // Choosing Today hides the fixed-value field BY CLASS (never an inline style), and the
        // save passes with no literal value at all — a required DATE parameter, bound.
        source(DATE_PARAMETER).selectOption("TODAY")
        // Hidden by the class, so ATTACHED — not visible — is the state to wait for.
        page
            .locator("[data-param-name='$DATE_PARAMETER'] [data-param-input].sch-value-hidden")
            .waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
        page.locator("[data-verb='schedule-save']").click()
        form.waitFor(
            Locator
                .WaitForOptions()
                .setState(WaitForSelectorState.DETACHED),
        )
        detail().waitFor()

        // The round-trip: edit re-opens with Today selected and the fixed field still hidden.
        page.locator("[data-verb='schedule-edit']").click()
        page.locator("#sch-dialog [data-sch-form]").waitFor()
        // The field itself is class-hidden (the preset survived): its source select is the visible half.
        source(DATE_PARAMETER).waitFor()
        source(DATE_PARAMETER).inputValue() shouldBe "TODAY"
        page
            .locator("[data-param-name='$DATE_PARAMETER'] [data-param-input].sch-value-hidden")
            .waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
    }

    @Test
    fun `a non-DATE parameter offers no presets - other types keep the plain field`() {
        val root = ready("schplain")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        openSchedules()

        page.locator("[data-verb='schedule-create']").first().click()
        page.locator("#sch-dialog [data-sch-form]").waitFor()
        page.fill("#sch-f-name", "$root/nightly/rows")
        page.fill("#sch-f-pipeline", pipeline)
        page.locator("#sch-f-param-$PARAMETER").waitFor()
        // The source selector is REMOVED from a non-DATE field's markup, not merely hidden.
        page.locator("[data-param-name='$PARAMETER'] [data-param-source]").count() shouldBe 0
        page.keyboard().press("Escape")
    }

    @Test
    fun `the run detail shows what the binding resolved to, beside the frozen parameters`() {
        val root = ready("schres")
        val pipeline = "$root/jobs/bound"
        releasedDatePipeline(page, pipeline)
        val id = createBoundSchedule(page, "$root/nightly/bound", pipeline)
        openSchedules("?id=$id")
        detail().waitFor()

        page.waitForResponse({ it.url().endsWith("/api/v1/schedules/$id/run") && it.status() == 202 }) {
            page.locator("[data-verb='schedule-run']").click()
        }
        page.waitForFunction(
            "() => (document.querySelector('#schedule-detail .sch-runrow') || {}).getAttribute && " +
                "document.querySelector('#schedule-detail .sch-runrow').getAttribute('data-state') === 'succeeded'",
            null,
            Page.WaitForFunctionOptions().setTimeout(60_000.0),
        )
        page.locator("#schedule-detail .sch-runrow [data-sch-action='open-run']").first().click()
        val dlg = page.locator("#sch-dialog .sch-run-dialog")
        dlg.waitFor()
        // The resolved parameters sit beside the frozen ones, inside the details toggle — open it.
        dlg.locator("details.sch-frozen summary").click()
        dlg.locator("[data-slot='resolved']:not([hidden])").waitFor()

        // What it shows is the server's own resolution: TODAY on the run's frozen reference, in
        // the schedule's zone — computed here the same way §5.2 spells it, never re-derived in
        // the browser.
        val runId = page.url().substringAfter("run=").substringBefore("&")
        val runBody = send(page, "GET", "/api/v1/schedules/$id/runs/$runId").body
        val referenceAt = Regex(""""reference_at"\s*:\s*"([^"]+)"""").find(runBody)!!.groupValues[1]
        val expected = LocalDate.ofInstant(Instant.parse(referenceAt), ZoneId.of("America/New_York")).toString()
        dlg.locator("[data-slot='resolved']").textContent() shouldContain expected
        // Beside the frozen parameters, which stay exactly what the schedule holds (empty here).
        dlg.locator("[data-slot='parameters']").textContent() shouldContain "{}"
        page.keyboard().press("Escape")
    }

    @Test
    fun `the presetted form and the resolved block hold light and dark at the three widths`() {
        val root = ready("schshots")
        val pipeline = "$root/jobs/shots"
        releasedDatePipeline(page, pipeline)
        val id = createBoundSchedule(page, "$root/nightly/shots", pipeline)

        val dir: Path = Paths.get("build", "reports", "schedules-screenshots").also { it.toFile().mkdirs() }

        fun shot(name: String) {
            page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
            page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("schedules-$name.png")))
        }

        listOf("light", "dark").forEach { mode ->
            ensureTheme(mode)
            listOf(1100 to 900, 1440 to 900, 1920 to 1080).forEach { (w, h) ->
                page.setViewportSize(w, h)
                openSchedules("?id=$id")
                page.locator("#schedule-tree-pane [data-leaf-name='$root/nightly/shots'][aria-selected='true']").waitFor()
                page.locator("[data-verb='schedule-edit']").click()
                page.locator("#sch-dialog [data-sch-form]").waitFor()
                source(DATE_PARAMETER).waitFor()
                source(DATE_PARAMETER).inputValue() shouldBe "TODAY"
                shot("bindings-form-$w-$mode")
                page.keyboard().press("Escape")
            }
            page.setViewportSize(1440, 900)
            openSchedules("?id=$id")
            detail().waitFor()
            page.locator("[data-verb='schedule-run']").click()
            page.waitForFunction(
                "() => (document.querySelector('#schedule-detail .sch-runrow') || {}).getAttribute && " +
                    "document.querySelector('#schedule-detail .sch-runrow').getAttribute('data-state') === 'succeeded'",
                null,
                Page.WaitForFunctionOptions().setTimeout(60_000.0),
            )
            page.locator("#schedule-detail .sch-runrow").first().waitFor()
            page.locator("#schedule-detail .sch-runrow [data-sch-action='open-run']").first().click()
            val dlg = page.locator("#sch-dialog .sch-run-dialog")
            dlg.waitFor()
            dlg.locator("details.sch-frozen summary").click()
            dlg.locator("[data-slot='resolved']:not([hidden])").waitFor()
            shot("bindings-run-1440-$mode")
            page.keyboard().press("Escape")
        }
    }
}
