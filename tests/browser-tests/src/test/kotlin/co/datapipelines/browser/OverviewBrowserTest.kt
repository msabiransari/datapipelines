package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #556 — the Pipelines overview in real Chromium: the menu's Pipelines item lands on
 * `/overview/pipelines` with the family's resource browser open beside it; the inventory, the
 * chart and Recently updated are the workspace's; Copy prompt puts the textarea's CURRENT text on
 * the clipboard and makes no request, a refused clipboard selects the text and says how to finish,
 * and the prompt names the workspace the menu shows after a switch.
 *
 * #598a: the inventory is two stacked charts (lifecycle, maturity) with no count card; the first
 * case reads their parts, legend and hidden table and takes the three viewport screenshots into
 * build/reports/598a-overview.
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

    /** #598a: the Pipelines inventory is the lifecycle chart's total line, e.g. "2 pipelines". */
    private fun total(): String = page.locator("[data-stacked-total='lifecycle']").innerText().trim()

    private fun lifecyclePart(key: String) = page.locator("[data-stacked='lifecycle'] rect[data-part='$key']")

    private fun screenshot(
        name: String,
        width: Int,
        height: Int,
    ) {
        page.setViewportSize(width, height)
        page.reload()
        page.waitForSelector("[data-stacked-chart='lifecycle']")
        page.evaluate("() => document.fonts.ready")
        val dir = Paths.get("build", "reports", "598a-overview").also { it.toFile().mkdirs() }
        page.locator("[data-overview-inventory]").scrollIntoViewIfNeeded()
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")))
    }

    @Test
    fun `the Pipelines item lands on the overview with its browser tree, inventory, chart and recent list`() {
        startTrace()
        readyTree()
        seedPipeline("acme/one")
        seedPipeline("acme/two")

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/pipelines"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Pipelines"
        page.locator("#app-browser").isVisible shouldBe true
        page.locator("[data-count]").count() shouldBe 0
        total() shouldBe "2 pipelines"
        lifecyclePart("live").getAttribute("x") shouldBe "0"
        lifecyclePart("live").getAttribute("width") shouldBe "100.0"
        listOf("in-development", "live-changes", "retired").forEach { lifecyclePart(it).getAttribute("width") shouldBe "0" }
        page.locator("[data-legend='live'] .app-overview-legend-v").innerText().trim() shouldBe "2"
        page.locator("[data-legend='live'] a").getAttribute("href") shouldContain "/pipelines"
        withClue("Retired has no page: its legend entry is not a link") {
            page.locator("[data-legend='retired'] a").count() shouldBe 0
        }
        withClue("seedPipeline releases once and opens no draft: both sit in the 1-release bin, no draft") {
            page.locator("[data-chart-table='maturity'] tr[data-row='r1'] td[data-part='no-draft']").textContent().trim() shouldBe "2"
            page.locator("[data-stacked='maturity'] [data-row='r1'] rect[data-part='no-draft']").getAttribute("width") shouldBe "100.0"
        }
        val chart = page.locator("[data-stacked-chart='lifecycle']").boundingBox()
        val card = page.locator("[data-overview-inventory]").boundingBox()
        println("598a-overview lifecycle height=${chart.height} width=${chart.width} card height=${card.height}")
        chart.height shouldBeGreaterThan 0.0
        chart.height shouldBeLessThanOrEqual card.height
        withClue("Recently updated is newest first: acme/two was released after acme/one") {
            page.locator("[data-overview-recent] li a").allInnerTexts() shouldBe listOf("acme/two", "acme/one")
        }
        page.locator("[data-overview-recent] time").first().getAttribute("datetime") shouldStartWith "20"

        withClue("a created, never released pipeline is In development and sits in the 0-release bin with its draft") {
            api("POST", "/api/v1/pipelines", pipelineBody("acme/three"))
            page.reload()
            total() shouldBe "3 pipelines"
            lifecyclePart("in-development").getAttribute("x") shouldBe "0"
            lifecyclePart("in-development").getAttribute("width") shouldBe "33.3"
            lifecyclePart("live").getAttribute("x") shouldBe "33.3"
            lifecyclePart("live").getAttribute("width") shouldBe "66.7"
            page.locator("[data-chart-table='maturity'] tr[data-row='r0'] td[data-part='draft-open']").textContent().trim() shouldBe "1"
        }
        screenshot("overview-1512", WIDE, WIDE_H)
        screenshot("overview-900", MID, MID)
        screenshot("overview-390", PHONE, PHONE_H)
        page.setViewportSize(WIDE, WIDE_H)
        page.locator("[data-overview-view-all]").click()
        page.waitForURL("**/pipelines")
        page.waitForSelector("#pipeline-list-wrapper")
    }

    @Test
    fun `Copy prompt copies the edited text, says Copied and makes no request`() {
        startTrace()
        readyTree()
        openOverview()
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
        total() shouldBe "1 pipeline"

        val second = "second-" + generatedPassword("ws").lowercase()
        createWorkspace(second)
        openOverview()
        total() shouldBe "0 pipelines"
        page.locator("#overview-prompt").inputValue() shouldStartWith "In workspace $second, build a draft pipeline"
        page.locator("[data-overview-context] b").innerText().trim() shouldBe second
        page.locator("[data-overview-recent-empty]").isVisible shouldBe true
    }

    private companion object {
        const val WIDE = 1512
        const val WIDE_H = 900
        const val MID = 900
        const val PHONE = 390
        const val PHONE_H = 844
    }
}
