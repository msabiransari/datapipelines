package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #581 — Visualizations menu, clipboard outcomes without requests, navigation and workspace isolation.
 * #599: the page is the v2 lifecycle home — the summary strip and its foot, the twelve weeks' columns and
 * the release-history bins are the workspace's two drafts; the definitions dialog opens from the foot and
 * gives focus back; the screenshots land in build/reports/599-object-family-homes.
 */
class OverviewVisualizationsBrowserTest : VisualizationBrowserSuite() {
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
        page.click(".app-nav-link[data-nav-section='/visualizations']")
        page.waitForURL("**/overview/visualizations")
        page.waitForSelector(".app-overview[data-overview='visualizations']")
        page.waitForFunction("() => !!window.DatapipelinesOverview")
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('visualizations')?.state.levels.get(null)?.complete")
    }

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    @Test
    fun `the Visualizations item lands on the v2 home with its browser tree, strip, columns, bins and recent list`() {
        startTrace()
        ready("viz-overview")
        createVisualization("acme/charts/one")
        createVisualization("acme/charts/two")

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/visualizations"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Visualizations"
        page.locator("#app-browser").isVisible shouldBe true
        OverviewHomeBrowserSupport.metrics(
            page,
            "total" to "2",
            "live" to "0",
            "in-development" to "2",
            "actively-developed" to "2",
            "live-changes" to "0",
            "retired" to "0",
        )
        page.locator("[data-count='in-development'] a").getAttribute("href") shouldContain "/visualizations?lifecycle=draft"
        OverviewHomeBrowserSupport.columnsAndBins(page, created = 2, released = 0, "r0:draft-open" to 2, "r1:no-draft" to 0)
        page.locator("[data-overview-panel='maturity'] [data-bar='r0'] .app-overview-bar-v").innerText().trim() shouldBe "2"
        page.locator("[data-task='drafts'] small").innerText().trim() shouldBe "2 visualizations are working toward their first release."
        OverviewHomeBrowserSupport.definitionsRoundTrip(page, total = 2)
        OverviewHomeBrowserSupport.screenshots(page, "visualizations")
        withClue("Recently updated is newest first: the second draft was created after the first") {
            page.locator("[data-overview-recent] .app-overview-recent li a").allInnerTexts() shouldBe
                listOf("acme/charts/two", "acme/charts/one")
        }
        page.locator("[data-overview-recent] time").first().getAttribute("datetime") shouldStartWith "20"
        page.locator("[data-overview-view-all]").click()
        page.waitForURL("**/visualizations")
        page.waitForSelector("#viz-list-wrapper")
        page.selectOption("#visualization-filter-lifecycle", "released")
        page.waitForFunction(
            "() => document.querySelector('#viz-list-wrapper')?.textContent.includes('No visualizations match: Released only')",
        )
        page
            .locator(
                "#viz-list-wrapper button",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("Clear filters"),
            ).click()
        page.waitForFunction("() => document.querySelectorAll('#viz-list-wrapper [data-leaf-id]').length === 2")
        page.locator("#visualization-filter-lifecycle").inputValue() shouldBe ""
    }

    @Test
    fun `Copy prompt copies the edited text, says Copied and makes no request`() {
        startTrace()
        ready("viz-overview")
        openOverview()
        page.click("[data-overview-agent]")
        val prompt = page.locator("#overview-prompt")
        prompt.inputValue() shouldContain "In workspace viz-overview"
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
        ready("viz-overview")
        openOverview()
        page.click("[data-overview-agent]")
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
        ready("viz-overview")
        createVisualization("acme/charts/one")
        openOverview()
        count("total") shouldBe "1"

        val second = "second-" + generatedPassword("ws").lowercase()
        createWorkspace(second)
        openOverview()
        count("total") shouldBe "0"
        page.locator("#overview-prompt").inputValue() shouldStartWith "In workspace $second, create a draft visualization"
        page.locator("[data-overview-context] b").innerText().trim() shouldBe second
        page.locator("[data-overview-recent-empty]").isVisible shouldBe true
    }
}
