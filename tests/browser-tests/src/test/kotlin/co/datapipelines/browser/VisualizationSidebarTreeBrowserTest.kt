package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #399 — the rail's Visualizations branch and the flat catalog in a real browser (ui-screens.md §3.4, §4.24): the
 * item sits in Build between Dashboards and Parameter Sets; the toggle opens the lazy tree without navigating; a
 * folder loads its level once; a leaf is a FULL document onto the workspace, which marks it current; the search
 * swaps the flat results into the root and clearing returns the tree; the catalog's `q` deep-links; the phone
 * drawer never makes the page scroll sideways; both themes. Every page is ZERO-CSP (the suite's after-each).
 */
class VisualizationSidebarTreeBrowserTest : VisualizationBrowserSuite() {
    private val panel = "#nav-tree-visualizations"

    private fun openTree() {
        page.click("[data-nav-branch='visualizations'] [data-nav-tree-toggle]")
        page.waitForSelector("#viz-tree-nav .tpl-tree, #viz-tree-nav .ds-empty")
    }

    private fun folder(path: String) = "$panel details.tpl-folder:has(> summary > span.tpl-label[title='$path'])"

    private fun expand(path: String) {
        page.click("${folder(path)} > summary")
        page.waitForSelector("${folder(path)} > .tpl-level:not(.tpl-level-pending)")
    }

    private fun leaf(id: String) = "$panel a.tpl-leaf[data-leaf-id='$id']"

    @Test
    fun `the branch sits between Dashboards and Parameter Sets, a folder loads once, and a leaf opens the workspace as a full document`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vtree")
        val (id, _) = createVisualization("$root/charts/units")
        page.navigate("$baseUrl/dashboard")

        val order =
            page.evaluate(
                "() => Array.from(document.querySelectorAll('.app-nav [data-nav-group=\"Build\"]'))" +
                    ".map(a => a.getAttribute('data-nav-section'))",
            ) as List<*>
        val at = order.indexOf("/visualizations")
        order[at - 1] shouldBe "/dashboards"
        order[at + 1] shouldBe "/parameter-sets"
        page.locator("[data-nav-branch='visualizations'] .app-nav-link use").getAttribute("href") shouldContain "#chart-line"

        page.evaluate("() => { window.__v399Tree = 1; }")
        openTree()
        page.evaluate("() => window.__v399Tree") shouldBe 1 // opening a tree is not navigating
        expand(root)
        expand("$root/charts")
        page.locator(leaf(id)).getAttribute("hx-boost") shouldBe "false"
        page.locator(leaf(id)).getAttribute("href") shouldBe "/visualizations/$id"

        page.click(leaf(id))
        page.waitForURL("**/visualizations/$id")
        page.waitForSelector("#viz-pane-preview:not([hidden])")
        page.evaluate("() => window.__v399Tree === undefined") shouldBe true // a FULL document
        page.waitForSelector("${leaf(id)}[aria-current='page']")
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `the search swaps flat results into the root, clearing returns the tree, and the catalog deep-links q`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vsrch")
        val (revenue, _) = createVisualization("$root/charts/revenue_by_region")
        createVisualization("$root/charts/units")
        page.navigate("$baseUrl/dashboard")
        openTree()

        page.fill("$panel [data-nav-tree-search]", "revenue")
        page.waitForSelector("#viz-tree-nav[data-visualization-list] a[data-leaf-id='$revenue']")
        page.locator("#viz-tree-nav[data-visualization-list] a[data-leaf-id]").count() shouldBe 1
        page.fill("$panel [data-nav-tree-search]", "")
        page.waitForSelector("#viz-tree-nav .tpl-tree details.tpl-folder")
        page.locator("#viz-tree-nav[data-visualization-list]").count() shouldBe 0

        // The catalog: flat rows, `q` a deep link, a row a full navigation onto the workspace.
        page.navigate("$baseUrl/visualizations?q=revenue")
        page.waitForSelector("#viz-list-wrapper a.tpl-result")
        page.locator("#viz-list-wrapper a.tpl-result").count() shouldBe 1
        page.locator("#viz-list-wrapper a.tpl-result").getAttribute("hx-boost") shouldBe "false"
        page.navigate("$baseUrl/visualizations")
        page.waitForSelector("#viz-list-wrapper a.tpl-result")
        page.locator("#viz-list-wrapper a.tpl-result").count() shouldBe 2
        page.click("#viz-list-wrapper a.tpl-result[href='/visualizations/$revenue']")
        page.waitForURL("**/visualizations/$revenue")
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `the phone drawer and the 1100 band never scroll the page sideways, and both themes render the open tree`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vtph")
        val (id, _) = createVisualization("$root/charts/units_with_a_deliberately_long_leaf_name_for_the_rail")

        for (theme in listOf("light", "dark")) {
            page.setViewportSize(DESKTOP_W, DESKTOP_H)
            ensureTheme(theme)
            page.navigate("$baseUrl/visualizations/$id")
            page.waitForSelector("#viz-pane-preview:not([hidden])")
            if (!page.locator(panel).isVisible) openTree()
            page.waitForSelector("${leaf(id)}[aria-current='page']")
            documentOverflowsX() shouldBe false
            shot("tree-$theme-1440")

            page.setViewportSize(BAND_W, BAND_H)
            page.navigate("$baseUrl/visualizations")
            page.waitForSelector("#viz-list-wrapper a.tpl-result")
            documentOverflowsX() shouldBe false
            shot("catalog-$theme-1100")

            page.setViewportSize(PHONE_W, PHONE_H)
            page.navigate("$baseUrl/visualizations/$id")
            page.click("#rail-open")
            page.waitForSelector("html.rail-open")
            if (!page.locator(panel).isVisible) openTree()
            page.waitForSelector("${leaf(id)}[aria-current='page']")
            documentOverflowsX() shouldBe false
            shot("tree-$theme-390")
        }
        drainCspViolations().shouldBeEmpty()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        ensureTheme("light")
    }

    private fun shot(name: String) {
        Files.createDirectories(SHOTS)
        page.screenshot(Page.ScreenshotOptions().setPath(SHOTS.resolve("$name.png")).setFullPage(false))
    }

    private companion object {
        const val DESKTOP_W = 1440
        const val DESKTOP_H = 900
        const val BAND_W = 1100
        const val BAND_H = 800
        const val PHONE_W = 390
        const val PHONE_H = 844
        val SHOTS = Paths.get("build", "reports", "399-visualizations")
    }
}
