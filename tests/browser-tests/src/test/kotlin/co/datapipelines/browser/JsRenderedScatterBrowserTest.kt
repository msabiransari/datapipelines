package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The scatter type in a real Chromium (brief 1062 A.7, the type-lane method's one browser test): the DOM is the engine's
 * primitives; near a point (within 24 px) its ring in its series' colour and its card (D9), beyond 24 px neither; the
 * legend switch hides a series, the DOM is then the primitives the engine hands for the same input with that series
 * hidden, and the value axis's tick texts are no longer those before (the fixture `hidden-owner`: series A owns both
 * ends of y); the keys walk the points in row order (D5: a point's key row is its input row), on one series and on two
 * series whose rows interleave. Nothing is asserted about the x axis's places (D4).
 *
 * No application is booted. The serving code is [ChartTestPage]'s: by request interception on an origin of its own the
 * page receives the engine (scatter's template among its files), the interaction layer, `css/app.css` and its fonts,
 * the emitted scatter inputs of the engine module, and from `chart-scatter/` one page (the reference page's policy as a
 * `meta` element) and its script, which draws an input with an empty hidden list and redraws on each switch. Every test
 * ends with its console errors, page errors, dialogs, policy violations, aborted requests and `style` attributes at 0.
 *
 * The oracle is the test's: the cards' texts are written here from the fixtures' rows, the reach (24) and the ring's
 * share of the text size (0.25) from the look contract's §4.4 and brief 1052 D9, the key plan from the fixture's rows and
 * D5 (one key row per input row).
 */
class JsRenderedScatterBrowserTest {
    @Test
    fun `A7 the DOM equals the engine's primitives for every drawn form of scatter at two frames`() {
        var checked = 0
        open().use { s ->
            assertSoftly {
                for (fixture in DOM_FIXTURES) {
                    for ((w, h) in listOf(960 to 540, 360 to 240)) {
                        val info = s.draw(fixture, w, h)
                        val read = s.read(Checks.DOM_JS, null)
                        withClue("$fixture $w x $h: the DOM is the primitives: $read") {
                            info["state"] shouldBe "rendered"
                            (read["mismatches"] as List<*>).shouldBeEmpty()
                            (read["elements"] as Number).toInt() shouldBe (read["primitives"] as Number).toInt()
                            (read["points"] as Number).toInt() shouldBeGreaterThanOrEqual 1
                        }
                        checked += (read["points"] as Number).toInt()
                    }
                }
            }
            s.clean("after the DOM checks")
        }
        println("A.7 DOM: ${DOM_FIXTURES.size} fixtures x 2 frames, $checked points")
        checked shouldBeGreaterThanOrEqual MIN_POINTS
    }

    @Test
    fun `A7 near a point its ring in its series' colour and its card, beyond 24 px neither, and a leave takes both away`() {
        open().use { s ->
            for ((fixture, datum, card) in CARDS) {
                s.draw(fixture, 960, 540)
                for ((dist, near) in listOf(NEAR_PX to true, FAR_PX to false)) {
                    val place = s.read(Checks.PLACE_JS, listOf(datum, dist))
                    withClue("$fixture $datum at $dist px: the place the test chose") { (place["problems"] as List<*>).shouldBeEmpty() }
                    s.hover((place["x"] as Number).toDouble(), (place["y"] as Number).toDouble())
                    val r = s.read(Checks.HOVER_JS, listOf(datum, RING_SHARE))
                    println("A.7 hover $fixture $datum at $dist px: $r")
                    withClue("$fixture $datum at $dist px: $r") {
                        if (near) {
                            r["shown"] shouldBe card
                            (r["problems"] as List<*>).shouldBeEmpty()
                        } else {
                            (r["shown"] as List<*>).shouldBeEmpty()
                            (r["rings"] as Number).toInt() shouldBe 0
                        }
                    }
                }
                s.leave()
                withClue("$fixture: after the pointer left") {
                    s.read(Checks.NOTHING_JS, null)["problems"] as List<*> shouldBe
                        emptyList<String>()
                }
            }
            s.clean("after the hover checks")
        }
    }

    @Test
    fun `A7 the legend switch hides a series - the DOM is the engine's draw with it hidden and the value axis's ticks change`() {
        open().use { s ->
            s.draw("hidden-owner", 960, 540)
            val before = s.read(Checks.VALUE_TICKS_JS, null)["ticks"] as List<*>
            val key = s.read(Checks.LEGEND_KEY_JS, "string:A")
            withClue("A's legend entry is drawn with its dot") { key["tag"] shouldBe "circle" }
            s.page.mouse().click((key["x"] as Number).toDouble(), (key["y"] as Number).toDouble())
            @Suppress("UNCHECKED_CAST")
            val asked = s.page.evaluate("() => window.ChartScatter.asked()") as List<String>
            withClue("a click on A's dot asks the host once") { asked shouldContainExactly listOf("string:A") }
            s.leave()
            val read = s.read(Checks.DOM_HIDDEN_JS, listOf("string:A"))
            val after = s.read(Checks.VALUE_TICKS_JS, null)["ticks"] as List<*>
            println("A.7 legend switch: value ticks before $before, after $after; $read")
            withClue("the DOM is the engine's draw of the same input with A hidden: $read") {
                read["state"] shouldBe "rendered"
                (read["mismatches"] as List<*>).shouldBeEmpty()
                (read["elements"] as Number).toInt() shouldBe (read["primitives"] as Number).toInt()
                (read["points"] as Number).toInt() shouldBe B_ROWS
            }
            withClue("the value axis's ticks are the engine's for that draw") { after shouldBe read["ticks"] }
            withClue("the value axis's ticks are no longer those before") {
                after.size shouldBeGreaterThanOrEqual 2
                after shouldNotBe before
            }
            s.clean("after the legend switch")
        }
    }

    @Test
    fun `A7 the keys walk the points in row order - one series - a key row is the input row`() {
        open().use { s ->
            s.draw("one-series", 960, 540)
            s.leave()
            s.page.locator("#chart").focus()
            // The plan (brief 1062 D5: a point's key row is its input row; the layer's rows, brief 1052 D8): Right enters at
            // row 0; Down walks the rows to the last and stays there; Up goes back one. A row holds one point, so Left, Right
            // (no legend, no cut text), Home and End stay on it.
            val plan =
                listOf("ArrowRight" to 0) + (1..11).map { "ArrowDown" to it } +
                    listOf("ArrowDown" to 11, "ArrowUp" to 10, "ArrowLeft" to 10, "ArrowRight" to 10, "Home" to 10, "End" to 10)
            val visited = s.walk(plan) { datum -> listOf("X", ONE_SERIES_X[datum].toString(), "Y", ONE_SERIES_Y[datum].toString()) }
            s.page.keyboard().press("Escape")
            println("A.7 keys, one series: rows visited $visited")
            withClue("every row was visited") { visited.toSet() shouldBe (0..11).toSet() }
            s.clean("after the keys' walk on one series")
        }
    }

    @Test
    fun `A7 the keys walk the points in row order - interleaved series - two points at one place are each reached`() {
        open().use { s ->
            s.draw("same-place", 960, 540)
            s.leave()
            s.page.locator("#chart").focus()
            // `same-place`: rows a, b, a; rows 0 and 1 at one place. Down and Up walk rows 0, 1, 2 across the two series and
            // stay at either end; Right on a row of one point reaches the point's own legend entry.
            val card = { datum: Int -> SAME_PLACE[datum] }
            val first =
                listOf(
                    "ArrowRight" to 0,
                    "ArrowDown" to 1,
                    "ArrowDown" to 2,
                    "ArrowDown" to 2,
                    "ArrowUp" to 1,
                    "ArrowUp" to 0,
                    "ArrowUp" to 0,
                    "ArrowLeft" to 0,
                    "Home" to 0,
                    "End" to 0,
                )
            val visited = s.walk(first, card)
            s.page.keyboard().press("ArrowRight")
            withClue("Right on row 0 (series a) reaches a's legend entry") { s.read(Checks.KEY_ENTRY_JS, "string:a")["on"] shouldBe true }
            s.page.keyboard().press("Escape")
            visited += s.walk(listOf("ArrowRight" to 0, "ArrowDown" to 1), card)
            s.page.keyboard().press("ArrowRight")
            withClue("Right on row 1 (series b) reaches b's legend entry") { s.read(Checks.KEY_ENTRY_JS, "string:b")["on"] shouldBe true }
            s.page.keyboard().press("Escape")
            println("A.7 keys, interleaved: rows visited $visited, then a's and b's entries")
            withClue("every row was visited, both of the two at one place among them") { visited.toSet() shouldBe setOf(0, 1, 2) }
            s.clean("after the keys' walk on interleaved series")
        }
    }

    // ------------------------------------------------------------------------------------------------ the session

    private inner class Session(
        private val opened: ChartTestPage.Opened,
    ) : AutoCloseable {
        val page = opened.page
        private var origin = 0.0 to 0.0
        private var scale = 1.0 to 1.0

        /** Draws an emitted input at w x h and readies the test's probes; returns the draw. */
        fun draw(
            fixture: String,
            w: Int,
            h: Int,
        ): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val info = page.evaluate(Checks.DRAW_JS, listOf("$fixture/960x540.input.json", w, h)) as Map<String, Any?>
            val rect = read(Checks.PREPARE_JS, null)
            origin = (rect["left"] as Number).toDouble() to (rect["top"] as Number).toDouble()
            scale = (rect["sx"] as Number).toDouble() to (rect["sy"] as Number).toDouble()
            return info
        }

        /** The pointer at a place of the frame. */
        fun hover(
            x: Double,
            y: Double,
        ) = page.mouse().move(origin.first + x * scale.first, origin.second + y * scale.second)

        /**
         * Presses each key of [plan] and checks, after each, one focus ring centred on the point of the row it names and
         * the card shown, the test's own [card] of that row; returns the rows visited.
         */
        fun walk(
            plan: List<Pair<String, Int>>,
            card: (Int) -> List<String>,
        ): MutableList<Int> {
            val visited = mutableListOf<Int>()
            for ((key, datum) in plan) {
                page.keyboard().press(key)
                val r = read(Checks.KEY_JS, listOf(datum, card(datum)))
                withClue("$key to row $datum: $r") { (r["problems"] as List<*>).shouldBeEmpty() }
                visited += datum
            }
            return visited
        }

        /** The pointer leaves the picture (to a corner of the viewport the picture does not cover). */
        fun leave() = page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())

        @Suppress("UNCHECKED_CAST")
        fun read(
            js: String,
            arg: Any?,
        ): Map<String, Any?> = page.evaluate(js, arg) as Map<String, Any?>

        fun seen(): Map<String, List<String>> {
            @Suppress("UNCHECKED_CAST")
            val styled = page.evaluate("() => [...document.querySelectorAll('[style]')].map((e) => e.tagName)") as List<String>
            return mapOf(
                "console errors, page errors and dialogs" to opened.errors.toList(),
                "policy violations" to opened.violations.toList(),
                "requests aborted" to opened.off.toList(),
                "style attributes" to styled,
            )
        }

        /** Every list empty, compared in ONE assertion so a red shows them all. */
        fun clean(point: String) {
            val seen = seen()
            withClue("$point: every list empty") { seen shouldBe seen.mapValues { emptyList<String>() } }
        }

        override fun close() = opened.context.close()
    }

    private fun open(): Session = Session(PAGE.open(browser, VIEW_W, VIEW_H))

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900

        /** The look contract's §4.4 reach and brief 1052 D9's ring (`markRing`, × the text size), written here. */
        private const val NEAR_PX = 16.0
        private const val FAR_PX = 30.0
        private const val RING_SHARE = 0.25

        /** `hidden-owner`'s B: its three rows are drawn with A hidden (the fixture file). */
        private const val B_ROWS = 3

        /** `one-series`' rows (the fixture file): x and y by row; its card has no heading (no colour channel, D9). */
        private val ONE_SERIES_X = (1..12).toList()
        private val ONE_SERIES_Y = listOf(3, 7, 2, 9, 4, 8, 5, 1, 6, 10, 2, 7)

        /** `same-place`'s cards by row, written from the fixture's rows (series a, b, a; x 5, 5, 8; y 5, 5, 2). */
        private val SAME_PLACE =
            listOf(
                listOf("a", "X", "5", "Y", "5"),
                listOf("b", "X", "5", "Y", "5"),
                listOf("a", "X", "8", "Y", "2"),
            )

        /** Every drawn form a saved scatter can take (series, size, shade, units, tooltip, log, time, hostile texts). */
        private val DOM_FIXTURES =
            listOf(
                "one-series",
                "eight-series",
                "negative",
                "long-labels",
                "log-x",
                "log-y",
                "time-x",
                "size-default",
                "size-range-4-64",
                "shade-sequential",
                "shade-diverging-mid",
                "shade-null-sum",
                "hidden-owner",
                "units",
                "tooltip-six",
                "same-place",
                "domain-linear",
                "hostile",
            )

        /** Floor (non-vacuity), under the points of the fixtures above at both frames. */
        private const val MIN_POINTS = 150

        /** The card of a row, written from the fixture's rows (D9): heading, then each row's name and value, then extra. */
        private val CARDS =
            listOf(
                Triple("hidden-owner", 4, listOf("B", "X", "45", "Y", "45")),
                Triple("units", 2, listOf("Distance", "2,500 km", "Speed", "55 km/h", "Weight", "5 kg")),
            )

        private val EMITTED = Regex("""scatter/[a-z0-9-]+/\d+x\d+\.input\.json""")

        private val PAGE =
            ChartTestPage(
                origin = "https://chart-scatter.test",
                resources = "chart-scatter",
                script = "chart-scatter.js",
                testTypes = emptyList(),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/$r" else null },
            )

        private var playwright: Playwright? = null
        private lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun launch() {
            val pw = Playwright.create().also { playwright = it }
            browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        }

        @JvmStatic
        @AfterAll
        fun shut() {
            if (::browser.isInitialized) browser.close()
            playwright?.close()
            playwright = null
        }
    }
}

/**
 * The in-page checks of [JsRenderedScatterBrowserTest], each an arrow function evaluated in the page. The expected
 * ring, card and key stops are the test's own; the layer's functions are never called, and the engine is called only
 * to hand the primitives of a draw with a hidden list of the test's choosing (A.7: "the primitives the engine hands for
 * the same input with that series hidden").
 */
private object Checks {
    private val HELPERS =
        """
        const R = window.JsRendered;
        const svg = document.getElementById("chart");
        const sc = window.__scatter;
        const out = sc.out;
        const TH = R.theme.resolve(out.theme);
        const S = TH.n("--chart-text");
        const marks = out.index.marks;
        const plot = out.index.plot;
        const cls = (e) => e.getAttribute("class") || "";
        const all = () => [...svg.querySelectorAll("*")];
        const added = () => all().filter((e) => !sc.written.has(e));
        const shown = () => [...svg.querySelectorAll("text.dp-tooltip")].map((t) => t.textContent);
        const near = (a, b, tol) => Math.abs(a - b) <= tol;
        const byDatum = (d) => marks.find((m) => m.datum === d);
        const tags = { rect: "rect", line: "line", path: "path", circle: "circle", text: "text", group: "g" };
        const own = (e) => !(e.tagName === "linearGradient" || e.tagName === "stop" || (e.parentNode === svg && (e.tagName === "title" || e.tagName === "desc")));
        const textOf = (p) => (p.shown !== null && p.shown !== undefined ? p.shown : p.text);
        const ticksOf = (prims) => prims.filter((p) => p.k === "text" && p.role === "tick" && p.anchor === "end").map(textOf);
        const domEquals = (prims) => {
          const els = all().filter(own), bad = [];
          for (let i = 0; i < Math.max(els.length, prims.length); i++) {
            const e = els[i], p = prims[i];
            if (!e || !p) { bad.push(i + ": " + (e ? e.tagName : "no element") + " / " + (p ? p.k : "no primitive")); continue; }
            const kids = [...e.querySelectorAll("*")].filter(own).length;
            const text = p.k === "text" ? textOf(p) : null;
            const ok = e.tagName === tags[p.k] && cls(e).split(" ")[0] === "dp-" + p.role && (text === null || e.textContent === text) && (p.k !== "group" || kids === p.n)
              && (p.k !== "circle" || (near(Number(e.getAttribute("cx")), p.cx, 0.011) && near(Number(e.getAttribute("cy")), p.cy, 0.011) && near(Number(e.getAttribute("r")), p.r, 0.011)));
            if (!ok) bad.push(i + ": " + e.tagName + "." + cls(e) + " / " + p.k + "." + p.role);
          }
          return { elements: els.length, primitives: prims.length, mismatches: bad.slice(0, 8), points: prims.filter((p) => p.k === "circle" && p.role === "mark").length };
        };
        """.trimIndent()

    private fun fn(
        args: String,
        body: String,
    ): String = "($args) => {\n$HELPERS\n$body\n}"

    /** Draws through the page's script and keeps the draw for the checks. */
    val DRAW_JS =
        """
        ([file, w, h]) => window.ChartScatter.draw(file, { w: w, h: h }).then((o) => {
          window.__scatter = { out: o };
          return { state: o.state };
        })
        """.trimIndent()

    /** The writer's elements, kept to tell the layer's additions apart; the picture's place and scale in the page. */
    val PREPARE_JS =
        """
        () => {
          const svg = document.getElementById("chart");
          const sc = window.__scatter;
          sc.written = new Set(svg.querySelectorAll("*"));
          sc.count = sc.written.size;
          const r = svg.getBoundingClientRect(), vb = svg.viewBox.baseVal;
          return { left: r.left, top: r.top, sx: r.width / vb.width, sy: r.height / vb.height };
        }
        """.trimIndent()

    /** The page's DOM against the drawn primitives. */
    val DOM_JS = fn("", "return domEquals(out.primitives);")

    /**
     * A place `dist` px from the centre of row `datum`'s point, inside the plot, where that point is the nearest and no
     * other point lies within 24 px (the reach; at a dist beyond it, no point at all lies within it).
     */
    val PLACE_JS =
        fn(
            "[datum, dist]",
            """
            const m = byDatum(datum), d = Math.SQRT1_2;
            if (!m) return { x: 0, y: 0, problems: ["no point for row " + datum] };
            for (const [ux, uy] of [[1, 0], [-1, 0], [0, 1], [0, -1], [d, d], [-d, d], [d, -d], [-d, -d]]) {
              const x = m.shape.x + ux * dist, y = m.shape.y + uy * dist;
              if (x < plot.x || x > plot.x + plot.w || y < plot.y || y > plot.y + plot.h) continue;
              const others = marks.filter((k) => k !== m).map((k) => Math.hypot(x - k.shape.x, y - k.shape.y));
              if (others.every((o) => o > 24 && o > dist)) return { x: x, y: y, problems: [] };
            }
            return { x: 0, y: 0, problems: ["no place " + dist + " px from row " + datum + " inside the plot and away from the other points"] };
            """.trimIndent(),
        )

    /** The card shown and the ring the layer added: one circle around row `datum`'s point, in its series' colour. */
    val HOVER_JS =
        fn(
            "[datum, share]",
            """
            const p = [], m = byDatum(datum);
            const rings = added().filter((e) => /\bdp-guide\b/.test(cls(e)));
            let ring = null;
            if (rings.length !== 1) p.push(rings.length + " rings");
            else {
              const g = rings[0];
              ring = { tag: g.tagName, cx: Number(g.getAttribute("cx")), cy: Number(g.getAttribute("cy")), r: Number(g.getAttribute("r")), stroke: g.getAttribute("stroke") };
              const key = svg.querySelector('g.dp-legend[data-legend="' + m.series + '"] circle');
              const colour = key !== null ? key.getAttribute("fill") : TH.css("--chart-series-1");
              if (g.tagName !== "circle") p.push("the ring is a " + g.tagName);
              if (!near(ring.cx, m.shape.x, 0.011) || !near(ring.cy, m.shape.y, 0.011)) p.push("the ring's centre " + ring.cx + "," + ring.cy + ", the point's " + m.shape.x + "," + m.shape.y);
              if (!near(ring.r, m.shape.r + share * S, 0.011)) p.push("the ring's radius " + ring.r + ", the test's own " + (m.shape.r + share * S));
              if (ring.stroke !== colour) p.push("the ring's colour " + ring.stroke + ", the series' " + colour);
              if (g.getAttribute("fill") !== "none") p.push("the ring's fill " + g.getAttribute("fill"));
              if (Number(g.getAttribute("stroke-width")) !== TH.n("--chart-line")) p.push("the ring's width " + g.getAttribute("stroke-width"));
            }
            return { shown: shown(), rings: rings.length, ring: ring, problems: p };
            """.trimIndent(),
        )

    /** A picture without a highlight: no card, ring, focus ring or dimming, and the writer's element count. */
    val NOTHING_JS =
        fn(
            "",
            """
            const p = [];
            if (shown().length !== 0) p.push("a card: " + shown());
            if (added().some((e) => /\bdp-(guide|focus)\b/.test(cls(e)))) p.push("a ring or a focus ring");
            if (svg.classList.contains("dp-dimmed")) p.push("dp-dimmed");
            if (all().length !== sc.count) p.push(all().length + " elements, the writer wrote " + sc.count);
            return { problems: p };
            """.trimIndent(),
        )

    /** The legend entry `id`'s key: its tag and its centre in the page. */
    val LEGEND_KEY_JS =
        fn(
            "id",
            """
            const g = svg.querySelector('g.dp-legend[data-legend="' + id + '"]');
            const key = g === null ? null : [...g.children].find((k) => k.tagName !== "text");
            if (!key) return { tag: null, x: 0, y: 0 };
            const b = key.getBoundingClientRect();
            return { tag: key.tagName, x: b.left + b.width / 2, y: b.top + b.height / 2 };
            """.trimIndent(),
        )

    /** The page's DOM against the engine's draw of the kept input with `hidden`; that draw's value-axis tick texts. */
    val DOM_HIDDEN_JS =
        fn(
            "hidden",
            """
            const input = window.ChartScatter.input();
            input.hidden = hidden;
            const o = R.render(input);
            return Object.assign({ state: o.state, ticks: ticksOf(o.primitives) }, domEquals(o.primitives));
            """.trimIndent(),
        )

    /** The value axis's tick texts as the page shows them (the tick texts anchored at their end: the vertical axis). */
    const val VALUE_TICKS_JS =
        """() => ({ ticks: [...document.querySelectorAll('#chart text.dp-tick[text-anchor="end"]')].map((t) => t.textContent) })"""

    /** After a key: one focus ring, centred on row `datum`'s point and around it; the card shown is `card`, whole. */
    val KEY_JS =
        fn(
            "[datum, card]",
            """
            const p = [], m = byDatum(datum);
            const rings = added().filter((e) => /\bdp-focus\b/.test(cls(e)));
            if (rings.length !== 1) p.push(rings.length + " focus rings");
            else {
              const f = ["x", "y", "width", "height"].map((a) => Number(rings[0].getAttribute(a)));
              if (m.shape.x < f[0] || m.shape.x > f[0] + f[2] || m.shape.y < f[1] || m.shape.y > f[1] + f[3]) p.push("the focus ring " + f + " is not around row " + datum + "'s point");
              if (!near(f[0] + f[2] / 2, m.shape.x, 0.02) || !near(f[1] + f[3] / 2, m.shape.y, 0.02)) p.push("the focus ring " + f + " is not centred on row " + datum + "'s point " + m.shape.x + "," + m.shape.y);
            }
            const c = shown();
            if (JSON.stringify(c) !== JSON.stringify(card)) p.push("the card " + JSON.stringify(c) + ", the row's " + JSON.stringify(card));
            return { problems: p, card: c };
            """.trimIndent(),
        )

    /** Whether the one focus ring stands on the legend entry `id`. */
    val KEY_ENTRY_JS =
        fn(
            "id",
            """
            const rings = added().filter((e) => /\bdp-focus\b/.test(cls(e)));
            const g = svg.querySelector('g.dp-legend[data-legend="' + id + '"]');
            if (rings.length !== 1 || g === null) return { on: false, rings: rings.length };
            const f = ["x", "y", "width", "height"].map((a) => Number(rings[0].getAttribute(a)));
            const b = g.getBBox(), cx = f[0] + f[2] / 2, cy = f[1] + f[3] / 2;
            return { on: cx >= b.x - 1 && cx <= b.x + b.width + 1 && cy >= b.y - 1 && cy <= b.y + b.height + 1, ring: f, box: [b.x, b.y, b.width, b.height] };
            """.trimIndent(),
        )
}
