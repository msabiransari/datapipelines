package co.datapipelines.browser

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Response
import com.microsoft.playwright.Route
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **The embedding example draws on a page of ANOTHER origin with the client library** (#1064 A.5, D9; design record
 * §8 A12, the browser half).
 *
 * The page is the example's COMMITTED `index.html` and `embed.js`, byte for byte, answered at the example's own
 * placeholder origins by request routing: the host page at `https://app.example.co/embed/`, the library's two files
 * and its fonts at `https://dp.example.co` (each request fetched from the running application at the same path and
 * answered with the application's own response, headers included), and the relay at `https://app.example.co/dp`
 * (the test's: the four runtime routes of `examples/dashboard-proxy/`, the dashboard key added, the placeholder
 * dashboard id replaced by the seeded board's). The page's origin is asserted to differ from the library's.
 *
 * The page's content policy is the test's, sent as the document's response header and read back from it before a
 * violation is counted: `script-src` the page's origin and the library's, `style-src` and `font-src` the library's,
 * `connect-src` the relay's. A visit of its own is the negative control: `style-src` naming the page's origin only
 * leaves a refusal naming the stylesheet in the suite's collector, drained by the control.
 *
 * The dashboard holds one chart per wire of the reference page (`reference.js`'s `WIRES`, read here, never copied), a
 * table and a KPI. **A type lane adds its chart in ONE place: an entry of [CHART_BODIES]** (its wire to a released
 * chart body over the board's `x`/`y` source); a wire of the reference page with no entry is red naming the wire.
 *
 * The fonts are judged by each face's own load result and by the font requests — never `document.fonts.check` or a
 * measured width, which stay true on a machine that has the font installed (the brief's measurement).
 */
class ClientLibraryEmbedBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `the example draws every tile on another origin under its enforced policy, with the library's fonts`() {
        val root = ready("cl1064")
        val board = seedEmbedBoard(root)
        val key = mintDashboardKey("$root/boards")
        val visit = Visit(policy = POLICY, board = board, key = key)
        val context = newBrowserContext()
        try {
            val page = visit.open(context)
            val documentPolicy = visit.documentPolicy
            println("1064|A5|origins|page=${URI(page.url()).origin()} library=${URI(LIBRARY_SCRIPT).origin()}")
            println("1064|A5|policy-as-sent|$documentPolicy")
            withClue("the page's origin is not the library's") { URI(page.url()).origin() shouldNotBe URI(LIBRARY_SCRIPT).origin() }
            withClue("the document's own response carries the test's policy (G9: read back before any violation is counted)") {
                documentPolicy shouldBe POLICY
            }
            assertTilesDrawn(page)
            val faces = loadFaces(page)
            println("1064|A5|faces|count=${faces.size} ${faces.joinToString(" ") { "${it["family"]}/${it["weight"]}=${it["status"]}" }}")
            println("1064|A5|font-requests|${visit.fontAnswers.joinToString(" ")} failed=${visit.failed}")
            println("1064|A5|library-requests|${visit.libraryRequests}")
            println("1064|A5|console-errors|${visit.consoleErrors}")
            val violations = drainCspViolations()
            println("1064|A5|csp-violations|count=${violations.size} $violations")
            assertSoftly {
                withClue("faces of document.fonts (floor $FACE_FLOOR)") { faces.size shouldBeGreaterThanOrEqual FACE_FLOOR }
                withClue("every face the page uses settled loaded (its own load result)") {
                    faces.filter { it["status"] != "loaded" }.shouldBeEmpty()
                }
                withClue("font requests to the library's origin (floor $FACE_FLOOR), each answered 200, none failed") {
                    visit.fontAnswers.size shouldBeGreaterThanOrEqual FACE_FLOOR
                    visit.fontAnswers.filterNot { it.endsWith("=200") }.shouldBeEmpty()
                    visit.failed.shouldBeEmpty()
                }
                withClue("content-policy violations") { violations.shouldBeEmpty() }
                withClue("console errors") { visit.consoleErrors.shouldBeEmpty() }
                withClue("from the library's origin, besides the fonts: exactly one script and one stylesheet, each once, each 200") {
                    visit.libraryRequests shouldContainExactlyInAnyOrder listOf("$LIBRARY_SCRIPT=200", "$LIBRARY_STYLESHEET=200")
                }
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `the negative control - a policy whose style-src names the page only leaves a refusal naming the stylesheet`() {
        val visit = Visit(policy = CONTROL_POLICY, board = null, key = null)
        val context = newBrowserContext()
        val refusals: List<String>
        try {
            val page = visit.open(context)
            println("1064|G9|control-policy-as-sent|${visit.documentPolicy}")
            withClue("the control's document carries the control's policy") { visit.documentPolicy shouldBe CONTROL_POLICY }
            try {
                page.waitForCondition {
                    drainedSoFar.addAll(drainCspViolations())
                    drainedSoFar.any { LIBRARY_STYLESHEET in it }
                }
            } catch (timeout: PlaywrightException) {
                println("1064|G9|control|no refusal naming the stylesheet within the wait: ${timeout.message?.lineSequence()?.first()}")
            }
        } finally {
            context.close()
            drainedSoFar.addAll(drainCspViolations())
            refusals = drainedSoFar.toList().also { drainedSoFar.clear() }
        }
        println("1064|G9|control-refusals|count=${refusals.size} $refusals")
        withClue("the collector holds a refusal naming the stylesheet's URL (a control that sees nothing is red)") {
            refusals.filter { LIBRARY_STYLESHEET in it }.size shouldBeGreaterThanOrEqual 1
        }
    }

    /** The control's drains, kept until the context is closed (the suite's after-test check reads the collector). */
    private val drainedSoFar = CopyOnWriteArrayList<String>()

    /** One visit of the example in its own context: the routes, and what the page asked of the library's origin. */
    private inner class Visit(
        val policy: String,
        val board: String?,
        val key: String?,
    ) {
        var documentPolicy: String? = null
        val fontAnswers = CopyOnWriteArrayList<String>()
        val libraryRequests = CopyOnWriteArrayList<String>()
        val failed = CopyOnWriteArrayList<String>()
        val consoleErrors = CopyOnWriteArrayList<String>()

        fun open(context: BrowserContext): Page {
            context.route("$LIBRARY_ORIGIN/**") { route -> fromApplication(route) }
            context.route("$PAGE_ORIGIN/**") { route -> hostOrRelay(route) }
            context.onRequestFailed { request -> failed += "${request.url()} ${request.failure()}" }
            context.onResponse { response ->
                val url = response.url()
                if (!url.startsWith(LIBRARY_ORIGIN)) return@onResponse
                val answer = "${url.substringBefore('?')}=${response.status()}"
                if (URI(url).path.startsWith(FONT_PREFIX)) fontAnswers += answer else libraryRequests += answer
            }
            val page = context.newPage()
            page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
            var response: Response? = null
            named(page, "the example's page at $PAGE_URL") { response = page.navigate(PAGE_URL) }
            documentPolicy = response?.headers()?.get("content-security-policy")
            return page
        }

        /** The library's origin is the application: the same path, the application's own answer and headers. */
        private fun fromApplication(route: Route) {
            val path = URI(route.request().url()).rawPath
            val answer = route.fetch(Route.FetchOptions().setUrl(baseUrl + path))
            route.fulfill(Route.FulfillOptions().setResponse(answer))
        }

        private fun hostOrRelay(route: Route) {
            val url = URI(route.request().url())
            when {
                url.rawPath == "/embed/index.html" -> fulfillFile(route, "index.html", "text/html; charset=utf-8", policy)
                url.rawPath == "/embed/embed.js" -> fulfillFile(route, "embed.js", "text/javascript; charset=utf-8", null)
                url.rawPath.startsWith(RELAY_PATH) -> relay(route, url.rawPath.removePrefix(RELAY_PATH), url.rawQuery)
                else -> route.fulfill(Route.FulfillOptions().setStatus(404).setBody("not part of the example"))
            }
        }

        private fun fulfillFile(
            route: Route,
            name: String,
            contentType: String,
            csp: String?,
        ) {
            val headers = if (csp == null) emptyMap() else mapOf("Content-Security-Policy" to csp)
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType(contentType)
                    .setHeaders(headers)
                    .setBodyBytes(File(repoRoot(), "$EXAMPLE_DIR/$name").readBytes()),
            )
        }

        /** The reference relay's rule: the four runtime routes only, three request headers through, the key added. */
        private fun relay(
            route: Route,
            path: String,
            query: String?,
        ) {
            val method = route.request().method()
            if (board == null || key == null || RUNTIME_ROUTES.none { (m, pattern) -> m == method && pattern.matches(path) }) {
                route.fulfill(Route.FulfillOptions().setStatus(404).setContentType("application/json").setBody(NOT_RELAYED))
                return
            }
            val forwarded = route.request().headers().filterKeys { it.lowercase() in FORWARDED_HEADERS } + ("DP-API-Key" to key)
            val upstream = baseUrl + path.replace(PLACEHOLDER_ID, board) + (query?.let { "?$it" } ?: "")
            val answer = route.fetch(Route.FetchOptions().setUrl(upstream).setHeaders(forwarded))
            route.fulfill(Route.FulfillOptions().setResponse(answer))
        }
    }

    /** Every tile drawn: each wire's chart picture with marks, the table's five rows, the KPI's value. */
    private fun assertTilesDrawn(page: Page) {
        for (wire in wires()) {
            val selector = "article.dp-dashboard-card[data-dp-viz=\"chart_$wire\"] svg.dp-chart .dp-mark"
            named(page, "the $wire chart's marks on the example") { page.waitForSelector(selector) }
            println("1064|A5|tile|chart_$wire|marks=${page.locator(selector).count()}")
        }
        val rows = "article.dp-dashboard-card[data-dp-viz=\"cells\"] .dp-dashboard-table tbody tr"
        named(page, "the table's five rows") { page.waitForCondition { page.locator(rows).count() == TABLE_ROWS } }
        val kpi = "article.dp-dashboard-card[data-dp-viz=\"total\"] .dp-dashboard-kpi-number"
        named(page, "the KPI's value") {
            page.waitForCondition { page.locator(kpi).count() == 1 && page.locator(kpi).textContent().trim() == KPI_VALUE }
        }
        println("1064|A5|tile|cells|rows=${page.locator(rows).count()} total|kpi=${page.locator(kpi).textContent().trim()}")
    }

    /** Every face of `document.fonts`, each asked to load and read from its OWN settled result. */
    @Suppress("UNCHECKED_CAST")
    private fun loadFaces(page: Page): List<Map<String, Any?>> =
        page.evaluate(
            """async () => {
              const faces = Array.from(document.fonts);
              await Promise.allSettled(faces.map((face) => face.load()));
              return faces.map((face) => ({ family: face.family, weight: face.weight, style: face.style, status: face.status }));
            }""",
        ) as List<Map<String, Any?>>

    /**
     * The test's board over [seedBoard]'s released pipelines: one chart per wire of the reference page (each a released
     * chart of [CHART_BODIES]), the table and the KPI, the grid pinned so every chart has room to draw, the initial
     * refresh. Rows are written by SQL as the suite's seeders write them (released, the board's workspace and author).
     */
    private fun seedEmbedBoard(root: String): String {
        val board = seedBoard(root)
        val wires = wires()
        val missing = wires.filterNot(CHART_BODIES::containsKey)
        withClue("a wire of the reference page with no chart in this test's dashboard: add it to CHART_BODIES") {
            missing.shouldBeEmpty()
        }
        for (wire in wires) {
            val name = "$root/visualizations/chart_$wire"
            val id =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val body = CHART_BODIES.getValue(wire)().replace("'", "''")
            sql(
                "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                    "SELECT '$id', d.workspace_id, '$name', '$name', '', 1, d.created_by FROM dashboards d WHERE d.id = '$board'::uuid",
            )
            sql(
                "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, released_at, released_by, " +
                    "created_by) SELECT '$id', 1, '$body'::jsonb, 'RELEASED', 'seeded-$id', NOW(), d.created_by, d.created_by " +
                    "FROM dashboards d WHERE d.id = '$board'::uuid",
            )
        }
        val charts =
            wires.joinToString(",") { wire ->
                """{"name":"chart_$wire","type":"visualization",""" +
                    """"visualization":{"name":"$root/visualizations/chart_$wire","version":1},""" +
                    """"inputs":{"main":{"source":"chart"}}}"""
            }
        val grid =
            (
                wires.mapIndexed { i, wire ->
                    """{"name":"chart_$wire","x":${(i % 2) * HALF},"y":${(i / 2) * CHART_H},"w":$HALF,"h":$CHART_H}"""
                } +
                    listOf(
                        """{"name":"cells","x":0,"y":${chartRows(wires) * CHART_H},"w":$HALF,"h":$CHART_H}""",
                        """{"name":"total","x":$HALF,"y":${chartRows(wires) * CHART_H},"w":$HALF,"h":2}""",
                    )
            ).joinToString(",", "[", "]")
        val body =
            """{"display_name":"Client library example","sources":[""" +
                """{"name":"chart","pipeline":{"name":"$root/pipelines/chart","version":1},"parameters":{}},""" +
                """{"name":"cells","pipeline":{"name":"$root/pipelines/table","version":1},"parameters":{}},""" +
                """{"name":"total","pipeline":{"name":"$root/pipelines/kpi","version":1},"parameters":{}}],""" +
                """"visualizations":[$charts,""" +
                """{"name":"cells","type":"visualization","visualization":{"name":"$root/visualizations/cells","version":1},""" +
                """"inputs":{"main":{"source":"cells"}}},""" +
                """{"name":"total","type":"visualization","visualization":{"name":"$root/visualizations/total","version":1},""" +
                """"inputs":{"main":{"source":"total"}}}],""" +
                """"layout":{"columns":12,"grid":$grid},""" +
                """"actions":[{"name":"refresh_all","type":"refresh","scope":"all","initial":true}]}"""
        sql("UPDATE dashboard_versions SET body_json = '${body.replace("'", "''")}'::jsonb WHERE dashboard_id = '$board'::uuid")
        return board
    }

    private fun chartRows(wires: List<String>): Int = (wires.size + 1) / 2

    /** A dashboard key minted through the REST route (the signed-in admin's page) and bound to [folder]. */
    @Suppress("UNCHECKED_CAST")
    private fun mintDashboardKey(folder: String): String {
        val result =
            page.evaluate(
                """async (folder) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const minted = await fetch('/api/v1/auth/api-keys', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ name: 'client library example ' + Date.now(), kind: 'dashboard' }) });
                  if (!minted.ok) return { error: 'mint ' + minted.status + ' ' + (await minted.text()).slice(0, 300) };
                  const key = (await minted.json()).data;
                  const bound = await fetch('/api/v1/dashboards/bindings', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ api_key_id: key.id, name_prefix: folder }) });
                  if (!bound.ok) return { error: 'bind ' + bound.status + ' ' + (await bound.text()).slice(0, 300) };
                  return { key: key.key };
                }""",
                folder,
            ) as Map<String, Any?>
        check(result["error"] == null) { "the dashboard key failed: ${result["error"]}" }
        return result["key"] as String
    }

    /** The reference page's wires, read from `reference.js` (never copied here). */
    private fun wires(): List<String> {
        val text = File(repoRoot(), REFERENCE_JS).readText()
        val list = requireNotNull(WIRES_LINE.find(text)) { "no `var WIRES = [...]` in $REFERENCE_JS" }.groupValues[1]
        return Regex("\"([^\"]+)\"").findAll(list).map { it.groupValues[1] }.toList().also {
            check(it.size >= WIRE_FLOOR) { "the reference page's wires read as $it (floor $WIRE_FLOOR)" }
        }
    }

    private fun repoRoot(): File =
        requireNotNull(
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() },
        ) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }

    private fun URI.origin(): String = "$scheme://$authority"

    private companion object {
        const val PAGE_ORIGIN = "https://app.example.co"
        const val LIBRARY_ORIGIN = "https://dp.example.co"
        const val PAGE_URL = "$PAGE_ORIGIN/embed/index.html"
        const val RELAY_PATH = "/dp"
        const val LIBRARY_SCRIPT = "$LIBRARY_ORIGIN/js/datapipelines.js"
        const val LIBRARY_STYLESHEET = "$LIBRARY_ORIGIN/css/datapipelines.css"
        const val FONT_PREFIX = "/vendor/fonts/"
        const val PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000"
        const val EXAMPLE_DIR = "examples/dashboard-embed"
        const val REFERENCE_JS = "modules/js-rendered/src/reference/reference.js"
        const val FACE_FLOOR = 4
        const val WIRE_FLOOR = 1
        const val TABLE_ROWS = 5
        const val KPI_VALUE = "42"
        const val HALF = 6
        const val CHART_H = 4
        const val NOT_RELAYED = """{"error":{"code":"proxy.not_found","message":"not relayed","details":{}}}"""
        val WIRES_LINE = Regex("""var WIRES = \[([^\]]*)\]""")

        /** D9's policy: four directives, the origins only; no 'unsafe-inline', no 'unsafe-eval', no star, no scheme. */
        const val POLICY =
            "script-src $PAGE_ORIGIN $LIBRARY_ORIGIN; style-src $LIBRARY_ORIGIN; font-src $LIBRARY_ORIGIN; connect-src $PAGE_ORIGIN"

        /** The negative control: the stylesheet's origin left out of `style-src`. */
        const val CONTROL_POLICY =
            "script-src $PAGE_ORIGIN $LIBRARY_ORIGIN; style-src $PAGE_ORIGIN; font-src $LIBRARY_ORIGIN; connect-src $PAGE_ORIGIN"

        /** `examples/dashboard-proxy/proxy.mjs`'s relayed routes and forwarded headers. */
        val RUNTIME_ROUTES =
            listOf(
                "GET" to Regex("""^/api/v1/dashboards/[^/]+/runtime/config$"""),
                "POST" to Regex("""^/api/v1/dashboards/[^/]+/runtime/parameters$"""),
                "POST" to Regex("""^/api/v1/dashboards/[^/]+/runtime/visualizations$"""),
                "POST" to Regex("""^/api/v1/dashboards/[^/]+/runtime/refreshes/[^/]+/abort$"""),
            )
        val FORWARDED_HEADERS = setOf("accept", "content-type", "last-event-id")

        /**
         * One released chart body per wire of the reference page, over [seedBoard]'s chart source (`x` INTEGER, `y`
         * INTEGER, four rows). **A type lane adds its wire here** — the only line of this class it touches.
         */
        val CHART_BODIES: Map<String, () -> String> =
            mapOf(
                "bar" to { ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Bar") },
                "line" to { withMark(ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Line"), "line") },
                "area" to { withMark(ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Area"), "area") },
            )

        /** [body] with its mark's type replaced (the bar body's one `"type":"bar"`). */
        fun withMark(
            body: String,
            mark: String,
        ): String {
            check(body.split(BAR_MARK).size == 2) { "the bar body holds its mark type once" }
            return body.replace(BAR_MARK, "\"mark\":{\"type\":\"$mark\"}")
        }

        const val BAR_MARK = "\"mark\":{\"type\":\"bar\"}"
    }
}
