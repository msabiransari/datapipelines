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
import org.junit.jupiter.api.Test
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
 * same draw (`index()` of the occurrence's chart handle, record §4.3): the index carries each bar's tooltip lines, and
 * since #990 the interaction layer's card shows them under the pointer (the pointer case below).
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
                    "sel => Array.from(document.querySelectorAll(sel + ' svg.dp-chart g.dp-legend text'))" +
                        ".map(t => t.textContent).join('\\n')",
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
     * through the old library's own relayout call) left with it; the control is now the probe's own: the same label
     * moved past the box's right edge must read as outside, so the product's "inside" is a measurement.
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
                " t.setAttribute('x', String(t.ownerSVGElement.viewBox.baseVal.width));" +
                " t.setAttribute('text-anchor', 'start'); return true; }",
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
                    (
                        preview.evaluate(
                            "sel => getComputedStyle(document.querySelector(sel)).getPropertyValue('--chart-gap')",
                            CASE,
                        ) as String
                    ).trim()
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
     * Treatment 4 of #987 D7, un-parked by #990 (D9): the pointer moves onto the bar, and the tooltip card the page then
     * shows (the interaction layer's `g.dp-tooltip`, read from the DOM) carries every expected part of the fixture's
     * `expect.hover`, for all five expectations of the five bar fixtures that carry one. The text half ([assertDrawn],
     * off the draw's data index) is unchanged.
     */
    @Test
    fun `moving the pointer over a bar shows its tooltip lines on the page`() {
        startTrace()
        val root = ready("vbarpointer")
        val agent = newYorkContext()
        var read = 0
        try {
            for (name in ChartBrowserFixtures.NAMES) read += readHovers(agent, root, name)
        } finally {
            agent.close()
        }
        withClue("the five hover expectations were read from the shown card (non-vacuity floor)") { read shouldBe HOVER_EXPECTATIONS }
    }

    /**
     * Brief 1050 A.6: a bar coloured by value (`bar-shade`, not in [ChartBrowserFixtures.NAMES], so no other case sees it).
     * The DOM's marks carry the fills of the engine's primitives of the same draw (captured by [SHADE_DRAW]), in order,
     * and those are the steps the fixture expects; the scale's legend is in the DOM with its seven steps and its two end
     * texts, and nothing in the chart is a button; the card shown under the pointer holds the value's line and the
     * shade's. No console error and no page error on the page; the CSP collector is asserted in `closePage`.
     *
     * Brief 1057 D5.3: hovering each bar in turn, the card's key is painted in that bar's own fill, both read off the
     * page (the bars are on three different steps, so a key in the series' first colour is red on two of them).
     */
    @Test
    fun `a bar coloured by value draws the engine's fills, a scale legend that is no button, and the shade's line in the card`() {
        startTrace()
        val root = ready("vbarshade")
        val agent = newYorkContext()
        val errors = mutableListOf<String>()
        agent.onPage { page ->
            page.onConsoleMessage { m -> if (m.type() == "error") errors += "${page.url()}: console ${m.text()}" }
            page.onPageError { e -> errors += "${page.url()}: page error $e" }
        }
        try {
            val fixture = ChartBrowserFixtures.fixture("bar-shade")
            val preview = open(agent, "$root/charts/bar_shade", fixture)
            try {
                assertDrawn(preview, fixture)
                preview.evaluate(SHADE_DRAW)
                val read = preview.evaluate(SHADE_READ, CASE) as Map<*, *>
                println("bar-shade: $read")
                val fills = fixture.expect.getAsJsonArray("fills").map { it.asString }
                withClue("bar-shade: the captured draw $read") { read["error"] shouldBe null }
                withClue("bar-shade: the DOM's marks are painted in the primitives' colours $read") {
                    (read["colours"] as List<*>).size shouldBe fills.size
                    read["dom"] shouldBe read["colours"]
                }
                withClue("bar-shade: each bar's fill is its shade's step $read") { read["prims"] shouldBe fills }
                withClue("bar-shade: the scale's legend, step 1 to step 7, painted on the page as drawn $read") {
                    read["legend"] shouldBe (1..7).map { "--chart-seq-$it" }
                    read["legendDom"] shouldBe read["legendColours"]
                    (read["legendColours"] as List<*>).toSet().size shouldBe 7
                }
                withClue("bar-shade: the legend's end texts $read") {
                    read["legendTexts"] shouldBe fixture.expect.getAsJsonArray("legend_texts").map { it.asString }
                }
                withClue("bar-shade: nothing in the chart is a button, no group names a legend entry $read") {
                    (read["buttons"] as Number).toInt() shouldBe 0
                    (read["switches"] as Number).toInt() shouldBe 0
                }
                val hovered = fixture.expect.getAsJsonArray("hover").sumOf { hoverAndRead(preview, fixture.name, it.asJsonObject) }
                withClue("bar-shade: the card was read (non-vacuity)") { hovered shouldBe 1 }
                val keys = (0 until fills.size).map { ci -> keyAgainstBar(preview, ci) }
                println("bar-shade: each bar's fill against its card's key: $keys")
                withClue("bar-shade: the bars are on three different fills (non-vacuity) $keys") {
                    keys.map { it.first }.toSet().size shouldBe 3
                }
                withClue("bar-shade: each card's key is painted in its own bar's fill $keys") {
                    keys.forEach { (bar, key) -> key shouldBe bar }
                }
            } finally {
                preview.close()
            }
        } finally {
            agent.close()
        }
        withClue("bar-shade: no console error, no page error") { errors.shouldBeEmpty() }
    }

    /** Moves the pointer onto the shaded bar of category [ci] and returns its fill and its card's one key's fill, off the page. */
    private fun keyAgainstBar(
        preview: Page,
        ci: Int,
    ): Pair<String, String> {
        val at =
            requireNotNull(preview.evaluate(BAR_CENTRE, mapOf("sel" to CASE, "si" to 0, "ci" to ci)) as Map<*, *>?) {
                "bar-shade: category $ci has no drawn bar"
            }
        preview.mouse().move((at["x"] as Number).toDouble(), (at["y"] as Number).toDouble())
        preview.waitForFunction("sel => !!document.querySelector(sel + ' svg.dp-chart g.dp-tooltip')", CASE)
        val read = preview.evaluate(KEY_AND_BAR, mapOf("sel" to CASE, "si" to 0, "ci" to ci)) as Map<*, *>
        withClue("bar-shade: category $ci: one key in the card, its bar on the page $read") {
            (read["keys"] as List<*>).size shouldBe 1
            read["bar"] shouldNotBe null
        }
        preview.mouse().move(0.0, 0.0)
        return read["bar"] as String to (read["keys"] as List<*>).single() as String
    }

    /** Opens fixture [name]'s preview when it carries `expect.hover` entries and reads each one; returns how many were read. */
    private fun readHovers(
        agent: BrowserContext,
        root: String,
        name: String,
    ): Int {
        val fixture = ChartBrowserFixtures.fixture(name)
        val hovers = fixture.expect.getAsJsonArray("hover").map { it.asJsonObject }
        if (hovers.isEmpty()) return 0
        val preview = open(agent, "$root/charts/pointer_${name.replace('-', '_')}", fixture)
        try {
            return hovers.sumOf { e -> hoverAndRead(preview, fixture.name, e) }
        } finally {
            preview.close()
        }
    }

    /** Moves the pointer onto the bar of one `expect.hover` entry and asserts the shown card holds each expected part; returns 1. */
    private fun hoverAndRead(
        preview: Page,
        fixture: String,
        e: JsonObject,
    ): Int {
        val (si, ci) = e.get("si").asInt to e.get("ci").asInt
        val at =
            requireNotNull(preview.evaluate(BAR_CENTRE, mapOf("sel" to CASE, "si" to si, "ci" to ci)) as Map<*, *>?) {
                "$fixture: series $si, category $ci has no drawn bar"
            }
        preview.mouse().move((at["x"] as Number).toDouble(), (at["y"] as Number).toDouble())
        preview.waitForFunction("sel => !!document.querySelector(sel + ' svg.dp-chart g.dp-tooltip')", CASE)
        val shown = preview.evaluate(SHOWN_CARD, CASE) as String
        println("$fixture: series $si, category $ci: the shown card reads $shown")
        e.getAsJsonArray("contains").map { it.asString }.forEach { part ->
            withClue("$fixture: the card shown over series $si, category $ci: $shown") { shown shouldContain part }
        }
        // `card` (optional): runs of the shown card's lines, where the card writes a detail's name and value as two texts.
        e.getAsJsonArray("card")?.map { it.asString }?.forEach { run ->
            withClue("$fixture: the card shown over series $si, category $ci, line by line: $shown") { shown shouldContain run }
        }
        preview.mouse().move(0.0, 0.0)
        return 1
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
            // `tip` (optional): whole lines the engine's tip carries, as the card does not show them (a name and a value).
            (e.getAsJsonArray("contains") + (e.getAsJsonArray("tip") ?: JsonArray())).map { it.asString }.forEach { part ->
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
                    "month" -> {
                        month to year
                    }

                    "date" -> {
                        "$month ${category.substring(MONTH_END + 1, DAY_END).toInt()}" to year
                    }

                    else -> {
                        category.substring(DAY_END + 1, TIME_END) to
                            "$month ${category.substring(MONTH_END + 1, DAY_END).toInt()}, $year"
                    }
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
         * (what a reader sees, left to right or top to bottom; a second line under a label, G9, joins it after a
         * newline), the value axis's tick texts, each series' value labels by
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

        /** The expectations of `expect.hover` over the bar fixtures (5, in 5 of the 14; #987's count). */
        const val HOVER_EXPECTATIONS = 5

        /**
         * The client centre of series [si]'s bar at category [ci]: the data index names its place along the band axis, the
         * drawn bar of that series nearest it is the one (scrolled into view first).
         */
        const val BAR_CENTRE =
            """args => {
              $INDEX
              const svg = document.querySelector(args.sel + ' svg.dp-chart');
              const e = ix.series[args.si];
              if (!e) return null;
              const k = e.datum.indexOf(args.ci);
              if (k < 0) return null;
              const vertical = ix.axis === 'x';
              let best = null;
              svg.querySelectorAll('g.dp-mark[data-series="' + CSS.escape(e.id) + '"] > .dp-mark').forEach(b => {
                const bb = b.getBBox();
                if (bb.width <= 0 || bb.height <= 0) return;
                const d = Math.abs((vertical ? bb.x + bb.width / 2 : bb.y + bb.height / 2) - e.at[k]);
                if (best === null || d < best.d) best = { d: d, b: b };
              });
              if (best === null) return null;
              best.b.scrollIntoView({ block: 'center', inline: 'center' });
              const r = best.b.getBoundingClientRect();
              return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
            }"""

        /**
         * One redraw of the case's chart (`resize`, the next animation frame) with the engine's result of that draw kept in
         * `window.__drawn` (`{error}` when it threw or never came): the pattern of `ChartPageSuite.REDRAW`, whose companion
         * this class does not see.
         */
        const val SHADE_DRAW =
            """() => new Promise(function (resolve) {
              var R = window.JsRendered, original = R.render, last = null, frames = 0;
              R.render = function () {
                try { var out = original.apply(this, arguments); last = out; return out; } catch (e) { last = { error: String(e), primitives: [] }; throw e; }
              };
              var o = window.DatapipelinesPreview.instances[0]._occurrences;
              o[Object.keys(o)[0]].renderer.resize();
              var wait = function () {
                if (last === null && ++frames > 120) last = { error: 'no draw reached the engine in 120 frames', primitives: [] };
                if (last === null) { requestAnimationFrame(wait); return; }
                requestAnimationFrame(function () { R.render = original; window.__drawn = last; resolve(true); });
              };
              requestAnimationFrame(wait);
            })"""

        /**
         * The shaded bar's page against the captured draw. The page writes literal colours (`writeDom` is the plan's
         * standalone form), so each DOM mark's fill is read against its primitive's resolved colour, and the tokens are
         * read off the primitives: the marks' fills, in order; the scale legend's step tokens, its rects' colours on the
         * page and in the draw, and its texts; the elements of role button and the groups naming a legend entry
         * (`data-legend`) in the chart.
         */
        const val SHADE_READ =
            """sel => {
              const svg = document.querySelector(sel + ' svg.dp-chart');
              const fill = el => el.getAttribute('fill');
              const drawn = window.__drawn;
              const legend = svg.querySelector('g.dp-legend');
              const marks = drawn.primitives.filter(p => p.role === 'mark' && p.k !== 'group');
              const steps = drawn.primitives.filter(p => p.role === 'legend' && p.k === 'rect');
              return { error: drawn.error || null,
                dom: Array.from(svg.querySelectorAll('g.dp-mark[data-series] > .dp-mark')).map(fill),
                colours: marks.map(p => p.color),
                prims: marks.map(p => p.fill),
                legend: steps.map(p => p.fill),
                legendDom: legend ? Array.from(legend.querySelectorAll('rect')).map(fill) : null,
                legendColours: steps.map(p => p.color),
                legendTexts: legend ? Array.from(legend.querySelectorAll('text')).map(t => t.textContent) : [],
                buttons: svg.querySelectorAll('[role="button"]').length,
                switches: svg.querySelectorAll('[data-legend]').length };
            }"""

        /**
         * The fill of series [si]'s bar at category [ci] as the page paints it ([BAR_CENTRE]'s choice of the element) and
         * the fills of the shown card's keys (the card's rects after its surface, the first rect).
         */
        const val KEY_AND_BAR =
            """args => {
              $INDEX
              const svg = document.querySelector(args.sel + ' svg.dp-chart');
              const e = ix.series[args.si];
              const k = e ? e.datum.indexOf(args.ci) : -1;
              const vertical = ix.axis === 'x';
              let bar = null, best = null;
              if (k >= 0) svg.querySelectorAll('g.dp-mark[data-series="' + CSS.escape(e.id) + '"] > .dp-mark').forEach(b => {
                const bb = b.getBBox();
                if (bb.width <= 0 || bb.height <= 0) return;
                const d = Math.abs((vertical ? bb.x + bb.width / 2 : bb.y + bb.height / 2) - e.at[k]);
                if (best === null || d < best) { best = d; bar = b; }
              });
              const rects = Array.from(svg.querySelectorAll('g.dp-tooltip rect'));
              return { bar: bar ? bar.getAttribute('fill') : null, keys: rects.slice(1).map(r => r.getAttribute('fill')) };
            }"""

        /** The texts of the tooltip card the page shows, one per line. */
        const val SHOWN_CARD =
            """sel => Array.from(document.querySelectorAll(sel + ' svg.dp-chart g.dp-tooltip text')).map(t => t.textContent).join('\n')"""

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
