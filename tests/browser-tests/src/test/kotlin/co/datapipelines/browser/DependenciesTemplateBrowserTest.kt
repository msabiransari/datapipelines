package co.datapipelines.browser

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #876 G6 §B — the template workspace's Dependencies tab (server-rendered `aria-selected`, panes hidden by attribute):
 * the button after Used by, an import drawn as "Imports", the tab's in-page history replay reusing the loaded pane,
 * and the exact empty-leaf text for a library that imports nothing.
 */
class DependenciesTemplateBrowserTest : DependencyPaneBrowserSuite() {
    private val pane = "#tw-pane-dependencies"

    @Test
    fun `an import is drawn as Imports and a back-and-forward replay reuses the loaded pane`() {
        startTrace()
        val root = ready("deptpl")
        val common = sqlTemplate("$root/common.sql", "<#macro one>SELECT 1</#macro>", library = true)
        val query = sqlTemplate("$root/query.sql", imports = listOf(common))
        countTreeRequests()
        page.navigate("$baseUrl/templates/$query")
        page.locator("#tw-tab-dependencies").innerText() shouldBe "Dependencies"
        page.click("#tw-tab-dependencies")
        awaitSettled(pane) shouldBe "ready"
        showList(pane)
        edgeLabels(pane) shouldContainExactly listOf("Imports")
        page.click("#tw-tab-overview")
        page.waitForSelector("#tw-pane-overview:not([hidden])")
        page.goBack()
        page.waitForSelector("$pane:not([hidden])")
        page.goForward()
        page.waitForSelector("#tw-pane-overview:not([hidden])")
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1
        drainCspViolations() shouldBe emptyList()
    }

    @Test
    fun `a library that imports nothing is the empty leaf`() {
        val root = ready("deptplleaf")
        val lib = sqlTemplate("$root/lib.sql", "<#macro one>SELECT 1</#macro>", library = true)
        page.navigate("$baseUrl/templates/$lib?tab=dependencies")
        awaitSettled(pane) shouldBe "empty"
        notes(pane) shouldContainExactly listOf("No dependencies")
    }

    private companion object {
        const val SETTLE_MS = 400.0
    }
}
