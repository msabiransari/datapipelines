package co.datapipelines.browser

import com.google.gson.JsonObject
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.ConsoleMessage
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitUntilState
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #1018 — a chart's text is measured in the font it is drawn in, also when the font file arrives late or never
 * (record 2026-11-14 §6, "the web font arrives after the first draw": one redraw with fresh measurements).
 *
 * **The recording.** An init script defines `JsRendered` on `window` as an accessor before any page script runs. When
 * the engine's first file assigns its namespace, the setter keeps the object and makes `render` and `writeDom`
 * accessors on it: the engine's own functions are kept, wrapped ones handed out. The wrapped `render` notes what the
 * adapter's `measure` callback RETURNED for every call of that draw; the wrapped `writeDom` files the draw under the
 * very `svg` it is handed. So every picture has its own draws in order, and nothing is read from a canvas.
 *
 * **A read** of one picture, against its LAST draw: every non-empty drawn text's `getComputedTextLength()` equals the
 * width the adapter returned for that text at its computed size and weight within [SLACK_PX] ("measured in the draw"),
 * or, without such a note, what that draw's own callback answers at the read ("asked at the read"); and G2's text-box
 * rule (nothing outside the picture, no two texts meeting). The report adds the test's own canvas width, the theme's
 * family list, the font Chromium drew with (CDP) and `document.fonts.check`, for the handback only.
 *
 * **The situations** (each its own test): the preview page (no preload) with G2's fixtures at G2's narrowest width
 * with the font files passed at once, held then released, aborted, and a repeat visit in a context with no route; the
 * board (the layout's preloads) with the files passed at once and held then released. Each is written to
 * `build/reports/chart-font-timing/`.
 */
class ChartFontTimingBrowserTest : ChartPageSuite() {
    /** Falsification copies of the adapter (a gate's plant is ONE entry here); empty in the product. */
    override val copies: Map<String, (String) -> String> = emptyMap()

    private val consoleErrors = mutableListOf<String>()
    private val lines = mutableListOf<String>()

    @AfterEach
    fun assertCleanConsole() {
        val seen = consoleErrors.toList()
        consoleErrors.clear()
        withClue("the test's pages wrote no console error and threw no page error") { seen.shouldBeEmpty() }
    }

    @Test
    fun `at once - every text is measured in the font it is drawn in, the cached redraw too`() {
        startTrace()
        val root = ready("cftnow")
        situation("at-once") {
            for (name in JsRenderedPageBrowserTest.G2_FIXTURES) {
                val fonts = FontRoute(FontRoute.Mode.PASS)
                val agent = previewContext(fonts)
                try {
                    val page = openPreview(agent, session(createCase(root, name, "now")))
                    settleFonts(page)
                    judge(read(page, PREVIEW_SVG, 0, "$name read 1"), PREVIEW_FLOOR)
                    val before = draws(page, PREVIEW_SVG, 0)
                    page.evaluate("() => { $HANDLE.resize(); return true; }")
                    withClue("$name: the asked redraw reached the picture") { grew(page, PREVIEW_SVG, 0, before) shouldBe true }
                    frames(page)
                    judge(read(page, PREVIEW_SVG, 0, "$name read 2 (redraw, cached widths)"), PREVIEW_FLOOR)
                    lines += "$name font requests passed=${fonts.seen}"
                    withClue("$name: the font route was hit") { fonts.seen shouldBeGreaterThan 0 }
                } finally {
                    agent.close()
                }
            }
        }
    }

    @Test
    fun `late - the adapter redraws by itself when held font files arrive, and measures in the drawn font`() {
        startTrace()
        val root = ready("cftlate")
        situation("late") {
            for (name in JsRenderedPageBrowserTest.G2_FIXTURES) {
                val fonts = FontRoute(FontRoute.Mode.HOLD)
                val agent = previewContext(fonts)
                try {
                    val page = openPreview(agent, session(createCase(root, name, "late")))
                    late(page, fonts, name, PREVIEW_SVG, 1, PREVIEW_FLOOR)
                } finally {
                    fonts.release()
                    agent.close()
                }
            }
        }
    }

    @Test
    fun `never - with every font file aborted each text is measured in the fallback it is drawn in`() {
        startTrace()
        val root = ready("cftnever")
        situation("never") {
            for (name in JsRenderedPageBrowserTest.G2_FIXTURES) {
                val fonts = FontRoute(FontRoute.Mode.ABORT)
                val agent = previewContext(fonts)
                try {
                    val page = openPreview(agent, session(createCase(root, name, "never")))
                    settleFonts(page)
                    judge(read(page, PREVIEW_SVG, 0, "$name read"), PREVIEW_FLOOR)
                    lines += "$name font requests aborted=${fonts.seen}"
                    withClue("$name: the font route was hit") { fonts.seen shouldBeGreaterThan 0 }
                } finally {
                    agent.close()
                }
            }
        }
    }

    /**
     * The repeat visit, in a context with NO route (a route turns the HTTP cache off). The cache class of each font file
     * of visit 2 is evidence, printed; it is not asserted (#1023 turns it into an assertion).
     */
    @Test
    fun `warm - a repeat visit measures in the drawn font, and its font files' cache class is printed`() {
        startTrace()
        val root = ready("cftwarm")
        situation("warm") {
            for (name in JsRenderedPageBrowserTest.G2_FIXTURES) {
                val agent = listening(newBrowserContext(Browser.NewContextOptions().setViewportSize(NARROW, DEFAULT_HEIGHT)))
                agent.addInitScript(RECORDER)
                try {
                    val id = createCase(root, name, "warm")
                    val headers = mutableListOf<String>()
                    agent.onResponse { r ->
                        if (FONT_PATH in r.url()) headers += "${r.url()} cache-control=${r.headerValue("cache-control")}"
                    }
                    val first = openPreview(agent, session(id))
                    first.evaluate(FONTS_READY)
                    val visit1 = timings(first)
                    first.close()
                    val second = openPreview(agent, session(id))
                    settleFonts(second)
                    val visit2 = timings(second)
                    lines += "$name font responses of both visits: ${headers.ifEmpty { listOf("(none)") }}"
                    lines += "$name visit 1 font timings: $visit1"
                    lines += "$name visit 2 font timings: $visit2"
                    lines += "$name visit 2 classes: ${visit2.map { classOf(it) }}"
                    withClue("$name: a font file on each visit") {
                        visit1.size shouldBeGreaterThan 0
                        visit2.size shouldBeGreaterThan 0
                    }
                    judge(read(second, PREVIEW_SVG, 0, "$name visit 2 read"), PREVIEW_FLOOR)
                } finally {
                    agent.close()
                }
            }
        }
    }

    @Test
    fun `board at once - each picture of a board is measured in the font it is drawn in`() {
        startTrace()
        val root = ready("cftboard")
        val board = seedBoardWithGrid(root)
        situation("board-at-once") {
            val fonts = FontRoute(FontRoute.Mode.PASS)
            val boardPage = openBoard(board, fonts)
            try {
                settleFonts(boardPage)
                repeat(BOARD_CHARTS) { judge(read(boardPage, BOARD_SVG, it, "board picture $it"), null) }
                lines += "board font requests passed=${fonts.seen}"
                withClue("board: the font route was hit") { fonts.seen shouldBeGreaterThan 0 }
            } finally {
                closeBoard(boardPage, fonts)
            }
        }
    }

    @Test
    fun `board late - each picture of a board redraws by itself when held font files arrive`() {
        startTrace()
        val root = ready("cftboardlate")
        val board = seedBoardWithGrid(root)
        situation("board-late") {
            val fonts = FontRoute(FontRoute.Mode.HOLD)
            val boardPage = openBoard(board, fonts)
            try {
                late(boardPage, fonts, "board", BOARD_SVG, BOARD_CHARTS, null)
            } finally {
                closeBoard(boardPage, fonts)
            }
        }
    }

    /**
     * "late" on one page: read 1 while a font request is held, then the release, `document.fonts.ready`, and a draw of
     * each picture's own within [LATE_REDRAW_MS] (never asked for here), two frames, read 2.
     */
    private fun late(
        page: Page,
        fonts: FontRoute,
        name: String,
        selector: String,
        pictures: Int,
        floor: Int?,
    ) {
        val before =
            (0 until pictures).map { i ->
                withClue("$name: a font request is still held at read 1 (held=${fonts.held.size})") {
                    fonts.held.size shouldBeGreaterThan 0
                }
                judge(read(page, selector, i, "$name picture $i read 1 (files held)"), floor)
                draws(page, selector, i)
            }
        lines += "$name font requests held=${fonts.seen} before the release"
        fonts.release()
        page.evaluate(FONTS_READY)
        val redrew = (0 until pictures).map { i -> grew(page, selector, i, before[i], LATE_REDRAW_MS) }
        lines += "$name redrew after the release, per picture: $redrew"
        frames(page)
        (0 until pictures).forEach { i ->
            withClue("$name picture $i: the adapter drew again by itself after the files arrived") { redrew[i] shouldBe true }
            judge(read(page, selector, i, "$name picture $i read 2 (after the release)"), floor)
        }
        withClue("$name: the font route was hit") { fonts.seen shouldBeGreaterThan 0 }
    }

    /** Runs [body] softly, writes the situation's report, and drains the policy collector. */
    private fun situation(
        name: String,
        body: () -> Unit,
    ) {
        try {
            assertSoftly { body() }
        } finally {
            report(name)
        }
        drainCspViolations().shouldBeEmpty()
    }

    /** A cookie-less preview context at [NARROW] px: the plants served, the recorder installed, the font route on. */
    private fun previewContext(fonts: FontRoute): BrowserContext {
        val agent = listening(chartContext(width = NARROW))
        agent.addInitScript(RECORDER)
        agent.route(FONT_GLOB) { fonts.handle(it) }
        return agent
    }

    /** The board in a new page of the signed-in context: the recorder, the plants and the font route on that page. */
    private fun openBoard(
        board: String,
        fonts: FontRoute,
    ): Page {
        val boardPage = page.context().newPage()
        boardPage.setDefaultTimeout(PREVIEW_TIMEOUT_MS)
        listen(boardPage)
        boardPage.addInitScript(RECORDER)
        for ((suffix, rewrite) in copies) {
            boardPage.route("**$suffix") { route ->
                val response = route.fetch()
                route.fulfill(Route.FulfillOptions().setResponse(response).setBody(rewrite(response.text())))
            }
        }
        boardPage.route(FONT_GLOB) { fonts.handle(it) }
        boardPage.navigate("$baseUrl/dashboards/$board", Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
        boardPage.waitForFunction(BOARD_READY, BOARD_CHARTS)
        return boardPage
    }

    private fun closeBoard(
        boardPage: Page,
        fonts: FontRoute,
    ) {
        fonts.release()
        boardPage.unroute(FONT_GLOB)
        boardPage.close()
    }

    /** A visualization of G2's fixture [fixture] created in the workspace [root]; its id. */
    private fun createCase(
        root: String,
        fixture: String,
        tag: String,
    ): String {
        val document = documentOf("$root/charts/${fixture.replace('-', '_')}_$tag", ChartBrowserFixtures.fixture(fixture))
        return api("POST", "/api/v1/visualizations", document).at("data", "id") as String
    }

    /** A new test session of [id]: its preview URL. */
    private fun session(id: String): String {
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        return started.at("data", "preview_url") as String
    }

    /**
     * The preview at [previewUrl], navigated to DOMContentLoaded (a held font can hold `load` back), ready when its case
     * is mounted and the card's state is `success` or `ready`; `document.fonts.ready` is NOT awaited here.
     */
    private fun openPreview(
        context: BrowserContext,
        previewUrl: String,
    ): Page {
        val preview = context.newPage()
        preview.setDefaultTimeout(PREVIEW_TIMEOUT_MS)
        preview.navigate("$baseUrl$previewUrl&theme=light", Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
        preview.waitForSelector("$CASE[data-dp-ready='true']")
        preview.waitForFunction(CARD_READY, CASE)
        return preview
    }

    private fun settleFonts(page: Page) {
        page.evaluate(FONTS_READY)
        frames(page)
    }

    private fun frames(page: Page) {
        page.evaluate(TWO_FRAMES)
    }

    /** How many draws the recorder filed under picture [index] of [selector]. */
    private fun draws(
        page: Page,
        selector: String,
        index: Int,
    ): Int = (page.evaluate(DRAW_COUNT, mapOf("sel" to selector, "index" to index)) as Number).toInt()

    /** Whether picture [index] gets a draw beyond [before] within [timeoutMs]. */
    private fun grew(
        page: Page,
        selector: String,
        index: Int,
        before: Int,
        timeoutMs: Double = PREVIEW_TIMEOUT_MS,
    ): Boolean =
        try {
            page.waitForFunction(
                "a => { var s = document.querySelectorAll(a.sel)[a.index]; var l = s && window.__fontTiming.pictures.get(s);" +
                    " return !!l && l.length > a.n; }",
                mapOf("sel" to selector, "index" to index, "n" to before),
                Page.WaitForFunctionOptions().setTimeout(timeoutMs),
            )
            true
        } catch (e: PlaywrightException) {
            lines += "no draw beyond $before within $timeoutMs ms: ${e.message?.lineSequence()?.firstOrNull()}"
            false
        }

    /** One read of picture [index] of [selector] (the class KDoc), with the font Chromium drew its first text in. */
    private fun read(
        page: Page,
        selector: String,
        index: Int,
        label: String,
    ): Map<*, *> {
        val read = page.evaluate(READ, mapOf("sel" to selector, "index" to index, "slack" to SLACK_PX)) as Map<*, *>
        lines += "$label $read fontDrawn=${fontDrawn(page, selector, index)}"
        return HashMap<Any?, Any?>(read).apply { put("label", label) }
    }

    /** Criterion 1 and G2's text boxes on [read]; [floor]: the least number of texts judged (the preview's). */
    private fun judge(
        read: Map<*, *>,
        floor: Int?,
    ) {
        withClue("${read["label"]}: $read") {
            read["installed"] shouldBe true
            read["unrecorded"] shouldBe false
            (read["draws"] as Number).toInt() shouldBeGreaterThanOrEqual 1
            val judged = (read["judged"] as Number).toInt()
            if (floor != null) judged shouldBeGreaterThanOrEqual floor
            judged shouldBeGreaterThan 0
            ((read["inDraw"] as Number).toInt() * 2) shouldBeGreaterThanOrEqual judged
            (read["failures"] as List<*>).shouldBeEmpty()
            (read["outside"] as List<*>).shouldBeEmpty()
            (read["meeting"] as List<*>).shouldBeEmpty()
        }
    }

    /**
     * The font Chromium used for the first text of the picture (CDP `CSS.getPlatformFontsForNode`): its PostScript name
     * and whether it came from a web font file or the system; report only. Where the system has a font of the theme's
     * first family installed, the fallback is that font, and only this line tells the two apart.
     */
    private fun fontDrawn(
        page: Page,
        selector: String,
        index: Int,
    ): String =
        runCatching {
            val cdp = page.context().newCDPSession(page)
            try {
                cdp.send("DOM.enable")
                cdp.send("CSS.enable")
                val document = nodeIdOf(cdp.send("DOM.getDocument").getAsJsonObject("root"))
                val svgs = cdp.send("DOM.querySelectorAll", node(document, selector)).getAsJsonArray("nodeIds")
                val text = nodeIdOf(cdp.send("DOM.querySelector", node(svgs[index].asInt, "text")))
                val fonts = cdp.send("CSS.getPlatformFontsForNode", JsonObject().apply { addProperty("nodeId", text) })
                fonts.getAsJsonArray("fonts").joinToString("+") { font ->
                    val face = font.asJsonObject
                    val origin = if (face.get("isCustomFont").asBoolean) "web file" else "system"
                    "${face.get("postScriptName").asString} ($origin, ${face.get("glyphCount").asInt} glyphs)"
                }
            } finally {
                cdp.detach()
            }
        }.getOrElse { "unread (${it.message?.lineSequence()?.firstOrNull()})" }

    private fun nodeIdOf(result: JsonObject): Int = result.get("nodeId").asInt

    private fun node(
        id: Int,
        selector: String,
    ) = JsonObject().apply {
        addProperty("nodeId", id)
        addProperty("selector", selector)
    }

    /** Every resource-timing entry under the font path: `{name, transferSize, encodedBodySize}`. */
    private fun timings(page: Page): List<Map<*, *>> = (page.evaluate(FONT_TIMINGS, FONT_PATH) as List<*>).map { it as Map<*, *> }

    /** "from the cache", "revalidated" or "fetched again" (the brief's rule over the two sizes). */
    private fun classOf(entry: Map<*, *>): String {
        val transfer = (entry["transferSize"] as Number).toDouble()
        val encoded = (entry["encodedBodySize"] as Number).toDouble()
        val kind =
            when {
                transfer == 0.0 -> "from the cache"
                transfer < encoded -> "revalidated"
                else -> "fetched again"
            }
        return "${entry["name"].toString().substringAfterLast('/')}=$kind"
    }

    /** The console collector on every page of [context]. */
    private fun listening(context: BrowserContext): BrowserContext {
        context.onPage { listen(it) }
        return context
    }

    /**
     * The console collector on [target]: console errors and page errors; the browser's own line for a font file a route
     * ABORTED ("Failed to load resource" located at that font) is the expected echo of the situation "never" and is
     * written to the report instead.
     */
    private fun listen(target: Page) {
        target.onConsoleMessage { m: ConsoleMessage ->
            if (m.type() != "error") return@onConsoleMessage
            if (m.text().startsWith("Failed to load resource") && FONT_PATH in m.location()) {
                lines += "aborted font echo: ${m.location()}"
            } else {
                consoleErrors += "${target.url()}: console ${m.text()}"
            }
        }
        target.onPageError { e -> consoleErrors += "${target.url()}: page error $e" }
    }

    private fun report(name: String) {
        Files.createDirectories(REPORTS)
        Files.write(REPORTS.resolve("$name.txt"), lines)
        lines.forEach { println("1018 $name: $it") }
        lines.clear()
    }

    /** The font files' route of one situation: passes, holds (until [release]) or aborts every request; counts them. */
    private class FontRoute(
        private val mode: Mode,
    ) {
        enum class Mode { PASS, HOLD, ABORT }

        var seen = 0
            private set
        val held = mutableListOf<Route>()
        private var released = false

        fun handle(route: Route) {
            seen++
            when {
                mode == Mode.ABORT -> route.abort()
                mode == Mode.HOLD && !released -> held += route
                else -> route.resume()
            }
        }

        fun release() {
            released = true
            held.toList().forEach { runCatching { it.resume() } }
            held.clear()
        }
    }

    private companion object {
        val REPORTS: Path = Paths.get("build", "reports", "chart-font-timing")
        const val NARROW = 480
        const val SLACK_PX = 0.5
        const val BOARD_CHARTS = 2
        const val LATE_REDRAW_MS = 10_000.0
        const val FONT_GLOB = "**/vendor/fonts/**"
        const val FONT_PATH = "/vendor/fonts/"
        const val PREVIEW_SVG = "$CASE svg.dp-chart"
        const val BOARD_SVG = "#dp-board svg.dp-chart"
        val PREVIEW_FLOOR = JsRenderedPageBrowserTest.G2_TEXT_FLOOR

        const val FONTS_READY = "() => document.fonts.ready.then(() => true)"
        const val TWO_FRAMES = "() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))"
        const val CARD_READY =
            "sel => { const c = document.querySelector(sel + ' .dp-dashboard-status');" +
                " return !!c && ['success', 'ready'].indexOf(c.getAttribute('data-dp-state')) >= 0; }"
        const val BOARD_READY =
            "n => { const s = Array.from(document.querySelectorAll('#dp-board svg.dp-chart'));" +
                " return s.length >= n && s.every(x => !!x.querySelector('g.dp-mark')); }"
        const val DRAW_COUNT =
            "a => { var s = document.querySelectorAll(a.sel)[a.index]; var l = s && window.__fontTiming.pictures.get(s);" +
                " return l ? l.length : 0; }"
        const val FONT_TIMINGS =
            "p => performance.getEntriesByType('resource').filter(e => e.name.indexOf(p) >= 0)" +
                ".map(e => ({ name: e.name, transferSize: e.transferSize, encodedBodySize: e.encodedBodySize }))"

        /** The recorder (the class KDoc): installed before any page script, in the test's browser only. */
        const val RECORDER =
            """(function () {
              if (window.__fontTiming) return;
              var rec = { installed: false, pictures: new WeakMap(), unrecorded: new WeakSet() };
              Object.defineProperty(window, '__fontTiming', { value: rec });
              var byPrimitives = new WeakMap(), seq = 0, ns;
              function wrap(obj) {
                var realRender = obj.render, realWrite = obj.writeDom;
                function render(input) {
                  var draw = { n: ++seq, notes: [], callback: null, family: null };
                  var given = input;
                  if (input && typeof input.measure === 'function') {
                    var cb = input.measure;
                    draw.callback = cb;
                    given = Object.assign({}, input, { measure: function (text, size, weight) {
                      var w = cb(text, size, weight);
                      draw.notes.push([String(text), Number(size), Number(weight), w]);
                      return w;
                    } });
                  }
                  try { draw.family = obj.theme.resolve(input.theme).font; } catch (e) { draw.family = 'unresolved: ' + e; }
                  var out = realRender.call(this, given);
                  if (draw.callback && out && out.primitives) byPrimitives.set(out.primitives, draw);
                  return out;
                }
                function writeDom(primitives, svg) {
                  if (svg && svg.querySelector && !rec.pictures.has(svg) && svg.querySelector('g.dp-mark')) rec.unrecorded.add(svg);
                  var result = realWrite.apply(this, arguments);
                  var draw = primitives && byPrimitives.get(primitives);
                  if (draw && svg) {
                    var list = rec.pictures.get(svg);
                    if (!list) { list = []; rec.pictures.set(svg, list); }
                    list.push(draw);
                  }
                  return result;
                }
                Object.defineProperty(obj, 'render', { configurable: true, enumerable: true,
                  get: function () { return typeof realRender === 'function' ? render : realRender; },
                  set: function (f) { if (f !== render) realRender = f; } });
                Object.defineProperty(obj, 'writeDom', { configurable: true, enumerable: true,
                  get: function () { return typeof realWrite === 'function' ? writeDom : realWrite; },
                  set: function (f) { if (f !== writeDom) realWrite = f; } });
              }
              Object.defineProperty(window, 'JsRendered', { configurable: true, enumerable: true,
                get: function () { return ns; },
                set: function (v) { ns = v; if (v && typeof v === 'object') { wrap(v); rec.installed = true; } } });
            })();"""

        /**
         * One read of picture `a.index` of `a.sel` against its last recorded draw (the class KDoc): criterion 1 per text,
         * G2's text boxes (the algorithm of [JsRenderedPageBrowserTest.TEXT_BOXES], per picture), and the report-only
         * values (the test's own canvas width, the theme's family list, `document.fonts.check`, the faces' status).
         */
        const val READ =
            """(a) => {
              var svg = document.querySelectorAll(a.sel)[a.index], rec = window.__fontTiming;
              if (!svg) return { installed: !!(rec && rec.installed), unrecorded: false, draws: 0, judged: 0, inDraw: 0, asked: 0,
                failures: ['no picture ' + a.index + ' of ' + a.sel], outside: [], meeting: [] };
              var list = (rec && rec.pictures.get(svg)) || [], draw = list[list.length - 1];
              var texts = Array.from(svg.querySelectorAll('text')).filter(function (t) { return t.textContent !== ''; });
              var ctx = document.createElement('canvas').getContext('2d');
              var inDraw = 0, asked = 0, worst = 0, worstText = null, own = 0, ownText = null, failures = [];
              texts.forEach(function (t) {
                if (!draw) return;
                var cs = getComputedStyle(t), size = parseFloat(cs.fontSize), weight = parseFloat(cs.fontWeight), text = t.textContent;
                var drawn = t.getComputedTextLength(), w = null;
                for (var i = draw.notes.length - 1; i >= 0 && w === null; i--) {
                  var n = draw.notes[i];
                  if (n[0] === text && Math.abs(n[1] - size) < 1e-6 && Math.abs(n[2] - weight) < 1e-6) w = n[3];
                }
                if (w !== null) inDraw++; else { w = draw.callback(text, size, weight); asked++; }
                var d = Math.abs(w - drawn);
                if (d > worst) { worst = d; worstText = text; }
                if (d > a.slack) failures.push(text + ' adapter=' + w.toFixed(2) + ' drawn=' + drawn.toFixed(2));
                ctx.font = cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
                var o = Math.abs(ctx.measureText(text).width - drawn);
                if (o > own) { own = o; ownText = text; }
              });
              var box = svg.getBoundingClientRect(), slack = 0.5, outside = [], meeting = [];
              var rects = texts.map(function (t) { return t.getBoundingClientRect(); });
              rects.forEach(function (r, i) {
                if (r.left < box.left - slack || r.right > box.right + slack || r.top < box.top - slack || r.bottom > box.bottom + slack)
                  outside.push(texts[i].textContent);
                for (var j = i + 1; j < rects.length; j++) {
                  var q = rects[j];
                  var w = Math.min(r.right, q.right) - Math.max(r.left, q.left), h = Math.min(r.bottom, q.bottom) - Math.max(r.top, q.top);
                  if (w > slack && h > slack) meeting.push(texts[i].textContent + ' | ' + texts[j].textContent);
                }
              });
              var family = draw ? draw.family : null;
              var faces = Array.from(document.fonts).filter(function (f) { return /Noto Sans/.test(f.family) && f.style === 'normal'; })
                .map(function (f) { return f.status; }).join(',');
              return { installed: !!(rec && rec.installed), unrecorded: rec ? rec.unrecorded.has(svg) : false, draws: list.length,
                drawNo: draw ? draw.n : null, judged: texts.length, inDraw: inDraw, asked: asked,
                worst: Math.round(worst * 1000) / 1000, worstText: worstText, failures: failures.slice(0, 8),
                ownWorst: Math.round(own * 1000) / 1000, ownWorstText: ownText, family: family,
                fontsCheck: family ? document.fonts.check('12px ' + family) : null, faces: faces,
                outside: outside.slice(0, 8), meeting: meeting.slice(0, 8) };
            }"""
    }
}
