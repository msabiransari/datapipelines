package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #584 — the Schedules home in real Chromium: the rail's Schedules item lands on
 * `/overview/schedules` (the explorer stays at `/schedules`, View all), lit as the Schedules item
 * with the resource browser closed — Schedules is not a tree family. A fresh workspace reads
 * zeros, the two empty notes and an author's New schedule; one schedule later, the page counts it
 * and its Up next row opens the explorer's detail for that schedule. #home-ops: on the v2 frame a
 * panel's info button opens its dialog section, the task row leads to the explorer, and a viewer
 * sees no New schedule, only who creates schedules.
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

        // #home-ops: the condition panel's info button opens its own dialog section; Escape closes
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

        // The first link of the sections, not the card: Continue your work opens on its task row (#home-ops).
        page.locator("[data-overview-recent] [data-task='next'] a").getAttribute("href") shouldBe "/schedules"
        val upNext = page.locator("[data-overview-recent] .app-overview-recent li a").first()
        upNext.innerText().trim() shouldBe "$root/nightly/rows"
        upNext.getAttribute("href") shouldBe "/schedules?id=$id"
        upNext.click()
        page.waitForURL("**/schedules?id=$id")
        detail().waitFor()
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
