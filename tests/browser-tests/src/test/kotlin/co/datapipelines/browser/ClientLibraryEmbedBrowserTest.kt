package co.datapipelines.browser

import com.google.gson.JsonParser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Response
import com.microsoft.playwright.Route
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **The embedding example draws on a page of ANOTHER origin with the client library** (#1064 A.5, D9; design record
 * §8 A12, the browser half).
 *
 * Two origins. The LIBRARY's is the running application itself, `http://localhost:<port>`, asked directly — no request
 * to it is intercepted, so the browser's own cross-origin rule judges the font answers (measured: a font answered by
 * Playwright's request fulfilment loads WITHOUT `Access-Control-Allow-Origin`, so a routed library origin cannot see
 * D4's header). The PAGE's is a second local server, [PageHost] on `http://127.0.0.1:<its own port>`, which serves the
 * example's committed `index.html` with its two documented values replaced (the Datapipelines origin,
 * `https://dp.example.co`, by the application's; the relay base, `https://app.example.co/dp`, by this server's `/dp`; the
 * page is asserted to differ from the committed file in nothing else) and its committed `embed.js` byte for byte — a
 * real server and not request routing, because the browser treats a routed document as public and refuses its requests
 * to a loopback address (measured: "Permission was denied for this request to access the `loopback` address space").
 * The relay under the page's `/dp` is request routing: the four runtime routes of `examples/dashboard-proxy/`, its three
 * forwarded headers, the dashboard key added, the placeholder dashboard id replaced by the seeded board's. The page's
 * origin is asserted to differ from the library's.
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
    private val libraryOrigin: String get() = baseUrl
    private val libraryScript: String get() = "$libraryOrigin/js/datapipelines.js"
    private val libraryStylesheet: String get() = "$libraryOrigin/css/datapipelines.css"

    /** D9's policy: four directives, the origins only; no 'unsafe-inline', no 'unsafe-eval', no star, no scheme. */
    private fun policy(
        pageOrigin: String,
        styleSource: String = libraryOrigin,
    ): String = "script-src $pageOrigin $libraryOrigin; style-src $styleSource; font-src $libraryOrigin; connect-src $pageOrigin"

    @Test
    fun `the example draws every tile on another origin under its enforced policy, with the library's fonts`() {
        val root = ready("cl1064")
        val board = seedEmbedBoard(root)
        val key = mintDashboardKey("$root/boards")
        val host = PageHost()
        val visit = Visit(host = host, policy = policy(host.origin), board = board, key = key)
        val context = newBrowserContext()
        try {
            val page = visit.open(context)
            val documentPolicy = visit.documentPolicy
            println("1064|A5|origins|page=${URI(page.url()).origin()} library=${URI(libraryScript).origin()}")
            println("1064|A5|policy-as-sent|$documentPolicy")
            withClue("the page's origin is not the library's") { URI(page.url()).origin() shouldNotBe URI(libraryScript).origin() }
            withClue("the document's own response carries the test's policy (G9: read back before any violation is counted)") {
                documentPolicy shouldBe policy(host.origin)
            }
            try {
                assertTilesDrawn(page)
            } catch (undrawn: AssertionError) {
                throw AssertionError(
                    "${undrawn.message}; the board shows: ${boardText(page)}; asked of the library's origin: ${visit.libraryRequests}; " +
                        "console errors: ${visit.consoleErrors}",
                    undrawn,
                )
            } finally {
                println("1064|A5|relay|${visit.relayed}")
                println("1064|A5|console-errors|${visit.consoleErrors}")
                println("1064|A5|board-text|${boardText(page)}")
            }
            val faces = loadFaces(page)
            println("1064|A5|faces|count=${faces.size} ${faces.joinToString(" ") { "${it["family"]}/${it["weight"]}=${it["status"]}" }}")
            println("1064|A5|font-requests|${visit.fontAnswers.joinToString(" ")} failed=${visit.fontsFailed}")
            println("1064|A5|library-requests|${visit.libraryRequests}")
            val violations = drainCspViolations()
            println("1064|A5|csp-violations|count=${violations.size} $violations")
            assertSoftly {
                withClue("faces of document.fonts (floor $FACE_FLOOR)") { faces.size shouldBeGreaterThanOrEqual FACE_FLOOR }
                withClue("every face the page uses settled loaded (its own load result)") {
                    faces.filter { it["status"] != "loaded" }.shouldBeEmpty()
                }
                withClue("font requests to the library's origin (floor $FACE_FLOOR), each answered 200, none failed") {
                    visit.fontAnswers.size shouldBeGreaterThanOrEqual FACE_FLOOR
                    visit.fontAnswers.filterNot { it.contains("=200 ") }.shouldBeEmpty()
                    visit.fontsFailed.shouldBeEmpty()
                }
                withClue("content-policy violations") { violations.shouldBeEmpty() }
                withClue("console errors") { visit.consoleErrors.shouldBeEmpty() }
                withClue("from the library's origin, besides the fonts: exactly one script and one stylesheet, each once, each 200") {
                    visit.libraryRequests shouldContainExactlyInAnyOrder listOf("$libraryScript=200", "$libraryStylesheet=200")
                }
            }
        } finally {
            visit.close(context)
            host.close()
        }
    }

    @Test
    fun `the negative control - a policy whose style-src names the page only leaves a refusal naming the stylesheet`() {
        val host = PageHost()
        val visit = Visit(host = host, policy = policy(host.origin, styleSource = host.origin), board = null, key = null)
        val context = newBrowserContext()
        val refusals: List<String>
        try {
            val page = visit.open(context)
            println("1064|G9|control-policy-as-sent|${visit.documentPolicy}")
            withClue("the control's document carries the control's policy") {
                visit.documentPolicy shouldBe policy(host.origin, styleSource = host.origin)
            }
            try {
                page.waitForCondition {
                    drainedSoFar.addAll(drainCspViolations())
                    drainedSoFar.any { libraryStylesheet in it }
                }
            } catch (timeout: PlaywrightException) {
                println("1064|G9|control|no refusal naming the stylesheet within the wait: ${timeout.message?.lineSequence()?.first()}")
            }
        } finally {
            visit.close(context)
            host.close()
            drainedSoFar.addAll(drainCspViolations())
            refusals = drainedSoFar.toList().also { drainedSoFar.clear() }
        }
        println("1064|G9|control-refusals|count=${refusals.size} $refusals")
        withClue("the collector holds a refusal naming the stylesheet's URL (a control that sees nothing is red)") {
            refusals.filter { libraryStylesheet in it }.size shouldBeGreaterThanOrEqual 1
        }
    }

    /** The control's drains, kept until the context is closed (the suite's after-test check reads the collector). */
    private val drainedSoFar = CopyOnWriteArrayList<String>()

    /**
     * The page's origin: a server on the loopback that answers the example's two files and nothing else (404), the page
     * with [policy] as its `Content-Security-Policy` header. Stopped by [close].
     */
    private inner class PageHost : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        val origin: String = "http://$LOOPBACK:${server.address.port}"
        val pageUrl: String = "$origin$PAGE_PATH"

        @Volatile
        var policy: String? = null

        init {
            server.createContext("/") { exchange ->
                try {
                    answer(exchange)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        private fun answer(exchange: HttpExchange) {
            val (body, contentType) =
                when (exchange.requestURI.rawPath) {
                    PAGE_PATH -> examplePage(origin) to "text/html; charset=utf-8"
                    SCRIPT_PATH -> exampleFile("embed.js") to "text/javascript; charset=utf-8"
                    else -> null to "text/plain; charset=utf-8"
                }
            exchange.responseHeaders.set("Content-Type", contentType)
            if (exchange.requestURI.rawPath == PAGE_PATH) policy?.let { exchange.responseHeaders.set("Content-Security-Policy", it) }
            val bytes = body ?: "not part of the example".toByteArray()
            exchange.sendResponseHeaders(if (body == null) NOT_FOUND else OK, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        override fun close() = server.stop(0)
    }

    /** One visit of the example in its own context: the relay's route, and what the page asked of the library's origin. */
    private inner class Visit(
        val host: PageHost,
        val policy: String,
        val board: String?,
        val key: String?,
    ) {
        var documentPolicy: String? = null
        val fontAnswers = CopyOnWriteArrayList<String>()
        val libraryRequests = CopyOnWriteArrayList<String>()
        val fontsFailed = CopyOnWriteArrayList<String>()
        val consoleErrors = CopyOnWriteArrayList<String>()
        val relayed = CopyOnWriteArrayList<String>()

        @Volatile
        private var closing = false

        /**
         * Closes [context]. A relayed request still open then (the refresh stream) fails inside Playwright; that failure
         * is expected while closing and only then, so it cannot replace the test's own verdict.
         */
        fun close(context: BrowserContext) {
            closing = true
            context.close()
        }

        fun open(context: BrowserContext): Page {
            host.policy = policy
            context.route("${host.origin}$RELAY_PATH/**") { route ->
                val url = URI(route.request().url())
                relay(route, url.rawPath.removePrefix(RELAY_PATH), url.rawQuery)
            }
            context.onRequestFailed { request ->
                if (!fromLibrary(request.url())) return@onRequestFailed
                if (URI(request.url()).path.startsWith(FONT_PREFIX)) {
                    fontsFailed += "${request.url()} ${request.failure()}"
                } else {
                    libraryRequests += "${request.url().substringBefore('?')}=failed(${request.failure()})"
                }
            }
            context.onResponse { response ->
                val url = response.url()
                if (!fromLibrary(url)) return@onResponse
                if (URI(url).path.startsWith(FONT_PREFIX)) {
                    val allowOrigin = response.headers()["access-control-allow-origin"]
                    fontAnswers += "${url.substringBefore('?')}=${response.status()} acao=$allowOrigin"
                } else {
                    libraryRequests += "${url.substringBefore('?')}=${response.status()}"
                }
            }
            val page = context.newPage()
            page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
            page.onPageError { error -> consoleErrors += "page error: $error" }
            var response: Response? = null
            named(page, "the example's page at ${host.pageUrl}") { response = page.navigate(host.pageUrl) }
            documentPolicy = response?.headers()?.get("content-security-policy")
            return page
        }

        private fun fromLibrary(url: String): Boolean = URI(url).origin() == libraryOrigin

        /** One of the four runtime routes the reference relay passes through. */
        private fun isRuntimeRoute(
            method: String,
            path: String,
        ): Boolean = RUNTIME_ROUTES.any { (m, pattern) -> m == method && pattern.matches(path) }

        /** The reference relay's rule: the four runtime routes only, three request headers through, the key added. */
        private fun relay(
            route: Route,
            path: String,
            query: String?,
        ) {
            val method = route.request().method()
            if (board == null || key == null || !isRuntimeRoute(method, path)) {
                relayed += "$method $path=404(not relayed)"
                route.fulfill(
                    Route
                        .FulfillOptions()
                        .setStatus(404)
                        .setContentType("application/json")
                        .setBody(NOT_RELAYED),
                )
                return
            }
            val forwarded = route.request().headers().filterKeys { it.lowercase() in FORWARDED_HEADERS } + ("DP-API-Key" to key)
            val upstream = baseUrl + path.replace(PLACEHOLDER_ID, board) + (query?.let { "?$it" } ?: "")
            try {
                val answer = route.fetch(Route.FetchOptions().setUrl(upstream).setHeaders(forwarded))
                relayed += "$method $path=${answer.status()}"
                route.fulfill(Route.FulfillOptions().setResponse(answer))
            } catch (closed: PlaywrightException) {
                if (!closing) throw closed
                relayed += "$method $path=(context closing)"
            }
        }
    }

    /**
     * The committed `index.html` with its two documented values replaced (the README's values 1 and 2), each occurrence
     * counted; replacing them back gives the committed bytes, so nothing else of the page differs.
     */
    private fun examplePage(pageOrigin: String): ByteArray {
        val committed = String(exampleFile("index.html"), Charsets.UTF_8)
        val replacements =
            listOf(
                "href=\"$PLACEHOLDER_LIBRARY/" to "href=\"$libraryOrigin/",
                "src=\"$PLACEHOLDER_LIBRARY/" to "src=\"$libraryOrigin/",
                "data-dp-proxy-base=\"$PLACEHOLDER_RELAY\"" to "data-dp-proxy-base=\"$pageOrigin$RELAY_PATH\"",
            )
        for ((from, _) in replacements) check(committed.split(from).size == 2) { "index.html holds `$from` exactly once" }
        val served = replacements.fold(committed) { text, (from, to) -> text.replace(from, to) }
        check(replacements.fold(served) { text, (from, to) -> text.replace(to, from) } == committed) {
            "the served page differs from the committed one beyond the two documented values"
        }
        return served.toByteArray(Charsets.UTF_8)
    }

    /** The board container's text, one line, cut (a bootstrap refusal shows its code there). */
    private fun boardText(page: Page): String =
        page
            .locator("#dashboard")
            .innerText()
            .replace('\n', ' ')
            .take(DIAGNOSTIC_CHARS)

    private fun exampleFile(name: String): ByteArray = File(repoRoot(), "$EXAMPLE_DIR/$name").readBytes()

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
        const val PLACEHOLDER_LIBRARY = "https://dp.example.co"
        const val PLACEHOLDER_RELAY = "https://app.example.co/dp"
        const val RELAY_PATH = "/dp"
        const val LOOPBACK = "127.0.0.1"
        const val PAGE_PATH = "/embed/index.html"
        const val SCRIPT_PATH = "/embed/embed.js"
        const val OK = 200
        const val NOT_FOUND = 404
        const val FONT_PREFIX = "/vendor/fonts/"
        const val PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000"
        const val EXAMPLE_DIR = "examples/dashboard-embed"
        const val REFERENCE_JS = "modules/js-rendered/src/reference/reference.js"
        const val FACE_FLOOR = 4
        const val DIAGNOSTIC_CHARS = 600
        const val WIRE_FLOOR = 1
        const val TABLE_ROWS = 5
        const val KPI_VALUE = "42"
        const val HALF = 6
        const val CHART_H = 4
        const val NOT_RELAYED = """{"error":{"code":"proxy.not_found","message":"not relayed","details":{}}}"""
        val WIRES_LINE = Regex("""var WIRES = \[([^\]]*)\]""")

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
                "scatter" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Scatter")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(SCATTER_CONFIG))
                    body.toString()
                },
                "pie" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Pie")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(PIE_CONFIG))
                    body.toString()
                },
                "combo" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Combo")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(COMBO_CONFIG))
                    body.toString()
                },
                "heatmap" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Heatmap")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(HEATMAP_CONFIG))
                    body.toString()
                },
                "histogram" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Histogram")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(HISTOGRAM_CONFIG))
                    body.toString()
                },
                "box" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Box")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(BOX_CONFIG))
                    body.toString()
                },
                "waterfall" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Waterfall")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(WATERFALL_CONFIG))
                    body.toString()
                },
                "funnel" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Funnel")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(FUNNEL_CONFIG))
                    body.toString()
                },
                "gauge" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Gauge")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(GAUGE_CONFIG))
                    body.toString()
                },
                "sparkline" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Sparkline")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(SPARKLINE_CONFIG))
                    body.toString()
                },
                "treemap" to {
                    val bar = ChartBrowserFixtures.barBody("x", "INTEGER", "y", displayName = "Treemap")
                    val body = JsonParser.parseString(bar).asJsonObject
                    body.add("config", JsonParser.parseString(TREEMAP_CONFIG))
                    body.toString()
                },
            )

        /** [body] with its mark's type replaced (the bar body's one `"type":"bar"`). */
        fun withMark(
            body: String,
            mark: String,
        ): String {
            check(body.split(BAR_MARK).size == 2) { "the bar body holds its mark type once" }
            return body.replace(BAR_MARK, "\"mark\":{\"type\":\"$mark\"}")
        }

        private const val TREEMAP_CONFIG =
            """{"mark":{"type":"treemap"},"encoding":{"path":[{"field":"x"}],""" +
                """"size":{"field":"y","type":"quantitative","aggregate":"sum"}}}"""

        /** Gauge on the board's INTEGER y: 10 + 20 + 30 + 40 = 100 of 200 (#1075). */
        const val GAUGE_CONFIG =
            """{"mark":{"type":"gauge"},"encoding":{"value":{"field":"y","type":"quantitative",""" +
                """"aggregate":"sum","scale":{"domain":[0,200]}}}}"""

        /** Heatmap: x and y bands, summed y as a shade on the same INTEGER delivery. */
        const val HEATMAP_CONFIG =
            """{"mark":{"type":"heatmap"},"encoding":{"x":{"field":"x","type":"nominal","sort":null},""" +
                """"y":{"field":"y","type":"nominal","sort":null},""" +
                """"color":{"field":"y","type":"quantitative","aggregate":"sum","scale":"sequential"}}}"""

        /** Histogram over the board’s INTEGER x column (#1071). */
        const val HISTOGRAM_CONFIG =
            """{"mark":{"type":"histogram"},"encoding":{"x":{"field":"x","type":"quantitative"},"y":{"aggregate":"count"}}}"""

        /** Box over the same INTEGER columns, one distribution per x category (brief 1072 A.7). */
        const val BOX_CONFIG =
            """{"mark":{"type":"box"},"encoding":{"x":{"field":"x","type":"nominal","sort":"ascending"},""" +
                """"y":{"field":"y","type":"quantitative","format":"integer"}}}"""

        /** Waterfall over the board's category and summed INTEGER measure (#1073). */
        const val WATERFALL_CONFIG =
            """{"mark":{"type":"waterfall"},"encoding":{"x":{"field":"x","type":"nominal","sort":null},""" +
                """"y":{"field":"y","type":"quantitative","aggregate":"sum"}}}"""

        /** Funnel over the board's non-null category and summed INTEGER measure (#1074). */
        const val FUNNEL_CONFIG =
            """{"mark":{"type":"funnel"},"encoding":{"stage":{"field":"x","type":"nominal","sort":null},""" +
                """"value":{"field":"y","type":"quantitative","aggregate":"sum"}}}"""

        const val BAR_MARK = "\"mark\":{\"type\":\"bar\"}"

        /** Scatter's config over the same two INTEGER columns: a point per row, no aggregate (brief 1062 A.8). */
        private const val COMBO_CONFIG = """
          {"mark":{"type":"combo"},"encoding":{
            "x":{"field":"x","type":"ordinal","sort":null},
            "layers":[
              {"mark":"bar","y":{"field":"y","type":"quantitative","aggregate":"sum"}},
              {"mark":"line","y":{"field":"y","type":"quantitative","aggregate":"mean"}}
            ]
          }}
        """

        const val SPARKLINE_CONFIG =
            """{"mark":{"type":"sparkline"},"encoding":{"x":{"field":"x","type":"quantitative"},""" +
                """"y":{"field":"y","type":"quantitative","aggregate":"sum"}}}"""
        const val SCATTER_CONFIG =
            """{"mark":{"type":"scatter"},"encoding":{"x":{"field":"x","type":"quantitative","format":"integer"},""" +
                """"y":{"field":"y","type":"quantitative","format":"integer"}}}"""

        /** Pie over the board's INTEGER x categories and summed y measure (brief 1068 A.8). */
        const val PIE_CONFIG =
            """{"mark":{"type":"pie"},"encoding":{"theta":{"field":"y","type":"quantitative","aggregate":"sum"},""" +
                """"color":{"field":"x","type":"nominal","sort":null}}}"""
    }
}
