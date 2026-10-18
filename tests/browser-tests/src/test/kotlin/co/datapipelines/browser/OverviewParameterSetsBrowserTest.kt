package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #582 — the Parameter Sets overview in real Chromium, on #556's framework: the menu's Parameter
 * Sets item lands on `/overview/parameter-sets` with the family's resource browser open beside it
 * and the item active; the inventory and Recently updated are the workspace's; Review drafts opens
 * the catalog filtered to `with-drafts`; Copy prompt puts the textarea's CURRENT text on the
 * clipboard and makes no request, and a refused clipboard selects the text and says how to finish.
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

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    private fun set(path: String): String = setBody(path, "[${constants("kind", listOf("a"))}]", displayName = path.substringAfterLast('/'))

    @Test
    fun `the Parameter Sets item lands on the overview with its browser, inventory, recent list and Review drafts`() {
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
        count("objects") shouldBe "2"
        count("with-releases") shouldBe "1"
        count("with-drafts") shouldBe "1"
        page.locator("[data-bar='released'] .app-overview-bar-v").innerText().trim() shouldBe "1"
        page.locator("[data-bar='draft'] .app-overview-bar-v").innerText().trim() shouldBe "1"
        page.locator("[data-overview-intro]").innerText() shouldContain "Parameter sets define reusable inputs"
        withClue("Recently updated is newest first: draft_one was created after released_one's release") {
            page.locator("[data-overview-recent] li a").allInnerTexts() shouldBe
                listOf("$root/parameters/draft_one", "$root/parameters/released_one")
        }
        page.locator("[data-overview-recent] time").first().getAttribute("datetime") shouldStartWith "20"

        page.locator("[data-overview-review]").click()
        page.waitForURL("**/parameter-sets?lifecycle=with-drafts")
        page.waitForSelector("#parameter-set-list-wrapper .tpl-result")
        page.locator("#parameter-set-filter-lifecycle").inputValue() shouldBe "with-drafts"
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
