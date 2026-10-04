package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.PARAMETER
import co.datapipelines.browser.ScheduleFixtures.YEARLY
import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.draftPipeline
import co.datapipelines.browser.ScheduleFixtures.etag
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2, A.3 — the schedule form (ui-screens.md §4.20) in a real browser: create through the
 * form with the §20 refusals beside their fields IN THE CATALOGUE'S OWN WORDS (#280 — the family
 * line satisfies nobody), a draft-only pipeline named at pick time and refused at save with the
 * same release-first sentence, the detail's next five equal to §20.9 and the form's preview equal
 * to both, and an edit against a stale revision — the 409 rendered in the form, reloaded, saved.
 *
 * The expected words are LITERALS here, not `ApiErrorCatalog.userMessageFor`: `modules/web`
 * reaches this module only through `modules:app`'s `implementation` dependency, so its classes
 * are not on this module's compile classpath. The slot's text flows catalogue → envelope →
 * `err.user_message` → DOM, so a literal equality here pins the catalogue row end to end.
 */
class SchedulesFormBrowserTest : SchedulesBrowserSuite() {
    /** The catalogue's `schedule.validation.target_not_released` line (#280) — the words at the pipeline field. */
    private val releaseFirst = "This pipeline has no released version yet. Release it (or switch its current version), then schedule it."

    @Test
    fun `notification settings round trip and refusal appears beside the field while mail stays off`() {
        startTrace()
        val root = ready("schnotify")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        openSchedules()
        page.locator("[data-verb='schedule-create']").first().click()
        val form = page.locator("#sch-dialog [data-sch-form]")
        form.waitFor()
        page.fill("#sch-f-name", "$root/nightly/rows")
        page.fill("#sch-f-pipeline", pipeline)
        page.locator("#sch-f-param-$PARAMETER").waitFor()
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        page.fill("#sch-f-param-$PARAMETER", "50")
        page.locator("#sch-dialog [role='note']").textContent() shouldContain "no mail is sent"
        page.isChecked("input[name='notification_event'][value='start']") shouldBe false
        page.isChecked("input[name='notification_event'][value='failure']") shouldBe true
        page.fill("#sch-f-recipients", "invalid")
        page.locator("[data-verb='schedule-save']").click()
        page.locator("[data-field-error='notifications']:not([hidden])").waitFor()
        page.fill("#sch-f-recipients", "first@example.com, second@example.com")
        page.check("input[name='notification_event'][value='start']")
        page.uncheck("input[name='notification_event'][value='failure']")
        page.locator("[data-verb='schedule-save']").click()
        form.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
        detail().waitFor()
        val summary = page.locator("#schedule-detail [data-slot='notifications']").textContent()
        summary shouldContain "2 recipients · Started, Outcome unknown, Schedule blocked"
        summary shouldContain "mail not enabled on this deployment"
        summary.contains("example.com") shouldBe false
        page.locator("[data-verb='schedule-edit']").click()
        form.waitFor()
        page.inputValue("#sch-f-recipients") shouldBe "first@example.com, second@example.com"
        page.isChecked("input[name='notification_event'][value='start']") shouldBe true
        page.isChecked("input[name='notification_event'][value='failure']") shouldBe false
        page.locator("#sch-dialog [role='note']").textContent() shouldContain "no mail is sent"
    }

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

        // 1: the grammar's refusal, beside the name, in the catalogue's own words (#280) —
        // "No leading slash" is the rule's words; the family line the field showed before #280
        // says none of them.
        page.locator("[data-verb='schedule-save']").click()
        page.locator("[data-field-error='name']:not([hidden])").waitFor()
        page.locator("[data-field-error='name']").textContent() shouldContain "No leading slash"
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

    // ------------------------------------------------------------------ #280 the draft-only pipeline

    @Test
    fun `a draft-only pipeline is named at pick time and refused at save with the release-first words`() {
        startTrace()
        val root = ready("schdraft")
        val draft = "$root/jobs/draftonly"
        draftPipeline(page, draft)
        val released = "$root/jobs/released"
        releasedPipeline(page, released)
        openSchedules()

        page.locator("[data-verb='schedule-create']").first().click()
        val form = page.locator("#sch-dialog [data-sch-form]")
        form.waitFor()
        page.fill("#sch-f-name", "$root/nightly/draft")
        // Typing the draft-only pipeline's full name resolves it: the release-first words are at
        // the pipeline field BEFORE any save — the parameters area carries nothing for it.
        page.fill("#sch-f-pipeline", draft)
        val pipelineError = page.locator("[data-field-error='pipeline']:not([hidden])")
        pipelineError.waitFor()
        pipelineError.textContent() shouldContain "has no released version yet"
        pipelineError.textContent() shouldBe releaseFirst
        page.locator("[data-field-error='parameters']").isHidden() shouldBe true

        // The save still goes to the server (the authority) and its refusal lands in the same
        // slot with the same words — the round-trip completed when the submit button is enabled
        // again and the dialog is still open.
        page.locator("[data-verb='schedule-save']").click()
        page.waitForFunction("() => { const b = document.querySelector(\"[data-verb='schedule-save']\"); return b && !b.disabled; }")
        form.waitFor()
        page.locator("[data-field-error='pipeline']:not([hidden])").waitFor()
        val afterSave = page.locator("[data-field-error='pipeline']").textContent()
        afterSave shouldContain "has no released version yet"
        afterSave shouldBe releaseFirst

        // Picking the released pipeline clears the slot; the save then succeeds.
        page.fill("#sch-f-pipeline", released)
        page.locator("#sch-f-param-$PARAMETER").waitFor()
        page.waitForFunction("() => { const p = document.querySelector(\"[data-field-error='pipeline']\"); return p && p.hidden; }")
        page.fill("#sch-f-param-$PARAMETER", "50")
        page.locator("[data-verb='schedule-save']").click()
        form.waitFor(
            com.microsoft.playwright.Locator
                .WaitForOptions()
                .setState(WaitForSelectorState.DETACHED),
        )
        detail().waitFor()
        page.locator("#schedule-detail [data-slot='leaf']").textContent() shouldBe "draft"
        page.locator("#schedule-detail [data-slot='parameters'] tr").evaluateAll("rs => rs.map(r => r.textContent)") shouldBe
            listOf("${PARAMETER}50")
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
