package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #584 — the Schedules home in real Chromium: the rail's Schedules item lands on
 * `/overview/schedules` (the explorer stays at `/schedules`, View all), lit as the Schedules item
 * with the resource browser closed — Schedules is not a tree family. A fresh workspace reads
 * zeros, the two empty notes and an author's New schedule; one schedule later, the page counts it
 * and its Up next row opens the explorer's detail for that schedule. #639: on the v2 frame a
 * panel's info button opens its dialog section, the task row leads to the explorer, and a viewer
 * sees no New schedule, only who creates schedules. #641: the Unblock task row opens the explorer
 * filtered to blocked — a flat list, the select reading Blocked, the address bar carrying it — and
 * All brings the tree back with the parameter gone. #725: each Last run bar opens the explorer
 * filtered to its bucket (`?outcome=`), and the bar's count is the filtered list's row count — walked
 * over the two buckets this suite makes for real (a Run now that succeeds; one refused for a discarded
 * release, which the worker records `not_started`); All outcomes brings the tree back.
 */
class OverviewSchedulesBrowserTest : SchedulesBrowserSuite() {
    private fun openOverview() {
        page.navigate("$baseUrl/dashboard")
        page.click(".app-nav-link[data-nav-section='/schedules']")
        page.waitForURL("**/overview/schedules")
        page.waitForSelector(".app-overview[data-overview='schedules']")
    }

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    private fun bar(
        chart: String,
        key: String,
    ): String = page.locator("[data-chart='$chart'] [data-bar='$key'] .app-overview-bar-v").innerText().trim()

    @Test
    fun `the Schedules item lands on the home - a fresh workspace reads zeros and offers New schedule`() {
        startTrace()
        ready("schov")

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/schedules"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Schedules"
        page.locator("h1").innerText().trim() shouldBe "Schedules"
        page.locator("#app-browser").isHidden shouldBe true
        page.locator("#browser-open").isHidden shouldBe true

        listOf("schedules", "enabled", "paused", "blocked").map(::count) shouldBe listOf("0", "0", "0", "0")
        bar("last-run", "never-ran") shouldBe "0"
        page.locator("[data-overview-recent]").innerText().let { recent ->
            (recent.contains("No enabled schedule is waiting to run.") && recent.contains("No schedules yet.")) shouldBe true
        }
        page.locator("[data-overview-prompt], [data-overview-copy]").count() shouldBe 0

        // #639: the condition panel's info button opens its own dialog section; Escape closes
        // it and focus returns to the button.
        page.click("[data-overview-panel='condition'] [data-open='condition']")
        page.waitForFunction("() => document.querySelector('dialog[data-overview-dialog]').open")
        page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "How conditions are counted"
        page.locator("[data-dialog-section='condition']").isVisible shouldBe true
        page.locator("[data-dialog-section='howitworks']").isVisible shouldBe false
        page.keyboard().press("Escape")
        page.waitForFunction("() => !document.querySelector('dialog[data-overview-dialog]').open")
        page.evaluate("() => document.activeElement?.getAttribute('data-open')") shouldBe "condition"

        // New schedule opens the explorer, where the form is.
        page.click("[data-overview-new-schedule]")
        page.waitForURL("**/schedules")
        page.locator("#schedule-list:not([aria-busy])").waitFor()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/schedules"
    }

    @Test
    fun `a schedule is counted, and its Up next row opens its detail`() {
        startTrace()
        val root = ready("schovn")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)

        openOverview()
        listOf("schedules", "enabled", "paused", "blocked").map(::count) shouldBe listOf("1", "1", "0", "0")
        bar("condition", "enabled") shouldBe "1"
        bar("last-run", "never-ran") shouldBe "1"
        page.locator("[data-chart='last-run'] [data-bar='never-ran'] rect").getAttribute("width") shouldBe "100.0"
        HomeOpsShots.take(page, "schedules", "condition")

        // The first link of the sections, not the card: Continue your work opens on its task row (#639).
        page.locator("[data-overview-recent] [data-task='next'] a").getAttribute("href") shouldBe "/schedules"
        val upNext = page.locator("[data-overview-recent] .app-overview-recent li a").first()
        upNext.innerText().trim() shouldBe "$root/nightly/rows"
        upNext.getAttribute("href") shouldBe "/schedules?id=$id"
        upNext.click()
        page.waitForURL("**/schedules?id=$id")
        detail().waitFor()
    }

    @Test
    fun `the Unblock row opens the explorer filtered to blocked, and All brings the tree back`() {
        startTrace()
        val root = ready("schovb")
        releasedPipeline(page, "$root/jobs/rows")
        val closing = releasedPipeline(page, "$root/jobs/close")
        createSchedule(page, "$root/nightly/rows", "$root/jobs/rows")
        val blocked = createSchedule(page, "$root/monthly/close", "$root/jobs/close")
        // Blocked the way a person meets it: its pipeline's release discarded, then a Run now that cannot start.
        send(page, "POST", "/api/v1/pipelines/$closing/versions/1/discard").status shouldBe 200
        openSchedules("?id=$blocked")
        detail().waitFor()
        page.locator("[data-verb='schedule-run']").click()
        page.locator("#schedule-detail [data-slot='blocked']:not([hidden])").waitFor()

        openOverview()
        count("blocked") shouldBe "1"
        page.click("[data-overview-recent] [data-task='unblock'] a")
        page.waitForURL("**/schedules?condition=blocked")
        page.locator("#schedule-list:not([aria-busy]) .tpl-results").waitFor()
        page.locator("#schedule-filter-condition").inputValue() shouldBe "blocked"
        page.locator("#schedule-tree-pane [data-leaf-name]").all().map { it.getAttribute("data-leaf-name") } shouldBe
            listOf("$root/monthly/close")
        page.locator("#schedule-list details.tpl-folder").count() shouldBe 0
        page.url().substringAfter(baseUrl) shouldBe "/schedules?condition=blocked"

        page.selectOption("#schedule-filter-condition", "")
        page.locator("#schedule-list:not([aria-busy]) [role='tree'] details.tpl-folder[data-folder='$root']").waitFor()
        page.locator("#schedule-list .tpl-results").count() shouldBe 0
        page.url().substringAfter(baseUrl) shouldBe "/schedules"
    }

    @Test
    fun `each Last run bar opens the explorer filtered to that last run - its count is the list's rows - and All brings the tree back`() {
        startTrace()
        val root = ready("schovo")
        releasedPipeline(page, "$root/jobs/rows")
        val closing = releasedPipeline(page, "$root/jobs/close")
        val ok = createSchedule(page, "$root/nightly/rows", "$root/jobs/rows")
        val stopped = createSchedule(page, "$root/monthly/close", "$root/jobs/close")
        // A real succeeded run: Run now, followed to its end (SchedulesRunsBrowserTest's wait).
        openSchedules("?id=$ok")
        detail().waitFor()
        page.waitForResponse({ it.url().endsWith("/api/v1/schedules/$ok/run") && it.status() == 202 }) {
            page.locator("[data-verb='schedule-run']").click()
        }
        page.waitForFunction(
            "() => (document.querySelector('#schedule-detail .sch-runrow') || {}).getAttribute && " +
                "document.querySelector('#schedule-detail .sch-runrow').getAttribute('data-state') === 'succeeded'",
            null,
            Page.WaitForFunctionOptions().setTimeout(60_000.0),
        )
        // A real run that did not start: its release discarded, then a Run now the worker refuses.
        send(page, "POST", "/api/v1/pipelines/$closing/versions/1/discard").status shouldBe 200
        openSchedules("?id=$stopped")
        detail().waitFor()
        page.locator("[data-verb='schedule-run']").click()
        page.locator("#schedule-detail [data-slot='blocked']:not([hidden])").waitFor()

        openOverview()
        listOf("succeeded", "failed", "in-progress", "did-not-run", "never-ran").map { bar("last-run", it) } shouldBe
            listOf("1", "0", "0", "1", "0")
        mapOf("succeeded" to "$root/nightly/rows", "did-not-run" to "$root/monthly/close").forEach { (outcome, name) ->
            withClue(outcome) {
                openOverview()
                val counted = bar("last-run", outcome).toInt()
                page.click("[data-chart='last-run'] [data-bar='$outcome'] .app-overview-bar-name a")
                page.waitForURL("**/schedules?outcome=$outcome")
                page.locator("#schedule-list:not([aria-busy]) .tpl-results").waitFor()
                page.locator("#schedule-filter-outcome").inputValue() shouldBe outcome
                page.locator("#schedule-filter-condition").inputValue() shouldBe ""
                val rows = page.locator("#schedule-tree-pane [data-leaf-name]").all().map { it.getAttribute("data-leaf-name") }
                rows shouldBe listOf(name)
                rows.size shouldBe counted
                page.locator("#schedule-list details.tpl-folder").count() shouldBe 0
                page.url().substringAfter(baseUrl) shouldBe "/schedules?outcome=$outcome"
            }
        }

        page.selectOption("#schedule-filter-outcome", "")
        page.locator("#schedule-list:not([aria-busy]) [role='tree'] details.tpl-folder[data-folder='$root']").waitFor()
        page.locator("#schedule-list .tpl-results").count() shouldBe 0
        page.url().substringAfter(baseUrl) shouldBe "/schedules"
    }

    @Test
    fun `a viewer lands on the home with no New schedule and reads who creates schedules`() {
        startTrace()
        // The viewer is a member of `default`; the home reads that workspace.
        val viewer =
            seedLocalUser(
                uniqueEmail("schov-viewer-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "viewer",
            )
        login(viewer.email, viewer.oneTimePassword)
        page.waitForURL("**/dashboard")
        openOverview()
        // Absent, not hidden: the canAuthor guard renders no link at all.
        page.locator("[data-overview-new-schedule]").count() shouldBe 0
        page.locator("[data-overview-create-note]").innerText() shouldContain "an author or a workspace admin"
        page.locator("[data-overview-recent] [data-task='next'] a").getAttribute("href") shouldBe "/schedules"
    }
}
