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
 * #9 slice 2, A.3 — the schedule form (ui-screens.md §4.20) in a real browser: create through the
 * form with the §20 refusals beside their fields (the name grammar, the binder's per-parameter
 * message), the detail's next five equal to §20.9 and the form's preview equal to both, and an
 * edit against a stale revision — the 409 rendered in the form, reloaded, saved.
 */
class SchedulesFormBrowserTest : SchedulesBrowserSuite() {
    // ------------------------------------------------------------------ A.3 create, A.2 detail

    @Test
    fun `create through the form - field-level refusals, then the detail's next five equal upcoming`() {
        startTrace()
        val root = ready("schnew")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        openSchedules()

        page.locator("[data-verb='schedule-create']").first().click()
        val form = page.locator("#sch-dialog [data-sch-form]")
        form.waitFor()
        // The browser's own zone is preselected; this test's schedule pins New York.
        page.selectOption("#sch-f-timezone", "America/New_York")
        page.fill("#sch-f-name", "Not A Valid Name")
        page.fill("#sch-f-pipeline", pipeline)
        // The pipeline's current version declares one required parameter: its field appears.
        page.locator("#sch-f-param-$PARAMETER").waitFor()
        // Typed AT ONCE — the pipeline field's blur fires `change` right here, and the lane walk
        // lost a value typed at this moment to a redundant re-read re-rendering the fields. Every
        // read the form makes must settle and leave the typed text in place. ("5x0" is also the
        // binder's refusal below: not an INTEGER, and never silently truncated to 5.)
        page.fill("#sch-f-param-$PARAMETER", "5x0")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        page.inputValue("#sch-f-param-$PARAMETER") shouldBe "5x0"
        page.selectOption("#sch-f-preset", "weekly")
        page.selectOption("#sch-f-dow", "1")
        page.fill("#sch-f-time", "06:30")
        page.locator("#sch-f-cron").inputValue() shouldBe "30 6 * * 1"
        // The preview says which pattern and zone it was computed for — wait for THIS one.
        val previewList = page.locator("[data-slot=preview][data-cron='30 6 * * 1'][data-timezone='America/New_York']")
        previewList.waitFor()
        val preview = previewList.locator("li").evaluateAll("ls => ls.map(l => l.getAttribute('data-at'))")

        // 1: the grammar's refusal, beside the name.
        page.locator("[data-verb='schedule-save']").click()
        page.locator("[data-field-error='name']:not([hidden])").waitFor()
        page.locator("[data-field-error='name']").textContent().isNotBlank() shouldBe true
        // 2: the binder's refusal (invalid_parameter_type), beside the parameter it names.
        page.fill("#sch-f-name", "$root/nightly/rows")
        page.locator("[data-verb='schedule-save']").click()
        page.locator("[data-param-name='$PARAMETER'] [data-param-error]:not([hidden])").waitFor()
        page.inputValue("#sch-f-param-$PARAMETER") shouldBe "5x0"
        // 3: a valid form saves; the dialog closes and the detail shows the new schedule.
        page.fill("#sch-f-param-$PARAMETER", "50")
        page.locator("[data-verb='schedule-save']").click()
        form.waitFor(
            com.microsoft.playwright.Locator
                .WaitForOptions()
                .setState(WaitForSelectorState.DETACHED),
        )
        detail().waitFor()
        page.locator("#schedule-detail [data-slot='leaf']").textContent() shouldBe "rows"
        page.locator("#schedule-detail [data-slot='when']").textContent() shouldBe "Every Monday at 06:30"
        page.locator("#schedule-detail [data-slot='timezone']").textContent() shouldBe "America/New_York"
        page.locator("#schedule-detail [data-slot='parameters'] tr").evaluateAll("rs => rs.map(r => r.textContent)") shouldBe
            listOf("${PARAMETER}50")

        // The next five on the page are §20.9's, and the form's preview (§20.3) said the same.
        val id = page.locator("#schedule-detail [data-schedule-id]").getAttribute("data-schedule-id")
        val upcoming = send(page, "GET", "/api/v1/schedules/$id/upcoming?count=5").body
        val fromRest = Regex(""""at"\s*:\s*"([^"]+)"""").findAll(upcoming).map { it.groupValues[1] }.toList()
        val shown =
            page
                .locator(
                    "#schedule-detail [data-slot='occurrences'] tr",
                ).evaluateAll("rs => rs.map(r => r.getAttribute('data-at'))")
        shown shouldBe fromRest
        preview shouldBe fromRest
        fromRest.size shouldBe 5
        // The address bar carries the selection (a reload lands on it).
        page.url() shouldContain "id=$id"
    }

    // ------------------------------------------------------------------ A.3 edit with a stale revision

    @Test
    fun `an edit against a stale revision renders the 409 in the form, reloads, and saves`() {
        startTrace()
        val root = ready("schstale")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)
        openSchedules("?id=$id")
        detail().waitFor()

        page.locator("[data-verb='schedule-edit']").click()
        page.locator("#sch-dialog [data-sch-form]").waitFor()
        page.locator("#sch-f-param-$PARAMETER").waitFor()
        // Someone else saves first (revision 1 → 2).
        val other =
            send(
                page,
                "PUT",
                "/api/v1/schedules/$id",
                """{"name":"$root/nightly/rows","payload":{"pipeline":"$pipeline","version":"current"},"parameters":{"$PARAMETER":99},""" +
                    """"cron":"$YEARLY","timezone":"Europe/London"}""",
                ifMatch = etag(page, id),
            )
        other.status shouldBe 200

        page.fill("#sch-f-param-$PARAMETER", "11")
        page.locator("[data-verb='schedule-save']").click()
        val conflict = page.locator("#sch-dialog [data-slot='conflict']:not([hidden])")
        conflict.waitFor()
        conflict.textContent() shouldContain "revision 1"
        conflict.textContent() shouldContain "revision 2"

        page.locator("[data-sch-action='reload-form']").click()
        page.waitForFunction(
            "() => document.getElementById('sch-f-timezone') && document.getElementById('sch-f-timezone').value === 'Europe/London'",
        )
        page.locator("#sch-f-param-$PARAMETER").waitFor()
        page.inputValue("#sch-f-param-$PARAMETER") shouldBe "99"
        page.fill("#sch-f-param-$PARAMETER", "11")
        page.locator("[data-verb='schedule-save']").click()
        page
            .locator(
                "#sch-dialog [data-sch-form]",
            ).waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(WaitForSelectorState.DETACHED),
            )
        page.waitForFunction("() => (document.querySelector('#schedule-detail [data-slot=revision]') || {}).textContent === 'revision 3'")
        page.locator("#schedule-detail [data-slot='parameters'] tr").evaluateAll("rs => rs.map(r => r.textContent)") shouldBe
            listOf("${PARAMETER}11")
    }
}
