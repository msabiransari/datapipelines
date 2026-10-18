package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #580 — the Dashboards overview in real Chromium: the menu's Dashboards item lands on
 * `/overview/dashboards` with the family's resource browser open beside it; the inventory, the chart
 * and Recently updated are the workspace's draft; Review drafts opens the catalog filtered to
 * `with-drafts` with the control showing it; Copy prompt makes no request whether the clipboard
 * answers or refuses (the clipboard stubbed per case, as in [OverviewBrowserTest]).
 */
class OverviewDashboardsBrowserTest : RestTreeBrowserFixture() {
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
        page.click(".app-nav-link[data-nav-section='/dashboards']")
        page.waitForURL("**/overview/dashboards")
        page.waitForSelector(".app-overview[data-overview='dashboards']")
        page.waitForFunction("() => !!window.DatapipelinesOverview")
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('dashboards')?.state.levels.get(null)?.complete")
    }

    /** One draft dashboard through the public create route: version 1 DRAFT, never released. */
    private fun seedDraftDashboard(name: String) {
        api(
            "POST",
            "/api/v1/dashboards",
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","visualizations":[],""" +
                """"layout":{"columns":12,"grid":[]}}""",
        )
    }

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    @Test
    fun `the Dashboards item lands on the overview with its browser tree, and Review drafts filters the catalog`() {
        startTrace()
        readyTree()
        seedDraftDashboard("acme/boards/sales")

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/dashboards"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Dashboards"
        page.locator("h1").innerText().trim() shouldBe "Dashboards"
        page.locator("#app-browser").isVisible shouldBe true
        page.locator("#app-browser-title [data-browser-family=dashboards]").isVisible shouldBe true
        page.locator("#app-browser-title [data-browser-family=dashboards]").innerText() shouldBe "Dashboards"
        page.locator("#app-browser-title [data-browser-family=pipelines]").isHidden shouldBe true
        count("objects") shouldBe "1"
        count("with-drafts") shouldBe "1"
        count("with-releases") shouldBe "0"
        page.locator("[data-bar='draft'] rect").getAttribute("width") shouldBe "100.0"
        page.locator("[data-bar='released'] rect").getAttribute("width") shouldBe "0.0"
        page.locator("[data-overview-recent] li a").allInnerTexts() shouldBe listOf("acme/boards/sales")
        page.locator("[data-overview-recent] li .ds-badge").innerText().trim() shouldBe "draft"
        page.locator("#overview-prompt").inputValue() shouldContain "build a draft dashboard"

        page.locator("[data-overview-review]").click()
        page.waitForURL("**/dashboards?lifecycle=with-drafts")
        page.waitForSelector("#dash-list-wrapper")
        page.locator("#dashboard-filter-lifecycle").inputValue() shouldBe "with-drafts"
        page.locator("#dash-list-wrapper a.tpl-result").count() shouldBe 1
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/dashboards"
    }

    @Test
    fun `Copy prompt copies the dashboards prompt, says Copied and makes no request`() {
        startTrace()
        readyTree()
        openOverview()
        val prompt = page.locator("#overview-prompt")
        prompt.fill(prompt.inputValue().replace("[question]", "which region missed its target?"))
        stubClipboard(refuse = false)
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => window.__copied !== null")
        page.locator("[data-overview-copy]").innerText().trim() shouldBe "Copied"
        page.evaluate("() => window.__copied") shouldBe prompt.inputValue()
        (page.evaluate("() => window.__copied") as String) shouldContain "which region missed its target?"
        requests shouldBe emptyList()
    }

    @Test
    fun `a refused clipboard selects the dashboards prompt with no request`() {
        startTrace()
        readyTree()
        openOverview()
        stubClipboard(refuse = true)
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => document.querySelector('[data-overview-copy]').textContent.trim() === 'Select and copy'")
        page.evaluate(
            """() => { const field = document.getElementById('overview-prompt');
              return document.activeElement === field && field.selectionStart === 0 && field.selectionEnd === field.value.length; }""",
        ) shouldBe true
        page.evaluate("() => window.__copied") shouldBe null
        requests shouldBe emptyList()
    }
}
