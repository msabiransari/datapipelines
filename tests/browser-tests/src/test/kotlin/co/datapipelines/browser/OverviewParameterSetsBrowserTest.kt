package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #582 — the Parameter Sets overview in real Chromium, on #556's framework: the menu's Parameter
 * Sets item lands on `/overview/parameter-sets` with the family's resource browser open beside it
 * and the item active; Copy prompt puts the textarea's CURRENT text on the clipboard and makes no
 * request, and a refused clipboard selects the text and says how to finish. #599: the page is the v2
 * lifecycle home — the summary strip and its foot, the twelve weeks' columns and the release-history
 * bins are the workspace's one released and one draft set; the definitions dialog opens from the foot
 * and gives focus back; Review your drafts opens the catalog filtered to `draft`; the screenshots land
 * in build/reports/599-object-family-homes.
 *
 * Sets are made through the real REST routes ([ParameterSetBrowserSuite]); the clipboard is stubbed
 * per case, as in [OverviewBrowserTest]: the subject is the page's handling of either answer.
 */
class OverviewParameterSetsBrowserTest : ParameterSetBrowserSuite() {
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
        page.click(".app-nav-link[data-nav-section='/parameter-sets']")
        page.waitForURL("**/overview/parameter-sets")
        page.waitForSelector(".app-overview[data-overview='parameter-sets']")
        page.waitForFunction("() => !!window.DatapipelinesOverview")
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('parameter-sets')?.state.levels.get(null)?.complete")
    }

    private fun set(path: String): String = setBody(path, "[${constants("kind", listOf("a"))}]", displayName = path.substringAfterLast('/'))

    @Test
    fun `the Parameter Sets item lands on the v2 home with its browser, strip, columns, bins, recent list and drafts row`() {
        startTrace()
        val root = ready("ps582")
        page.setViewportSize(1440, 900)
        val (released, hash) = createSet(set("$root/parameters/released_one"))
        release(released, hash)
        createSet(set("$root/parameters/draft_one"))

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/parameter-sets"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Parameter Sets"
        page.locator("#app-browser").isVisible shouldBe true
        page.locator(".app-overview h1").innerText().trim() shouldBe "Parameter Sets"
        OverviewHomeBrowserSupport.metrics(
            page,
            "total" to "2",
            "live" to "1",
            "in-development" to "1",
            "actively-developed" to "1",
            "live-changes" to "0",
            "retired" to "0",
        )
        page.locator("[data-count='live'] a").getAttribute("href") shouldContain "/parameter-sets?lifecycle=released"
        OverviewHomeBrowserSupport.columnsAndBins(page, created = 2, released = 1, "r0:draft-open" to 1, "r1:no-draft" to 1)
        withClue("How it works opens the guide's section, the introduction in it") {
            page.click("[data-open='howitworks']")
            page.waitForFunction("() => document.querySelector('dialog[data-overview-dialog]').open")
            page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "Create, evaluate, then release"
            page.locator("[data-dialog-section='howitworks']").innerText() shouldContain "Parameter sets define reusable inputs"
            page.keyboard().press("Escape")
            page.waitForFunction("() => !document.querySelector('dialog[data-overview-dialog]').open")
        }
        withClue("Recently updated is newest first: draft_one was created after released_one's release") {
            page.locator("[data-overview-recent] .app-overview-recent li a").allInnerTexts() shouldBe
                listOf("$root/parameters/draft_one", "$root/parameters/released_one")
        }
        page.locator("[data-overview-recent] time").first().getAttribute("datetime") shouldStartWith "20"

        OverviewHomeBrowserSupport.definitionsRoundTrip(page, total = 2)
        OverviewHomeBrowserSupport.screenshots(page, "parameter-sets")

        page.locator("[data-task='drafts'] small").innerText().trim() shouldBe "1 parameter set is working toward its first release."
        page.locator("[data-task='drafts'] a").click()
        page.waitForURL("**/parameter-sets?lifecycle=draft")
        page.waitForSelector("#parameter-set-list-wrapper .tpl-result")
        page.locator("#parameter-set-filter-lifecycle").inputValue() shouldBe "draft"
        val listed = page.locator("#parameter-set-list-wrapper").innerText()
        listed shouldContain "draft_one"
        listed.contains("released_one") shouldBe false
    }

    @Test
    fun `Copy prompt copies the edited text, says Copied and makes no request`() {
        startTrace()
        ready("ps582copy")
        val workspace = activeWorkspace()
        openOverview()
        page.click("[data-overview-agent]")
        val prompt = page.locator("#overview-prompt")
        prompt.inputValue() shouldStartWith "In workspace $workspace, create a draft parameter set for"
        prompt.fill(prompt.inputValue().replace("[inputs]", "a year and a month"))
        stubClipboard(refuse = false)
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => window.__copied !== null")
        page.locator("[data-overview-copy]").innerText().trim() shouldBe "Copied"
        page.locator("[data-overview-copy-status]").innerText().trim() shouldBe "Prompt copied to the clipboard."
        page.evaluate("() => window.__copied") shouldBe prompt.inputValue()
        (page.evaluate("() => window.__copied") as String) shouldContain "a year and a month"
        requests shouldBe emptyList()
    }

    @Test
    fun `a refused clipboard selects the prompt and says how to copy it, with no request`() {
        startTrace()
        ready("ps582refuse")
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
}
