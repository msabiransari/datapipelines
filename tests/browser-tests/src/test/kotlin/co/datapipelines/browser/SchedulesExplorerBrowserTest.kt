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
 * #9 slice 2, A.1 — the Schedules explorer (ui-screens.md §4.20) in a real browser: folders
 * derived from the names, each level ONE §20.1 read by prefix when its folder first opens, search
 * over every rendered column, and the empty state an author gets. See [SchedulesBrowserSuite] for
 * the fixtures' isolation.
 */
class SchedulesExplorerBrowserTest : SchedulesBrowserSuite() {
    // ------------------------------------------------------------------ A.1 the explorer

    @Test
    fun `the explorer lists folders from the names, reads a folder by prefix, and search filters every column`() {
        startTrace()
        val root = ready("schx")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        listOf("$root/daily/revenue", "$root/daily/costs", "$root/weekly/summary").forEach { createSchedule(page, it, pipeline) }

        openSchedules()
        val folder = page.locator("#schedule-list details.tpl-folder[data-folder='$root']")
        folder.locator(".tpl-count").textContent() shouldBe "3"

        // Opening a folder is ONE §20.1 read of its subtree, by prefix.
        val read = page.waitForRequest({ it.url().contains("/api/v1/schedules?prefix=$root&") }) { folder.locator("summary").click() }
        read.method() shouldBe "GET"
        val children = folder.locator(":scope > .tpl-level > ul > li > details.tpl-folder")
        children.first().waitFor()
        children.evaluateAll("ds => ds.map(d => d.getAttribute('data-folder') + ':' + d.querySelector('.tpl-count').textContent)") shouldBe
            listOf("$root/daily:2", "$root/weekly:1")
        page.waitForRequest({ it.url().contains("prefix=$root%2Fdaily") }) {
            folder.locator("details.tpl-folder[data-folder='$root/daily'] > summary").click()
        }
        leaf("$root/daily/costs").waitFor()
        page.locator("#schedule-tree-pane .tpl-leaf").evaluateAll("bs => bs.map(b => b.getAttribute('data-leaf-name'))") shouldBe
            listOf("$root/daily/costs", "$root/daily/revenue")

        // Search: a flat list of full paths; it matches the pipeline column too, and clears back to the tree.
        page.fill("#schedule-filter-q", "weekly")
        page.locator("#schedule-tree-pane button.tpl-result").first().waitFor()
        page.locator("#schedule-tree-pane button.tpl-result").evaluateAll("bs => bs.map(b => b.getAttribute('data-leaf-name'))") shouldBe
            listOf("$root/weekly/summary")
        page.fill("#schedule-filter-q", "jobs/rows")
        page.waitForFunction("() => document.querySelectorAll('#schedule-tree-pane button.tpl-result').length === 3")
        page.fill("#schedule-filter-q", "no-such-thing")
        page.locator("#schedule-list [data-empty='no-match']").waitFor()
        page.locator("[data-sch-action='clear-search']").click()
        page.locator("#schedule-list details.tpl-folder[data-folder='$root']").waitFor()
    }

    @Test
    fun `an empty workspace says so - an author's copy with the create verb`() {
        startTrace()
        ready("schempty")
        openSchedules()
        val empty = page.locator("#schedule-list [data-empty='none']")
        empty.waitFor()
        empty.textContent() shouldContain "No schedules yet"
        empty.locator("[data-verb='schedule-create']").count() shouldBe 1
    }
}
