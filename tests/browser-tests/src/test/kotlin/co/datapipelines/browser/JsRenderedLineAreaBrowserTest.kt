package co.datapipelines.browser

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.BrowserContext
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

/**
 * #989 (js-rendered F5) — the page proofs of the line and area types, record §8 A4's three halves (brief 989 D10) and
 * the hostile fixture of both types (A8), read off a real page under the product's live policy ([ChartPageSuite]):
 *  1. the DOM the adapter wrote is the engine's primitives (an area's faded fill adds its gradient and two stops before
 *     its path, each checked against the path that names it), and the picture is not blank;
 *  2. with the page's real font at three widths no text leaves the picture or meets another;
 *  3. with the server's width table (the input's `measure` null) the page's engine draws every `line/` and `area/`
 *     fixture of the engine module byte-identical to Node at every emitted frame — lane 987's mechanism
 *     ([JsRenderedPageBrowserTest.IDENTITY]); the fixtures compared are counted against the engine module's fixture
 *     files.
 *
 * Every document comes from the engine module's own fixtures (Node's emitted input at 960 × 540), saved through the REST
 * route, so the product's schema and save rules accept each one first. The tooltip and legend-switch proofs of the
 * type-lane method belong to lane 990 (the layer does not exist yet). Each case writes what it measured to
 * `build/reports/js-rendered-line-area/`.
 */
class JsRenderedLineAreaBrowserTest : ChartPageSuite() {
    /** A4 (1), G8 — DOM equals primitives for each drawing fixture of both types and for both hostile fixtures. */
    @Test
    fun `A4-1 - the page's DOM equals the engine's primitives for line and area, gradients included, and is not blank`() {
        startTrace()
        val root = ready("jsla1")
        val agent = chartContext()
        val lines = mutableListOf<String>()
        var faded = 0
        try {
            assertSoftly {
                for ((wire, name) in DOM_FIXTURES) {
                    val page = preview(agent, document("$root/charts/${wire}_${name.replace('-', '_')}", wire, name))
                    page.evaluate(REDRAW)
                    val read = page.evaluate(DOM_EQUALS_PRIMITIVES, CASE) as Map<*, *>
                    lines += "$wire/$name $read"
                    faded += (read["fades"] as Number).toInt()
                    withClue("$wire/$name: the DOM is the primitives: $read") {
                        read["state"] shouldBe "rendered"
                        (read["mismatches"] as List<*>).shouldBeEmpty()
                        (read["badFades"] as List<*>).shouldBeEmpty()
                        (read["elements"] as Number).toInt() shouldBe (read["primitives"] as Number).toInt()
                        (read["gradients"] as Number).toInt() shouldBe (read["fades"] as Number).toInt()
                        (read["stops"] as Number).toInt() shouldBe 2 * (read["fades"] as Number).toInt()
                        (read["marks"] as Number).toInt() shouldBeGreaterThan 0
                        (read["texts"] as Number).toInt() shouldBeGreaterThan 0
                    }
                    page.close()
                }
            }
        } finally {
            agent.close()
            report("a4-1.txt", lines + "faded fills checked=$faded")
        }
        withClue("faded fills were drawn and checked (non-vacuity)") { faded shouldBeGreaterThanOrEqual FADE_FLOOR }
        drainCspViolations().shouldBeEmpty()
    }

    /** A4 (2) — the real font at three widths: no text leaves the picture, no two texts meet (read from the DOM). */
    @Test
    fun `A4-2 - with the page's real font at three widths no line or area text leaves the picture or meets another`() {
        startTrace()
        val root = ready("jsla2")
        val lines = mutableListOf<String>()
        var measured = 0
        try {
            assertSoftly {
                for (width in WIDTHS) {
                    val agent = chartContext(width = width)
                    try {
                        for ((wire, name) in FONT_FIXTURES) {
                            val page = preview(agent, document("$root/charts/${wire}_${name.replace('-', '_')}_$width", wire, name))
                            val read = page.evaluate(JsRenderedPageBrowserTest.TEXT_BOXES, CASE) as Map<*, *>
                            lines += "$width $wire/$name $read"
                            measured += (read["texts"] as Number).toInt()
                            withClue("$wire/$name at $width px: $read") {
                                read["fontLoaded"] shouldBe true
                                (read["texts"] as Number).toInt() shouldBeGreaterThanOrEqual TEXT_FLOOR
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
            report("a4-2.txt", lines + "texts measured=$measured")
        }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * A4 (3), G11 — the page's engine, given Node's own input text (its `measure` null: the server's width table), returns
     * Node's result text byte for byte for every `line/` and `area/` picture the engine module emitted; the fixtures
     * compared equal the engine module's `line/` and `area/` fixture files.
     */
    @Test
    fun `A4-3 - the page's engine with the server's width table draws every line and area fixture byte-identical to Node`() {
        startTrace()
        val root = ready("jsla3")
        val entries = emitted("index.txt").lines().filter { it.startsWith("line/") || it.startsWith("area/") }
        val fixtures = entries.map { it.substringBeforeLast('/') }.distinct()
        val files = WIRES.sumOf { wire -> fixtureFiles(wire) }
        withClue("the emitted line and area pictures are there (non-vacuity)") { entries.size shouldBeGreaterThanOrEqual PICTURE_FLOOR }
        withClue("every line/ and area/ fixture of the engine module was emitted") { fixtures.size shouldBe files }
        val agent = chartContext()
        val differing = mutableListOf<String>()
        var compared = 0
        try {
            val page = preview(agent, documentOf("$root/charts/identity", ChartBrowserFixtures.fixture("bar-vertical")))
            for (batch in entries.groupBy { it.substringBeforeLast('/') }.values) {
                val pairs = batch.map { listOf(emitted("$it.input.json"), emitted("$it.output.json")) }
                val result = page.evaluate(JsRenderedPageBrowserTest.IDENTITY, pairs) as List<*>
                differing += batch.zip(result).filter { it.second != null }.map { (entry, diff) -> "$entry: $diff" }
                compared += batch.size
            }
            page.close()
        } finally {
            agent.close()
            val counts = "fixtures=${fixtures.size} files=$files pictures compared=$compared differing=${differing.size}"
            report("a4-3.txt", listOf(counts) + differing)
        }
        compared shouldBe entries.size
        withClue("the page's results that differ from Node's") { differing.shouldBeEmpty() }
        drainCspViolations().shouldBeEmpty()
    }

    /** A8 for both types — the hostile fixture is text on the page: nothing runs, no element is injected, no violation. */
    @Test
    fun `A8 - the line and area hostile fixtures are text on the page - nothing runs, no element is injected, no violation`() {
        startTrace()
        val root = ready("jsla8")
        val agent = chartContext()
        val dialogs = mutableListOf<String>()
        val lines = mutableListOf<String>()
        try {
            agent.onPage { it.onDialog { dialog -> dialogs += dialog.message().also { dialog.dismiss() } } }
            assertSoftly {
                for (wire in WIRES) {
                    val page = preview(agent, document("$root/charts/${wire}_hostile", wire, "hostile"))
                    page.evaluate(REDRAW)
                    val dom = page.evaluate(DOM_EQUALS_PRIMITIVES, CASE) as Map<*, *>
                    val read = page.evaluate(JsRenderedPageBrowserTest.INJECTED, CASE) as Map<*, *>
                    lines += "$wire dom $dom"
                    lines += "$wire injected $read"
                    withClue("$wire: every text of the hostile picture is its primitive's text: $dom") {
                        (dom["mismatches"] as List<*>).shouldBeEmpty()
                        (dom["texts"] as Number).toInt() shouldBeGreaterThan 0
                    }
                    withClue("$wire: the hostile values are on the page as text: $read") {
                        read["text"].toString().contains("__proto__") shouldBe true
                        read["text"].toString().contains("<script>") shouldBe true
                        read["text"].toString().contains("a & b") shouldBe true
                    }
                    withClue("$wire: nothing was injected: $read") {
                        read["injected"] shouldBe 0
                        read["events"] shouldBe 0
                        read["foreign"] shouldBe 0
                    }
                    page.close()
                }
            }
        } finally {
            agent.close()
            report("a8.txt", lines + "dialogs $dialogs")
        }
        withClue("no dialog opened") { dialogs.shouldBeEmpty() }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * A.1 of issue 1132, on a real page — the crosshair dot over the line/one-series first point at 80 × 24 with
     * --chart-point 16 and --chart-gap 2 stays inside the frame, read off the SVG in the picture's own coordinates
     * (the viewBox), by the keyboard and by the pointer, under the light and the dark theme; the card still names the
     * activated datum. The host is resized and the two tokens set on the page, then the picture redrawn through the
     * adapter's own ResizeObserver, so the dot the interaction layer writes is the one the page actually shows. Each
     * reading records the theme the PAGE applied (`data-theme` on the document element, never echoed from the argument)
     * and the adapter's own resolver (`resolveTheme(svg)`: `theme.base`, `theme.tokens`, `refused`), whole in the
     * report. The two pages are told apart by the resolved `--chart-grid`, asserted to differ between the readings.
     */
    @Test
    fun `A4-4 - the hover dot's ink stays inside the frame under both themes, keyboard and pointer`() {
        startTrace()
        val root = ready("jsla4")
        val agent = chartContext()
        val log = mutableListOf<String>()
        try {
            val (readings, firsts) = readBothThemes(agent, root)
            for ((mode, r) in readings) {
                log += "$mode $r"
                val dot = r["dot"] as Map<*, *>?
                if (dot != null) log += inkBoundsLine(mode, dot)
            }
            val grid = mutableMapOf<String, Any?>()
            for ((mode, r) in readings) assertReading(mode, r, firsts.getValue(mode.substringBefore(" ")), grid)
            val grids = "light ${grid["light"]}, dark ${grid["dark"]}"
            withClue("the two themes resolve different --chart-grid colours ($grids)") {
                (grid["light"] != grid["dark"]) shouldBe true
            }
        } finally {
            agent.close()
            report("a4-4.txt", log)
        }
        drainCspViolations().shouldBeEmpty()
    }

    /** The four A4-4 readings (light/dark × keyboard/pointer) and each page's first point, taken before any assertion. */
    private fun readBothThemes(
        agent: BrowserContext,
        root: String,
    ): Pair<Map<String, Map<*, *>>, Map<String, Map<*, *>>> {
        val readings = mutableMapOf<String, Map<*, *>>()
        val firsts = mutableMapOf<String, Map<*, *>>()
        for (theme in listOf("light", "dark")) {
            val doc = document("$root/charts/line_one_series_dot_$theme", "line", "one-series")
            val page = preview(agent, doc, theme = theme)
            try {
                val setup = page.evaluate(SET_FRAME_AND_TOKENS, CASE) as Map<*, *>
                withClue("$theme: the host is resized to the 80 x 24 frame and the picture redraws at it: $setup") {
                    (setup["w"] as Number).toDouble() shouldBe 80.0
                    (setup["h"] as Number).toDouble() shouldBe 24.0
                    setup["state"] shouldBe "rendered"
                }
                val first = page.evaluate(FIRST_POINT, CASE) as Map<*, *>
                firsts[theme] = first
                page.evaluate("sel => { document.querySelector(sel + ' svg.dp-chart').focus(); return true; }", CASE)
                page.keyboard().press("ArrowRight")
                page.evaluate(FRAME)
                readings["$theme key"] = page.evaluate(READ_DOT, CASE) as Map<*, *>
                page.mouse().move((first["x"] as Number).toDouble(), (first["y"] as Number).toDouble())
                page.evaluate(FRAME)
                readings["$theme pointer"] = page.evaluate(READ_DOT, CASE) as Map<*, *>
            } finally {
                page.close()
            }
        }
        return readings to firsts
    }

    /** One reading's four outer ink bounds as the report writes them. */
    private fun inkBoundsLine(
        mode: String,
        dot: Map<*, *>,
    ): String {
        val cx = (dot["cx"] as Number).toDouble()
        val cy = (dot["cy"] as Number).toDouble()
        val e = (dot["r"] as Number).toDouble() + (dot["strokeWidth"] as Number).toDouble() / 2
        return "$mode ink left=${cx - e} right=${cx + e} top=${cy - e} bottom=${cy + e}"
    }

    /** One reading's assertions: the page's applied theme, its resolved --chart-grid, the frame, the ink and the card. */
    private fun assertReading(
        mode: String,
        r: Map<*, *>,
        first: Map<*, *>,
        grid: MutableMap<String, Any?>,
    ) {
        val theme = mode.substringBefore(" ")
        val dot = r["dot"] as Map<*, *>?
        withClue("$mode: the crosshair dot is drawn: $r") { (dot != null) shouldBe true }
        val cx = (dot!!["cx"] as Number).toDouble()
        val cy = (dot["cy"] as Number).toDouble()
        val radius = (dot["r"] as Number).toDouble()
        val stroke = (dot["strokeWidth"] as Number).toDouble()
        withClue("$mode: the page applied the theme asked for: ${r["dataTheme"]}") {
            r["dataTheme"] shouldBe theme
        }
        val resolved = r["resolved"] as Map<*, *>?
        val resolvedTheme = resolved?.get("theme") as Map<*, *>?
        val tokens = resolvedTheme?.get("tokens") as Map<*, *>?
        val chartGrid = tokens?.get("--chart-grid")
        grid[theme] = chartGrid
        withClue("$mode: the resolver named a --chart-grid for the page's theme: $resolved") {
            (chartGrid != null) shouldBe true
        }
        withClue("$mode: the picture is the 80 x 24 frame: ${r["viewBox"]}") { r["viewBox"] shouldBe "0 0 80 24" }
        requireInside("$mode dot", cx, cy, radius, stroke)
        withClue("$mode: the dot stays centered on the first datum's point (${first["fx"]}, ${first["fy"]})") {
            (Math.abs(cx - (first["fx"] as Number).toDouble()) <= 1e-6) shouldBe true
            (Math.abs(cy - (first["fy"] as Number).toDouble()) <= 1e-6) shouldBe true
        }
        withClue("$mode: the card names the first datum's value (${first["value"]}): ${r["card"]}") {
            val texts = (r["card"] as List<*>).map { it.toString() }
            texts.any { it.trim() == first["value"].toString() } shouldBe true
        }
    }

    /** The dot's four outer ink bounds (its center ± its radius and half its ring) inside the 80 x 24 frame, to 1e-9. */
    private fun requireInside(
        label: String,
        cx: Double,
        cy: Double,
        radius: Double,
        stroke: Double,
    ) {
        val e = radius + stroke / 2
        withClue("$label: the ink stays inside the frame (left ${cx - e}, top ${cy - e}, right ${cx + e}, bottom ${cy + e})") {
            (cx - e >= -1e-9) shouldBe true
            (cy - e >= -1e-9) shouldBe true
            (cx + e <= 80.0 + 1e-9) shouldBe true
            (cy + e <= 24.0 + 1e-9) shouldBe true
        }
    }

    /**
     * The REST create body of the engine module's fixture [wire]/[fixture] at [name], from Node's emitted input at 960 ×
     * 540: its display name, renderer, input contract and config unchanged, except that the band `x` and the series
     * column are declared not nullable (the save refuses a nullable band or series column) and a row with a null there is
     * dropped (the engine leaves such a row out anyway); one test case over the rows (`rendered`, or `no_data` when no
     * row is left). The page's drawing is compared with the page's own engine result, never with Node's here, so the
     * dropped rows change nothing a test reads; the identity case reads Node's input whole.
     */
    private fun document(
        name: String,
        wire: String,
        fixture: String,
    ): String {
        val input = JsonParser.parseString(emitted("$wire/$fixture/960x540.input.json")).asJsonObject
        val source = input.getAsJsonObject("document")
        val strict = strictColumns(source.getAsJsonObject("config").getAsJsonObject("encoding"))
        val (inputName, contract) = source.getAsJsonObject("inputs").entrySet().single()
        val declared = contract.deepCopy().asJsonObject
        declared.getAsJsonArray("columns").forEach { column ->
            val c = column.asJsonObject
            if (c.get("name").asString in strict) c.addProperty("nullable", false)
        }
        val rows = rowsOf(input.getAsJsonObject("columns"), strict)
        val assertions = JsonArray()
        assertions.add(JsonObject().apply { addProperty("kind", if (rows.size() == 0) "no_data" else "rendered") })
        val case =
            JsonObject().apply {
                addProperty("name", fixture)
                add("fixtures", JsonObject().apply { add(inputName, rows) })
                add("assertions", assertions)
            }
        val document =
            JsonObject().apply {
                addProperty("name", name)
                add("display_name", source.get("display_name"))
                add("renderer", source.get("renderer"))
                add("inputs", JsonObject().apply { add(inputName, declared) })
                add("config", source.get("config"))
                add("tests", JsonObject().apply { add("cases", JsonArray().apply { add(case) }) })
            }
        return GSON.toJson(document)
    }

    /** The columns the save refuses nullable: the band `x` (a nominal or ordinal one) and the series column, when present. */
    private fun strictColumns(encoding: JsonObject): Set<String> {
        val x = encoding.getAsJsonObject("x")
        return listOfNotNull(
            x.get("field").asString.takeIf { x.get("type").asString in BAND_TYPES },
            encoding.getAsJsonObject("color")?.get("field")?.asString,
        ).toSet()
    }

    /** Node's columns turned into rows, a row with a null in a [strict] column dropped. */
    private fun rowsOf(
        columns: JsonObject,
        strict: Set<String>,
    ): JsonArray {
        val count = columns.entrySet().maxOfOrNull { it.value.asJsonArray.size() } ?: 0
        val rows = JsonArray()
        (0 until count)
            .map { i -> JsonObject().apply { columns.entrySet().forEach { (field, values) -> add(field, values.asJsonArray[i]) } } }
            .filter { row -> strict.all { row.get(it) != null && !row.get(it).isJsonNull } }
            .forEach(rows::add)
        return rows
    }

    /** The engine module's fixture files of [wire] (one `.json` file per fixture). */
    private fun fixtureFiles(wire: String): Int =
        Files.list(FIXTURE_DIR.resolve(wire)).use { files -> files.filter { it.toString().endsWith(".json") }.count().toInt() }

    private fun report(
        name: String,
        lines: List<String>,
    ) {
        Files.createDirectories(REPORTS)
        Files.write(REPORTS.resolve(name), lines)
    }

    private companion object {
        val REPORTS: Path = Paths.get("build", "reports", "js-rendered-line-area")

        /**
         * The engine module's fixtures. The test task runs from this module's directory (only `siteShots` moves to the
         * repository root), so the root is found by walking up to the directory that holds the engine module.
         */
        val FIXTURE_DIR: Path =
            generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
                .firstOrNull { Files.isDirectory(it.resolve("modules/js-rendered/src/test/fixtures")) }
                ?.resolve("modules/js-rendered/src/test/fixtures")
                ?: error("no directory above ${Paths.get("").toAbsolutePath()} holds modules/js-rendered/src/test/fixtures")
        val WIRES = listOf("line", "area")
        val BAND_TYPES = setOf("nominal", "ordinal")

        /** Nulls are written: a chart's `sort: null` is a value. */
        val GSON = GsonBuilder().serializeNulls().create()

        /** The next animation frame twice, so the adapter's scheduled redraw and the layer's paint have run. */
        const val FRAME = "() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))"

        /**
         * The A4-4 host, resized to the 80 x 24 frame with the two tokens the witness needs, then a redraw waited for by
         * wrapping `JsRendered.render` (the host's own ResizeObserver redraws it, as it would for a page resize). Resolves
         * `{w, h, state}` — the host's client size and the captured draw's state — and leaves `window.__drawn` as that draw.
         */
        const val SET_FRAME_AND_TOKENS =
            """(sel) => new Promise(function (resolve) {
              var svg = document.querySelector(sel + ' svg.dp-chart');
              var host = svg.parentElement;
              host.style.position = 'fixed'; host.style.left = '0'; host.style.top = '0';
              host.style.padding = '0'; host.style.margin = '0'; host.style.border = '0'; host.style.boxSizing = 'content-box';
              host.style.minWidth = '0'; host.style.minHeight = '0'; host.style.maxWidth = '80px'; host.style.maxHeight = '24px';
              host.style.width = '80px'; host.style.height = '24px';
              host.style.setProperty('--chart-point', '16'); host.style.setProperty('--chart-gap', '2');
              var R = window.JsRendered, original = R.render, last = null, frames = 0;
              R.render = function () {
                try { var out = original.apply(this, arguments); last = out; return out; } catch (e) { last = { error: String(e) }; throw e; }
              };
              var wait = function () {
                if (last === null && ++frames > 120) last = { error: 'no draw reached the engine in 120 frames' };
                if (last === null) { requestAnimationFrame(wait); return; }
                requestAnimationFrame(function () { R.render = original; window.__drawn = last; resolve({ w: host.clientWidth, h: host.clientHeight, state: last.state }); });
              };
              requestAnimationFrame(wait);
            })"""

        /**
         * The line/one-series first point: its series id and datum, its card's heading and value (the index's tip), its
         * anchor in the frame's coordinates (`fx`, `fy`) and on the page (`x`, `y`, through the picture's screen matrix).
         */
        const val FIRST_POINT =
            """(sel) => {
              var R = window.JsRendered, I = window.DatapipelinesChartInteractions._internal;
              var svg = document.querySelector(sel + ' svg.dp-chart');
              var out = window.__drawn, ix = out.index;
              var m = I.modelOf(R, out, function () { return 0; });
              var ser = m.series[0], d = ser.marks[0].datum;
              var a = I.anchorOf(m, ser.id, d);
              var cm = svg.getScreenCTM();
              return { id: ser.id, datum: d, heading: ix.series[0].tip[0][0], value: ix.series[0].tip[0][1],
                fx: a.x, fy: a.y, x: cm.a * a.x + cm.c * a.y + cm.e, y: cm.b * a.x + cm.d * a.y + cm.f };
            }"""

        /** The crosshair dot the layer wrote, and the card it wrote, in the picture's own coordinates (the viewBox),
         *  plus the theme the page applied (`data-theme` on the document element) and the adapter's own resolver at
         *  the chart's svg (`resolveTheme(svg)`: `theme.base`, `theme.tokens`, `refused`). */
        const val READ_DOT =
            """(sel) => {
              var svg = document.querySelector(sel + ' svg.dp-chart');
              var dot = svg.querySelector('circle.dp-guide');
              var card = svg.querySelector('g.dp-tooltip');
              var resolved = window.DatapipelinesDashboardChart._internal.resolveTheme(svg);
              return {
                dataTheme: document.documentElement.getAttribute('data-theme'),
                resolved: resolved,
                viewBox: svg.getAttribute('viewBox'),
                dot: dot === null ? null : { cx: parseFloat(dot.getAttribute('cx')), cy: parseFloat(dot.getAttribute('cy')),
                  r: parseFloat(dot.getAttribute('r')), strokeWidth: parseFloat(dot.getAttribute('stroke-width')) },
                card: card === null ? null : Array.from(card.querySelectorAll('text')).map(function (t) { return t.textContent; })
              };
            }"""

        /**
         * Every form the two templates draw that a saved case can carry: curves, points, end labels, log, fields, time,
         * gaps, stacks. The large thinned fixtures are not among them: a saved case holds at most the product's
         * fixture-row limit (`datapipelines.visualization.max-fixture-rows-per-case`, 1,000 rows; the save refuses
         * more, so such a fixture here goes red on its save, never silently), and thinning starts above the drawing
         * caps (2,000 points per series on a line, 1,000 on an area), so those fixtures' rows cannot be saved directly
         * as one case. That limit counts the case's fixture rows as written, and nothing else: the rows the case's
         * pinned template returns from them are not counted by it (record §4.9, item 3), so a saved preview can still
         * draw a thinned result. The thinned fixtures' pictures are compared with Node's byte for byte in the page by
         * A4-3.
         */
        val DOM_FIXTURES =
            listOf(
                "line" to "two-series",
                "line" to "one-series",
                "line" to "eight-series",
                "line" to "curve-step",
                "line" to "curve-linear-no-points",
                "line" to "labels-last",
                "line" to "log",
                "line" to "fields",
                "line" to "null-gap",
                "line" to "nominal-x",
                "line" to "quantitative-x",
                "line" to "temporal-datetime",
                "line" to "production-monthly--x_three_years",
                "area" to "two-series",
                "area" to "stacked",
                "area" to "normalized",
                "area" to "overlap",
                "area" to "stacked-gap",
                "area" to "fields",
                "area" to "negative",
            )
        val FONT_FIXTURES =
            listOf("line" to "two-series", "line" to "long-labels", "line" to "labels-last", "area" to "stacked", "area" to "long-labels")
        val WIDTHS = listOf(1280, 800, 480)
        const val TEXT_FLOOR = 5
        const val FADE_FLOOR = 10

        /** 48 fixtures × 7 frames when written; a floor, not the count (the count is the index's, compared above). */
        const val PICTURE_FLOOR = 300

        /**
         * The drawn svg against the captured result's primitives (`window.__drawn`) in document order, an area's
         * gradient elements and the picture's own `title` and `desc` (#990 D6) set aside: tag per kind, the class
         * `dp-<role>`, a text's shown text, a group's descendant count (gradients not counted); each faded path's
         * `fill` names a gradient of the picture whose two stops carry the path's colour as the literal its primitive
         * carries (writeDom writes the standalone form and never a var(), look contract §9) and its two opacities. Then
         * what makes the picture non-blank.
         */
        const val DOM_EQUALS_PRIMITIVES =
            """(sel) => {
              var svg = document.querySelector(sel + ' svg.dp-chart'), out = window.__drawn;
              var tags = { rect: 'rect', line: 'line', path: 'path', circle: 'circle', text: 'text', group: 'g' };
              // The gradients' elements, and the picture's own description (#990 D6: a title and a desc), are no primitives.
              var fadeEl = function (e) { return e.tagName === 'linearGradient' || e.tagName === 'stop' || (e.parentNode === svg && (e.tagName === 'title' || e.tagName === 'desc')); };
              var all = Array.from(svg.querySelectorAll('*'));
              var els = all.filter(function (e) { return !fadeEl(e); }), prims = out.primitives, bad = [], badFades = [], fades = 0;
              for (var i = 0; i < Math.max(els.length, prims.length); i++) {
                var e = els[i], p = prims[i];
                if (!e || !p) { bad.push(i + ': ' + (e ? e.tagName : 'no element') + ' / ' + (p ? p.k : 'no primitive')); continue; }
                var cls = (e.getAttribute('class') || '').split(' ')[0];
                var text = p.k === 'text' ? (p.shown !== null && p.shown !== undefined ? p.shown : p.text) : null;
                var kids = Array.from(e.querySelectorAll('*')).filter(function (k) { return !fadeEl(k); }).length;
                var ok = e.tagName === tags[p.k] && cls === 'dp-' + p.role && (text === null || e.textContent === text)
                  && (p.k !== 'group' || kids === p.n);
                if (!ok) bad.push(i + ': ' + e.tagName + '.' + cls + ' / ' + p.k + '.' + p.role);
                if (p.k === 'path' && p.fade !== null && p.fade !== undefined) {
                  fades++;
                  var m = /^url\(#([^)]+)\)$/.exec(e.getAttribute('fill') || ''), g = m ? document.getElementById(m[1]) : null;
                  var stops = g ? Array.from(g.querySelectorAll('stop')) : [];
                  var colour = p.fill === null ? 'none' : p.color;
                  var fine = g !== null && g.tagName === 'linearGradient' && stops.length === 2
                    && stops[0].getAttribute('stop-color') === colour && stops[1].getAttribute('stop-color') === colour
                    && Number(stops[0].getAttribute('stop-opacity')) === p.fade[0]
                    && Number(stops[1].getAttribute('stop-opacity')) === p.fade[1];
                  if (!fine) badFades.push(i + ': fill ' + e.getAttribute('fill') + ' stops ' + stops.map(function (s) {
                    return s.getAttribute('stop-color') + '@' + s.getAttribute('stop-opacity'); }).join(' ') + ' meant ' + colour + '@' + p.fade[0] + ',' + p.fade[1]);
                }
              }
              var marks = Array.from(svg.querySelectorAll('g.dp-mark[data-series] > .dp-mark'))
                .filter(function (m) { var b = m.getBBox(); return b.width > 0 || b.height > 0; }).length;
              return { state: out.state, elements: els.length, primitives: prims.length, mismatches: bad.slice(0, 8),
                fades: fades, badFades: badFades.slice(0, 8),
                gradients: svg.querySelectorAll('linearGradient').length, stops: svg.querySelectorAll('stop').length,
                marks: marks, texts: svg.querySelectorAll('text').length };
            }"""
    }
}
