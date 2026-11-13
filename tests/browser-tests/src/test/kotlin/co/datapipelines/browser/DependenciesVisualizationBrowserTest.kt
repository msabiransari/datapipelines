package co.datapipelines.browser

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #876 G6 §B — the visualization workspace's Dependencies tab: one strip button after Used by, one pane loaded
 * lazily ONCE however often the tab is revisited, the block's template pin as its one relationship, and the exact
 * empty-leaf text for a chart with no transform.
 */
class DependenciesVisualizationBrowserTest : DependencyPaneBrowserSuite() {
    private val pane = "#viz-pane-dependencies"

    @Test
    fun `the Dependencies tab draws the chart's template pin and loads once across tab switches`() {
        startTrace()
        val root = ready("depviz")
        val formatter = jsonataTemplate("$root/formatter.jsonata")
        val id = visualization("$root/chart", formatter)
        countTreeRequests()
        page.navigate("$baseUrl/visualizations/$id")
        page.locator("[data-dp-tab='dependencies']").count() shouldBe 1
        page.locator("#viz-tab-dependencies").innerText() shouldBe "Dependencies"
        page.click("#viz-tab-dependencies")
        awaitSettled(pane) shouldBe "ready"
        page.url().contains("tab=dependencies") shouldBe true
        showList(pane)
        edgeLabels(pane) shouldContainExactly listOf("Transform template")
        listRows(pane) shouldHaveSize 2
        page.click("#viz-tab-overview")
        page.click("#viz-tab-dependencies")
        page.waitForSelector("$pane:not([hidden])")
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1
        drainCspViolations() shouldBe emptyList()
    }

    @Test
    fun `a chart with no transform is the empty leaf`() {
        val root = ready("depvizleaf")
        val created = api("POST", "/api/v1/visualizations", ChartBrowserFixtures.workspaceDocument("$root/plain"))
        page.navigate("$baseUrl/visualizations/${created.data("id")}?tab=dependencies")
        awaitSettled(pane) shouldBe "empty"
        notes(pane) shouldContainExactly listOf("No dependencies")
    }

    private companion object {
        const val SETTLE_MS = 400.0
    }
}
