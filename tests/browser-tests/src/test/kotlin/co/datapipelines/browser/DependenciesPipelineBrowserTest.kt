package co.datapipelines.browser

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #876 G6 §B — the pipeline workspace's Dependencies tab (four places: the enum, tabs.js, init.js's select handler and
 * the strip): the button after Usage, the pane loaded through the shared mount (never `ensureTabLoaded`'s partial
 * path), the node's template and datasource with the template's own import, and one request per activation.
 */
class DependenciesPipelineBrowserTest : DependencyPaneBrowserSuite() {
    private val pane = "#pe-pane-dependencies"

    @Test
    fun `the Dependencies tab draws the node's template, its import and the datasource, loaded once`() {
        startTrace()
        val root = ready("deppipe")
        val datasource = registerDatasource()
        val common = sqlTemplate("$root/common.sql", "<#macro one>SELECT 1</#macro>", library = true)
        val query = sqlTemplate("$root/query.sql", imports = listOf(common))
        val id = pipeline("$root/totals", query, datasource)
        val partials = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/partials/pipelines/") && it.url().contains("dependenc")) partials += it.url() }
        countTreeRequests()
        page.navigate("$baseUrl/pipelines/$id")
        page.locator(".pe-root").waitFor()
        page.locator("#pe-tab-dependencies").innerText() shouldBe "Dependencies"
        page.click("#pe-tab-dependencies")
        awaitSettled(pane) shouldBe "ready"
        page.url() shouldContain "tab=dependencies"
        showList(pane)
        edgeLabels(pane) shouldContainExactlyInAnyOrder listOf("Template", "Datasource", "Imports")
        page.click("#pe-tab-overview")
        page.click("#pe-tab-dependencies")
        page.waitForSelector("$pane:not([hidden])")
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1
        partials shouldHaveSize 0
        drainCspViolations() shouldBe emptyList()
    }

    private companion object {
        const val SETTLE_MS = 400.0
    }
}
