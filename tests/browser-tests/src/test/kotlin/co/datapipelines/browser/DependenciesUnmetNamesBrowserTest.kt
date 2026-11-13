package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #908 — a dashboard's parameter-set edge whose `parameter_names` constraint carries `unmet_names` (record Draft 10
 * erratum (11), #892) shows those names in BOTH views as a second constraint text, `unmet parameters <names>`, after
 * the met one; it is never a diagnostic, so the pane stays `ready` with no note.
 *
 * The real fixture, staged as #892's `DependenciesUnmetParameterNamesE2eTest` case "removed" does: a set declaring
 * `region` and `since`, a pipeline declaring both, a dashboard binding both and pinning the set's OPEN DRAFT v1; then
 * the draft is overwritten in place without `since` and the same tree is read again. The worked graph cannot stage
 * it (its pipeline declares no parameter and its dashboard binds none), so the seeding is this class's own: no shared
 * helper changes. A real name cannot hold markup, so the hostile case rewrites the route's answer in flight, as
 * [DependencyPaneEscapingBrowserTest] does.
 */
class DependenciesUnmetNamesBrowserTest : DependencyPaneBrowserSuite() {
    private val pane = "#dp-pane-dependencies"

    @Test
    fun `an unmet bound name shows as a second constraint in the list and the panel - absent until the set drops it`() {
        startTrace()
        val world = seed(ready("depunmet"))
        open(world)
        awaitSettled(pane) shouldBe "ready"
        showList(pane)
        withClue("before the set drops a name") {
            assertSoftly {
                edgeConstraint() shouldBe MET
                setNodeConstraint() shouldBe MET
                panelItem(world) shouldContain "($MET)"
                paneText() shouldNotContain "unmet"
            }
        }

        dropSince(world)
        open(world)
        awaitSettled(pane) shouldBe "ready"
        notes(pane) shouldBe emptyList()
        showList(pane)
        withClue("after the set's draft dropped since") {
            assertSoftly {
                withClue("the edge table's Constraints cell") { edgeConstraint() shouldBe "$MET; unmet parameters since" }
                withClue("the node table's Constraints cell") { setNodeConstraint() shouldBe "$MET; unmet parameters since" }
                withClue("the graph's selection panel") { panelItem(world) shouldContain "($MET; unmet parameters since)" }
            }
        }
        drainCspViolations() shouldBe emptyList()
    }

    @Test
    fun `a hostile unmet name renders as text in the list and the panel - no element is parsed from it`() {
        startTrace()
        val world = seed(ready("depunxss"))
        dropSince(world)
        page.route("**$TREE_ROUTE**") { route ->
            val answer = route.fetch()
            val body = answer.text().replace("\"since\"", "\"$HOSTILE\"")
            route.fulfill(Route.FulfillOptions().setResponse(answer).setBody(body))
        }
        open(world)
        awaitSettled(pane) shouldBe "ready"
        showList(pane)
        val expected = "requires parameters region, $HOSTILE; unmet parameters $HOSTILE"
        assertSoftly {
            withClue("the edge table's Constraints cell") { edgeConstraint() shouldBe expected }
            withClue("the node table's Constraints cell") { setNodeConstraint() shouldBe expected }
            withClue("img elements in the pane (list)") { page.locator("$pane img").count() shouldBe 0 }
        }
        assertSoftly {
            withClue("the graph's selection panel") { panelItem(world) shouldContain "($expected)" }
            withClue("img elements in the pane (graph)") { page.locator("$pane img").count() shouldBe 0 }
            withClue("the planted handler never ran") { page.evaluate("() => window.__dpHostile === undefined") shouldBe true }
        }
        drainCspViolations() shouldBe emptyList()
    }

    // ------------------------------------------------------------------ fixture

    private data class Unmet(
        val root: String,
        val dashboardId: String,
        val setId: String,
    ) {
        val board get() = "$root/board"
        val set get() = "$root/filters"
    }

    /** Template, datasource, set (region, since), pipeline declaring both, chart, and a dashboard binding both to set v1. */
    private fun seed(root: String): Unmet {
        val datasource = registerDatasource()
        val query = sqlTemplate("$root/query.sql")
        visualization("$root/chart", jsonataTemplate("$root/shape.jsonata"))
        val setId = api("POST", "/api/v1/parameter-sets", setDocument("$root/filters", listOf("region", "since"))).data("id")
        api(
            "POST",
            "/api/v1/pipelines",
            """{"name":"$root/source","display_name":"source","description":"#908","nodes":[""" +
                """{"id":"n0","type":"DQL","source":"$datasource","description":"One row",""" +
                """"template":{"id":"$query","version":1},"depends_on":[]}],""" +
                """"parameters":{"region":{"type":"STRING","required":false},"since":{"type":"STRING","required":false}}}""",
        )
        val dashboardId =
            api(
                "POST",
                "/api/v1/dashboards",
                """{"name":"$root/board","display_name":"Board","description":"#908",""" +
                    """"parameter_set":{"name":"$root/filters","version":1},""" +
                    """"sources":[{"name":"revenue_source","pipeline":{"name":"$root/source","version":1},""" +
                    """"parameters":{"region":{"parameter":"region"},"since":{"parameter":"since"}}}],""" +
                    """"visualizations":[{"name":"revenue_chart","type":"visualization",""" +
                    """"visualization":{"name":"$root/chart","version":1},"inputs":{"revenue":{"source":"revenue_source"}}}],""" +
                    """"layout":{"grid":[{"name":"revenue_chart","x":0,"y":0,"w":6,"h":4}]}}""",
            ).data("id")
        return Unmet(root, dashboardId, setId)
    }

    /** A set of optional STRING `INPUT` parameters, the e2e's `setDocument`. */
    private fun setDocument(
        name: String,
        parameters: List<String>,
    ): String =
        """{"name":"$name","display_name":"Filters","description":"#908","parameters":[""" +
            parameters.joinToString(",") {
                """{"name":"$it","label":"${it.replaceFirstChar(Char::uppercase)}","type":"STRING","kind":"INPUT","required":false}"""
            } + "]}"

    /** Overwrites the set's open draft without `since`; the write must land on version 1, still a DRAFT. */
    private fun dropSince(world: Unmet) {
        val hash = api("GET", "/api/v1/parameter-sets/${world.setId}", null).data("body_hash")
        val written = api("PUT", "/api/v1/parameter-sets/${world.setId}", setDocument(world.set, listOf("region")), ifMatch = hash)
        withClue("the draft is overwritten in place") {
            ((written["data"] as Map<*, *>)["version"] as Number).toInt() shouldBe 1
            written.data("status") shouldBe "DRAFT"
        }
    }

    private fun open(world: Unmet) {
        page.navigate("$baseUrl/dashboards/${world.dashboardId}?tab=dependencies")
    }

    // ------------------------------------------------------------------ reads

    /** The edge table's Constraints cell (the fourth) of the one `Parameter set` row. */
    private fun edgeConstraint(): String =
        page.evaluate(
            "s => { const rows = [...document.querySelectorAll(s + ' [data-dp-deps-list=\"edges\"] tbody tr')]" +
                ".filter(tr => tr.querySelectorAll('td')[1].textContent.trim() === 'Parameter set');" +
                " return rows.length === 1 ? rows[0].querySelectorAll('td')[3].textContent : 'rows: ' + rows.length; }",
            pane,
        ) as String

    /** The node table's Constraints cell (the sixth) of the one parameter-set row. */
    private fun setNodeConstraint(): String =
        page.evaluate(
            "s => { const rows = [...document.querySelectorAll(s + ' [data-dp-deps-list=\"nodes\"] tbody tr')]" +
                ".filter(tr => tr.querySelectorAll('td')[0].textContent.trim() === 'Parameter set');" +
                " return rows.length === 1 ? rows[0].querySelectorAll('td')[5].textContent : 'rows: ' + rows.length; }",
            pane,
        ) as String

    /** Every text in the pane — both list tables and the graph's panel, shown or not. */
    private fun paneText(): String = page.evaluate("s => document.querySelector(s).textContent", pane) as String

    /**
     * Selects the set's row (by its version cell — the name cell's link would navigate), switches to the graph and
     * returns the panel's `Parameter set` item; leaves the pane in the list view again.
     */
    private fun panelItem(world: Unmet): String {
        page.locator("$pane tr.dp-deps-row:has(td.dp-deps-name [title='${world.set}']) td:nth-child(3)").click()
        page.locator("$pane [data-dp-deps-view='graph']").click()
        page.waitForSelector("$pane .dp-deps-panel li")
        val item =
            page.evaluate(
                "s => { const items = [...document.querySelectorAll(s + ' .dp-deps-panel li')]" +
                    ".map(li => li.textContent).filter(t => t.startsWith('Parameter set'));" +
                    " return items.length === 1 ? items[0] : 'items: ' + JSON.stringify(items); }",
                pane,
            ) as String
        showList(pane)
        return item
    }

    private companion object {
        const val MET = "requires parameters region, since"
        const val HOSTILE = "<img src=x onerror=window.__dpHostile=1>"
    }
}
