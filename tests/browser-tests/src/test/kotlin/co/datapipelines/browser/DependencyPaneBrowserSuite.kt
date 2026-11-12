package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #876 G6 — the Dependencies pane's browser classes share this: one signed-in workspace admin per test, the objects of
 * the record's §5.4 worked graph authored through the REAL REST routes (every version a DRAFT read back through G4's
 * `GET /api/v1/dependencies/<family>`), and the pane's observable contract — its `data-dp-deps-state` marker, its list
 * tables and the tree requests the page sent, counted off the network.
 *
 * Its own helper (owner ruling 2026-11-08: one browser class per feature family, fixtures in their own helper): it
 * extends [BrowserSuite] only, so no family suite's helpers change and no browser closure is owed. The datasource is
 * registered through the native form exactly as [DashboardBrowserSuite.registerSourceDatasource] does; the templates
 * are POSTGRES like that datasource; the visualization's jsonata shape and its chart are #873's `FamilyRootsFixture`
 * documents.
 */
abstract class DependencyPaneBrowserSuite : BrowserSuite() {
    /** Every `GET /api/v1/dependencies/…` the page sent since [countTreeRequests], in order. */
    protected val treeRequests = CopyOnWriteArrayList<String>()

    /** A fresh workspace admin in a workspace of their own; returns the folder this test's names live under. */
    protected fun ready(slug: String): String {
        val tag = generatedPassword("d").takeLast(8).lowercase()
        val user = seedLocalUser(uniqueEmail("$slug-$tag"), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        landOnDashboard()
        createWorkspace("$slug$tag")
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        return "test/deps$tag"
    }

    /** Starts recording the page's tree requests (the URL with its query string). */
    protected fun countTreeRequests(on: Page = page) {
        treeRequests.clear()
        on.onRequest { request -> if (request.url().contains(TREE_ROUTE)) treeRequests += request.url() }
    }

    // ------------------------------------------------------------------ REST + SQL

    /** One REST call from the signed-in page (cookie + the CSRF double-submit); non-2xx fails naming the answer. */
    @Suppress("UNCHECKED_CAST")
    protected fun api(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Map<*, *> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const init = { method: args.method, credentials: 'same-origin', headers };
                  if (args.body !== null) init.body = args.body;
                  const response = await fetch(args.path, init);
                  const text = await response.text();
                  let json = null;
                  try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }
                  return { status: response.status, text: text, json: json };
                }""",
                mapOf("method" to method, "path" to path, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        withClue("$method $path -> $status ${(result["text"] as String).take(EXCERPT)}") { (status in 200..299) shouldBe true }
        return (result["json"] as Map<*, *>?) ?: emptyMap<String, Any?>()
    }

    protected fun Map<*, *>.data(key: String): String = ((this["data"] as Map<*, *>)[key]).toString()

    protected fun sql(statement: String) {
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.createStatement().use { it.execute(statement) }
        }
    }

    /** One scalar of a one-row query, as text (null when the column is NULL). */
    protected fun scalar(query: String): String? =
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(query).use { rows ->
                    check(rows.next()) { "no row: $query" }
                    rows.getString(1)
                }
            }
        }

    /** The active workspace's id — the SQL plants' and pointer reads' scope. */
    protected fun workspaceId(): String {
        val name = page.evaluate("() => document.getElementById('workspace-switcher').selectedOptions[0].text") as String
        check(Regex("^[a-z0-9-]+$").matches(name)) { "unexpected workspace name: $name" }
        return scalar("SELECT id::text FROM workspaces WHERE name = '$name'")!!
    }

    // ------------------------------------------------------------------ authoring

    /** The native datasource form (the dashboard suite's registration, verbatim in shape); returns its name. */
    @Suppress("UNCHECKED_CAST")
    protected fun registerDatasource(): String {
        named(page, "the datasources page navigation ($baseUrl/datasources)") { page.navigate("$baseUrl/datasources") }
        named(page, "the datasources page's network idle ($baseUrl/datasources)", ::shellInitFacts) {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        }
        val name = "deps-src-" + generatedPassword("s").takeLast(8).lowercase()
        val failures =
            page.evaluate(
                """async (args) => {
                  const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
                  const body = new URLSearchParams({
                    name: args.name, displayName: 'Dependencies browser source', dialect: 'POSTGRES',
                    jdbcUrl: args.url, credentialKind: 'password', username: args.user, password: args.password,
                    description: 'the #876 dependency fixture',
                  });
                  const res = await fetch('/partials/datasources', {
                    method: 'POST', credentials: 'same-origin',
                    headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, body,
                  });
                  return res.ok ? [] : [res.status + ' ' + (await res.text()).slice(0, 300)];
                }""",
                mapOf(
                    "name" to name,
                    "url" to SharedBrowserE2e.jdbcUrl.substringBefore("?"),
                    "user" to SharedBrowserE2e.username,
                    "password" to SharedBrowserE2e.password,
                ),
            ) as List<String>
        check(failures.isEmpty()) { "the datasource registration failed: $failures" }
        return name
    }

    /** A POSTGRES template (v1, DRAFT); [imports] pin version 1 of each, aliased `lib<i>`. */
    protected fun sqlTemplate(
        name: String,
        body: String = "SELECT 1 AS \\\"n\\\"",
        library: Boolean = false,
        imports: List<String> = emptyList(),
    ): String {
        val importJson = imports.mapIndexed { i, id -> """{"id":"$id","version":1,"alias":"lib$i"}""" }.joinToString(",", "[", "]")
        api(
            "POST",
            "/api/v1/templates",
            """{"id":"$name","dialect":"POSTGRES","display_name":"Dependencies","description":"#876",""" +
                """"is_library":$library,"imports":$importJson,"body":"$body"}""",
        )
        return name
    }

    /** #873's jsonata shape (a script template) the chart's `transform.template` pins. */
    protected fun jsonataTemplate(name: String): String {
        api(
            "POST",
            "/api/v1/templates",
            """{"id":"$name","type":"jsonata","display_name":"Shape","description":"#876",""" +
                """"body":"[ rows.{\"month_labels\": month, \"amounts\": amount} ]",""" +
                """"contract":{"mode":"row","inputs":{"rows":{"kind":"table","columns":[""" +
                """{"name":"month","type":"DATE"},{"name":"amount","type":"DECIMAL","precision":12,"scale":2}]}},""" +
                """"output":{"kind":"table","columns":[{"name":"month_labels","type":"STRING"},""" +
                """{"name":"amounts","type":"DECIMAL","precision":12,"scale":2}]}},""" +
                """"invariants":[],"tests":[{"name":"empty input","input":{"rows":[],"inputs":{}},"expect":{"output":[]}}]}""",
        )
        return name
    }

    /** A one-node DQL pipeline over [template] reading [datasource]; returns its id. */
    protected fun pipeline(
        name: String,
        template: String,
        datasource: String,
    ): String =
        api(
            "POST",
            "/api/v1/pipelines",
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"#876","nodes":[""" +
                """{"id":"n0","type":"DQL","source":"$datasource","description":"One row",""" +
                """"template":{"id":"$template","version":1},"depends_on":[]}]}""",
        ).data("id")

    /** A set whose one SELECT parameter reads [template] on [datasource]; returns its id. */
    protected fun parameterSet(
        name: String,
        template: String,
        datasource: String,
    ): String =
        api(
            "POST",
            "/api/v1/parameter-sets",
            """{"name":"$name","display_name":"Filters","description":"#876","parameters":[{"name":"region","label":"Region",""" +
                """"type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":false,""" +
                """"source":{"template":{"id":"$template","version":1},"datasource":"$datasource"}}]}""",
        ).data("id")

    /** #873's chart over the jsonata [template]; returns its id. */
    protected fun visualization(
        name: String,
        template: String,
    ): String =
        api(
            "POST",
            "/api/v1/visualizations",
            """{"name":"$name","display_name":"Chart","description":"#876","renderer":{"kind":"chart","version":"1"},""" +
                """"inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},""" +
                """{"name":"amount","type":"DECIMAL","nullable":false}]}},""" +
                """"transform":{"template":{"name":"$template","version":1},"inputs":{"rows":"revenue"}},""" +
                """"config":{"mark":{"type":"bar"},"encoding":{"x":{"field":"month_labels","type":"nominal","sort":"ascending"},""" +
                """"y":{"field":"amounts","type":"quantitative","aggregate":"sum"}}},""" +
                """"bindings":{"amounts":"amounts","month_labels":"month_labels"},""" +
                """"tests":{"cases":[{"name":"one month","fixtures":{"revenue":[{"month":"2026-01-01","amount":10.5}]},""" +
                """"assertions":[{"kind":"rendered"},{"kind":"mark_count","equals":1}]}]}}""",
        ).data("id")

    /** A dashboard over [set], one [source] pipeline and one [chart]; returns its id. */
    protected fun dashboard(
        name: String,
        set: String,
        source: String,
        chart: String,
    ): String =
        api(
            "POST",
            "/api/v1/dashboards",
            """{"name":"$name","display_name":"Sales","description":"#876","parameter_set":{"name":"$set","version":1},""" +
                """"sources":[{"name":"revenue_source","pipeline":{"name":"$source","version":1},"parameters":{}}],""" +
                """"visualizations":[{"name":"revenue_chart","type":"visualization",""" +
                """"visualization":{"name":"$chart","version":1},"inputs":{"revenue":{"source":"revenue_source"}}}],""" +
                """"layout":{"grid":[{"name":"revenue_chart","x":0,"y":0,"w":6,"h":4}]}}""",
        ).data("id")

    /**
     * The record's §5.4 worked graph, every version a DRAFT v1: dashboard `sales` → set `filters` (→ template `selector`
     * → library `common`; → the datasource), pipeline `totals` (→ template `query` → the SAME `common`; → the SAME
     * datasource) and visualization `chart` (→ jsonata `formatter`). Nine objects, nine relationships; `common` and the
     * datasource are each reached twice and drawn once.
     */
    protected fun seedWorkedGraph(root: String): WorkedGraph {
        val datasource = registerDatasource()
        val common = sqlTemplate("$root/common.sql", "<#macro one>SELECT 1 AS \\\"n\\\"</#macro>", library = true)
        val selector = sqlTemplate("$root/selector.sql", SELECTOR_BODY, imports = listOf(common))
        val query = sqlTemplate("$root/query.sql", imports = listOf(common))
        val formatter = jsonataTemplate("$root/formatter.jsonata")
        val setId = parameterSet("$root/filters", selector, datasource)
        val pipelineId = pipeline("$root/totals", query, datasource)
        val chartId = visualization("$root/chart", formatter)
        val boardId = dashboard("$root/sales", "$root/filters", "$root/totals", "$root/chart")
        return WorkedGraph(root, datasource, boardId, setId, pipelineId, chartId, common, selector, query, formatter)
    }

    data class WorkedGraph(
        val root: String,
        val datasource: String,
        val dashboardId: String,
        val setId: String,
        val pipelineId: String,
        val chartId: String,
        val common: String,
        val selector: String,
        val query: String,
        val formatter: String,
    ) {
        val dashboard get() = "$root/sales"
        val set get() = "$root/filters"
        val pipeline get() = "$root/totals"
        val chart get() = "$root/chart"
    }

    // ------------------------------------------------------------------ the pane

    /** The pane under [scope] reached a SETTLED state (anything but idle/loading); returns it. */
    protected fun awaitSettled(
        scope: String,
        on: Page = page,
    ): String {
        named(on, "the Dependencies pane under $scope settling (data-dp-deps-state)", ::shellInitFacts) {
            on.waitForFunction(
                "s => { const p = document.querySelector(s + ' [data-dp-deps]');" +
                    " return !!p && !['idle', 'loading'].includes(p.getAttribute('data-dp-deps-state')); }",
                scope,
            )
        }
        return paneState(scope, on)
    }

    protected fun paneState(
        scope: String,
        on: Page = page,
    ): String = on.evaluate("s => document.querySelector(s + ' [data-dp-deps]').getAttribute('data-dp-deps-state')", scope) as String

    /** The node rows of the pane's list, as the user reads them: `name|version|status|used by`. */
    @Suppress("UNCHECKED_CAST")
    protected fun listRows(
        scope: String,
        on: Page = page,
    ): List<String> =
        on.evaluate(
            "s => Array.from(document.querySelectorAll(s + ' [data-dp-deps-list=\"nodes\"] tbody tr')).map(tr => {" +
                " const c = tr.querySelectorAll('td'); return [c[1], c[2], c[3], c[4]].map(td => td.textContent.trim()).join('|'); })",
            scope,
        ) as List<String>

    /** The relationship labels of the pane's edge table, in order. */
    @Suppress("UNCHECKED_CAST")
    protected fun edgeLabels(
        scope: String,
        on: Page = page,
    ): List<String> =
        on.evaluate(
            "s => Array.from(document.querySelectorAll(s + ' [data-dp-deps-list=\"edges\"] tbody tr'))" +
                ".map(tr => tr.querySelectorAll('td')[1].textContent.trim())",
            scope,
        ) as List<String>

    /** The pane's text notes (cycle, limit, empty, incomplete). */
    @Suppress("UNCHECKED_CAST")
    protected fun notes(
        scope: String,
        on: Page = page,
    ): List<String> =
        on.evaluate(
            "s => Array.from(document.querySelectorAll(s + ' .dp-deps-text')).map(p => p.textContent.trim())",
            scope,
        ) as List<String>

    /** Switches the pane to its list view (the toggle the user presses). */
    protected fun showList(
        scope: String,
        on: Page = page,
    ) {
        on.locator("$scope [data-dp-deps-view='list']").click()
        on.waitForSelector("$scope [data-dp-deps-list='nodes']")
    }

    /** Holds every tree response until [release] — the A26 race, forced, never hoped for. */
    protected class HeldTree {
        val held = CopyOnWriteArrayList<Route>()

        fun release(index: Int) = held[index].resume()
    }

    protected fun holdTreeResponses(on: Page = page): HeldTree {
        val hold = HeldTree()
        on.route("**$TREE_ROUTE**") { route -> hold.held += route }
        return hold
    }

    protected companion object {
        const val TREE_ROUTE = "/api/v1/dependencies/"
        const val DESKTOP_W = 1440
        const val DESKTOP_H = 900
        const val EXCERPT = 600
        const val SELECTOR_BODY = "SELECT 'a' AS \\\"value\\\", 'A' AS \\\"display_value\\\", FALSE AS \\\"is_default\\\" ORDER BY 1"
    }
}
