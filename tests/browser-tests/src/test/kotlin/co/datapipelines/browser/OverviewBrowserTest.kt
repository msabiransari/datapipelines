package co.datapipelines.browser

import co.datapipelines.browser.OverviewHomeBrowserSupport.PHONE
import co.datapipelines.browser.OverviewHomeBrowserSupport.PHONE_H
import co.datapipelines.browser.OverviewHomeBrowserSupport.WEEKS
import co.datapipelines.browser.OverviewHomeBrowserSupport.WIDE
import co.datapipelines.browser.OverviewHomeBrowserSupport.WIDE_H
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #556 — the Pipelines overview in real Chromium: the menu's Pipelines item lands on
 * `/overview/pipelines` with the family's resource browser open beside it; the inventory, the
 * charts and Recently updated are the workspace's; Copy prompt puts the textarea's CURRENT text on
 * the clipboard and makes no request, a refused clipboard selects the text and says how to finish,
 * and the prompt names the workspace the menu shows after a switch.
 *
 * #638 re-cut the page on the approved 2026-10-20 direction: the summary strip (Total, Live,
 * In development, Actively developed; the foot's Live with changes and Retired), the activity
 * columns and the release-history bars, Continue your work and Quick start, and ONE dialog the
 * openers fill by key. The first case reads the strip, the columns and the bars after two and then
 * three pipelines, takes pipelines-prefixed screenshots into build/reports/599-object-family-homes,
 * and measures the prototype's geometry: at 1512 the strip's four cells are one width, the twelve columns one pitch
 * and the plot 192px; at 390, 768, 1024 and 1280 nothing overflows the page.
 *
 * The clipboard is stubbed per case (`navigator.clipboard.writeText` resolving or rejecting): the
 * subject is the page's handling of either answer, not Chromium's permission prompt. Every request
 * the page makes between the click and the outcome is recorded and must be none.
 */
class OverviewBrowserTest : RestTreeBrowserFixture() {
    private fun stubClipboard(refuse: Boolean) {
        page.evaluate(
            """(refuse) => {
              window.__copied = null;
              Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: (text) => refuse ? Promise.reject(new DOMException('denied', 'NotAllowedError'))
                                            : Promise.resolve().then(() => { window.__copied = text; }),
              }});
            }""",
            refuse,
        )
    }

    private fun openOverview() {
        page.navigate("$baseUrl/dashboard")
        page.click(".app-nav-link[data-nav-section='/pipelines']")
        page.waitForURL("**/overview/pipelines")
        page.waitForSelector(".app-overview[data-overview='pipelines']")
        page.waitForFunction("() => !!window.DatapipelinesOverview")
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('pipelines')?.state.levels.get(null)?.complete")
    }

    /** The newest activity column: the week the suite runs in, the last of the twelve. */
    private fun newestWeek() = page.locator("[data-column]:last-child")

    private fun dialog() = page.locator("dialog[data-overview-dialog]")

    /** The prototype's geometry at 1512: four equal strip cells, twelve columns at one pitch, the plot 192px tall. */
    private fun geometryAt1512() {
        page.setViewportSize(WIDE, WIDE_H)
        page.reload()
        page.waitForSelector("[data-overview-summary]")
        @Suppress("UNCHECKED_CAST")
        val measured =
            page.evaluate(
                """() => ({
                  cells: [...document.querySelectorAll('dl.app-overview-counts > [data-count]')].map(c => c.getBoundingClientRect().width),
                  columns: [...document.querySelectorAll('[data-column]')].map(c => c.getBoundingClientRect().left),
                  plot: document.querySelector('[data-column] .app-overview-column-track').getBoundingClientRect().height,
                  grid: document.querySelector('[data-overview-columns] .app-overview-gridlines').getBoundingClientRect().height,
                })""",
            ) as Map<String, Any>
        val cells = (measured.getValue("cells") as List<*>).map { (it as Number).toDouble() }
        val lefts = (measured.getValue("columns") as List<*>).map { (it as Number).toDouble() }
        val pitches = lefts.zipWithNext { a, b -> b - a }
        println("home-frame-overview 1512 cells=$cells pitches=$pitches plot=${measured["plot"]} grid=${measured["grid"]}")
        withClue("the strip's four cells are one width at 1512") {
            cells.size shouldBe STRIP_CELLS
            cells.forEach { it shouldBe (cells[0] plusOrMinus SUBPIXEL) }
        }
        withClue("the twelve columns stand at one pitch at 1512") {
            lefts.size shouldBe WEEKS
            pitches.forEach { it shouldBe (pitches[0] plusOrMinus SUBPIXEL) }
        }
        withClue("every column and the gridlines stand in the --overview-chart-h band, 192px") {
            (measured.getValue("plot") as Number).toDouble() shouldBe (PLOT_H plusOrMinus SUBPIXEL)
            (measured.getValue("grid") as Number).toDouble() shouldBe (PLOT_H plusOrMinus SUBPIXEL)
        }
    }

    /** No region pushes the page sideways at the prototype's validation widths. */
    private fun noOverflow() {
        listOf(PHONE, TABLET, LAPTOP, DESK).forEach { width ->
            page.setViewportSize(width, WIDE_H)
            page.reload()
            page.waitForSelector("[data-overview-summary]")
            val overflow =
                page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number
            withClue("no horizontal overflow at $width") { overflow.toInt() shouldBe 0 }
        }
        page.setViewportSize(WIDE, WIDE_H)
        page.reload()
    }

    @Test
    fun `the Pipelines item lands on the overview with its browser tree, strip, charts and recent list`() {
        startTrace()
        readyTree()
        seedPipeline("acme/one")
        seedPipeline("acme/two")

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/pipelines"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Pipelines"
        page.locator("#app-browser").isVisible shouldBe true
        OverviewHomeBrowserSupport.metrics(
            page,
            "total" to "2",
            "live" to "2",
            "in-development" to "0",
            "actively-developed" to "0",
            "live-changes" to "0",
            "retired" to "0",
        )
        page.locator("[data-count='live'] a").getAttribute("href") shouldContain "/pipelines?lifecycle=released"
        withClue("Retired has no page: its foot entry is not a link") {
            page.locator("[data-count='retired'] a").count() shouldBe 0
        }
        // #593: the badge counts the three live groups, as the resource browser's header does. The two are
        // not compared here: OverviewStackedTest pins badge = objects - retired; FamilyRecentE2eTest pins the
        // header's (read on every render since #574, PipelineSidebarTreeStateBrowserTest's create case).
        withClue("#593: View all's badge counts the three live groups") {
            page.locator("[data-overview-view-all-count]").innerText().trim() shouldBe "2"
        }
        withClue("seedPipeline releases once and opens no draft: both sit in the 1-release bar, no draft") {
            page.locator("[data-chart-table='maturity'] tr[data-row='r1'] td[data-part='no-draft']").textContent().trim() shouldBe "2"
            page.locator("[data-overview-panel='maturity'] [data-bar='r1'] rect").getAttribute("width") shouldBe "100.0"
            page.locator("[data-overview-panel='maturity'] [data-bar='r1'] .app-overview-bar-v").innerText().trim() shouldBe "2"
        }
        withClue("two pipelines created and released this week are 2 + 2 events: the busiest week, full height") {
            page.locator("[data-column]").count() shouldBe WEEKS
            newestWeek().locator("rect[data-part='released']").getAttribute("y") shouldBe "50.0"
            newestWeek().locator("rect[data-part='released']").getAttribute("height") shouldBe "50.0"
            newestWeek().locator("rect[data-part='created']").getAttribute("y") shouldBe "0"
            newestWeek().locator("rect[data-part='created']").getAttribute("height") shouldBe "50.0"
            newestWeek().locator("title").textContent() shouldContain ": 2 created, 2 released, this week so far"
            page.locator(".app-overview-headline strong").innerText().trim() shouldBe "4 events"
            page.locator("[data-chart-table='activity'] tbody tr").count() shouldBe WEEKS
        }
        withClue("Recently updated is newest first: acme/two was released after acme/one") {
            page.locator("[data-overview-recent] .app-overview-recent li a").allInnerTexts() shouldBe listOf("acme/two", "acme/one")
        }
        page.locator("[data-overview-recent] time").first().getAttribute("datetime") shouldStartWith "20"

        withClue("a created, never released pipeline is In development, actively developed, and this week's third new one") {
            api("POST", "/api/v1/pipelines", pipelineBody("acme/three"))
            page.reload()
            OverviewHomeBrowserSupport.metrics(page, "total" to "3", "in-development" to "1", "actively-developed" to "1")
            OverviewHomeBrowserSupport.columnsAndBins(page, created = 3, released = 2, "r0:draft-open" to 1)
            page.locator("[data-task='drafts'] small").innerText().trim() shouldBe "1 pipeline is working toward its first release."
        }
        OverviewHomeBrowserSupport.screenshots(page, "pipelines", additionalViewports = listOf(MID to MID))
        geometryAt1512()
        noOverflow()
        page.locator("[data-overview-view-all]").click()
        page.waitForURL("**/pipelines")
        page.waitForSelector("#pipeline-list-wrapper")
    }

    /** The definitions dialog opens from the strip, Escape closes it natively, and focus returns to the button. */
    @Test
    fun `the definitions dialog opens from the strip and Escape returns focus to its button`() {
        startTrace()
        readyTree()
        seedPipeline("acme/one")
        openOverview()

        OverviewHomeBrowserSupport.definitionsRoundTrip(page, total = 1)

        withClue("the release-history info button opens its own section") {
            OverviewHomeBrowserSupport.openDialog(page, "[data-overview-panel='maturity'] [data-open='releases']")
            page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "Understanding release history"
            page.locator("[data-overview-dialog-close]").click()
            dialog().isVisible shouldBe false
        }
        val shots = OverviewHomeBrowserSupport.SHOTS
        shots.toFile().mkdirs()
        listOf(WIDE to WIDE_H, PHONE to PHONE_H).forEach { (width, height) ->
            page.setViewportSize(width, height)
            OverviewHomeBrowserSupport.openDialog(page, "[data-overview-foot] [data-open='definitions']")
            page.screenshot(Page.ScreenshotOptions().setPath(shots.resolve("pipelines-dialog-definitions-$width.png")))
            page.keyboard().press("Escape")
            OverviewHomeBrowserSupport.openDialog(page, "[data-overview-panel='activity'] [data-overview-view-data]")
            page.screenshot(Page.ScreenshotOptions().setPath(shots.resolve("pipelines-dialog-activity-$width.png")))
            page.keyboard().press("Escape")
            OverviewHomeBrowserSupport.openDialog(page, "[data-overview-agent]")
            page.screenshot(Page.ScreenshotOptions().setPath(shots.resolve("pipelines-dialog-prompt-$width.png")))
            page.keyboard().press("Escape")
        }
    }

    /** View data borrows the activity table into the dialog and gives it back: the page never holds two. */
    @Test
    fun `View data shows the twelve weeks and the page holds one table after close`() {
        startTrace()
        readyTree()
        openOverview()

        OverviewHomeBrowserSupport.openDialog(page, "[data-overview-view-data]")
        page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "Development activity · 12 weeks"
        val table = page.locator("dialog[data-overview-dialog] table[data-chart-table='activity']")
        table.isVisible shouldBe true
        table.locator("tbody tr").count() shouldBe WEEKS
        page.locator("table[data-chart-table='activity']").count() shouldBe 1
        page.keyboard().press("Escape")
        page.waitForFunction("() => !document.querySelector('dialog[data-overview-dialog]').open")
        page.locator("table[data-chart-table='activity']").count() shouldBe 1
        page.locator("[data-overview-panel='activity'] [data-table-home='activity'] table").count() shouldBe 1
        page.locator("[data-table-home='activity']").getAttribute("class") shouldContain "app-visually-hidden"
        page.evaluate("() => document.activeElement?.hasAttribute('data-overview-view-data')") shouldBe true
    }

    @Test
    fun `the question composes the prompt, Copy copies the composed text, and markup stays words, with no request`() {
        startTrace()
        readyTree()
        openOverview()
        OverviewHomeBrowserSupport.openDialog(page, "[data-overview-agent]")
        page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "Start with a clear question"
        stubClipboard(refuse = false)
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.locator("[data-overview-question]").fill("Compare taxi trips on rainy and dry days")
        val prompt = page.locator("#overview-prompt")
        prompt.inputValue() shouldContain "build a draft pipeline to answer: Compare taxi trips on rainy and dry days."
        page.click("[data-overview-copy]")
        page.waitForFunction("() => window.__copied !== null")
        page.evaluate("() => window.__copied") shouldBe prompt.inputValue()

        page.locator("[data-overview-question]").fill("<img src=x onerror=alert(1)>")
        prompt.inputValue() shouldContain "to answer: <img src=x onerror=alert(1)>."
        page.locator("dialog[data-overview-dialog] img").count() shouldBe 0
        page.evaluate("() => document.querySelectorAll('img[src=\"x\"]').length") shouldBe 0

        prompt.fill("my own words")
        page.locator("[data-overview-question]").fill("anything")
        prompt.inputValue() shouldBe "my own words"
        page.click("[data-overview-reset]")
        prompt.inputValue() shouldContain "to answer: [question]."
        page.locator("[data-overview-question]").inputValue() shouldBe ""
        requests.shouldBeEmpty()
    }

    @Test
    fun `Copy prompt copies the edited text, says Copied and makes no request`() {
        startTrace()
        readyTree()
        openOverview()
        OverviewHomeBrowserSupport.openDialog(page, "[data-overview-agent]")
        val prompt = page.locator("#overview-prompt")
        prompt.inputValue() shouldContain "In workspace tree-"
        prompt.fill(prompt.inputValue().replace("[question]", "which borough grew fastest?"))
        stubClipboard(refuse = false)
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => window.__copied !== null")
        page.locator("[data-overview-copy]").innerText().trim() shouldBe "Copied"
        page.locator("[data-overview-copy-status]").innerText().trim() shouldBe "Prompt copied to the clipboard."
        page.evaluate("() => window.__copied") shouldBe prompt.inputValue()
        (page.evaluate("() => window.__copied") as String) shouldContain "which borough grew fastest?"
        page.waitForFunction("() => document.querySelector('[data-overview-copy]').textContent.trim() === 'Copy prompt'")
        requests shouldBe emptyList()
    }

    @Test
    fun `a refused clipboard selects the prompt and says how to copy it, with no request`() {
        startTrace()
        readyTree()
        openOverview()
        OverviewHomeBrowserSupport.openDialog(page, "[data-overview-prepare]")
        stubClipboard(refuse = true)
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => document.querySelector('[data-overview-copy]').textContent.trim() === 'Select and copy'")
        page.locator("[data-overview-copy-status]").innerText() shouldContain "press Ctrl+C"
        page.evaluate(
            """() => { const field = document.getElementById('overview-prompt');
              return document.activeElement === field && field.selectionStart === 0 && field.selectionEnd === field.value.length; }""",
        ) shouldBe true
        page.evaluate("() => window.__copied") shouldBe null
        requests shouldBe emptyList()
    }

    @Test
    fun `after a workspace switch the overview is the new workspace's`() {
        startTrace()
        readyTree()
        seedPipeline("acme/one")
        openOverview()
        OverviewHomeBrowserSupport.metrics(page, "total" to "1")

        val second = "second-" + generatedPassword("ws").lowercase()
        createWorkspace(second)
        openOverview()
        OverviewHomeBrowserSupport.metrics(page, "total" to "0")
        page.locator("#overview-prompt").inputValue() shouldStartWith "In workspace $second, build a draft pipeline"
        page.locator("#overview-prompt").inputValue() shouldNotContain "tree-"
        page.locator("[data-overview-context] b").innerText().trim() shouldBe second
        page.locator("[data-overview-recent-empty]").isVisible shouldBe true
        page.locator("[data-overview-view-all-count]").innerText().trim() shouldBe "0"
    }

    private companion object {
        const val MID = 900
        const val TABLET = 768
        const val LAPTOP = 1024
        const val DESK = 1280
        const val STRIP_CELLS = 4

        /** The plot band (`--overview-chart-h`), unchanged since #556. */
        const val PLOT_H = 192.0

        /** Layout rounds to sub-pixels; one CSS pixel of slack is a different layout, not rounding. */
        const val SUBPIXEL = 0.5
    }
}
