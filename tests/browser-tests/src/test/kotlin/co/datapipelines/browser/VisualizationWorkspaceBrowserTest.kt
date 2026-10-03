package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.RequestOptions
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #399 — the visualization WORKSPACE in a real browser on the real application (ui-screens.md §4.24): the Preview
 * tab renders the version's test-case FIXTURES one case at a time with no data request; the tab strip swaps panes in
 * page and carries `?tab=`; Evidence shows a run's screenshot only once one was stored; the Release dialog lists its
 * refusals before the button and its POST releases through the service; a viewer's and a promoter's pages issue no
 * lifecycle request; the 3d bundle; every tab ZERO-CSP in both themes (the suite's after-each), at three widths.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VisualizationWorkspaceBrowserTest : VisualizationBrowserSuite() {
    @Test
    @Order(1)
    fun `Preview renders the fixtures one case at a time, the strip swaps panes in page, and no data request leaves`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vws")
        val (id, _) = createVisualization("$root/charts/units")
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        page.onRequest { requested += it.method() + " " + it.url() }

        page.navigate("$baseUrl/visualizations/$id")
        // ONE Plotly bundle, the server's choice for the viewed version's traces.
        page.locator("script[data-dp-plotly-bundle]").count() shouldBe 1
        page.locator("script[data-dp-plotly-bundle]").getAttribute("data-dp-plotly-bundle") shouldBe "2d"
        page.locator("#viz-tab-preview").innerText() shouldContain "test fixtures"
        page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'][data-dp-ready='true']")
        val traces =
            page.evaluate(
                "() => { const g = document.querySelector(\"#viz-pane-preview section[data-viz-case-index='0'] .js-plotly-plot\");" +
                    " return g && g._fullData ? g._fullData.length : -1; }",
            ) as Number
        withClue("case 'two series' renders its two traces") { traces.toInt() shouldBe 2 }
        // One case at a time: the second is hidden and NOT mounted until it is selected.
        page.locator("#viz-pane-preview section[data-viz-case-index='1']").getAttribute("hidden") shouldBe "hidden"
        page.locator("#viz-pane-preview section[data-viz-case-index='1'][data-dp-ready]").count() shouldBe 0

        page.selectOption("[data-viz-case-select]", "1")
        page.waitForSelector("#viz-pane-preview section[data-viz-case-index='1'][data-dp-ready='true']")
        page.locator("#viz-pane-preview section[data-viz-case-index='0']").getAttribute("hidden") shouldBe "hidden"
        page.waitForFunction(
            "() => { const c = document.querySelector(\"#viz-pane-preview section[data-viz-case-index='1'] .dp-dashboard-status\");" +
                " return c && c.getAttribute('data-dp-state') === 'no-data'; }",
        )
        withClue("the fixture mode ran on the block alone: no request reached /api/v1 — $requested") {
            requested.filter { it.contains("/api/v1/") }.shouldBeEmpty()
        }

        // In page: the same document, each lazy pane loading once, the URL carrying the tab.
        page.evaluate("() => { window.__v399Marker = 1; }")
        page.click("#viz-tab-overview")
        page.waitForSelector("#viz-pane-overview:not([hidden]) dl, #viz-pane-overview:not([hidden]) .ds-card")
        page.url() shouldContain "tab=overview"
        page.click("#viz-tab-evidence")
        page.waitForSelector("#viz-pane-evidence:not([hidden]) [data-viz-runs-cap]")
        page.locator("#viz-pane-evidence [data-viz-runs-cap]").innerText() shouldContain "100"
        page.click("#viz-tab-used-by")
        page.waitForSelector("#viz-pane-used-by:not([hidden]) [data-viz-used-by-empty]")
        page.locator("#viz-pane-used-by").innerText() shouldContain "no dashboard pins this visualization"
        page.click("#viz-tab-versions")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-version-row='1']")
        page.locator("#viz-pane-versions [data-viz-export]").getAttribute("href") shouldBe "/api/v1/visualizations/$id/export"
        page.evaluate("() => window.__v399Marker") shouldBe 1
        page.url() shouldContain "tab=versions"

        // The URL is the state: a reload re-selects Versions.
        page.reload()
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-version-row='1']")
        page.locator("#viz-pane-preview").getAttribute("hidden") shouldBe "hidden"
    }

    @Test
    @Order(2)
    @Suppress("LongMethod") // one walk on purpose: the run the Evidence tab reads is the run the release consumes
    fun `the Release dialog refuses before evidence, Evidence shows the screenshot only once stored, and the POST releases`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vrel")
        val (id, hash) = createVisualization("$root/charts/units")

        // No run yet: the dialog lists the evidence refusal BEFORE any button, and offers no confirm.
        page.navigate("$baseUrl/visualizations/$id?tab=versions")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-verb='visualization-release']")
        page.click("[data-verb='visualization-release']")
        page.waitForSelector("#dp-dialog [data-lifecycle-dialog='visualization-release']")
        page.locator("#dp-dialog [data-refusal-code]").count() shouldBeGreaterThan 0
        page.locator("#dp-dialog [data-verb='visualization-release-confirm']").count() shouldBe 0
        page.click("#dp-dialog [data-lifecycle-close]")
        page.waitForFunction("() => !document.querySelector('#dp-dialog .app-modal')")

        // A GREEN run, submitted with NO screenshot yet.
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val sessionId = started.at("data", "session_id") as String
        val runId = started.at("data", "run_id") as String
        val submitted =
            api(
                "POST",
                "/api/v1/visualizations/$id/tests/sessions/$sessionId/results",
                """{"cases":[{"name":"two series","verdict":"green"},{"name":"empty","verdict":"green"}],
                   "environment":{"browser":"chromium (playwright)","theme":"light","viewport":"1440x900"}}""",
            )
        submitted.at("data", "status") shouldBe "GREEN"
        val upload = submitted.at("data", "upload") as Map<*, *>

        // Observed event one: the run's detail says "no screenshot" and renders no image.
        page.navigate("$baseUrl/visualizations/$id?tab=evidence")
        page.waitForSelector("#viz-pane-evidence:not([hidden]) [data-run-row='$runId']")
        page.click("[data-run-row='$runId'] button")
        page.waitForSelector("#viz-evidence-run [data-viz-no-screenshot]")
        page.locator("#viz-evidence-run img").count() shouldBe 0

        // The upload, with the single-use capability alone (the agent's path).
        val image = page.screenshot()
        val stored =
            page.request().post(
                "$baseUrl${upload["url"]}?case=two%20series",
                RequestOptions
                    .create()
                    .setHeader(upload["header"] as String, upload["token"] as String)
                    .setHeader("Content-Type", "image/png")
                    .setData(image),
            )
        withClue(stored.text().take(EXCERPT)) { stored.status() shouldBe 201 }

        // Observed event two: the same run now shows the stored image through the existing route.
        page.reload()
        page.waitForSelector("#viz-pane-evidence:not([hidden]) [data-run-row='$runId']")
        page.click("[data-run-row='$runId'] button")
        page.waitForSelector("#viz-evidence-run img[data-viz-screenshot]")
        page.waitForFunction(
            "() => { const i = document.querySelector('#viz-evidence-run img[data-viz-screenshot]'); return i && i.naturalWidth > 0; }",
        )
        page.locator("#viz-evidence-run [data-viz-no-screenshot]").count() shouldBe 0

        // The dialog now admits the release; its POST (htmx, the page's CSRF header) releases and redirects.
        page.navigate("$baseUrl/visualizations/$id?tab=versions")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-verb='visualization-release']")
        page.click("[data-verb='visualization-release']")
        page.waitForSelector("#dp-dialog [data-verb='visualization-release-confirm']")
        page.locator("#dp-dialog [data-refusal-code]").count() shouldBe 0
        page.click("#dp-dialog [data-verb='visualization-release-confirm']")
        page.waitForURL("**/visualizations/$id?tab=versions&ok=released")
        page.waitForSelector("#toast .ds-toast, #toast [role='status'], #toast [role='alert']")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-version-row='1']")
        val record = api("GET", "/api/v1/visualizations/$id", null)
        withClue("the service released the exact draft ($hash)") { record.at("data", "current_version") shouldBe 1 }
        page.locator("[data-viz-viewed-label]").innerText() shouldContain "released"
    }

    @Test
    @Order(3)
    fun `a viewer reads every tab with no verb and no lifecycle request, and a promoter's fail-closed lens is the 404`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vrole")
        val (id, _) = createVisualization("$root/charts/units")

        val viewer = memberSession("vview", "viewer")
        val vpage = viewer.page
        val lifecycle = mutableListOf<String>()
        vpage.onRequest { request ->
            if (request.method() == "POST" || request.url().contains("/lifecycle/")) lifecycle += request.method() + " " + request.url()
        }
        TABS.forEach { tab ->
            vpage.navigate("$baseUrl/visualizations/$id?tab=$tab")
            awaitPane(vpage, tab)
        }
        vpage.waitForSelector("#viz-pane-versions [data-version-row='1']")
        vpage.locator("#app-main [data-verb]").count() shouldBe 0
        withClue("the viewer's page issued no POST and no lifecycle read: $lifecycle") { lifecycle.shouldBeEmpty() }
        viewer.close()

        // No promotion target here: the promoter's lens FAILS CLOSED — the workspace is the family's 404.
        val promoter = memberSession("vpromo", "promoter")
        val ppage = promoter.page
        val posts = mutableListOf<String>()
        ppage.onRequest { request ->
            if (request.method() == "POST" || request.url().contains("/lifecycle/")) posts += request.method() + " " + request.url()
        }
        ppage.navigate("$baseUrl/visualizations/$id").status() shouldBe 404
        ppage.navigate("$baseUrl/visualizations/$id?version=1&tab=versions").status() shouldBe 404
        ppage.navigate("$baseUrl/visualizations")
        ppage.waitForSelector("#app-main [data-lens-unavailable]")
        ppage.locator("#app-main a.tpl-result").count() shouldBe 0
        ppage.locator("#app-main [data-verb]").count() shouldBe 0
        withClue("the promoter's page issued no POST and no lifecycle read: $posts") { posts.shouldBeEmpty() }
        promoter.close()
    }

    @Test
    @Order(4)
    fun `every tab is CSP-clean in both themes at three widths, and a 3d trace declares the 3d bundle`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vshot")
        val (id, _) = createVisualization("$root/charts/units")
        val (surface, _) = createVisualization("$root/charts/hill", trace3d = true)

        for (theme in listOf("light", "dark")) {
            page.setViewportSize(DESKTOP_W, DESKTOP_H) // the theme toggle lives in the desktop top bar
            ensureTheme(theme)
            for ((w, h) in WIDTHS) {
                page.setViewportSize(w, h)
                TABS.forEach { tab -> checkTab(id, tab, "${w}x$h in $theme", "$theme-$w") }
            }
        }

        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        page.navigate("$baseUrl/visualizations/$surface")
        page.locator("script[data-dp-plotly-bundle]").count() shouldBe 1
        page.locator("script[data-dp-plotly-bundle]").getAttribute("data-dp-plotly-bundle") shouldBe "3d"
        page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'][data-dp-ready='true']")
        shot("workspace-preview-3d")
        drainCspViolations().shouldBeEmpty()
        ensureTheme("light")
    }

    /** One tab opened by URL at the current viewport: rendered, no sideways scroll, zero CSP; two tabs are photographed. */
    private fun checkTab(
        id: String,
        tab: String,
        where: String,
        shotSuffix: String,
    ) {
        page.navigate("$baseUrl/visualizations/$id?tab=$tab")
        awaitPane(page, tab)
        if (tab == "preview") page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'][data-dp-ready='true']")
        page.waitForTimeout(SETTLE_MS)
        withClue("$tab at $where scrolls sideways") { documentOverflowsX() shouldBe false }
        withClue("CSP on $tab at $where") { drainCspViolations().shouldBeEmpty() }
        if (tab == "preview" || tab == "versions") shot("workspace-$tab-$shotSuffix")
    }

    /** The tab's pane is visible and its lazy partial has replaced the loading note. */
    private fun awaitPane(
        target: Page,
        tab: String,
    ) {
        target.waitForSelector("#viz-pane-$tab:not([hidden])")
        target.waitForFunction("t => !document.querySelector('#viz-pane-' + t + ' > p.dp-tab-note')", tab)
    }

    private fun shot(name: String) {
        Files.createDirectories(SHOTS)
        page.screenshot(Page.ScreenshotOptions().setPath(SHOTS.resolve("$name.png")).setFullPage(false))
    }

    private companion object {
        const val DESKTOP_W = 1440
        const val DESKTOP_H = 900
        const val SETTLE_MS = 300.0
        val WIDTHS = listOf(1440 to 900, 1100 to 800, 390 to 844)
        val TABS = listOf("preview", "overview", "evidence", "used-by", "versions")
        val SHOTS = Paths.get("build", "reports", "399-visualizations")
    }
}
