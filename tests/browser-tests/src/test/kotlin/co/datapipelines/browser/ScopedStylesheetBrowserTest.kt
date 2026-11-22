package co.datapipelines.browser

import com.google.gson.JsonObject
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Response
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ColorScheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * **The client library's stylesheet applies inside the dashboard's containers only** (#1065 A.6; design record §4.11,
 * the note of 2026-11-21 "The stylesheet inside the container").
 *
 * T13's harness (`ClientLibraryEmbedBrowserTest`, read, not edited): the LIBRARY's origin is the running application,
 * asked directly; the PAGE's is a second local server, [PageHost], which serves this class's own resources under
 * `scoped-stylesheet/` — a host page WITH its own look (`host.html`, `host.css`: a body font, margin and colours, a
 * heading, a paragraph, a link, a button, form controls and a table, and `html { overflow-y: scroll }` so a growing
 * page keeps its widths) and a bare page for the reference (`bare.html`); the relay under the page's `/dp` is request
 * routing with the dashboard key added. Each document is answered with the content policy of T13's test; `style-src`
 * also names the page's own origin, which serves the host's own sheet (no `'unsafe-inline'` anywhere).
 *
 * The test drives the page through its own probe (`probe.js`, evaluated into the page, never served to it): it adds
 * the stylesheets, boots the dashboard through the library's script, and reads computed styles. Every comparison reads
 * ALL computed properties that are not custom properties, matches elements by document order, asserts the two runs
 * hold the same sequence of tags, and prints the count compared beside its floor.
 */
class ScopedStylesheetBrowserTest : DashboardBrowserSuite() {
    private val libraryOrigin: String get() = baseUrl

    private fun policy(pageOrigin: String): String =
        "script-src $pageOrigin $libraryOrigin; style-src $pageOrigin $libraryOrigin; font-src $libraryOrigin; connect-src $pageOrigin"

    /**
     * B1, B2, B3, B7, B8, B9 and B10: the host page is untouched by the stylesheet, the dashboard is the reference
     * dashboard on this host page (also under a 10px root and at the zoom of 150%), a host rule by id wins, and the
     * page's enforced policy sees no violation.
     */
    @Test
    fun `the host page is untouched and the dashboard is the reference dashboard, at any root size and zoom`() {
        val root = ready("ss1065a")
        val board = seedBoardWithGrid(root)
        val key = mintDashboardKey("$root/boards")
        val host = PageHost()
        try {
            // One soft scope over the whole case: every B reports in a run, so a plant that breaks one check does not
            // hide another (the nested soft blocks below defer to this one).
            assertSoftly {
                val onHost = hostRun(host, board, key)
                val reference = referenceRun(host, board, key, onHost.zoomedWidth)
                val underSmallRoot = rootFontRun(host, board, key)
                compareInside("B3", reference.atFixedWidth, onHost.atFixedWidth)
                compareInside("B10 (B3 at 150%)", reference.atZoom, onHost.atZoom)
                compareInside("B7 (B3 under a 10px root)", reference.atFixedWidth, underSmallRoot)
            }
        } finally {
            host.close()
        }
    }

    /** B4, B5 and B6: a theme sheet waits for its attribute, the charts follow it, from the start and with `auto`. */
    @Test
    fun `a theme sheet waits for its attribute, and cards and charts follow the attribute together`() {
        val root = ready("ss1065b")
        val board = seedBoardWithGrid(root)
        val key = mintDashboardKey("$root/boards")
        val host = PageHost()
        try {
            assertSoftly {
                val light = themeAtRunTime(host, board, key)
                themeFromTheStart(host, board, key, light)
                themeAuto(host, board, key)
            }
        } finally {
            host.close()
        }
    }

    /**
     * The reference: the same dashboard under the six source sheets on a bare page, at the fixed width and at the zoom
     * of 150% with its containers at [zoomedWidth], the width the host's container had there.
     */
    private fun referenceRun(
        host: PageHost,
        board: String,
        key: String,
        zoomedWidth: Double,
    ): Snapshots =
        Visit(host, board, key).use { visit ->
            visit.open(BARE_PAGE)
            visit.call("setWidth", "${FIXED_WIDTH}px")
            println("1065|B3|reference-sheets|${visit.call("addSheets", SOURCE_SHEETS.map { libraryOrigin + it })}")
            visit.boot()
            visit.settle("the reference at ${FIXED_WIDTH}px")
            val fixed = visit.snapshotInside()
            visit.metrics(ZOOMED_WIDTH, ZOOMED_HEIGHT, ZOOM)
            visit.call("setWidth", "${zoomedWidth}px")
            visit.settle("the reference at the zoom of 150%, ${zoomedWidth}px")
            println("1065|B10|reference-at150|${visit.zoom()}")
            Snapshots(fixed, visit.snapshotInside(), zoomedWidth)
        }

    /** The elements inside the two containers at the fixed width and at the zoom of 150%, and the container's width there. */
    private class Snapshots(
        val atFixedWidth: List<Map<String, Any?>>,
        val atZoom: List<Map<String, Any?>>,
        val zoomedWidth: Double,
    )

    @Suppress("LongMethod")
    private fun hostRun(
        host: PageHost,
        board: String,
        key: String,
    ): Snapshots =
        Visit(host, board, key).use { visit ->
            visit.open(HOST_PAGE)
            println("1065|B9|policy-as-sent|${visit.documentPolicy}")
            withClue("the document's own response carries the test's policy (read back before any violation is counted)") {
                visit.documentPolicy shouldBe policy(host.origin)
            }
            val hostBefore = visit.call("hostSnapshot").asSnapshot()
            val customBefore = visit.customCounts()
            visit.call("setWidth", "${FIXED_WIDTH}px")
            println("1065|B1|sheets|${visit.call("addSheets", listOf("$libraryOrigin/css/datapipelines.css"))}")
            visit.boot()
            visit.settle("the host page at ${FIXED_WIDTH}px")
            val inside = visit.snapshotInside()
            val hostAfter = visit.call("hostSnapshot").asSnapshot()
            val customAfter = visit.customCounts()
            println("1065|B2|custom-properties|before=$customBefore after=$customAfter classes=${visit.call("classes")}")
            assertSoftly {
                compareHost(hostBefore, hostAfter)
                withClue("B2: custom properties on the host's root and body, before and after (the same count)") {
                    customAfter["html"] shouldBe customBefore["html"]
                    customAfter["body"] shouldBe customBefore["body"]
                }
                withClue("B2: custom properties on the board container (floor $CUSTOM_FLOOR)") {
                    (customAfter["dashboard"] ?: 0) shouldBeGreaterThanOrEqual CUSTOM_FLOOR
                }
            }
            val (zoomedWidth, insideAtZoom) = zoomCourse(visit)
            visit.call("addSheets", listOf("${host.origin}/scoped/host-background.css"))
            val background = visit.theme("revenue")["containerBackground"]
            println("1065|B8|container-background|$background")
            withClue("B8: the host's rule on its container's id wins over the library's background") {
                background shouldBe HOST_BACKGROUND
            }
            val violations = drainCspViolations()
            println("1065|B9|csp-violations|count=${violations.size} $violations")
            println("1065|B9|console-errors|${visit.consoleErrors}")
            println("1065|B9|relay|${visit.relayed}")
            withClue("B9: content-policy violations") { violations.shouldBeEmpty() }
            withClue("B9: console errors") { visit.consoleErrors.shouldBeEmpty() }
            Snapshots(inside, insideAtZoom, zoomedWidth)
        }

    /**
     * B10: the containers at the page's width; the viewport 1,300 → 867 CSS px at a device scale factor of 1.5 (what
     * the browser's own zoom of 150% gives the page) → each picture is its host's new width, the fonts in CSS px are
     * the ones before, and B3 holds at that state; back at 1,300 each picture has its first width.
     */
    private fun zoomCourse(visit: Visit): Pair<Double, List<Map<String, Any?>>> {
        visit.call("setWidth", "auto")
        visit.settle("the host page at the page's width")
        val before = visit.zoom()
        visit.metrics(ZOOMED_WIDTH, ZOOMED_HEIGHT, ZOOM)
        val followed =
            visit.waitFor("each picture redrawn at its host's new width") {
                visit.call("chartsFit") == true &&
                    visit.zoomedFrom(before)
            }
        val at150 = visit.zoom()
        println("1065|B10|before|$before")
        println("1065|B10|at150|$at150 followed=$followed")
        visit.settle("the host page at the zoom of 150%")
        val insideAt150 = visit.snapshotInside()
        visit.metrics(FULL_WIDTH, FULL_HEIGHT, 1.0)
        visit.waitFor("each picture back at its first width") { visit.call("chartsFit") == true && !visit.zoomedFrom(before) }
        val back = visit.zoom()
        println("1065|B10|back|$back")
        val pictures = before.pictures()
        assertSoftly {
            withClue("B10: chart pictures (floor $CHART_FLOOR)") { pictures.size shouldBeGreaterThanOrEqual CHART_FLOOR }
            withClue("B10: the card follows the viewport (CSS layout)") {
                (at150["card"] as Number).toDouble() shouldBeLessThan (before["card"] as Number).toDouble()
            }
            withClue("B10: at 150% each picture is its host's new width and its viewBox that width") {
                followed shouldBe true
                for (picture in at150.pictures()) {
                    abs(picture.getValue("width") - picture.getValue("hostWidth")) shouldBeLessThan 1.0
                    abs(picture.getValue("viewBoxWidth") - picture.getValue("width")) shouldBeLessThan PRECISION
                }
                at150.pictures().map { it.getValue("width") } shouldNotBe pictures.map { it.getValue("width") }
            }
            withClue("B10: the font sizes in CSS px (a card, a table cell, a chart's text) are the ones before") {
                listOf(at150["cardFont"], at150["cellFont"], at150["chartTextFont"]) shouldBe
                    listOf(before["cardFont"], before["cellFont"], before["chartTextFont"])
            }
            withClue("B10: back at 1,300 each picture has its first width") {
                back.pictures().map { it.getValue("width") } shouldBe pictures.map { it.getValue("width") }
            }
        }
        return (at150["container"] as Number).toDouble() to insideAt150
    }

    /** B7: a host page whose root font size is 10px; compared with the reference by the caller (B3 still 0). */
    private fun rootFontRun(
        host: PageHost,
        board: String,
        key: String,
    ): List<Map<String, Any?>> =
        Visit(host, board, key).use { visit ->
            visit.open(HOST_PAGE)
            visit.call("setRootFont", "10px")
            visit.call("setWidth", "${FIXED_WIDTH}px")
            visit.call("addSheets", listOf("$libraryOrigin/css/datapipelines.css"))
            visit.boot()
            visit.settle("the host page with a 10px root")
            println("1065|B7|root-font|${visit.page.evaluate("() => getComputedStyle(document.documentElement).fontSize")}")
            visit.snapshotInside()
        }

    /** B4: the dark sheet loaded with no attribute changes nothing; the attribute turns cards and charts dark; removed, light. */
    private fun themeAtRunTime(
        host: PageHost,
        board: String,
        key: String,
    ): Map<String, Any?> =
        Visit(host, board, key).use { visit ->
            visit.open(HOST_PAGE)
            visit.call("setWidth", "${FIXED_WIDTH}px")
            visit.call("addSheets", listOf("$libraryOrigin/css/datapipelines.css"))
            visit.boot()
            visit.settle("the light dashboard")
            val light = visit.theme("revenue")
            println("1065|B4|sheet|${visit.call("addSheets", listOf(themeSheet("dark")))}")
            visit.settle("the dark sheet loaded, no attribute")
            val sheetOnly = visit.theme("revenue")
            visit.call("setTheme", "dark")
            val turned = visit.waitFor("the chart's canvas turned to the dark token") { visit.theme("revenue").isDrawnInItsTokens(light) }
            val dark = visit.theme("revenue")
            visit.call("setTheme", null)
            visit.waitFor("the chart's canvas back to the light value") { visit.theme("revenue")["canvasFill"] == light["canvasFill"] }
            val again = visit.theme("revenue")
            val darkShadow = darkTipShadow(visit)
            println("1065|B4|light|$light")
            println("1065|B4|sheet-only|$sheetOnly")
            println("1065|B4|dark|$dark turned=$turned shadow-expected=$darkShadow")
            println("1065|B4|again|$again")
            assertSoftly {
                withClue("B4 step 1: the dark sheet with no attribute leaves the card and the chart light") {
                    sheetOnly["cardBackground"] shouldBe light["cardBackground"]
                    sheetOnly["canvasFill"] shouldBe light["canvasFill"]
                }
                withClue("B4 step 2: data-theme=\"dark\" → the card's background is the dark surface token") {
                    dark["cardBackground"] shouldBe dark["surfaceToken"]
                    dark["cardBackground"] shouldNotBe light["cardBackground"]
                }
                withClue("B4 step 2: the chart's canvas fill is the computed --chart-canvas, and it changed") {
                    dark["canvasFill"] shouldBe dark["canvasToken"]
                    dark["canvasFill"] shouldNotBe light["canvasFill"]
                }
                withClue("B4 step 2: the chart's text ink is one of the dark ink tokens, and it changed") {
                    dark["textInk"] shouldBeIn dark.inkTokens()
                    dark["textInk"] shouldNotBe light["textInk"]
                }
                withClue("B4 step 2: the tooltip shadow token is the dark one") {
                    dark["tipShadow"].toString().noSpace() shouldBe darkShadow.noSpace()
                    dark["tipShadow"] shouldNotBe light["tipShadow"]
                }
                withClue("B4 step 3: the attribute removed → the light values again") {
                    listOf(again["cardBackground"], again["canvasFill"], again["textInk"], again["tipShadow"]) shouldBe
                        listOf(light["cardBackground"], light["canvasFill"], light["textInk"], light["tipShadow"])
                }
            }
            light
        }

    /** B5: the sheet and the attribute in the page before the dashboard starts → the first drawn picture is dark. */
    private fun themeFromTheStart(
        host: PageHost,
        board: String,
        key: String,
        light: Map<String, Any?>,
    ) {
        Visit(host, board, key).use { visit ->
            visit.open(HOST_PAGE)
            visit.call("setWidth", "${FIXED_WIDTH}px")
            visit.call("addSheets", listOf("$libraryOrigin/css/datapipelines.css", themeSheet("dark")))
            visit.call("setTheme", "dark")
            visit.call("watchFirstFills")
            visit.boot()
            visit.settle("the dark dashboard from the start")
            val dark = visit.theme("revenue")

            @Suppress("UNCHECKED_CAST")
            val first = visit.call("firstFills") as List<String>
            println("1065|B5|first-fills|$first canvas-token=${dark["canvasToken"]} card=${dark["cardBackground"]}")
            withClue("B5: every chart's FIRST canvas is the dark --chart-canvas, and the card is the dark surface") {
                first.size shouldBeGreaterThanOrEqual CHART_FLOOR
                first.distinct() shouldBe listOf(dark["canvasToken"])
                first.first() shouldNotBe light["canvasFill"]
                dark["cardBackground"] shouldBe dark["surfaceToken"]
            }
        }
    }

    /** B6: `auto` under an emulated dark colour scheme is dark; under a light one, light. */
    private fun themeAuto(
        host: PageHost,
        board: String,
        key: String,
    ) {
        Visit(host, board, key, ColorScheme.DARK).use { visit ->
            visit.open(HOST_PAGE)
            visit.call("setWidth", "${FIXED_WIDTH}px")
            visit.call("addSheets", listOf("$libraryOrigin/css/datapipelines.css", themeSheet("auto")))
            visit.call("setTheme", "auto")
            visit.boot()
            visit.settle("auto under a dark scheme")
            val dark = visit.theme("revenue")
            visit.page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
            visit.waitFor("auto redrawn under a light scheme") { visit.theme("revenue")["canvasFill"] != dark["canvasFill"] }
            visit.settle("auto under a light scheme")
            val light = visit.theme("revenue")
            println("1065|B6|dark-scheme|$dark")
            println("1065|B6|light-scheme|$light")
            assertSoftly {
                withClue("B6: under a dark scheme the card and the canvas are their tokens, and differ from the light scheme's") {
                    dark["cardBackground"] shouldBe dark["surfaceToken"]
                    dark["canvasFill"] shouldBe dark["canvasToken"]
                    dark["canvasFill"] shouldNotBe light["canvasFill"]
                    dark["cardBackground"] shouldNotBe light["cardBackground"]
                }
                withClue("B6: under a light scheme the card and the canvas are the light tokens") {
                    light["cardBackground"] shouldBe light["surfaceToken"]
                    light["canvasFill"] shouldBe light["canvasToken"]
                }
            }
        }
    }

    private fun themeSheet(name: String): String = "$libraryOrigin/css/datapipelines-theme-$name.css"

    /** The dark `--chart-tip-shadow` as the served stylesheet declares it (its rule on the dark attribute). */
    private fun darkTipShadow(visit: Visit): String {
        val text =
            visit.page
                .request()
                .get("$libraryOrigin/css/datapipelines.css")
                .text()
        val rule = requireNotNull(DARK_SHADOW.find(text)) { "no dark --chart-tip-shadow rule in the served stylesheet" }
        return rule.groupValues[1].trim()
    }

    /** B1: the host's elements before any Datapipelines stylesheet against the end of the same visit. */
    private fun compareHost(
        before: List<Map<String, Any?>>,
        after: List<Map<String, Any?>>,
    ) {
        withClue("B1: the host's elements hold the same sequence of tags") { after.tags() shouldBe before.tags() }
        val changed =
            before.zip(after).mapIndexedNotNull { i, (b, a) ->
                val left = HOST_HEIGHT_ONLY.takeIf { b["tag"] in PAGE_ELEMENTS }.orEmpty()
                differences(b.styles(), a.styles(), left).takeIf { it.isNotEmpty() }?.let { "#$i ${b["tag"]}: $it" }
            }
        println("1065|B1|host-elements|compared=${before.size} floor=$HOST_FLOOR changed=${changed.size} ${changed.take(SHOWN)}")
        withClue("B1: host elements compared (floor $HOST_FLOOR)") { before.size shouldBeGreaterThanOrEqual HOST_FLOOR }
        withClue("B1: host elements changed by the stylesheet") { changed.shouldBeEmpty() }
    }

    /** B3 and its variants: the elements inside the two containers, and on the containers only the two expected properties. */
    private fun compareInside(
        label: String,
        reference: List<Map<String, Any?>>,
        actual: List<Map<String, Any?>>,
    ) {
        withClue("$label: the two runs hold the same sequence of tags") { actual.tags() shouldBe reference.tags() }
        val inside = mutableListOf<String>()
        val onContainers = mutableMapOf<String, Set<String>>()
        reference.zip(actual).forEachIndexed { i, (r, a) ->
            val diff = differences(r.styles(), a.styles(), emptySet())
            val tag = r["tag"].toString()
            if (tag.startsWith("container#")) {
                onContainers[tag] = diff.keys
            } else if (diff.isNotEmpty()) {
                inside += "#$i $tag: $diff"
            }
        }
        val compared = reference.count { !it["tag"].toString().startsWith("container#") }
        println("1065|$label|inside|compared=$compared floor=$INSIDE_FLOOR differ=${inside.size} ${inside.take(SHOWN)}")
        println("1065|$label|containers|$onContainers")
        withClue(
            "$label: elements compared inside the containers (floor $INSIDE_FLOOR)",
        ) { compared shouldBeGreaterThanOrEqual INSIDE_FLOOR }
        withClue("$label: elements inside the containers that differ from the reference") { inside.shouldBeEmpty() }
        withClue("$label: on the two containers only background-color and box-sizing may differ") {
            onContainers.values
                .flatten()
                .toSet()
                .filterNot { it in CONTAINER_ONLY }
                .shouldBeEmpty()
        }
    }

    /** property → "reference → actual" for every property that differs, leaving out [left] (B1's four, on html and body). */
    private fun differences(
        reference: Map<String, String>,
        actual: Map<String, String>,
        left: Set<String>,
    ): Map<String, String> {
        val names = (reference.keys + actual.keys) - left.filter { !it.contains(':') }.toSet()
        val out = sortedMapOf<String, String>()
        for (name in names) {
            var r = reference[name]
            var a = actual[name]
            if (name in ORIGINS && "$name:vertical" in left) {
                r = r?.substringBefore(' ')
                a = a?.substringBefore(' ')
            }
            if (r != a) out[name] = "$r → $a"
        }
        return out
    }

    /** One visit in a context of its own: the relay's route, the page's console errors. */
    private inner class Visit(
        val host: PageHost,
        val board: String,
        val key: String,
        scheme: ColorScheme? = null,
    ) : AutoCloseable {
        private val context: BrowserContext =
            newBrowserContext(
                Browser
                    .NewContextOptions()
                    .setViewportSize(FULL_WIDTH, FULL_HEIGHT)
                    .apply { if (scheme != null) setColorScheme(scheme) },
            )
        lateinit var page: Page
        var documentPolicy: String? = null
        val consoleErrors = CopyOnWriteArrayList<String>()
        val relayed = CopyOnWriteArrayList<String>()

        @Volatile
        private var closing = false

        fun open(path: String) {
            host.policy = policy(host.origin)
            context.route("${host.origin}$RELAY_PATH/**") { route -> relay(route) }
            page = context.newPage()
            page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
            page.onPageError { error -> consoleErrors += "page error: $error" }
            var response: Response? = null
            named(page, "the page ${host.origin}$path") { response = page.navigate("${host.origin}$path") }
            documentPolicy = response?.headers()?.get("content-security-policy")
            page.evaluate(PROBE)
        }

        fun call(
            name: String,
            arg: Any? = null,
        ): Any? = page.evaluate("(arg) => window.__dp1065.$name(arg)", arg)

        fun boot() {
            val booted =
                call(
                    "boot",
                    mapOf(
                        "script" to "$libraryOrigin/js/datapipelines.js",
                        "proxyBase" to "${host.origin}$RELAY_PATH",
                        "board" to board,
                    ),
                )
            println("1065|boot|$booted relay=$relayed")
            check(booted == "ready") { "the dashboard did not boot: $booted; relay $relayed; console $consoleErrors" }
        }

        fun settle(what: String) {
            val args = mapOf("charts" to CHARTS, "rows" to TABLE_ROWS, "kpi" to KPI_VALUE)
            val settled = waitFor(what) { call("settled", args) == true }
            check(settled) { "$what never settled: ${call("zoom")}; console $consoleErrors" }
            // Two frames more: a redraw scheduled by the last change lands before anything is read.
            page.evaluate("() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))")
        }

        /** True when [condition] held within the wait; false when it did not (the caller asserts with its own words). */
        fun waitFor(
            what: String,
            condition: () -> Boolean,
        ): Boolean =
            try {
                page.waitForCondition({ condition() }, Page.WaitForConditionOptions().setTimeout(WAIT_MILLIS))
                true
            } catch (timeout: PlaywrightException) {
                println("1065|wait|$what|not within ${WAIT_MILLIS}ms: ${timeout.message?.lineSequence()?.first()}")
                false
            }

        fun snapshotInside(): List<Map<String, Any?>> = call("insideSnapshot").asSnapshot()

        @Suppress("UNCHECKED_CAST")
        fun customCounts(): Map<String, Int> = (call("customCounts") as Map<String, Number>).mapValues { it.value.toInt() }

        @Suppress("UNCHECKED_CAST")
        fun theme(viz: String): Map<String, Any?> = call("theme", viz) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun zoom(): Map<String, Any?> = call("zoom") as Map<String, Any?>

        fun zoomedFrom(before: Map<String, Any?>): Boolean = zoom().pictures().map { it["width"] } != before.pictures().map { it["width"] }

        /** The viewport the page sees, set through the DevTools protocol (`Emulation.setDeviceMetricsOverride`). */
        fun metrics(
            width: Int,
            height: Int,
            scale: Double,
        ) {
            val parameters =
                JsonObject().apply {
                    addProperty("width", width)
                    addProperty("height", height)
                    addProperty("deviceScaleFactor", scale)
                    addProperty("mobile", false)
                }
            context.newCDPSession(page).send("Emulation.setDeviceMetricsOverride", parameters)
        }

        private fun relay(route: Route) {
            val url = URI(route.request().url())
            val path = url.rawPath.removePrefix(RELAY_PATH)
            val method = route.request().method()
            if (RUNTIME_ROUTES.none { (m, pattern) -> m == method && pattern.matches(path) }) {
                relayed += "$method $path=404(not relayed)"
                route.fulfill(
                    Route
                        .FulfillOptions()
                        .setStatus(NOT_FOUND)
                        .setContentType("application/json")
                        .setBody(NOT_RELAYED),
                )
                return
            }
            val forwarded = route.request().headers().filterKeys { it.lowercase() in FORWARDED_HEADERS } + ("DP-API-Key" to key)
            val upstream = baseUrl + path + (url.rawQuery?.let { "?$it" } ?: "")
            try {
                val answer = route.fetch(Route.FetchOptions().setUrl(upstream).setHeaders(forwarded))
                relayed += "$method $path=${answer.status()}"
                route.fulfill(Route.FulfillOptions().setResponse(answer))
            } catch (closed: PlaywrightException) {
                if (!closing) throw closed
            }
        }

        override fun close() {
            closing = true
            context.close()
        }
    }

    /** The page's origin: a loopback server answering this class's resources under `/scoped/`, the pages with [policy]. */
    private inner class PageHost : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        val origin: String = "http://$LOOPBACK:${server.address.port}"

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
            val name = exchange.requestURI.rawPath.removePrefix("/scoped/")
            val body = name.takeIf { it in SERVED }?.let { resource(it) }
            val type = if (name.endsWith(".css")) "text/css; charset=utf-8" else "text/html; charset=utf-8"
            exchange.responseHeaders.set("Content-Type", type)
            if (name.endsWith(".html")) policy?.let { exchange.responseHeaders.set("Content-Security-Policy", it) }
            val bytes = body ?: "not part of the test's host".toByteArray()
            exchange.sendResponseHeaders(if (body == null) NOT_FOUND else OK, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        override fun close() = server.stop(0)
    }

    /** A dashboard key minted through the REST route (the signed-in admin's page) and bound to [folder]: T13's way. */
    @Suppress("UNCHECKED_CAST")
    private fun mintDashboardKey(folder: String): String {
        val result =
            page.evaluate(
                """async (folder) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const minted = await fetch('/api/v1/auth/api-keys', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ name: 'scoped stylesheet ' + Date.now(), kind: 'dashboard' }) });
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

    private companion object {
        const val HOST_PAGE = "/scoped/host.html"
        const val BARE_PAGE = "/scoped/bare.html"
        const val RELAY_PATH = "/dp"
        const val LOOPBACK = "127.0.0.1"
        const val OK = 200
        const val NOT_FOUND = 404
        const val FIXED_WIDTH = 1100
        const val FULL_WIDTH = 1300
        const val FULL_HEIGHT = 1000
        const val ZOOMED_WIDTH = 867
        const val ZOOMED_HEIGHT = 667
        const val ZOOM = 1.5
        const val HOST_FLOOR = 15
        const val CUSTOM_FLOOR = 300
        const val INSIDE_FLOOR = 100
        const val CHART_FLOOR = 2
        const val TABLE_ROWS = 5
        const val KPI_VALUE = "42"
        const val SHOWN = 8
        const val PRECISION = 0.01
        const val WAIT_MILLIS = 15_000.0
        const val HOST_BACKGROUND = "rgb(250, 240, 200)"
        const val NOT_RELAYED = """{"error":{"code":"proxy.not_found","message":"not relayed","details":{}}}"""

        /** The board's two charts ([seedBoardWithGrid]'s occurrences). */
        val CHARTS = listOf("revenue", "slowchart")

        val SERVED = setOf("host.html", "bare.html", "host.css", "host-background.css")

        /** The six source sheets of the library's stylesheet, in its order (#1064 D2), as the product's pages load them. */
        val SOURCE_SHEETS =
            listOf(
                "/vendor/design-system/tokens.css",
                "/vendor/design-system/themes/light.css",
                "/vendor/design-system/base.css",
                "/vendor/design-system/primitives.css",
                "/css/app.css",
                "/css/dashboards.css",
            )

        /**
         * B1's closed list, on `html` and `body` ONLY: the page is taller once a dashboard stands in it, and each of
         * these is the element's own height — `block-size`, `height`, and the VERTICAL part of `perspective-origin`
         * and `transform-origin` (a computed origin is half the box in px; its horizontal part is compared).
         */
        val HOST_HEIGHT_ONLY = setOf("block-size", "height", "perspective-origin:vertical", "transform-origin:vertical")
        val ORIGINS = setOf("perspective-origin", "transform-origin")
        val PAGE_ELEMENTS = setOf("html", "body")

        /** B3: the container is the host's element: the theme's surface is painted on it, and its box sizing is the host's. */
        val CONTAINER_ONLY = setOf("background-color", "box-sizing")

        val DARK_SHADOW = Regex("""&:is\(&\)\[data-theme="dark"\]\s*\{\s*--chart-tip-shadow:\s*([^;}]+)""")

        val RUNTIME_ROUTES =
            listOf(
                "GET" to Regex("""^/api/v1/dashboards/[^/]+/runtime/config$"""),
                "POST" to Regex("""^/api/v1/dashboards/[^/]+/runtime/parameters$"""),
                "POST" to Regex("""^/api/v1/dashboards/[^/]+/runtime/visualizations$"""),
                "POST" to Regex("""^/api/v1/dashboards/[^/]+/runtime/refreshes/[^/]+/abort$"""),
            )
        val FORWARDED_HEADERS = setOf("accept", "content-type", "last-event-id")

        val PROBE: String by lazy { String(resource("probe.js"), Charsets.UTF_8) }

        fun resource(name: String): ByteArray =
            requireNotNull(
                ScopedStylesheetBrowserTest::class.java.getResource("/scoped-stylesheet/$name"),
            ) { "no resource $name" }.readBytes()

        @Suppress("UNCHECKED_CAST")
        fun Any?.asSnapshot(): List<Map<String, Any?>> = (this as List<Map<String, Any?>>).also { it.shouldNotBeEmpty() }

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.styles(): Map<String, String> = this["styles"] as Map<String, String>

        fun List<Map<String, Any?>>.tags(): List<Any?> = map { it["tag"] }

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.pictures(): List<Map<String, Double>> =
            (this["pictures"] as List<Map<String, Number>>).map { picture -> picture.mapValues { it.value.toDouble() } }

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.inkTokens(): List<Any?> = this["inkTokens"] as List<Any?>

        /** The canvas is drawn in its own token, and it is no longer [light]'s. */
        fun Map<String, Any?>.isDrawnInItsTokens(light: Map<String, Any?>): Boolean =
            this["canvasFill"] == this["canvasToken"] && this["canvasFill"] != light["canvasFill"]

        fun String.noSpace(): String = replace(Regex("""\s+"""), "")
    }
}
