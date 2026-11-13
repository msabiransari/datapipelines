package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #876 G6 Security — every name the tree payload carries reaches the DOM as TEXT. A real name cannot hold markup (the
 * name grammar refuses it), so the route's own answer is rewritten on the way in: the worked graph's shared library is
 * renamed `<img src=x onerror=…>`. The list's name cell, the edge table and the selection panel show the string
 * verbatim and no element is ever parsed from it (zero `img` in the pane, the handler never ran, no CSP report); the
 * graph's family-glyph layer holds glyph elements only, with no text.
 */
class DependencyPaneEscapingBrowserTest : DependencyPaneBrowserSuite() {
    private val board = "#dp-pane-dependencies"

    @Test
    fun `a hostile name renders as text in the list, the edges and the panel - no element is parsed from it`() {
        val world = seedWorkedGraph(ready("depxss"))
        page.route("**$TREE_ROUTE**") { route ->
            val answer = route.fetch()
            val body = answer.text().replace("\"${world.common}\"", "\"$HOSTILE\"")
            route.fulfill(Route.FulfillOptions().setResponse(answer).setBody(body))
        }
        page.navigate("$baseUrl/dashboards/${world.dashboardId}?tab=dependencies")
        awaitSettled(board) shouldBe "ready"
        showList(board)
        @Suppress("UNCHECKED_CAST")
        val cells =
            page.evaluate(
                "([s, h]) => [...document.querySelectorAll(s + ' [data-dp-deps-list] td')].map(td => td.textContent)" +
                    ".filter(t => t.includes(h))",
                listOf(board, HOSTILE),
            ) as List<String>
        // Its own name cell, and the To cell of each importer's edge (the selector and the query import it).
        withClue("cells carrying the name as text: $cells") { cells.size shouldBeGreaterThanOrEqual IMPORTERS + 1 }
        page.getAttribute("$board td.dp-deps-name [title='$HOSTILE']", "title") shouldBe HOSTILE
        page.locator("$board img").count() shouldBe 0
        // The panel: select the row by its version cell (the name cell's link would navigate), then the graph view.
        page.locator("$board tr.dp-deps-row:has(td.dp-deps-name [title='$HOSTILE']) td:nth-child(3)").click()
        page.locator("$board [data-dp-deps-view='graph']").click()
        page.locator("$board .dp-deps-panel").innerText() shouldContain HOSTILE
        page.locator("$board img").count() shouldBe 0
        // The graph's family-glyph layer (R1) holds only glyphs: one svg + use per drawn node, no text, nothing parsed.
        @Suppress("UNCHECKED_CAST")
        val layer =
            page.evaluate(
                "s => { const l = document.querySelector(s + ' [data-dp-deps-glyphs]'); return l ? { tags: [...new Set(" +
                    "[...l.querySelectorAll('*')].map(e => e.localName))].sort(), text: l.textContent," +
                    " svgs: l.querySelectorAll('svg').length } : null; }",
                board,
            ) as Map<String, Any?>?
        withClue("the glyph layer: $layer") {
            val drawn = requireNotNull(layer) { "the graph drew no glyph layer" }
            drawn["tags"] shouldBe listOf("svg", "use")
            drawn["text"] shouldBe ""
            drawn["svgs"] shouldBe WORKED_NODES
        }
        page.evaluate("() => window.__dpHostile === undefined") shouldBe true
        drainCspViolations() shouldBe emptyList()
    }

    private companion object {
        const val HOSTILE = "<img src=x onerror=window.__dpHostile=1>"
        const val IMPORTERS = 2
        const val WORKED_NODES = 9
    }
}
