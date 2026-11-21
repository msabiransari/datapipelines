package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The legend's forms in a real Chromium (lane 1054, js-rendered G6; brief 1054 D11, A.8): a legend of keys is no
 * switch (look contract C11, LA15), and a switch entry with a dot key is found, pressed and dimmed by its dot.
 *
 * No application is booted. The serving code is [ChartTestPage]'s, shared with [ChartMarkLookupBrowserTest]: by
 * request interception on an origin of its own the page receives the engine, the layer, `css/app.css` and its fonts,
 * the test-only types `test_switch` and `test_keys` from the test classpath with their emitted inputs, and from
 * `chart-legend-forms/` one page (the reference page's policy as a `meta` element) and its script. The script draws the
 * fixture at the frame the test names, writes it with `JsRendered.writeDom` and `describe`, and attaches the layer
 * with a host that counts every switch it is asked for and draws again with the new hidden list. Every test ends with
 * its console errors, page errors, dialogs, policy violations, aborted requests and `style` attributes at 0.
 *
 * The oracle is the test's: the texts of the description and the dimming's value (`--chart-dim` 0.25, the look
 * contract's §3) are written here; places are read from the elements the writer wrote.
 */
class ChartLegendFormsBrowserTest {
    @Test
    fun `A8 a legend of keys is no switch - no button, an image, its entries described, a click asks nothing, nothing dims`() {
        keysChecks(emptyMap())
    }

    @Test
    fun `A8 a switch with a dot key - three buttons, a click on the dot asks once and dims it, Enter undoes it, hover dims`() {
        switchChecks(emptyMap())
    }

    @Test
    fun `G1 and G7 the browser half - a key-only group written as a switch, and a layer without the dot's box, each turn its check red`() {
        val chrome = Files.readString(ChartTestPage.dir.resolve("engine/10-chrome.js"))
        val group = "{ role: \"legend\", series: null, legend: null, label: null, on: true }"
        withClue("G1's anchor stands once") { chrome.split(group).size - 1 shouldBe 1 }
        val asSwitch = chrome.replace(group, "{ role: \"legend\", series: null, legend: it.s.id, label: null, on: true }")
        val g1 = shouldThrow<AssertionError> { keysChecks(mapOf("engine/10-chrome.js" to asSwitch.toByteArray())) }
        withClue("G1 red names the switch attribute: ${g1.message}") { g1.message shouldContain "D10, no switch attribute in the page" }

        val layer = Files.readString(ChartTestPage.dir.resolve(ChartTestPage.LAYER))
        val branch = " : p.k === \"circle\" ? { x: p.cx - p.r, y: p.cy - p.r, w: 2 * p.r, h: 2 * p.r } : null;"
        withClue("G7's anchor stands once") { layer.split(branch).size - 1 shouldBe 1 }
        val g7 = shouldThrow<AssertionError> { switchChecks(mapOf(ChartTestPage.LAYER to layer.replace(branch, " : null;").toByteArray())) }
        withClue("G7 red names the hover: ${g7.message}") { g7.message shouldContain "the pointer over the dot: the picture is dimmed" }
    }

    /** A.8 (a) on the page served with [overrides] (none: the real files). */
    private fun keysChecks(overrides: Map<String, ByteArray>) {
        val descriptions =
            mapOf(
                "two-squares-line" to "Test_keys chart; series: Amount; legend: Increase, Decrease, Opening and total; 3 marks.",
                "square-line-dot" to "Test_keys chart; series: Amount; legend: Bars, Lines, Points; 3 marks.",
                "id-equals-series" to "Test_keys chart; series: North, South; legend: North as a key, Other; 4 marks.",
            )
        var places = 0
        open(overrides).use { s ->
            for ((fixture, description) in descriptions) places += keysFixture(s, fixture, description)
            s.clean("legend of keys")
        }
        withClue("A.8 (a) non-vacuity: pointer places tried") { places shouldBeGreaterThanOrEqual MIN_PLACES }
    }

    /** A.8 (a) on one fixture of test_keys; returns the pointer places tried. */
    private fun keysFixture(
        s: Session,
        fixture: String,
        description: String,
    ): Int {
        var places = 0
        s.draw("test_keys/$fixture/960x540.input.json")
        val r = s.read(LEGEND_JS)
        val groups = s.groups()
        withClue("$fixture: the legend of keys is drawn") { groups.size shouldBeGreaterThanOrEqual 2 }
        withClue("$fixture: D10, no switch attribute in the page") { (r["switchy"] as Number).toInt() shouldBe 0 }
        withClue("$fixture: the picture stays an image") { r["role"] shouldBe "img" }
        withClue("$fixture: no child of the picture is hidden from assistive technology") { (r["hiddenKids"] as Number).toInt() shouldBe 0 }
        withClue("$fixture: the desc names the entries") { r["desc"] as String shouldContain description }
        for (g in groups) {
            withClue("$fixture: a legend group carries no label from a title") { g["label"] shouldBe null }
            withClue("$fixture: a key at the full opacity, a name never struck") {
                (g["keyOpacity"] as Number).toDouble() shouldBe 1.0
                g["struck"] shouldBe null
            }
            for (at in listOf(g["key"], g["name"]).filterNotNull()) {
                pointNothing(s, fixture, point(at))
                places++
            }
        }
        s.page.locator("#chart").focus()
        for (key in WALK) {
            s.page.keyboard().press(key)
            val ring = s.read(RING_JS)
            val onLegend = s.groups().map { point(it["key"]) }.filter { (x, y) -> inside(ring, x, y) }
            withClue("$fixture after $key: the keys' stop is on a legend group") { onLegend.shouldBeEmpty() }
            s.page.keyboard().press("Enter")
        }
        withClue("$fixture: the host was asked for no switch") { s.asked().shouldBeEmpty() }
        return places
    }

    /** The pointer over a place of a key-only entry dims nothing; a click there (the host's count is read later). */
    private fun pointNothing(
        s: Session,
        fixture: String,
        at: Pair<Double, Double>,
    ) {
        val (x, y) = at
        s.page.mouse().move(x, y)
        s.settled()
        val dim = s.read(DIM_JS)
        withClue("$fixture at $x,$y: nothing is dimmed") {
            dim["dimmed"] shouldBe false
            @Suppress("UNCHECKED_CAST")
            (dim["marks"] as List<Number>).map { it.toDouble() }.distinct() shouldContainExactly listOf(1.0)
        }
        s.page.mouse().click(x, y)
    }

    /** A.8 (b) on the page served with [overrides] (none: the real files). */
    private fun switchChecks(overrides: Map<String, ByteArray>) {
        open(overrides).use { s ->
            s.draw("test_switch/three-forms/960x540.input.json")
            val buttons = s.groups()
            withClue("three buttons") {
                buttons.map { it["legend"] } shouldContainExactly listOf("string:North", "string:South", "string:East")
            }
            buttons.forEach { withClue("${it["legend"]} is a pressed button") { it["pressed"] shouldBe "true" } }
            val east = buttons.single { it["legend"] == "string:East" }
            withClue("East's key is a dot") { east["keyTag"] shouldBe "circle" }

            // The pointer over the dot dims the other series and not East.
            val (dx, dy) = point(east["key"])
            s.page.mouse().move(dx, dy)
            s.settled()
            val dim = s.read(DIM_JS)
            withClue("the pointer over the dot: the picture is dimmed") { dim["dimmed"] shouldBe true }
            @Suppress("UNCHECKED_CAST")
            val bySeries = dim["bySeries"] as Map<String, List<Number>>
            withClue("the pointer over the dot: East's marks stay, the others fade to --chart-dim: $bySeries") {
                bySeries.getValue("string:East").map { it.toDouble() }.distinct() shouldContainExactly listOf(1.0)
                bySeries.getValue("string:North").map { it.toDouble() }.distinct() shouldContainExactly listOf(DIM)
                bySeries.getValue("string:South").map { it.toDouble() }.distinct() shouldContainExactly listOf(DIM)
            }

            // A click on the dot asks the host once; after the redraw East is off: not pressed, its dot dim, its name struck.
            s.page.mouse().click(dx, dy)
            withClue("a click on the dot asks the host once") { s.asked() shouldContainExactly listOf("string:East") }
            val off = s.groups().single { it["legend"] == "string:East" }
            withClue("after the redraw East reads off") {
                off["pressed"] shouldBe "false"
                (off["keyOpacity"] as Number).toDouble() shouldBe DIM
                off["struck"] shouldBe "line-through"
            }

            // Enter on the entry brings it back: the keys walk until the focus ring holds East's dot, then Enter.
            s.page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
            s.page.locator("#chart").focus()
            var steps = 0
            while (!inside(s.read(RING_JS), dx, dy) && steps < MAX_STEPS) {
                s.page.keyboard().press("ArrowRight")
                steps++
            }
            withClue("the keys reach East's entry within $MAX_STEPS steps") { inside(s.read(RING_JS), dx, dy) shouldBe true }
            s.page.keyboard().press("Enter")
            withClue("Enter on the entry asks the host once more") { s.asked() shouldContainExactly listOf("string:East", "string:East") }
            val back = s.groups().single { it["legend"] == "string:East" }
            withClue("East is back on") {
                back["pressed"] shouldBe "true"
                (back["keyOpacity"] as Number).toDouble() shouldBe 1.0
                back["struck"] shouldBe null
            }
            s.clean("switch with a dot key")
        }
    }

    @Test
    fun `G14 the page's cleanliness can fail - a style attribute and a request outside the served set are seen`() {
        val script = ChartTestPage.resourceText("chart-legend-forms/chart-legend-forms.js")
        val anchor = "    var handed = { output: out, theme: input.theme, measure: input.measure, frame: input.frame };\n"
        withClue("the plant's anchor stands once") { script.split(anchor).size - 1 shouldBe 1 }
        val planted =
            script.replace(
                anchor,
                anchor + "    svg.setAttribute(\"style\", \"outline: none\");\n    fetch(\"not-served.json\").catch(function () {});\n",
            )
        open(mapOf("chart-legend-forms.js" to planted.toByteArray())).use { s ->
            s.draw("test_keys/two-squares-line/960x540.input.json")
            s.until("the stray request is aborted") { s.seen().getValue("requests aborted").isNotEmpty() }
            val seen = s.seen()
            withClue("the planted style attribute is seen") { seen.getValue("style attributes") shouldNotBe emptyList<String>() }
            withClue("the stray request is seen") { seen.getValue("requests aborted").single() shouldContain "not-served.json" }
        }
    }

    // ------------------------------------------------------------------------------------------------ the session

    private inner class Session(
        private val opened: ChartTestPage.Opened,
    ) : AutoCloseable {
        val page = opened.page

        fun draw(file: String) {
            page.evaluate("([f, w, h]) => window.ChartLegendForms.draw(f, { w: w, h: h }).then(() => true)", listOf(file, FRAME_W, FRAME_H))
        }

        @Suppress("UNCHECKED_CAST")
        fun read(js: String): Map<String, Any?> = page.evaluate(js) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun groups(): List<Map<String, Any?>> = read(LEGEND_JS)["groups"] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        fun asked(): List<String> = page.evaluate("() => window.ChartLegendForms.asked()") as List<String>

        /** The dimming eases over --chart-ease (app.css): computed opacities are read once every transition has ended. */
        fun settled() =
            until("the transitions of the dimming have ended") { page.evaluate("() => document.getAnimations().length === 0") == true }

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
            withClue("$point: D11, every list empty") { seen shouldBe seen.mapValues { emptyList<String>() } }
        }

        fun until(
            what: String,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + WAIT_NANOS
            while (!condition()) {
                if (System.nanoTime() > deadline) throw AssertionError("$what: not reached; ${seen()}")
                page.waitForTimeout(POLL_MILLIS)
            }
        }

        override fun close() = opened.context.close()
    }

    private fun open(overrides: Map<String, ByteArray> = emptyMap()): Session = Session(PAGE.open(browser, VIEW_W, VIEW_H, overrides))

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private const val FRAME_W = 960
        private const val FRAME_H = 540
        private const val WAIT_NANOS = 10_000_000_000L
        private const val POLL_MILLIS = 50.0

        /** `--chart-dim`, the look contract's §3 value, written here (the oracle is not the theme table). */
        private const val DIM = 0.25
        private const val MAX_STEPS = 16
        private const val MIN_PLACES = 12

        /** The keys a stop walk presses (Tab leaves the picture, so it is not among them); Enter follows each. */
        private val WALK =
            listOf("ArrowRight", "ArrowRight", "ArrowDown", "ArrowLeft", "End", "ArrowUp", "Home", "ArrowRight", "ArrowRight", "End")

        private val EMITTED = Regex("""(test_switch|test_keys)/[a-z0-9-]+/\d+x\d+\.input\.json""")

        private val PAGE =
            ChartTestPage(
                origin = "https://chart-legend-forms.test",
                resources = "chart-legend-forms",
                script = "chart-legend-forms.js",
                testTypes = listOf("test_switch", "test_keys"),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/_types/$r" else null },
            )

        /**
         * The picture as written: its role, its desc, the elements with a switch attribute, the children hidden from
         * assistive technology, and each legend group (`g` elements only: the writer also gives a legend group's key and
         * name the class) with its attributes, its key's and name's centre in the page, its key's tag and opacity, and
         * whether its name is struck. A name's point is a quarter into its box: the layer measures names with the
         * engine's table, the browser draws them in its own font, and the two agree on where a name starts.
         */
        private val LEGEND_JS =
            """
            () => {
              const svg = document.getElementById("chart");
              const centre = (e) => { const b = e.getBoundingClientRect(); return { x: b.left + b.width / 2, y: b.top + b.height / 2 }; };
              const groups = [...svg.querySelectorAll("g.dp-legend")].map((g) => {
                const kids = [...g.children];
                const key = kids.find((k) => k.tagName !== "text");
                const name = kids.find((k) => k.tagName === "text") || null;
                const nb = name ? name.getBoundingClientRect() : null;
                return {
                  legend: g.getAttribute("data-legend"), pressed: g.getAttribute("aria-pressed"), label: g.getAttribute("aria-label"),
                  key: centre(key), keyTag: key.tagName, keyOpacity: Number(getComputedStyle(key).opacity),
                  name: nb ? { x: nb.left + nb.width / 4, y: nb.top + nb.height / 2 } : null,
                  struck: name ? name.getAttribute("text-decoration") : null,
                };
              });
              const desc = svg.querySelector("desc");
              return {
                role: svg.getAttribute("role"),
                desc: desc ? desc.textContent : "",
                switchy: document.querySelectorAll('[data-legend], [role="button"], [aria-pressed]').length,
                hiddenKids: [...svg.children].filter((c) => c.hasAttribute("aria-hidden")).length,
                groups: groups,
              };
            }
            """.trimIndent()

        /** Whether the picture is dimmed, and the computed opacity of every mark group, all and by series. */
        private val DIM_JS =
            """
            () => {
              const svg = document.getElementById("chart");
              const marks = [...svg.querySelectorAll("g.dp-mark")];
              const bySeries = {};
              for (const m of marks) {
                const id = m.getAttribute("data-series");
                if (id !== null) (bySeries[id] = bySeries[id] || []).push(Number(getComputedStyle(m).opacity));
              }
              return { dimmed: svg.classList.contains("dp-dimmed"), marks: marks.map((m) => Number(getComputedStyle(m).opacity)), bySeries: bySeries };
            }
            """.trimIndent()

        /** The focus ring's box in the page, or no box. */
        private val RING_JS =
            """
            () => {
              const ring = document.querySelector("#chart .dp-focus");
              if (!ring) return { on: false };
              const b = ring.getBoundingClientRect();
              return { on: true, left: b.left, top: b.top, right: b.right, bottom: b.bottom };
            }
            """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        private fun point(at: Any?): Pair<Double, Double> {
            val m = at as Map<String, Any?>
            return (m["x"] as Number).toDouble() to (m["y"] as Number).toDouble()
        }

        private fun inside(
            ring: Map<String, Any?>,
            x: Double,
            y: Double,
        ): Boolean {
            if (ring["on"] != true) return false

            fun n(k: String) = (ring[k] as Number).toDouble()
            return x >= n("left") && x <= n("right") && y >= n("top") && y <= n("bottom")
        }

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
