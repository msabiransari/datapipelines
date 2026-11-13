package co.datapipelines.browser

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #876 G6 §B — the dashboard workspace's Dependencies tab (its own strip logic, dashboards/workspace.js): the button
 * after Refreshes, the pane loaded once on first activation, the §5.4 board's three direct pins and their own
 * dependencies, and the board itself the unlinked root.
 */
class DependenciesDashboardBrowserTest : DependencyPaneBrowserSuite() {
    private val pane = "#dp-pane-dependencies"

    @Test
    fun `the Dependencies tab draws the board's closure and loads once across the strip`() {
        startTrace()
        val world = seedWorkedGraph(ready("depboard"))
        countTreeRequests()
        page.navigate("$baseUrl/dashboards/${world.dashboardId}")
        page.locator("#dp-tab-dependencies").innerText() shouldBe "Dependencies"
        treeRequests shouldHaveSize 0
        page.click("#dp-tab-dependencies")
        awaitSettled(pane) shouldBe "ready"
        showList(pane)
        edgeLabels(pane) shouldContainExactlyInAnyOrder
            listOf(
                "Parameter set",
                "Source pipeline",
                "Visualization",
                "Selector template",
                "Selector datasource",
                "Template",
                "Datasource",
                "Imports",
                "Imports",
                "Transform template",
            )
        page.locator("$pane td.dp-deps-name:has(.dp-deps-root-mark) a").count() shouldBe 0
        for (tab in listOf("overview", "versions", "dependencies")) {
            page.click("#dp-tab-$tab")
            page.waitForSelector("#dp-pane-$tab:not([hidden])")
        }
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1
        drainCspViolations() shouldBe emptyList()
    }

    private companion object {
        const val SETTLE_MS = 400.0
    }
}
