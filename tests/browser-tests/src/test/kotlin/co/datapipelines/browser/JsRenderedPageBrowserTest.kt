package co.datapipelines.browser

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import co.datapipelines.web.ui.JsRenderedAssetConfig
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * #987 (js-rendered F3) — the page proofs of the chart engine, record §8 A4 (G1, G2, G3), A6's browser half (G4), A8
 * (G5) and A10's browser half (G7), each read off a real page under the product's live policy ([ChartPageSuite]).
 * Each case writes what it measured to `build/reports/js-rendered-page/` for the handback.
 */
class JsRenderedPageBrowserTest : ChartPageSuite() {
    /**
     * G1 (A4) — the DOM the adapter wrote is the engine's primitives, one element per primitive in order: the kind's
     * element, the role's class, a text's shown text, a group's descendant count; and the picture is not blank (bars
     * with an area, text). The primitives are the result of the same draw, captured on the page ([REDRAW]).
     */
    @Test
    fun `G1 - the page's DOM equals the engine's primitives element by element, and the picture is not blank`() {
        startTrace()
        val root = ready("jsrg1")
        val agent = chartContext()
        val lines = mutableListOf<String>()
        try {
            assertSoftly {
                for ((name, fixture) in g1Fixtures()) {
                    val page = preview(agent, documentOf("$root/charts/$name", fixture))
                    page.evaluate(REDRAW)
                    val read = page.evaluate(DOM_EQUALS_PRIMITIVES, CASE) as Map<*, *>
                    lines += "$name $read"
                    withClue("$name: the DOM is the primitives: $read") {
                        read["state"] shouldBe "rendered"
                        (read["mismatches"] as List<*>).shouldBeEmpty()
                        (read["elements"] as Number).toInt() shouldBe (read["primitives"] as Number).toInt()
                        (read["marks"] as Number).toInt() shouldBeGreaterThan 0
                        (read["texts"] as Number).toInt() shouldBeGreaterThan 0
                    }
                    page.close()
                }
            }
        } finally {
            agent.close()
            report("g1.txt", lines)
        }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * G2 (A4) — with the page's real font (the theme's family, loaded: `document.fonts.check`) at three widths, no text's
     * box leaves the picture and no two texts' boxes meet, read from the DOM (`getBoundingClientRect`).
     */
    @Test
    fun `G2 - with the page's real font at three widths no text leaves the picture or meets another`() {
        startTrace()
        val root = ready("jsrg2")
        val lines = mutableListOf<String>()
        try {
            assertSoftly {
                for (width in G2_WIDTHS) {
                    val agent = chartContext(width = width)
                    try {
                        for (name in G2_FIXTURES) {
                            val page =
                                preview(
                                    agent,
                                    documentOf("$root/charts/${name.replace('-', '_')}_$width", ChartBrowserFixtures.fixture(name)),
                                )
                            val read = page.evaluate(TEXT_BOXES, CASE) as Map<*, *>
                            lines += "$width $name $read"
                            withClue("$name at $width px: $read") {
                                read["fontLoaded"] shouldBe true
                                (read["texts"] as Number).toInt() shouldBeGreaterThanOrEqual G2_TEXT_FLOOR
                                (read["outside"] as List<*>).shouldBeEmpty()
                                (read["meeting"] as List<*>).shouldBeEmpty()
                            }
                            page.close()
                        }
                    } finally {
                        agent.close()
                    }
                }
            }
        } finally {
            report("g2.txt", lines)
        }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * G3 (A4) — the page's engine, given Node's own input text (its `measure` null: the server's width table, the one
     * Node and the sandbox draw with), returns Node's result text byte for byte, for every bar fixture at every emitted
     * frame (the engine module's `jsRenderedEmit`).
     */
    @Test
    fun `G3 - the page's engine with the server's width table draws every bar fixture byte-identical to Node`() {
        startTrace()
        val root = ready("jsrg3")
        val entries = emitted("index.txt").lines().filter { it.startsWith("bar/") }
        withClue("the emitted bar pictures are there (non-vacuity)") { entries.size shouldBeGreaterThanOrEqual G3_FLOOR }
        val agent = chartContext()
        val differing = mutableListOf<String>()
        var compared = 0
        try {
            val page = preview(agent, documentOf("$root/charts/identity", ChartBrowserFixtures.fixture("bar-vertical")))
            for (batch in entries.groupBy { it.substringBeforeLast('/') }.values) {
                differing += identityDiffs(page, batch)
                compared += batch.size
            }
            page.close()
        } finally {
            agent.close()
            report("g3.txt", listOf("compared=$compared differing=${differing.size}") + differing)
        }
        compared shouldBe entries.size
        withClue("the page's results that differ from Node's") { differing.shouldBeEmpty() }
        drainCspViolations().shouldBeEmpty()
    }

    /** One batch of emitted pictures drawn by the page's engine from Node's input: `"<entry>: <diff>"` for each that differs. */
    private fun identityDiffs(
        page: Page,
        batch: List<String>,
    ): List<String> {
        val pairs = batch.map { listOf(emitted("$it.input.json"), emitted("$it.output.json")) }
        val result = page.evaluate(IDENTITY, pairs) as List<*>
        return batch.zip(result).filter { it.second != null }.map { (entry, diff) -> "$entry: $diff" }
    }

    /**
     * G4 (A6) — one source: the preview, the visualization workspace and the board each load the engine's files in its
     * load order, each once, and every file the page received is the engine module's file byte for byte.
     */
    @Test
    fun `G4 - every page that draws a chart receives the engine's files byte for byte in load order`() {
        startTrace()
        val root = ready("jsrg4")
        servingCopies(page.context())
        val lines = mutableListOf<String>()
        val agent = chartContext()
        try {
            val previewBodies = capture { agent.onResponse(it) }
            val previewPage = preview(agent, documentOf("$root/charts/source", ChartBrowserFixtures.fixture("bar-vertical")))
            assertOneSource("preview", previewPage, previewBodies, lines)
            previewPage.close()

            val (id, _) = createVisualization("$root/charts/workspace")
            val workspaceBodies = capture { page.onResponse(it) }
            page.navigate("$baseUrl/visualizations/$id")
            page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'] svg.dp-chart g.dp-mark")
            assertOneSource("visualization workspace", page, workspaceBodies, lines)

            val board = seedBoardWithGrid(root)
            val boardPage = page.context().newPage()
            val boardBodies = capture { boardPage.onResponse(it) }
            boardPage.navigate("$baseUrl/dashboards/$board")
            boardPage.waitForSelector("#dp-board svg.dp-chart g.dp-mark")
            assertOneSource("board", boardPage, boardBodies, lines)
            boardPage.close()
        } finally {
            agent.close()
            report("g4.txt", lines)
        }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * G5 (A8) — the engine's hostile fixture is text everywhere on the page: its every text element shows its
     * primitive's text, nothing ran (no dialog, no policy violation), and the picture holds no script, foreign object,
     * image, link, style element, inline style or event attribute.
     */
    @Test
    fun `G5 - the hostile fixture is text on the page - nothing runs, no element is injected, no violation`() {
        startTrace()
        val root = ready("jsrg5")
        val agent = chartContext()
        val dialogs = mutableListOf<String>()
        try {
            agent.onPage { it.onDialog { dialog -> dialogs += dialog.message().also { dialog.dismiss() } } }
            val page = preview(agent, documentOf("$root/charts/hostile", hostileFixture()))
            page.evaluate(REDRAW)
            val dom = page.evaluate(DOM_EQUALS_PRIMITIVES, CASE) as Map<*, *>
            val read = page.evaluate(INJECTED, CASE) as Map<*, *>
            report("g5.txt", listOf("dom $dom", "injected $read", "dialogs $dialogs"))
            withClue("every text of the hostile picture is its primitive's text: $dom") {
                (dom["mismatches"] as List<*>).shouldBeEmpty()
                (dom["texts"] as Number).toInt() shouldBeGreaterThan 0
            }
            withClue("the hostile values are on the page as text: $read") {
                read["text"].toString().contains("__proto__") shouldBe true
                read["text"].toString().contains("<script>") shouldBe true
                read["text"].toString().contains("a & b") shouldBe true
            }
            withClue("nothing was injected: $read") {
                read["injected"] shouldBe 0
                read["events"] shouldBe 0
                read["foreign"] shouldBe 0
            }
            page.close()
        } finally {
            agent.close()
        }
        withClue("no dialog opened") { dialogs.shouldBeEmpty() }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * G7 (A10's browser half) — a bar over the series cap (9 of 8) shows the code's fixed sentence on its card and draws
     * no mark; the same document at the cap draws its 24 bars, so the refusal is the cap's.
     */
    @Test
    fun `G7 - over the series cap the card shows the code's sentence and draws no mark`() {
        startTrace()
        val root = ready("jsrg7")
        val agent = chartContext()
        try {
            val atCap = preview(agent, documentOf("$root/charts/eight", seriesFixture(SERIES_CAP)))
            val drawn = atCap.evaluate(MARKS, CASE) as Map<*, *>
            withClue("at the cap the chart draws: $drawn") { drawn["marks"] shouldBe SERIES_CAP * REGIONS.size }
            atCap.close()

            val over = preview(agent, documentOf("$root/charts/nine", seriesFixture(SERIES_CAP + 1)), states = null)
            over.waitForFunction(
                "args => document.querySelector(args.sel).textContent.indexOf(args.sentence) >= 0",
                mapOf(
                    "sel" to CASE,
                    "sentence" to SERIES_SENTENCE,
                ),
            )
            val refused = over.evaluate(MARKS, CASE) as Map<*, *>
            report("g7.txt", listOf("at cap $drawn", "over $refused"))
            withClue("over the cap: the sentence once, no mark: $refused") {
                refused["sentences"] shouldBe 1
                refused["marks"] shouldBe 0
                refused["children"] shouldBe 0
            }
            over.close()
        } finally {
            agent.close()
        }
        drainCspViolations().shouldBeEmpty()
    }

    private fun g1Fixtures(): List<Pair<String, ChartBrowserFixtures.Fixture>> =
        G1_FIXTURES.map { it.replace('-', '_') to ChartBrowserFixtures.fixture(it) } + ("hostile" to hostileFixture())

    /** The script bodies a page receives under the engine's prefix, by path, registered through [register]. */
    private fun capture(register: ((Response) -> Unit) -> Unit): MutableMap<String, ByteArray> {
        val bodies = ConcurrentHashMap<String, ByteArray>()
        register { response ->
            val path = java.net.URI(response.url()).path
            if (path.startsWith(JsRenderedAssetConfig.PREFIX) && response.status() == HTTP_OK) bodies[path] = response.body()
        }
        return bodies
    }

    private fun assertOneSource(
        where: String,
        target: Page,
        bodies: Map<String, ByteArray>,
        lines: MutableList<String>,
    ) {
        val scripts = engineScripts(target)
        val differing =
            ENGINE_SCRIPTS.filter { path ->
                val name = path.removePrefix(JsRenderedAssetConfig.PREFIX)
                val own =
                    checkNotNull(
                        javaClass.classLoader.getResourceAsStream("${JsRenderedEngineFiles.ROOT}/$name"),
                    ).use { it.readBytes() }
                !(bodies[path]?.contentEquals(own) ?: false)
            }
        lines += "$where scripts=${scripts.size} received=${bodies.size} differing=$differing"
        withClue("$where: the engine's files in load order, each once") { scripts shouldBe ENGINE_SCRIPTS }
        withClue("$where: every file received is the engine module's, byte for byte") {
            bodies.keys shouldBe ENGINE_SCRIPTS.toSet()
            differing.shouldBeEmpty()
        }
    }

    private fun report(
        name: String,
        lines: List<String>,
    ) {
        Files.createDirectories(REPORTS)
        Files.write(REPORTS.resolve(name), lines)
    }

    internal companion object {
        val REPORTS: Path = Paths.get("build", "reports", "js-rendered-page")
        val G1_FIXTURES = listOf("bar-vertical", "bar-horizontal", "bar-grouped", "bar-stacked", "bar-utc-date", "bar-negatives")
        val G2_WIDTHS = listOf(1280, 800, 480)
        val G2_FIXTURES = listOf("bar-vertical", "bar-grouped", "bar-long-labels")
        const val G2_TEXT_FLOOR = 5
        const val G3_FLOOR = 280
        const val SERIES_CAP = 8
        const val HTTP_OK = 200

        /** The engine's fixed sentence for `chart.too_many_series` over 9 series (record §4.3, §4.9). */
        const val SERIES_SENTENCE = "Too many series to draw (9, the limit is 8). Group the smaller ones in the query."

        /**
         * The drawn svg against the captured result's primitives (`window.__drawn`), in document order: tag per kind,
         * the class `dp-<role>`, a text's shown text, a group's descendant count; and what makes the picture non-blank.
         */
        const val DOM_EQUALS_PRIMITIVES =
            """(sel) => {
              var svg = document.querySelector(sel + ' svg.dp-chart'), out = window.__drawn;
              var tags = { rect: 'rect', line: 'line', path: 'path', circle: 'circle', text: 'text', group: 'g' };
              var els = Array.from(svg.querySelectorAll('*')), prims = out.primitives, bad = [], kinds = {};
              for (var i = 0; i < Math.max(els.length, prims.length); i++) {
                var e = els[i], p = prims[i];
                if (p) kinds[p.k] = (kinds[p.k] || 0) + 1;
                if (!e || !p) { bad.push(i + ': ' + (e ? e.tagName : 'no element') + ' / ' + (p ? p.k : 'no primitive')); continue; }
                var cls = (e.getAttribute('class') || '').split(' ')[0];
                var text = p.k === 'text' ? (p.shown !== null && p.shown !== undefined ? p.shown : p.text) : null;
                var ok = e.tagName === tags[p.k] && cls === 'dp-' + p.role && (text === null || e.textContent === text)
                  && (p.k !== 'group' || e.querySelectorAll('*').length === p.n);
                if (!ok) bad.push(i + ': ' + e.tagName + '.' + cls + ' / ' + p.k + '.' + p.role);
              }
              var marks = Array.from(svg.querySelectorAll('g.dp-mark[data-series] > .dp-mark'))
                .filter(function (m) { var b = m.getBBox(); return b.width > 0 && b.height > 0; }).length;
              return { state: out.state, elements: els.length, primitives: prims.length, mismatches: bad.slice(0, 8),
                kinds: JSON.stringify(kinds), marks: marks, texts: svg.querySelectorAll('text').length };
            }"""

        /**
         * Every non-empty text's box against the picture's box and against every other text's box (a meeting is an
         * overlap of more than half a pixel both ways), and whether the theme's font is the one loaded.
         */
        const val TEXT_BOXES =
            """(sel) => {
              var svg = document.querySelector(sel + ' svg.dp-chart'), box = svg.getBoundingClientRect(), slack = 0.5;
              var resolved = window.DatapipelinesDashboardChart._internal.resolveTheme(svg).theme;
              var font = window.JsRendered.theme.resolve(resolved).font;
              var texts = Array.from(svg.querySelectorAll('text')).filter(function (t) { return t.textContent !== ''; });
              var rects = texts.map(function (t) { return t.getBoundingClientRect(); });
              var outside = [], meeting = [];
              rects.forEach(function (r, i) {
                if (r.left < box.left - slack || r.right > box.right + slack || r.top < box.top - slack || r.bottom > box.bottom + slack)
                  outside.push(texts[i].textContent);
                for (var j = i + 1; j < rects.length; j++) {
                  var q = rects[j];
                  var w = Math.min(r.right, q.right) - Math.max(r.left, q.left), h = Math.min(r.bottom, q.bottom) - Math.max(r.top, q.top);
                  if (w > slack && h > slack) meeting.push(texts[i].textContent + ' | ' + texts[j].textContent);
                }
              });
              return { font: font, fontLoaded: document.fonts.check('12px ' + font), texts: texts.length, outside: outside.slice(0, 8), meeting: meeting.slice(0, 8) };
            }"""

        /** Each [input, output] pair rendered on the page: null when the text is Node's, else where it first differs. */
        const val IDENTITY =
            """(pairs) => pairs.map(function (pair) {
              var got = JSON.stringify(window.JsRendered.render(JSON.parse(pair[0]))), want = pair[1];
              if (got === want) return null;
              var at = 0;
              while (at < got.length && got[at] === want[at]) at++;
              return 'first difference at ' + at + ': page …' + got.slice(Math.max(0, at - 40), at + 40) + ' / node …' + want.slice(Math.max(0, at - 40), at + 40);
            })"""

        /** The picture's injection read: its text, and the elements and attributes no writer of text may produce. */
        const val INJECTED =
            """(sel) => {
              var svg = document.querySelector(sel + ' svg.dp-chart');
              var all = [svg].concat(Array.from(svg.querySelectorAll('*')));
              var events = 0;
              all.forEach(function (e) { for (var a = 0; a < e.attributes.length; a++) if (/^on/i.test(e.attributes[a].name)) events++; });
              return { text: svg.textContent,
                injected: svg.querySelectorAll('script, foreignObject, image, img, a, style, iframe, b').length + all.filter(function (e) { return e.hasAttribute('style'); }).length,
                events: events,
                foreign: all.filter(function (e) { return e.namespaceURI !== 'http://www.w3.org/2000/svg'; }).length };
            }"""

        /** The case's drawn bars, the elements of its picture, and how often the card shows the series cap's sentence. */
        val MARKS =
            """(sel) => {
              var section = document.querySelector(sel), svg = section.querySelector('svg.dp-chart');
              var marks = svg ? Array.from(svg.querySelectorAll('g.dp-mark[data-series] > .dp-mark')).filter(function (m) { var b = m.getBBox(); return b.width > 0 && b.height > 0; }).length : 0;
              return { marks: marks, children: svg ? svg.children.length : 0,
                sentences: section.textContent.split('$SERIES_SENTENCE').length - 1 };
            }"""
    }
}
