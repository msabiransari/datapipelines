package co.datapipelines.browser

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #860 A.5 — every bar proof of the `chart` kind v1, in a real browser on the capability preview (the page an agent
 * reviews), under the product's live CSP (the suite's collector, asserted in `closePage`). Each fixture of
 * [ChartBrowserFixtures] is created through the REST route as a DRAFT with its one test case, a session is started, and
 * the preview is opened in a cookie-less context whose clock is `America/New_York` — so a formatter that read the local
 * zone would print a UTC-midnight DATE as the previous day.
 *
 * Since #987 (js-rendered F3) the page draws with the product's own chart engine. Everything asserted is read off what
 * the engine drew on the page — the `svg.dp-chart` it wrote (marks by `g.dp-mark[data-series]`, ticks `text.dp-tick`,
 * value labels `text.dp-guide`, the title `text.dp-title`, the legend `g.dp-legend`) — or off the data index of that
 * same draw (`index()` of the occurrence's chart handle, record §4.3): the index carries each bar's tooltip lines, which
 * no element shows until the interaction layer lands (lane 990; the one parked case below).
 *
 * The record (2026-11-06 §4.4, R11) lets `<` and `>` into a title and requires them escaped at render: the markup
 * fixture and the `markup-title` example are rendered as TEXT, nothing executes and no element is injected.
 */
class BarChartBrowserTest : VisualizationBrowserSuite() {
    @Test
    fun `every bar fixture draws its marks, band ticks, tooltip lines and value labels - read off the page in New York`() {
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
                titled.evaluate("sel => document.querySelector(sel + ' svg.dp-chart text.dp-title').textContent", CASE) shouldBe
                    MARKUP_TITLE
                ((titled.evaluate(INJECTED, CASE) as Map<*, *>)["bold"] as Number).toInt() shouldBe 0
            }
            shoot(titled, "markup-title")
            // The control: the same title written RAW into the drawn title (what a writer that parsed its text would do) becomes
            // a bold element, and the probe below sees it — so its zero over the product's chart is a measurement.
            titled.evaluate(
                "([sel, t]) => { document.querySelector(sel + ' svg.dp-chart text.dp-title').innerHTML = t; return true; }",
                listOf(CASE, MARKUP_TITLE),
            )
            withClue("the control: a raw title write is found by the probe") {
                ((titled.evaluate(INJECTED, CASE) as Map<*, *>)["bold"] as Number).toInt() shouldBeGreaterThan 0
            }
            titled.close()

            val markup = ChartBrowserFixtures.fixture("bar-markup")
            val preview = open(agent, "$root/charts/markup", markup)
            assertDrawn(preview, markup)
            val legend =
                preview.evaluate(
                    "sel => Array.from(document.querySelectorAll(sel + ' svg.dp-chart g.dp-legend text')).map(t => t.textContent).join('\\n')",
                    CASE,
                ) as String
            withClue("the series names are text in the legend") { legend shouldContain "<b>bold</b>" }
            val read = preview.evaluate(INJECTED, CASE) as Map<*, *>
            withClue("no markup became an element and nothing ran: $read") {
                read["images"] shouldBe 0
                read["bold"] shouldBe 0
                read["foreign"] shouldBe 0
                read["xss"] shouldBe "undefined"
            }
        } finally {
            agent.close()
        }
    }

    /**
     * Q-L4 with the two `quirks.md:64` fixtures at the corpus's 900 px render and a 480 px phone column: the longest bar's
     * value label is inside the chart box. The control of the library era (the reference converter's layout planted
     * through the old library's own relayout call) left with it; the control is now the probe's own: the same label moved past the box's right
     * edge must read as outside, so the product's "inside" is a measurement.
     */
    @Test
    fun `Q-L4 - the longest bar's value label stays inside the chart box, and a label moved past the box reads outside`() {
        startTrace()
        val root = ready("vbarql4")
        for (width in listOf(QL4_WIDE, QL4_NARROW)) {
            val agent = newBrowserContext(Browser.NewContextOptions().setTimezoneId(ZONE).setViewportSize(width, QL4_HEIGHT))
            try {
                for (name in listOf("bar-horizontal", "bar-long-labels")) {
                    val preview = open(agent, "$root/charts/${name.replace('-', '_')}_$width", ChartBrowserFixtures.fixture(name))
                    assertLabelInside(preview, name, width)
                    preview.close()
                }
            } finally {
                agent.close()
            }
        }
    }

    /** The product's longest value label is inside the chart box at [width]; the same label moved past the box is not. */
    private fun assertLabelInside(
        preview: Page,
        name: String,
        width: Int,
    ) {
        val product = preview.evaluate(LONGEST_LABEL, CASE) as Map<*, *>
        println("Q-L4 $name at $width product: $product")
        withClue("$name at $width px: the product's longest value label is inside the chart box: $product") {
            product["inside"] shouldBe true
        }
        Files.createDirectories(SHOTS)
        Files.write(SHOTS.resolve("ql4-$name-$width-product.png"), preview.locator(CASE).screenshot())
        preview.evaluate(
            "sel => { const t = document.querySelector(sel + ' svg.dp-chart text.dp-guide[data-ql4-longest]');" +
                " t.setAttribute('x', String(t.ownerSVGElement.viewBox.baseVal.width)); t.setAttribute('text-anchor', 'start'); return true; }",
            CASE,
        )
        val control = preview.evaluate(LONGEST_LABEL, CASE) as Map<*, *>
        println("Q-L4 $name at $width control: $control")
        withClue("$name at $width px: the label moved past the box reads outside (the control): $control") {
            control["inside"] shouldBe false
        }
    }

    /**
     * #860b R1 — an aggregate float64 cannot carry (1e308 + 1e308) draws as a GAP in a real browser: three bars drawn
     * where four rows exist, the band ticks intact, and the value axis's ticks all finite. Read off the engine's drawing,
     * like every case above.
     */
    @Test
    fun `an aggregate past float64 draws as a gap - three bars, finite value-axis ticks`() {
        startTrace()
        val root = ready("vbaroverflow")
        val agent = newYorkContext()
        try {
            val fixture = overflowFixture()
            val preview = open(agent, "$root/charts/overflow", fixture)
            try {
                val read = assertDrawn(preview, fixture)
                val ticks = read["values"] as List<*>
                withClue("the value axis draws ticks") { ticks.shouldNotBeEmpty() }
                withClue("the value axis's ticks are all finite: $ticks") {
                    ticks.forEach { t -> (t as String) shouldNotContain "Infinity" }
                    ticks.forEach { t -> (t as String) shouldNotContain "NaN" }
                }
            } finally {
                preview.close()
            }
        } finally {
            agent.close()
        }
    }

    /**
     * Two shares of 1e308 each: the absolute total overflows, the shares do not. The engine draws no value label on a
     * normalized bar (look contract §4.3: a share is 100 % and carries no label), so the 50 % each share must show is read
     * off its tooltip lines, and the two bars are equal and nonzero on the page.
     */
    @Test
    fun `two finite bars whose absolute total overflows draw equal nonzero shares`() {
        startTrace()
        val root = ready("vbarnormalizedoverflow")
        val source = ChartBrowserFixtures.fixture("bar-normalized")
        val rows =
            JsonParser
                .parseString("""[{"region":"A","channel":"one","amount":1e308},{"region":"A","channel":"two","amount":1e308}]""")
                .asJsonArray
                .map { it.asJsonObject }
        val expected =
            JsonParser
                .parseString(
                    """{"bars":2,"categories":["A"],"vertical":true,
                       "hover":[{"si":0,"ci":0,"contains":["50%"]},{"si":1,"ci":0,"contains":["50%"]}],"label_texts":[]}""",
                ).asJsonObject
        val fixture = ChartBrowserFixtures.Fixture("normalized-overflow", source.spec, rows, expected)
        val agent = newYorkContext()
        try {
            val preview = open(agent, "$root/charts/normalized_overflow", fixture)
            try {
                val lengths = (assertDrawn(preview, fixture)["lengths"] as List<*>).map { (it as Number).toDouble() }
                val gap =
                    (preview.evaluate("sel => getComputedStyle(document.querySelector(sel)).getPropertyValue('--chart-gap')", CASE) as String)
                        .trim()
                        .removeSuffix("px")
                        .toDouble()
                withClue("the two shares are drawn equal and nonzero, the upper one less the stack's gap ($gap px): $lengths") {
                    lengths.size shouldBe 2
                    lengths[1] shouldBeGreaterThan 0.0
                    (lengths[0] - lengths[1]) shouldBe (gap plusOrMinus SHARE_TOLERANCE)
                }
            } finally {
                preview.close()
            }
        } finally {
            agent.close()
        }
    }

    /**
     * Treatment 4 of #987 D7: the library showed a bar's hover text when the pointer moved over it; the engine's drawing
     * answers no pointer until the interaction layer lands. The tooltip lines themselves are asserted today off the draw's
     * data index ([assertDrawn]); what is parked is the page showing them.
     */
    @Test
    @Disabled("js-rendered F6 #990: moving the pointer over a bar shows its tooltip lines on the page")
    fun `moving the pointer over a bar shows its tooltip lines on the page`() {
        startTrace()
        val root = ready("vbarpointer")
        val agent = newYorkContext()
        try {
            val fixture = ChartBrowserFixtures.fixture("bar-markup")
            val preview = open(agent, "$root/charts/pointer", fixture)
            val bar = preview.locator("$CASE svg.dp-chart g.dp-mark[data-series] > .dp-mark").first()
            bar.scrollIntoViewIfNeeded()
            val box = checkNotNull(bar.boundingBox()) { "the first series has no drawn bar" }
            preview.mouse().move(box.x + box.width / 2, box.y + box.height / 2)
            fail("the interaction layer (lane 990) shows a bar's tooltip lines on the page; this case is written when it lands")
        } finally {
            agent.close()
        }
    }

    private fun overflowFixture(): ChartBrowserFixtures.Fixture {
        val rawSpec =
            """
            {
              "display_name": "Overflow",
              "renderer": {"kind": "chart", "version": "1"},
              "inputs": {
                "sales": {
                  "columns": [
                    {"name": "region", "type": "STRING", "nullable": false},
                    {"name": "channel", "type": "STRING", "nullable": false},
                    {"name": "amount", "type": "DECIMAL", "nullable": false}
                  ]
                }
              },
              "config": {
                "mark": {"type": "bar"},
                "encoding": {
                  "x": {"field": "region", "type": "nominal", "sort": "ascending", "title": "Region"},
                  "y": {"field": "amount", "type": "quantitative", "aggregate": "sum", "stack": "zero"},
                  "color": {"field": "channel", "type": "nominal", "sort": "ascending", "title": "Channel"}
                },
                "title": {"text": "Overflow"}
              }
            }
            """.trimIndent()
        val spec = JsonParser.parseString(rawSpec).asJsonObject
        val gson = GsonBuilder().serializeNulls().create()
        val rows =
            listOf(
                mapOf("region" to "North", "channel" to "Online", "amount" to 1e308),
                mapOf("region" to "North", "channel" to "Online", "amount" to 1e308),
                mapOf("region" to "North", "channel" to "Retail", "amount" to 500),
                mapOf("region" to "South", "channel" to "Online", "amount" to 400),
                mapOf("region" to "South", "channel" to "Retail", "amount" to 300),
            ).map { gson.toJsonTree(it).asJsonObject }
        val expect =
            JsonObject().apply {
                addProperty("bars", 3)
                add(
                    "categories",
                    JsonArray().apply {
                        add("North")
                        add("South")
                    },
                )
                addProperty("vertical", true)
                add("label_texts", JsonArray())
                add("hover", JsonArray())
            }
        return ChartBrowserFixtures.Fixture("bar-aggregate-overflow", spec, rows, expect)
    }

    /**
     * Draws [fixture]'s expectations off the engine's drawing: bars, the band ticks in reading order, the value labels,
     * the tooltip lines; answers the read for a caller's own checks.
     */
    private fun assertDrawn(
        preview: Page,
        fixture: ChartBrowserFixtures.Fixture,
    ): Map<*, *> {
        val read = preview.evaluate(DRAWN, mapOf("sel" to CASE, "vertical" to fixture.vertical)) as Map<*, *>
        println("${fixture.name}: $read")
        withClue("${fixture.name}: the drawn bars $read") { (read["bars"] as Number).toInt() shouldBe fixture.bars }
        if (fixture.bars == 0) return read
        withClue("${fixture.name}: the band axis's ticks show the categories, in order: $read") {
            bandTicksMatch(read["ticks"] as List<*>, fixture) shouldBe true
        }
        val labels = read["labels"] as List<*>
        fixture.expect.getAsJsonArray("label_texts").map { it.asJsonObject }.forEach { e ->
            val (si, ci) = e.get("si").asInt to e.get("ci").asInt
            withClue("${fixture.name}: the value label of series $si, category $ci: $labels") {
                (labels[si] as Map<*, *>)[ci.toString()] shouldBe e.get("text").asString
            }
        }
        fixture.expect.getAsJsonArray("hover").map { it.asJsonObject }.forEach { e ->
            val lines = tooltipLines(preview, e.get("si").asInt, e.get("ci").asInt)
            withClue("${fixture.name}: series ${e.get("si")}, category ${e.get("ci")} has tooltip lines") { lines shouldNotBe null }
            val shown = lines.orEmpty().joinToString("\n")
            e.getAsJsonArray("contains").map { it.asString }.forEach { part ->
                withClue("${fixture.name}: the tooltip lines of series ${e.get("si")}, category ${e.get("ci")}: $lines") {
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
        return read
    }

    /**
     * The band ticks show the categories in reading order: every category when the engine drew them all, else (the axis
     * thinned its labels, contract §4.6) a strictly increasing run of them. A tick the engine cut to its room is the
     * category's own leading text and an ellipsis.
     */
    private fun bandTicksMatch(
        ticks: List<*>,
        fixture: ChartBrowserFixtures.Fixture,
    ): Boolean {
        if (ticks.isEmpty()) return false
        val full = fixture.expect.getAsJsonArray("categories_full")?.map { it.asString } ?: fixture.categories
        temporalFormat(fixture)?.let { return ticks == g9Labels(full, it) }
        val shows = { tick: String, category: String -> tick == category || (tick.endsWith("…") && category.startsWith(tick.dropLast(1))) }
        var at = -1
        for (tick in ticks.map { it as String }) {
            at = (at + 1 until full.size).firstOrNull { shows(tick, full[it]) } ?: return false
        }
        return true
    }

    /** The band field's temporal format (`month`, `date`, `datetime`), or null for a band that is not temporal. */
    private fun temporalFormat(fixture: ChartBrowserFixtures.Fixture): String? {
        val encoding = fixture.spec.getAsJsonObject("config").getAsJsonObject("encoding")
        val band = encoding.getAsJsonObject(if (fixture.vertical) "x" else "y")
        return if (band.get("type")?.asString == "temporal") band.get("format").asString else null
    }

    /**
     * #987 D8 (formatted text): the band labels G9 writes for the corpus's UTC category texts, every one drawn — the
     * library printed `2024-01`; the engine prints `Jan` with the year under the first label and wherever it changes
     * (month), `Mar 1` with the year (date), `02:30` with the date (datetime). Computed here from the categories' own
     * text, never from the engine, so a clock that shifted a UTC-midnight date to the previous evening is red.
     */
    private fun g9Labels(
        full: List<String>,
        format: String,
    ): List<String> {
        var previous: String? = null
        return full.map { category ->
            val year = category.substring(0, YEAR_END)
            val month = MONTHS[category.substring(YEAR_END + 1, MONTH_END).toInt() - 1]
            val (text, under) =
                when (format) {
                    "month" -> month to year
                    "date" -> "$month ${category.substring(MONTH_END + 1, DAY_END).toInt()}" to year
                    else -> category.substring(DAY_END + 1, TIME_END) to "$month ${category.substring(MONTH_END + 1, DAY_END).toInt()}, $year"
                }
            val label = if (under != previous) "$text\n$under" else text
            previous = under
            label
        }
    }

    /** The tooltip lines of series [series] at category [category], off the data index of the occurrence's last draw. */
    @Suppress("UNCHECKED_CAST")
    private fun tooltipLines(
        preview: Page,
        series: Int,
        category: Int,
    ): List<String>? = preview.evaluate(TOOLTIP, mapOf("si" to series, "ci" to category)) as List<String>?

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
        const val SHARE_TOLERANCE = 0.05
        val SHOTS = Paths.get("build", "reports", "chart-bar")
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        const val YEAR_END = 4
        const val MONTH_END = 7
        const val DAY_END = 10
        const val TIME_END = 16

        /**
         * The data index of the one case's chart: the preview's registry (`window.DatapipelinesPreview`, the same path the
         * #456 probe used) holds the case's instance; its one occurrence's chart handle answers `index()` (record §4.3).
         */
        const val INDEX =
            """const occurrences = window.DatapipelinesPreview.instances[0]._occurrences;
              const ix = occurrences[Object.keys(occurrences)[0]].renderer.index();"""

        /**
         * The drawn bars (a mark with an area; a gap draws nothing), the band axis's tick texts sorted by their position
         * (what a reader sees, left to right or top to bottom; a second line under a label, G9, joins it after a newline), the value axis's tick texts, each series' value labels by
         * category (a label belongs to the bar whose centre along the band axis is nearest it), and each bar's length
         * across the band axis.
         */
        const val DRAWN =
            """args => {
              $INDEX
              const svg = document.querySelector(args.sel + ' svg.dp-chart');
              const num = (el, a) => parseFloat(el.getAttribute(a));
              const p = ix.plot;
              const bars = Array.from(svg.querySelectorAll('g.dp-mark[data-series] > .dp-mark'))
                .filter(m => { const b = m.getBBox(); return b.width > 0 && b.height > 0; });
              const isBand = t => args.vertical ? num(t, 'x') >= p.x : num(t, 'y') <= p.y + p.h;
              const ticks = Array.from(svg.querySelectorAll('text.dp-tick'));
              const band = ticks.filter(isBand).map(t => ({ text: t.textContent, at: num(t, args.vertical ? 'x' : 'y') }))
                .sort((a, b) => a.at - b.at)
                .reduce((out, t) => { const last = out[out.length - 1];
                  if (last && Math.abs(last.at - t.at) < 0.5) last.text += '\n' + t.text; else out.push({ text: t.text, at: t.at });
                  return out; }, []).map(t => t.text);
              const values = ticks.filter(t => !isBand(t)).map(t => t.textContent);
              const labels = ix.series.map(() => ({}));
              svg.querySelectorAll('text.dp-guide').forEach(t => {
                const at = num(t, args.vertical ? 'x' : 'y');
                let best = null;
                ix.series.forEach((e, si) => e.at.forEach((c, k) => {
                  const d = Math.abs(c - at);
                  if (best === null || d < best.d) best = { d: d, si: si, ci: e.datum[k] };
                }));
                if (best !== null) labels[best.si][String(best.ci)] = t.textContent;
              });
              const lengths = bars.map(m => { const b = m.getBBox(); return args.vertical ? b.height : b.width; });
              return { bars: bars.length, ticks: band, values: values, labels: labels, lengths: lengths,
                plot: p, series: ix.series.map(e => e.id) };
            }"""

        /** One bar's tooltip lines off the data index: series by position, category by the datum the index records. */
        const val TOOLTIP =
            """args => {
              $INDEX
              const e = ix.series[args.si];
              const k = e ? e.datum.indexOf(args.ci) : -1;
              return k < 0 ? null : e.tip[k];
            }"""

        /** What markup would leave behind had it been parsed: an image, a bold run, a foreign object, the payload's global. */
        const val INJECTED =
            """sel => {
              const s = document.querySelector(sel);
              return { images: s.querySelectorAll('img, image').length, bold: s.querySelectorAll('svg.dp-chart b, svg.dp-chart tspan').length,
                foreign: s.querySelectorAll('svg.dp-chart foreignObject, svg.dp-chart script').length, xss: typeof window.__xss };
            }"""

        /**
         * The size frame's evidence: every drawn text (value labels, ticks, axis titles, legend entries, the title) outside
         * the chart's box, and every title line whose bottom passes the plot area's top or a value label's top. One pixel
         * of rounding is allowed on each edge.
         */
        const val FRAMED =
            """sel => {
              $INDEX
              const svg = document.querySelector(sel + ' svg.dp-chart');
              const box = svg.getBoundingClientRect();
              const scale = box.height / svg.viewBox.baseVal.height;
              const edge = el => { const b = el.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom].map(Math.round); };
              const inBox = b => b[0] >= Math.floor(box.left) - 1 && b[1] >= Math.floor(box.top) - 1
                && b[2] <= Math.ceil(box.right) + 1 && b[3] <= Math.ceil(box.bottom) + 1;
              const shown = t => { const b = t.getBoundingClientRect(); return !!t.textContent && (b.width > 0 || b.height > 0); };
              const drawn = q => Array.from(svg.querySelectorAll(q)).filter(shown).map(t => ({ text: t.textContent, at: edge(t) }));
              const texts = drawn('text');
              const labels = drawn('text.dp-guide');
              const plotTop = Math.round(box.top + ix.plot.y * scale);
              const floor = Math.min.apply(null, [plotTop].concat(labels.map(l => l.at[1])));
              const titles = drawn('text.dp-title');
              return { outside: texts.filter(t => !inBox(t.at)), overlapping: titles.filter(t => t.at[3] > floor + 1),
                box: [box.left, box.top, box.right, box.bottom].map(Math.round), plotTop: plotTop, titles: titles };
            }"""

        /**
         * The longest bar's value label against the chart's box: both boxes and whether the first is inside. The label is
         * marked (`data-ql4-longest`) so the control can move the same element.
         */
        const val LONGEST_LABEL =
            """sel => {
              const svg = document.querySelector(sel + ' svg.dp-chart');
              const box = svg.getBoundingClientRect();
              const bars = Array.from(svg.querySelectorAll('g.dp-mark[data-series] > .dp-mark')).map(m => m.getBoundingClientRect());
              const mid = r => (r.top + r.bottom) / 2;
              let longest = null;
              svg.querySelectorAll('text.dp-guide').forEach(t => {
                const r = t.getBoundingClientRect();
                let bar = null;
                bars.forEach(b => { if (bar === null || Math.abs(mid(b) - mid(r)) < Math.abs(mid(bar) - mid(r))) bar = b; });
                if (bar !== null && (longest === null || bar.width > longest.bar.width)) longest = { bar: bar, text: t };
              });
              svg.querySelectorAll('[data-ql4-longest]').forEach(t => t.removeAttribute('data-ql4-longest'));
              longest.text.setAttribute('data-ql4-longest', '');
              const r = longest.text.getBoundingClientRect();
              const inside = r.left >= box.left && r.right <= box.right && r.top >= box.top && r.bottom <= box.bottom;
              const edge = rect => [rect.left, rect.top, rect.right, rect.bottom].map(Math.round);
              return { inside: inside, label: edge(r), box: edge(box), bar: edge(longest.bar), text: longest.text.textContent };
            }"""
    }
}
