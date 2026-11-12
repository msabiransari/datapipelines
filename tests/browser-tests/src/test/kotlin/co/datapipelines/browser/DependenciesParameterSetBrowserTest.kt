package co.datapipelines.browser

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #876 G6 §B — the parameter-set workspace's Dependencies section (a third server arm, `?tab=dependencies`; the page
 * has no client strip): the strip link opens it at the viewed version, the set's selector template with its import
 * and the datasource are drawn, and the depends_on card the workspace arm keeps is titled "Parameter graph" so the
 * page names exactly one thing "Dependencies".
 */
class DependenciesParameterSetBrowserTest : DependencyPaneBrowserSuite() {
    private val pane = "section[aria-labelledby='ps-dependencies-title']"

    @Test
    fun `the strip's Dependencies link opens the set's closure, and the depends_on card is the Parameter graph`() {
        startTrace()
        val world = seedWorkedGraph(ready("depset"))
        page.navigate("$baseUrl/parameter-sets/${world.setId}")
        page.locator("#ps-graph-title").innerText() shouldBe "Parameter graph"
        page.locator("[data-ps-tab='dependencies']").innerText().trim() shouldBe "Dependencies"
        page.locator("h2:text-is('Dependencies')").count() shouldBe 0
        page.click("[data-ps-tab='dependencies']")
        page.waitForURL("**tab=dependencies**")
        awaitSettled(pane) shouldBe "ready"
        page.getAttribute("$pane [data-dp-deps]", "data-version") shouldBe "1"
        page.url() shouldContain "version=1"
        showList(pane)
        edgeLabels(pane) shouldContainExactlyInAnyOrder listOf("Selector template", "Selector datasource", "Imports")
        drainCspViolations() shouldBe emptyList()
    }
}
