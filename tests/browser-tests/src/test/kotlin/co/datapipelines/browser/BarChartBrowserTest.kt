package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #860 A.5 — every bar proof of the `chart` kind v1, in a real browser on the capability preview (the page an agent
 * reviews), under the product's live CSP (the suite's collector, asserted in `closePage`). Each fixture of
 * [ChartBrowserFixtures] is created through the REST route as a DRAFT with its one test case, a session is started, and
 * the preview is opened in a cookie-less context whose clock is `America/New_York` — so a formatter that read the local
 * zone would print a UTC-midnight DATE as the previous day. Everything asserted is read off the DOM Plotly drew (the
 * drawn bar paths, the tick and hover text, the value labels' boxes), never off the option the converter produced.
 *
 * The record (2026-11-06 §4.4, R11) lets `<` and `>` into a title and requires them escaped at render: the markup
 * fixture and the `markup-title` example are rendered as TEXT, nothing executes and no element is injected.
 */
class BarChartBrowserTest : VisualizationBrowserSuite() {
    @Test
    fun `every bar fixture draws its marks, band ticks, hover text and value labels - read off the DOM in New York`() {
        startTrace()
        val root = ready("vbar")
        val agent = newYorkContext()
        try {
            withClue("the agent's clock is New York, where UTC midnight is the previous evening") {
                val clock = agent.newPage()
                clock.evaluate("() => Intl.DateTimeFormat().resolvedOptions().timeZone") shouldBe ZONE
                clock.evaluate("() => new Date('2026-03-01T00:00:00Z').getDate()") shouldBe 28
                clock.close()
            }
            assertSoftly {
                for (name in ChartBrowserFixtures.NAMES) {
                    val fixture = ChartBrowserFixtures.fixture(name)
                    val preview = open(agent, "$root/charts/${name.replace('-', '_')}", fixture)
                    try {
                        assertDrawn(preview, fixture)
                        if (name in ChartBrowserFixtures.EXAMPLES) shoot(preview, name)
                    } finally {
                        preview.close()
                    }
                }
            }
        } finally {
            agent.close()
        }
    }

    @Test
    fun `markup in a title, a category and a series is shown as text - nothing executes, no element is injected`() {
        startTrace()
        val root = ready("vbarmarkup")
        val agent = newYorkContext()
        try {
            val example = ChartBrowserFixtures.fixture("bar-vertical").withTitle(MARKUP_TITLE)
            val titled = open(agent, "$root/charts/markup_title", example)
            withClue("the markup-title example's in-chart title is its literal text") {
                titled.evaluate("sel => document.querySelector(sel + ' .gtitle').textContent", CASE) shouldBe MARKUP_TITLE
                titled.evaluate("sel => document.querySelectorAll(sel + ' .gtitle tspan[style*=\"bold\"]').length", CASE) shouldBe 0
            }
            shoot(titled, "markup-title")
            // The control: the same title written RAW into Plotly's sink (what an unescaped converter would hand it) becomes a
            // bold element, and the probe below sees it — so its zero over the product's chart is a measurement.
            titled.evaluate(
                "([sel, t]) => window.Plotly.relayout(document.querySelector(sel + ' .js-plotly-plot'), {'title.text': t})" +
                    ".then(() => true)",
                listOf(CASE, MARKUP_TITLE),
            )
            withClue("the control: a raw title write is found by the probe") {
                ((titled.evaluate(INJECTED, CASE) as Map<*, *>)["bold"] as Number).toInt() shouldBeGreaterThan 0
            }
            // The raw write's bold run is an inline style, which the product's CSP also refuses: a second witness. It is the
            // control's own, so it is drained here and the test's closing collector still sees the product page alone.
            val refused = awaitCspViolations(titled)
            withClue("the control's raw markup is also refused by the page's CSP") { refused.shouldNotBeEmpty() }
            titled.close()

            val markup = ChartBrowserFixtures.fixture("bar-markup")
            val preview = open(agent, "$root/charts/markup", markup)
            assertDrawn(preview, markup)
            val legend = preview.evaluate("sel => document.querySelector(sel + ' .legend').textContent", CASE) as String
            withClue("the series names are text in the legend") { legend shouldContain "<b>bold</b>" }
            val read = preview.evaluate(INJECTED, CASE) as Map<*, *>
            withClue("no markup became an element and nothing ran: $read") {
                read["images"] shouldBe 0
                read["bold"] shouldBe 0
                read["xss"] shouldBe "undefined"
            }
        } finally {
            agent.close()
        }
    }

    /**
     * Q-L4 with the two `quirks.md:64` fixtures at the corpus's 900 px render and a 480 px phone column. The control plants
     * the reference converter's layout — the product's but for the right margin (20 px, record O-2) — by `relayout`. On
     * the product page the value axis draws a tick label at its range end, and Plotly's automargin reserves half its width
     * on the right, so the reference keeps `bar-horizontal`'s label (its bar ends short of the range) inside the box; its
     * reference geometry is printed, not asserted. `bar-long-labels`' longest bar reaches the range end and its label is
     * wider than that half tick, so the reference puts it outside at both widths: that control is asserted red.
     */
    @Test
    fun `Q-L4 - the longest bar's value label stays inside the chart box, and the reference layout puts it outside`() {
        startTrace()
        val root = ready("vbarql4")
        for (width in listOf(QL4_WIDE, QL4_NARROW)) {
            val agent = newBrowserContext(Browser.NewContextOptions().setTimezoneId(ZONE).setViewportSize(width, QL4_HEIGHT))
            try {
                for ((name, control) in listOf("bar-horizontal" to false, "bar-long-labels" to true)) {
                    val preview = open(agent, "$root/charts/${name.replace('-', '_')}_$width", ChartBrowserFixtures.fixture(name))
                    assertLabelInside(preview, name, width, control)
                    preview.close()
                }
            } finally {
                agent.close()
            }
        }
    }

    /**
     * The product's longest value label is inside the chart box at [width]; the reference layout is then planted and
     * measured, and with [control] its label must be outside the box.
     */
    private fun assertLabelInside(
        preview: Page,
        name: String,
        width: Int,
        control: Boolean,
    ) {
        val product = preview.evaluate(LONGEST_LABEL, CASE) as Map<*, *>
        println("Q-L4 $name at $width product: $product")
        withClue("$name at $width px: the product's longest value label is inside the chart box: $product") {
            product["inside"] shouldBe true
        }
        Files.createDirectories(SHOTS)
        Files.write(SHOTS.resolve("ql4-$name-$width-product.png"), preview.locator(CASE).screenshot())
        preview.evaluate(
            "sel => window.Plotly.relayout(document.querySelector(sel + ' .js-plotly-plot'), {'margin.r': 20}).then(() => true)",
            CASE,
        )
        val reference = preview.evaluate(LONGEST_LABEL, CASE) as Map<*, *>
        println("Q-L4 $name at $width reference: $reference")
        Files.write(SHOTS.resolve("ql4-$name-$width-reference.png"), preview.locator(CASE).screenshot())
        if (!control) return
        withClue("$name at $width px: the reference layout puts the label outside (the control): $reference") {
            reference["inside"] shouldBe false
        }
    }

    /** The CSP violations a planted write caused, drained once they arrive (the collector hears them asynchronously). */
    private fun awaitCspViolations(page: Page): List<String> {
        val seen = mutableListOf<String>()
        var waited = 0.0
        while (seen.isEmpty() && waited < CSP_WAIT_MS) {
            page.waitForTimeout(CSP_POLL_MS)
            waited += CSP_POLL_MS
            seen += drainCspViolations()
        }
        return seen
    }

    /** #456, moved from `VisualizationPreviewBrowserTest` (#860): the preview host's Plotly marker, over a bar chart. */
    @Test
    fun `the head marker precedes Plotly's first lookup and the linked rules apply in both themes`() {
        startTrace()
        val root = ready("vmarker")
        val created =
            api(
                "POST",
                "/api/v1/visualizations",
                ChartBrowserFixtures.document("$root/charts/marker", ChartBrowserFixtures.fixture("bar-vertical")),
            )
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val agent = newBrowserContext()
        recordMarkerAtLoad(agent)
        try {
            val preview = agent.newPage()
            preview.setDefaultTimeout(FIRST_RENDER_TIMEOUT_MS)
            for (theme in listOf("light", "dark")) {
                preview.navigate("$baseUrl$previewUrl&theme=$theme")
                preview.waitForSelector("section[data-dp-case-index='0'][data-dp-ready='true']")
                val observation =
                    preview.evaluate(
                        """() => {
                          const marker = document.getElementById('plotly.js-style-global');
                          return { atLoad: window.__plotlyMarkerAtLoad,
                            rules: marker && marker.sheet ? marker.sheet.cssRules.length : 0,
                            cssLoaded: Array.from(document.styleSheets).some(s => s.href && s.href.endsWith('/vendor/plotly/plotly.css')),
                            font: getComputedStyle(document.querySelector('.js-plotly-plot .plotly')).fontFamily };
                        }""",
                    ) as Map<*, *>
                println("Plotly marker ($theme): $observation")
                val atLoad = observation["atLoad"] as Map<*, *>
                withClue("parser-placed marker at the bundle's first lookup ($theme): $observation") {
                    atLoad["marked"] shouldBe true
                    atLoad["inHead"] shouldBe true
                    atLoad["beforeScript"] shouldBe true
                    atLoad["tag"] shouldBe "TEMPLATE"
                    atLoad["children"] shouldBe 0
                    atLoad["hasSheet"] shouldBe false
                    atLoad["rules"] shouldBe 0
                }
                observation["rules"] shouldBe 0
                observation["cssLoaded"] shouldBe true
                // Reuse DashboardConformanceBrowserTest's positive stylesheet probe.
                (observation["font"] as String).contains("Open Sans") shouldBe true
            }
        } finally {
            agent.close()
        }
    }

    /**
     * #526 — a host that does NOT pre-place the marker gets it from the runtime's fallback
     * (`_ensureStyleElement`), so the fallback's marker must be CSP-compliant too. Chromium
     * refuses EVERY script-created `<style>` element at insertion as an inline style under
     * `style-src 'self'` (with a text child, with an empty comment, or childless — each with the
     * empty-text hash; measured, the #526 lane's probe), so the fallback creates the marker as a
     * TEMPLATE — the same shape every first-party host pre-places (#456): found by id, matching
     * the class, no children, never processed as a stylesheet (no sheet — the host marker's own
     * shape), and ZERO `Applying inline style` refusals from the application's own scripts —
     * the narrowed collector fails the test on any of the latter in `closePage`. Plotly's
     * opt-out did not disable the vendored stylesheet: the positive probe of the conformance
     * case (`getComputedStyle('.js-plotly-plot .plotly').fontFamily`) still reads Open Sans.
     * The instance is reached through `window.DatapipelinesPreview.instances` — the preview's
     * own registry (`visualization-preview.js`), the same path the #456 probe used.
     */
    @Test
    fun `the missing-marker fallback creates a CSP-compliant marker in both themes`() {
        startTrace()
        val root = ready("vfallback")
        val created =
            api(
                "POST",
                "/api/v1/visualizations",
                ChartBrowserFixtures.document("$root/charts/fallback", ChartBrowserFixtures.fixture("bar-vertical")),
            )
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val agent = newBrowserContext()
        try {
            val preview = agent.newPage()
            preview.setDefaultTimeout(FIRST_RENDER_TIMEOUT_MS)
            for (theme in listOf("light", "dark")) {
                preview.navigate("$baseUrl$previewUrl&theme=$theme")
                preview.waitForSelector("section[data-dp-case-index='0'][data-dp-ready='true']")
                val observation =
                    preview.evaluate(
                        """() => {
                          const host = document.getElementById('plotly.js-style-global');
                          if (host) host.remove();
                          window.DatapipelinesPreview.instances[0]._ensureStyleElement();
                          const el = document.getElementById('plotly.js-style-global');
                          return { exists: !!el,
                            inHead: !!el && el.parentElement === document.head,
                            tag: el ? el.tagName : null,
                            className: el ? el.className : null,
                            children: el ? el.childNodes.length : null,
                            hasSheet: !!el && !!el.sheet,
                            matches: !!el && el.matches('.no-inline-styles') };
                        }""",
                    ) as Map<*, *>
                println("Fallback marker ($theme): $observation")
                withClue("the fallback-created marker is CSP-compliant ($theme): $observation") {
                    observation["exists"] shouldBe true
                    observation["inHead"] shouldBe true
                    observation["tag"] shouldBe "TEMPLATE"
                    (observation["className"] as String).contains("no-inline-styles") shouldBe true
                    observation["children"] shouldBe 0
                    observation["matches"] shouldBe true
                    observation["hasSheet"] shouldBe false
                }
                // Falsification 4: the opt-out did not disable plotly.css (the conformance case's probe).
                val font =
                    preview.evaluate(
                        "() => getComputedStyle(document.querySelector('.js-plotly-plot .plotly')).fontFamily",
                    ) as String
                withClue("the vendored stylesheet still applies with the fallback-created marker ($theme)") {
                    font.contains("Open Sans") shouldBe true
                }
            }
        } finally {
            agent.close()
        }
    }

    private fun recordMarkerAtLoad(agent: BrowserContext) {
        // Record the marker at Plotly's FIRST opt-out lookup, before it can create one itself.
        // Final DOM order alone cannot distinguish a parser-placed marker from Plotly's late insertion.
        agent.addInitScript(
            """(() => {
              const original = document.getElementById.bind(document);
              document.getElementById = function (id) {
                const marker = original(id);
                if (id === 'plotly.js-style-global' && !window.__plotlyMarkerAtLoad) {
                  const script = document.querySelector('script');
                  window.__plotlyMarkerAtLoad = {
                    marked: !!marker && marker.classList.contains('no-inline-styles'),
                    inHead: !!marker && marker.parentElement === document.head,
                    beforeScript: !!marker && !!script && !!(marker.compareDocumentPosition(script) & Node.DOCUMENT_POSITION_FOLLOWING),
                    tag: marker ? marker.tagName : null,
                    children: marker ? (marker.content || marker).childNodes.length : null,
                    hasSheet: !!marker && !!marker.sheet,
                    rules: marker && marker.sheet ? marker.sheet.cssRules.length : 0
                  };
                }
                return marker;
              };
            })();""",
        )
    }

    /** Draws [fixture]'s expectations off the DOM: bars, the band ticks in reading order, the value labels, the hover. */
    private fun assertDrawn(
        preview: Page,
        fixture: ChartBrowserFixtures.Fixture,
    ) {
        val read = preview.evaluate(DRAWN, mapOf("sel" to CASE, "axis" to if (fixture.vertical) "x" else "y")) as Map<*, *>
        withClue("${fixture.name}: the drawn bars ${read["paths"]}") { (read["bars"] as Number).toInt() shouldBe fixture.bars }
        if (fixture.bars == 0) return
        withClue("${fixture.name}: the band axis's ticks show the categories, in order") { read["ticks"] shouldBe fixture.categories }
        val labels = read["labels"] as List<*>
        fixture.expect.getAsJsonArray("label_texts").map { it.asJsonObject }.forEach { e ->
            val (si, ci) = e.get("si").asInt to e.get("ci").asInt
            withClue("${fixture.name}: the value label of series $si, category $ci") {
                (labels[si] as List<*>)[ci] shouldBe e.get("text").asString
            }
        }
        fixture.expect.getAsJsonArray("hover").map { it.asJsonObject }.forEach { e ->
            val text = hover(preview, e.get("si").asInt, e.get("ci").asInt)
            val shown = text.joinToString("\n")
            e.getAsJsonArray("contains").map { it.asString }.forEach { part ->
                withClue("${fixture.name}: the hover text of series ${e.get("si")}, category ${e.get("ci")}: $text") {
                    shown shouldContain part
                }
            }
        }
        withClue("${fixture.name}: no element was injected") { (preview.evaluate(INJECTED, CASE) as Map<*, *>)["images"] shouldBe 0 }
        val framed = preview.evaluate(FRAMED, CASE) as Map<*, *>
        withClue("${fixture.name}: #386's compact frame keeps every drawn text inside the chart box $framed") {
            (framed["outside"] as List<*>).shouldBeEmpty()
        }
        withClue("${fixture.name}: the title and subtitle sit above the plot area and its value labels $framed") {
            (framed["overlapping"] as List<*>).shouldBeEmpty()
        }
    }

    /** Moves the mouse onto one drawn bar (Plotly's drag layer is on top, so a hover on the path itself is intercepted). */
    private fun hover(
        preview: Page,
        series: Int,
        category: Int,
    ): List<String> {
        val bar =
            preview
                .locator("$CASE .barlayer .trace")
                .nth(series)
                .locator(".point")
                .nth(category)
                .locator("path")
        bar.scrollIntoViewIfNeeded()
        val box = checkNotNull(bar.boundingBox()) { "series $series, category $category has no drawn bar" }
        preview.mouse().move(box.x + box.width / 2, box.y + box.height / 2)
        preview.waitForFunction("sel => !!document.querySelector(sel + ' .hoverlayer .hovertext')", CASE)
        @Suppress("UNCHECKED_CAST")
        return preview.evaluate(
            "sel => Array.from(document.querySelectorAll(sel + ' .hoverlayer .hovertext')).map(h => h.textContent)",
            CASE,
        ) as List<String>
    }

    /** Creates [fixture] at [name], starts its test session and opens the preview in [agent]; waits for the case's state. */
    private fun open(
        agent: BrowserContext,
        name: String,
        fixture: ChartBrowserFixtures.Fixture,
        theme: String = "light",
    ): Page {
        val created = api("POST", "/api/v1/visualizations", ChartBrowserFixtures.document(name, fixture))
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val preview = agent.newPage()
        preview.setDefaultTimeout(FIRST_RENDER_TIMEOUT_MS)
        preview.navigate("$baseUrl$previewUrl&theme=$theme")
        preview.waitForSelector("$CASE[data-dp-ready='true']")
        val states = if (fixture.bars == 0) listOf("no-data") else listOf("success", "ready")
        preview.waitForFunction(
            "args => { const c = document.querySelector(args.sel + ' .dp-dashboard-status');" +
                " return !!c && args.states.indexOf(c.getAttribute('data-dp-state')) >= 0; }",
            mapOf("sel" to CASE, "states" to states),
        )
        return preview
    }

    /** The review evidence: the case's card in this theme and the other. */
    private fun shoot(
        preview: Page,
        name: String,
    ) {
        Files.createDirectories(SHOTS)
        for (theme in listOf("light", "dark")) {
            if (theme != "light") {
                preview.navigate(preview.url().replace("theme=light", "theme=$theme"))
                preview.waitForSelector("$CASE[data-dp-ready='true']")
            }
            preview.waitForTimeout(SETTLE_MS)
            Files.write(SHOTS.resolve("$name-$theme.png"), preview.locator(CASE).screenshot())
            withClue("CSP on the $name preview in $theme") { drainCspViolations().shouldBeEmpty() }
        }
    }

    private fun newYorkContext(): BrowserContext = newBrowserContext(Browser.NewContextOptions().setTimezoneId(ZONE))

    private companion object {
        const val CASE = "section[data-dp-case-index='0']"
        const val ZONE = "America/New_York"
        const val MARKUP_TITLE = "<b>Amount</b> by region"
        const val SETTLE_MS = 400.0
        const val QL4_WIDE = 900
        const val QL4_NARROW = 480
        const val QL4_HEIGHT = 700
        const val CSP_WAIT_MS = 5_000.0
        const val CSP_POLL_MS = 100.0
        val SHOTS = Paths.get("build", "reports", "chart-bar")

        /**
         * The drawn bars (a path Plotly leaves as `M0,0Z` is a gap), the band axis's tick texts sorted by their position on
         * screen (what a reader sees, left to right or top to bottom), and each trace's value labels by point.
         */
        const val DRAWN =
            """args => {
              const s = document.querySelector(args.sel);
              // A drawn bar has an area: Plotly leaves a null cell's path in place with no width or no height.
              const bars = Array.from(s.querySelectorAll('.barlayer .trace .point path'))
                .filter(p => { const r = p.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).length;
              const ticks = Array.from(s.querySelectorAll('.' + args.axis + 'tick text'))
                .map(t => ({ text: t.textContent, at: t.getBoundingClientRect()[args.axis === 'x' ? 'left' : 'top'] }))
                .sort((a, b) => a.at - b.at).map(t => t.text);
              const labels = Array.from(s.querySelectorAll('.barlayer .trace')).map(tr =>
                Array.from(tr.querySelectorAll('.point')).map(p => { const t = p.querySelector('text'); return t ? t.textContent : null; }));
              const paths = Array.from(s.querySelectorAll('.barlayer .trace')).map(tr =>
                Array.from(tr.querySelectorAll('.point path')).map(p => (p.getAttribute('d') || '').slice(0, 40)));
              return { bars: bars, ticks: ticks, labels: labels, paths: paths };
            }"""

        /** What markup would leave behind had it been parsed: an image, a bold run, the payload's global. */
        const val INJECTED =
            """sel => {
              const s = document.querySelector(sel);
              return { images: s.querySelectorAll('img, image').length, bold: s.querySelectorAll('tspan[style*="bold"], b').length, xss: typeof window.__xss };
            }"""

        /**
         * The size frame's evidence: every drawn text (value labels, ticks, axis and legend titles, legend entries, the
         * title and subtitle) outside the chart container's box, and every title line whose bottom passes the plot area's
         * top or a value label's top. One pixel of rounding is allowed on each edge.
         */
        const val FRAMED =
            """sel => {
              const s = document.querySelector(sel);
              const box = s.querySelector('.js-plotly-plot').getBoundingClientRect();
              const edge = el => { const b = el.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom].map(Math.round); };
              const inBox = b => b[0] >= Math.floor(box.left) - 1 && b[1] >= Math.floor(box.top) - 1
                && b[2] <= Math.ceil(box.right) + 1 && b[3] <= Math.ceil(box.bottom) + 1;
              const shown = t => { const b = t.getBoundingClientRect(); return !!t.textContent && (b.width > 0 || b.height > 0); };
              const drawn = sel => Array.from(s.querySelectorAll(sel)).filter(shown)
                .map(t => ({ text: t.textContent, at: edge(t) }));
              const texts = drawn('.barlayer .point text, .xtick text, .ytick text, .g-xtitle text, .g-ytitle text, .legend text, .gtitle, .gtitle-subtitle');
              const drag = s.querySelector('.nsewdrag');
              const labels = drawn('.barlayer .point text');
              const floor = Math.min.apply(null, [drag ? edge(drag)[1] : Infinity].concat(labels.map(l => l.at[1])));
              const titles = drawn('.gtitle, .gtitle-subtitle');
              return { outside: texts.filter(t => !inBox(t.at)), overlapping: titles.filter(t => t.at[3] > floor + 1),
                box: [box.left, box.top, box.right, box.bottom].map(Math.round), plot: drag ? edge(drag) : null, titles: titles };
            }"""

        /** The longest bar's value label against the chart container: both boxes and whether the first is inside. */
        const val LONGEST_LABEL =
            """sel => {
              const s = document.querySelector(sel);
              const box = s.querySelector('.js-plotly-plot').getBoundingClientRect();
              let longest = null;
              s.querySelectorAll('.barlayer .point').forEach(p => {
                const path = p.querySelector('path');
                const w = path ? path.getBoundingClientRect().width : 0;
                if (p.querySelector('text') && (!longest || w > longest.w)) longest = { w: w, path: path, text: p.querySelector('text') };
              });
              const r = longest.text.getBoundingClientRect();
              const bar = longest.path.getBoundingClientRect();
              const drag = s.querySelector('.nsewdrag');
              const plot = drag ? drag.getBoundingClientRect() : null;
              const inside = r.left >= box.left && r.right <= box.right && r.top >= box.top && r.bottom <= box.bottom;
              const edge = rect => rect ? [rect.left, rect.top, rect.right, rect.bottom].map(Math.round) : null;
              return { inside: inside, label: edge(r), box: edge(box), bar: edge(bar), plot: edge(plot),
                text: longest.text.textContent, transform: longest.text.getAttribute('transform') };
            }"""
    }
}
