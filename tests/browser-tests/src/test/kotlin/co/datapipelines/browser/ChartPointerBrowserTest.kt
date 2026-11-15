package co.datapipelines.browser

import co.datapipelines.browser.InteractionCharts.JOIN
import co.datapipelines.browser.InteractionCharts.QUARTERS
import co.datapipelines.browser.InteractionCharts.bar
import co.datapipelines.browser.InteractionCharts.line
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #990 (js-rendered F6) — what the interaction layer does on the POINTER, on a mounted chart read off the page (brief
 * 990 A.2): A5 on bar, line and area (the card on hover and on keyboard focus, the dimming, the crosshair's dots and its
 * one card, a legend switch refitting the value axis and surviving a refresh with no other series recoloured, the
 * description, dispose leaving no listener and no node); a 1 px move under the card; a tap and a tap elsewhere; LA2 and
 * LA4 for the tokens only the pointer shows; D4a's look-table fields each changing what is drawn (A14, LA5); the
 * reduced card of an 8-series chart; `--chart-text: 32` at 80 × 24; the draw context (G15); hostile text (A8).
 */
class ChartPointerBrowserTest : ChartInteractionSuite() {
    private val bar2 = bar(linkedMapOf("A" to listOf(30, 50, 20), "B" to listOf(60, 90, 40)))
    private val line2 = line(linkedMapOf("A" to listOf(70, 90, 80), "B" to listOf(20, 40, 30)))
    private val stacked2 = bar(linkedMapOf("A" to listOf(30, 50, 20), "B" to listOf(60, 90, 40)), title = "Stack", stacked = true)
    private val area2 = line(linkedMapOf("A" to listOf(70, 90, 80), "B" to listOf(20, 40, 30)), type = "area", title = "Areas")

    @Test
    fun `A5 - bar, line and area - the card on hover and on focus, dimming, crosshair, the legend refit, the description, dispose`() {
        startTrace()
        val (context, page) = harnessPage("a5", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        try {
            for ((kind, chart, tallest) in listOf(Triple("Bar", bar2, "B"), Triple("Line", line2, "A"), Triple("Area", area2, "A"))) {
                mount(page, chart, FULL_W, FULL_H)["ok"] shouldBe true
                val other = if (tallest == "A") "B" else "A"
                val at = datums(page, other)[1]
                a5Card(page, log, kind, chart, other, at)
                a5Legend(page, log, kind, chart, tallest, other)
                hover(page, pointOf(page, other, at))
                withClue("$kind D7: the picture carries listeners while mounted") { listeners(page) shouldBeGreaterThan 0 }
                val gone = page.evaluate("() => window.__h.dispose()") as Map<*, *>
                log += "$kind dispose: $gone"
                withClue("$kind D7: dispose leaves no listener, no card, no ring and no picture: $gone") {
                    gone["listeners"] shouldBe 0
                    gone["tooltips"] shouldBe 0
                    gone["rings"] shouldBe 0
                    gone["inPage"] shouldBe false
                }
            }
        } finally {
            context.close()
            report("a5.txt", log)
        }
    }

    /** A5's description (D6), the card on hover with the dimming and the crosshair (D2), leaving, and the card on focus (D5). */
    private fun a5Card(
        page: Page,
        log: MutableList<String>,
        kind: String,
        chart: Chart,
        other: String,
        at: Int,
    ) {
        val first = read(page)
        withClue("$kind D6: the picture is one image named by the chart's title, its description the summary: $first") {
            first["role"] shouldBe "img"
            first["title"] shouldBe chart.name
            (first["desc"] as String) shouldStartWith "$kind chart; series: A, B;"
        }
        hover(page, pointOf(page, other, at))
        val hovered = read(page)
        log += "$kind hover $other@1: ${hovered["card"]} guides=${hovered["guides"]}"
        withClue("$kind D2: hovering $other's mark shows ONE card with its name and value, the series dimmed but $other: $hovered") {
            hovered["cards"] shouldBe 1
            names(hovered) shouldContain other
            values(hovered) shouldContain chart.valueAt(other, 1).toString()
            hovered["dimmed"] shouldBe true
            activeMarks(hovered) shouldBe listOf(other)
            inside(hovered) shouldBe true
        }
        if (kind != "Bar") {
            withClue("$kind D2: the crosshair, one guide line and a dot on each series at that x, one card: ${hovered["guides"]}") {
                val guides = hovered["guides"] as Map<*, *>
                guides["lines"] shouldBe 1
                (guides["dots"] as List<*>).size shouldBe 2
            }
        }
        page.mouse().move(1.0, 1.0)
        page.evaluate(FRAME)
        val left = read(page)
        withClue("$kind: leaving the picture closes the card and the dimming: $left") {
            left["cards"] shouldBe 0
            left["dimmed"] shouldBe false
        }
        tabIn(page)
        press(page, "ArrowRight")
        val focused = read(page)
        log += "$kind focus: ${focused["card"]} ring=${focused["ring"]}"
        withClue("$kind D5: on keyboard focus the target's card and the ring: $focused") {
            focused["focused"] shouldBe true
            focused["cards"] shouldBe 1
            focused["ring"] shouldNotBe null
            names(focused).isNotEmpty() shouldBe true
        }
        press(page, "Escape")
    }

    /** A5's legend switch (D4): the tallest series hidden refits the axis, recolours nothing (I7), and survives a refresh. */
    private fun a5Legend(
        page: Page,
        log: MutableList<String>,
        kind: String,
        chart: Chart,
        tallest: String,
        other: String,
    ) {
        val before = read(page)
        click(page, entry(before, tallest))
        val after = read(page)
        log += "$kind hide $tallest: values ${before["values"]} -> ${after["values"]}"
        withClue("$kind D4: hiding the tallest series $tallest refits the value axis, recolours nothing (I7): $after") {
            after["values"] shouldNotBe before["values"]
            after["hidden"] shouldBe listOf(tallest)
            entry(after, tallest)["off"] shouldBe true
            drawnOf(after, tallest) shouldBe 0
            paint(after, other) shouldBe paint(before, other)
            after["same"] shouldBe true
        }
        refresh(page, chart.columns)["ok"] shouldBe true
        val refreshed = read(page)
        withClue("$kind D4: a data refresh keeps the list, the entry struck and the refit axis: $refreshed") {
            refreshed["hidden"] shouldBe listOf(tallest)
            entry(refreshed, tallest)["off"] shouldBe true
            refreshed["values"] shouldBe after["values"]
        }
    }

    @Test
    fun `D2 - a 1 px move with the card over the pointer keeps the mark and the card, a tap shows the card, a tap elsewhere closes it`() {
        startTrace()
        val root = ready("move")
        val (context, page) = openHarness(root, "move", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        try {
            mount(page, bar2, MOVE_W, MOVE_H)["ok"] shouldBe true
            var over = 0
            for (id in listOf("A", "B")) {
                for (at in datums(page, id)) over += moveOnePixel(page, log, id, at)
            }
            withClue("the precondition held at least once: the card over the pointer at $MOVE_W x $MOVE_H") {
                over shouldBeGreaterThanOrEqual 1
            }
        } finally {
            context.close()
        }
        val (touchContext, touchPage) = openHarness(root, "touch", VIEW_W, VIEW_H, touch = true)
        try {
            mount(touchPage, bar2, FULL_W, FULL_H)["ok"] shouldBe true
            val (x, y) = pointOf(touchPage, "B", datums(touchPage, "B")[1])
            touchPage.touchscreen().tap(x, y + TAP_INTO_BAR)
            touchPage.evaluate(FRAME)
            val tapped = read(touchPage)
            log += "tap: ${tapped["card"]}"
            withClue("D7: a tap on B's bar shows its card (pointer events: touch works): $tapped") {
                tapped["cards"] shouldBe 1
                names(tapped) shouldContain "B"
            }
            // Outside the plot (the picture's corner): a tap there is over no mark and no entry.
            val box = tapped["box"] as Map<*, *>
            touchPage.touchscreen().tap(n(box, "x") + EDGE, n(box, "y") + EDGE)
            touchPage.evaluate(FRAME)
            withClue("D7: a tap elsewhere in the picture closes the card") { read(touchPage)["cards"] shouldBe 0 }
        } finally {
            touchContext.close()
            report("move-touch.txt", log)
        }
    }

    /**
     * D2 at one mark: hovers it; when the card then lies over the pointer (the precondition), a further 1 px move must
     * keep the hovered mark and the card. Returns 1 when the precondition held (and was asserted), 0 when it did not.
     */
    private fun moveOnePixel(
        page: Page,
        log: MutableList<String>,
        id: String,
        at: Int,
    ): Int {
        val (x, y) = pointOf(page, id, at)
        hover(page, x to y)
        val r = read(page)
        val box = (r["card"] as Map<*, *>?)?.get("box") as Map<*, *>?
        if (box == null || !within(box, x, y)) return 0
        hover(page, (x + 1) to y)
        val moved = read(page)
        log += "$id@$at at ($x, $y): ${r["card"]} -> ${moved["card"]}"
        withClue("D2: the card is over the pointer at $id's mark $at; a 1 px move keeps the mark and the card: $moved") {
            activeMarks(moved) shouldBe activeMarks(r)
            (moved["card"] as Map<*, *>?)?.get("texts") shouldBe (r["card"] as Map<*, *>)["texts"]
            moved["cards"] shouldBe 1
        }
        return 1
    }

    @Test
    fun `LA2 and LA4 - the dimming, the ease, reduced motion, the five tip tokens and the card's filter are what the page sets`() {
        startTrace()
        val (context, page) = harnessPage("la2", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        try {
            mount(page, bar2, FULL_W, FULL_H)["ok"] shouldBe true
            val at = pointOf(page, "B", datums(page, "B")[1])
            hover(page, at)
            la2Motion(page, log)
            la4Card(page, log, at)
        } finally {
            context.close()
            report("la2.txt", log)
        }
    }

    /** LA2 for --chart-dim and --chart-ease (defaults, then a second value set in the page), and D8 under reduced motion. */
    private fun la2Motion(
        page: Page,
        log: MutableList<String>,
    ) {
        settled(page)
        val dim = read(page)
        log += "default: A opacity ${paintOf(dim, "A")["opacity"]} transition ${paintOf(dim, "A")["transition"]}"
        withClue("LA2 defaults: the other series at --chart-dim 0.25, easing over --chart-ease 120 ms: $dim") {
            paintOf(dim, "A")["opacity"] shouldBe "0.25"
            paintOf(dim, "A")["transition"] shouldBe "0.12s"
        }
        token(page, "--chart-dim", "0.5")
        token(page, "--chart-ease", "300")
        settled(page)
        val second = read(page)
        log += "second: A opacity ${paintOf(second, "A")["opacity"]} transition ${paintOf(second, "A")["transition"]}"
        withClue("LA2: a second --chart-dim and --chart-ease are what the browser reads back: $second") {
            paintOf(second, "A")["opacity"] shouldBe "0.5"
            paintOf(second, "A")["transition"] shouldBe "0.3s"
        }
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        val still = read(page)
        withClue("D8: under reduced motion there is no transition, the dimming stays: $still") {
            paintOf(still, "A")["transition"] shouldBe "0s"
            still["dimmed"] shouldBe true
        }
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.NO_PREFERENCE))
    }

    /** LA2 for the five tip tokens as drawn, and LA4's clause on the card group's filter (default, a second value, red). */
    private fun la4Card(
        page: Page,
        log: MutableList<String>,
        at: Pair<Double, Double>,
    ) {
        val plain = read(page)
        withClue("LA4: with nothing set the card group's filter is the page's declared --chart-tip-shadow: ${filterOf(plain)}") {
            filterOf(plain) shouldBe probeFilter(page, "var(--chart-tip-shadow)")
            filterOf(plain) shouldNotBe "none"
        }
        for ((name, value) in TIP_TOKENS) token(page, name, value)
        val ack = refresh(page, bar2.columns)
        hover(page, at.first + 1 to at.second)
        val tipped = read(page)
        val expected = page.evaluate(EXPECTED_TIP) as Map<*, *>
        log += "tip tokens: ${tipped["card"]} expected $expected ack $ack"
        withClue("LA2: the tip surface, ink, border and radius set in the page are the card as drawn: $tipped; expected $expected") {
            ((ack["ack"] as Map<*, *>)["warnings"] as List<*>).shouldBeEmpty()
            val card = tipped["card"] as Map<*, *>
            card["fill"] shouldBe expected["surface"]
            card["stroke"] shouldBe expected["border"]
            card["rx"] shouldBe TIP_RADIUS
            val heading = cardLines(tipped).first()["text"]
            cardLines(tipped).filter { it["anchor"] == "start" && it["text"] != heading }.forEach { it["fill"] shouldBe expected["ink"] }
        }
        token(page, "--chart-tip-shadow", SECOND_SHADOW)
        val shadowed = read(page)
        withClue("LA2: a second --chart-tip-shadow is the card group's computed filter: ${filterOf(shadowed)}") {
            filterOf(shadowed) shouldBe probeFilter(page, SECOND_SHADOW)
            filterOf(shadowed) shouldNotBe filterOf(plain)
        }
        token(page, "--chart-tip-shadow", "red")
        val refused = refresh(page, bar2.columns)
        hover(page, at)
        val red = read(page)
        log += "shadow: plain ${filterOf(plain)} second ${filterOf(shadowed)} red ${filterOf(red)}"
        withClue("LA4: --chart-tip-shadow: red gives no shadow and adds no warning: $red $refused") {
            filterOf(red) shouldBe "none"
            ((refused["ack"] as Map<*, *>)["warnings"] as List<*>).shouldBeEmpty()
        }
    }

    @Test
    fun `D4a - each interaction field of the look table changes the card, the dot or the ring it measures (A14, LA5)`() {
        startTrace()
        val root = ready("look")
        val log = mutableListOf<String>()
        val base = measuresIn(root, "default", emptyMap())
        log += "default: $base"
        var changed = 0
        for ((field, value) in FIELD_VALUES) {
            plantCopies =
                mapOf(
                    TABLES to
                        { text: String -> once(text, "$field: f(${plain(FIELD_DEFAULTS.getValue(field))},", "$field: f(${plain(value)},") },
                )
            val got = measuresIn(root, field.lowercase(), emptyMap())
            plantCopies = emptyMap()
            log += "$field=$value: $got"
            withClue("D4a $field: a second value changes what it measures; default $base, with $value $got") {
                when (field) {
                    "tipDistance" -> {
                        got.d("distance") shouldBe (value plusOrMinus TOLERANCE)
                        base.d("distance") shouldBe (FIELD_DEFAULTS.getValue(field) plusOrMinus TOLERANCE)
                    }

                    "tipLine" -> {
                        got.d("h") - base.d("h") shouldBe ((value - 1.3) * TEXT * base.d("lines") plusOrMinus TOLERANCE)
                    }

                    "tipPad" -> {
                        got.d("w") - base.d("w") shouldBe (2 * (value - 0.33) * TEXT plusOrMinus TOLERANCE)
                        got.d("h") - base.d("h") shouldBe (2 * (value - 0.33) * TEXT plusOrMinus TOLERANCE)
                    }

                    "tipKey", "tipGap" -> {
                        (got.d("w") > base.d("w") + 1) shouldBe true
                    }

                    "tipMax" -> {
                        base["long"] shouldBe LONG
                        (got["long"] as String) shouldEndWith "…"
                    }

                    "tipValueWeight" -> {
                        got.d("valueWeight") shouldBe value
                        base.d("valueWeight") shouldBe 600.0
                    }

                    "crosshairDot" -> {
                        got.d("dot") - base.d("dot") shouldBe ((value - 0.5) plusOrMinus TOLERANCE)
                    }

                    "focusGap" -> {
                        got.d("ringW") - base.d("ringW") shouldBe (2 * (value - 0.25) * TEXT plusOrMinus TOLERANCE)
                    }

                    else -> {
                        error("no check for $field")
                    }
                }
            }
            changed++
        }
        report("look-fields.txt", log + "fields changed=$changed")
        withClue("every interaction field of the look table checked (count)") { changed shouldBe FIELD_VALUES.size }
    }

    @Test
    fun `D2a - 8 series with six tooltip fields show the heading, the hovered row first and +n more, text 32 at 80 x 24 shows no card`() {
        startTrace()
        val (context, page) = harnessPage("more", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        try {
            moreRows(page, log)
            page.mouse().move(1.0, 1.0)
            cutName(page, log)
            page.mouse().move(1.0, 1.0)
            val big = mount(page, line2, SMALL_W, SMALL_H, tokens = mapOf("--chart-text" to "32"))
            tabIn(page)
            press(page, "ArrowRight")
            val tiny = read(page)
            log += "text 32 at 80x24: $big ${tiny["card"]} ring=${tiny["ring"]}"
            withClue("D2a: --chart-text 32 at 80 x 24 has no card, no error and no warning, the ring is drawn: $tiny $big") {
                big["ok"] shouldBe true
                ((big["ack"] as Map<*, *>)["warnings"] as List<*>).shouldBeEmpty()
                tiny["cards"] shouldBe 0
                tiny["ring"] shouldNotBe null
                tiny["focused"] shouldBe true
            }
        } finally {
            context.close()
            report("more.txt", log)
        }
    }

    /** D2a: eight series with six tooltip fields at a height whose k (computed here) is below the full card's lines. */
    private fun moreRows(
        page: Page,
        log: MutableList<String>,
    ) {
        val ids = (1..EIGHT).map { "S$it" }
        val fields = (1..SIX).associate { "f$it" to listOf("a$it") }
        val values = LinkedHashMap<String, List<Int?>>()
        ids.forEachIndexed { i, id -> values[id] = listOf(10 * i, 10 * i + 5, 10 * i + 2) }
        mount(page, line(values, title = "Eight", tooltip = fields), FULL_W, MORE_H)["ok"] shouldBe true
        val hoveredId = "S5"
        hover(page, pointOf(page, hoveredId, datums(page, hoveredId)[1]))
        val r = read(page)
        val m = page.evaluate(LOOK) as Map<*, *>
        val h = m.d("tipLine") * TEXT
        val p = m.d("tipPad") * TEXT
        val k = Math.floor((MORE_H - 2 * p) / h).toInt()
        val full = 1 + EIGHT + SIX
        val n = full - (k - 1)
        log += "k=$k full=$full n=$n card=${r["card"]}"
        withClue("D2a: k $k below the full card's $full lines: the heading, $hoveredId's row first, then +$n more, inside: $r") {
            (k < full) shouldBe true
            val texts = (r["card"] as Map<*, *>)["texts"] as List<*>
            texts.first() shouldBe "2024-02"
            names(r).drop(1).first() shouldBe hoveredId
            texts.last() shouldBe "+$n more"
            inside(r) shouldBe true
        }
    }

    /**
     * D2a: a name wider than the content area w but not than the box's greatest width W (= the frame's width here) is
     * cut to w, and the card with its texts stays inside the picture (G4: the content width is W − 2p).
     */
    private fun cutName(
        page: Page,
        log: MutableList<String>,
    ) {
        val chart = line(linkedMapOf(LONG to listOf(20, 40, 30), "B" to listOf(10, 15, 12)), title = "Cut")
        mount(page, chart, CUT_W, CUT_H)["ok"] shouldBe true
        tabIn(page)
        press(page, "ArrowRight")
        val cut = read(page)
        log += "cut name at ${CUT_W}x$CUT_H: ${cut["card"]}"
        withClue("D2a: at $CUT_W x $CUT_H the long name is cut and the card with its texts lies inside the picture: $cut") {
            names(cut).first { it.startsWith(LONG.take(LONG_PREFIX)) } shouldEndWith "…"
            inside(cut) shouldBe true
        }
    }

    @Test
    fun `G15 - an update with another tip surface and a doubled measure is what the next card is drawn with, on the same picture`() {
        startTrace()
        val (context, page) = harnessPage("g15", VIEW_W, VIEW_H)
        try {
            val r = page.evaluate(DRAW_CONTEXT, mapOf("long" to LONG)) as Map<*, *>
            report("g15.txt", listOf(r.toString()))
            withClue("G15: the second card has the updated surface and the name cut by the doubled measure, same node and listeners: $r") {
                r["firstName"] shouldBe LONG
                r["firstFill"] shouldBe r["defaultSurface"]
                r["secondFill"] shouldBe r["newSurface"]
                (r["secondName"] == LONG) shouldBe false
                r["same"] shouldBe true
                r["listenersAfter"] shouldBe r["listenersBefore"]
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `r2 D2 - by pointer a stacked bar's card heads with its category and total, a second join moves it, hostile stays text`() {
        startTrace()
        val root = ready("total")
        val log = mutableListOf<String>()
        try {
            val (context, page) = openHarness(root, "total", VIEW_W, VIEW_H)
            try {
                stackHeadings(page, log)
                hostileStack(page, log)
            } finally {
                context.close()
            }
            plantCopies = mapOf(TABLES to { text: String -> once(text, "tipTotalJoin: f(\"$JOIN\",", "tipTotalJoin: f(\"$SECOND_JOIN\",") })
            val (second, page2) = openHarness(root, "total-join", VIEW_W, VIEW_H)
            plantCopies = emptyMap()
            try {
                mount(page2, stacked2, FULL_W, FULL_H)["ok"] shouldBe true
                hover(page2, pointOf(page2, "B", datums(page2, "B")[0]))
                val r = read(page2)
                log += "second join: ${r["card"]}"
                withClue("A14 (tipTotalJoin): a second value of the join is the text between the category and the total: $r") {
                    cardLines(r).first()["text"] shouldBe "Q1${SECOND_JOIN}90"
                }
            } finally {
                second.close()
            }
        } finally {
            report("stack-total.txt", log)
        }
    }

    /** Every segment of a 2-series stack by pointer: the heading is the category, the join and the stack's sum (the test's). */
    private fun stackHeadings(
        page: Page,
        log: MutableList<String>,
    ) {
        mount(page, stacked2, FULL_W, FULL_H)["ok"] shouldBe true
        withClue("the page's look table holds the join") { page.evaluate("() => window.JsRendered.tables.look('tipTotalJoin')") shouldBe JOIN }
        var read = 0
        for (id in listOf("A", "B")) {
            datums(page, id).forEachIndexed { c, datum ->
                hover(page, pointOf(page, id, datum))
                val r = read(page)
                val total = stacked2.valueAt("A", c) + stacked2.valueAt("B", c)
                log += "$id@${QUARTERS[c]}: ${(r["card"] as Map<*, *>?)?.get("texts")}"
                withClue("r2 D2: hovering $id at ${QUARTERS[c]}: the heading is the category, the join and the stack's total $total: $r") {
                    r["cards"] shouldBe 1
                    cardLines(r).first()["text"] shouldBe "${QUARTERS[c]}$JOIN$total"
                    boldName(r) shouldBe id
                }
                read++
            }
        }
        withClue("stacked segments read (floor 6)") { read shouldBe 6 }
    }

    /** The hostile fixture stacked: each heading is its category's text, the join and the total, as text; no script. */
    private fun hostileStack(
        page: Page,
        log: MutableList<String>,
    ) {
        val plainChart = engineChart("bar/hostile")
        val chart = plainChart.copy(config = once(plainChart.config, "\"aggregate\":\"sum\"", "\"aggregate\":\"sum\",\"stack\":\"zero\""))
        mount(page, chart, FULL_W, FULL_H)["ok"] shouldBe true
        val k = chart.columns.getValue("k")
        val v = chart.columns.getValue("v")
        var shown = 0
        k.forEachIndexed { i, category ->
            val at = page.evaluate("h => window.__h.datum(h)", category) as Map<*, *>
            hover(page, pointOf(page, at["id"] as String, (at["datum"] as Number).toInt()))
            val r = read(page)
            val total = (v[i] as Number).toDouble().let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
            log += "hostile stack $category: ${(r["card"] as Map<*, *>?)?.get("texts")}"
            withClue("A8 with the total: the heading of $category is text, never markup: $r") {
                r["scripts"] shouldBe 0
                cardLines(r).first()["text"] shouldBe "$category$JOIN$total"
            }
            shown++
        }
        withClue("A8: no CSP violation with the stacked hostile chart") { drainCspViolations().shouldBeEmpty() }
        withClue("hostile stacked headings read (floor 4)") { shown shouldBe k.size }
    }

    @Test
    fun `A8 - the hostile chart with the layer active - every mark by pointer and keys shows text, no script, no CSP violation`() {
        startTrace()
        val (context, page) = harnessPage("hostile", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        var shown = 0
        try {
            mount(page, engineChart("bar/hostile"), FULL_W, FULL_H)["ok"] shouldBe true
            val ids = (read(page)["summary"] as List<*>).map { (it as Map<*, *>)["id"] as String }
            for (id in ids) {
                for (at in datums(page, id)) {
                    hover(page, pointOf(page, id, at))
                    val r = read(page)
                    log += "$id@$at ${r["card"]}"
                    withClue("A8: the card on $id's mark $at is text, no script: $r") {
                        r["scripts"] shouldBe 0
                        r["cards"] shouldBe 1
                    }
                    shown++
                }
            }
            page.mouse().move(1.0, 1.0)
            tabIn(page)
            repeat(shown) {
                press(page, "ArrowRight")
                val r = read(page)
                withClue("A8: by keys, step $it: text, no script: $r") { r["scripts"] shouldBe 0 }
            }
            withClue("A8: no CSP violation with the layer active") { drainCspViolations().shouldBeEmpty() }
        } finally {
            context.close()
            report("hostile.txt", log + "cards shown=$shown")
        }
        withClue("A8: hostile cards shown (non-vacuity floor 1)") { shown shouldBeGreaterThanOrEqual 1 }
    }

    // -------------------------------------------------------------------------------------------------- helpers

    /** In a new context of [root] (serving the current copies), one line chart's card, dot and ring, read. */
    private fun measuresIn(
        root: String,
        name: String,
        tokens: Map<String, String>,
    ): Map<String, Any?> {
        val (context, page) = openHarness(root, name, VIEW_W, VIEW_H)
        try {
            val chart = line(linkedMapOf("Alpha" to listOf(70, 90, 80), LONG to listOf(20, 40, 30)), title = "Look")
            mount(page, chart, FULL_W, FULL_H, tokens = tokens)["ok"] shouldBe true
            val at = datums(page, LONG)[1]
            val anchor = pointOf(page, LONG, at)
            hover(page, anchor)
            val r = read(page)
            val card = r["card"] as Map<*, *>
            val box = card["box"] as Map<*, *>
            val dot = page.evaluate("() => parseFloat(window.__h.svg().querySelector('circle.dp-guide').getAttribute('r'))") as Number
            val longName = names(r).firstOrNull { it.startsWith(LONG.take(LONG_PREFIX)) }
            val valueWeight = cardLines(r).first { it["anchor"] == "end" }["weight"]
            page.mouse().move(1.0, 1.0)
            page.evaluate(FRAME)
            tabIn(page)
            press(page, "ArrowRight")
            press(page, "ArrowRight")
            val ring = read(page)["ring"] as Map<*, *>
            return mapOf(
                "distance" to n(box, "x") - anchor.first,
                "w" to n(box, "w"),
                "h" to n(box, "h"),
                "lines" to (((r["card"] as Map<*, *>)["texts"] as List<*>).size - values(r).size).toDouble(),
                "long" to longName,
                "valueWeight" to (valueWeight as Number).toDouble(),
                "dot" to dot.toDouble(),
                "ringW" to n(ring, "w"),
            )
        } finally {
            context.close()
        }
    }

    /** A look-table number as the table writes it (14, not 14.0). */
    private fun plain(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private fun Map<*, *>.d(key: String): Double = (this[key] as Number).toDouble()

    private fun n(
        m: Map<*, *>,
        key: String,
    ): Double = (m[key] as Number).toDouble()

    private fun within(
        box: Map<*, *>,
        x: Double,
        y: Double,
    ): Boolean = x >= n(box, "x") && x <= n(box, "x") + n(box, "w") && y >= n(box, "y") && y <= n(box, "y") + n(box, "h")

    @Suppress("UNCHECKED_CAST")
    private fun datums(
        page: Page,
        id: String,
    ): List<Int> = (page.evaluate("id => window.__h.datums(id)", id) as List<Number>).map { it.toInt() }

    private fun listeners(page: Page): Int = (page.evaluate("() => window.__h.listeners()") as Number).toInt()

    private fun token(
        page: Page,
        name: String,
        value: String,
    ) {
        page.evaluate("a => window.__h.token(a.n, a.v)", mapOf("n" to name, "v" to value))
        page.evaluate(FRAME)
    }

    /** Waits past the longest ease a test sets, so the computed opacity is the transition's end. */
    private fun settled(page: Page) {
        page.evaluate("() => new Promise(r => setTimeout(() => r(true), $SETTLE_MS))")
    }

    private fun activeMarks(r: Map<*, *>): List<Any?> =
        (r["marks"] as List<*>)
            .map {
                it as Map<*, *>
            }.filter { it["active"] == true }
            .map { it["id"] }
            .distinct()

    private fun paintOf(
        r: Map<*, *>,
        id: String,
    ): Map<*, *> = (r["marks"] as List<*>).map { it as Map<*, *> }.first { it["id"] == id }

    private fun paint(
        r: Map<*, *>,
        id: String,
    ): List<Any?> = paintOf(r, id).let { listOf(it["fill"], it["stroke"]) }

    private fun entry(
        r: Map<*, *>,
        id: String,
    ): Map<*, *> = entries(r).first { it["id"] == id }

    private fun click(
        page: Page,
        e: Map<*, *>,
    ) {
        val b = e["box"] as Map<*, *>
        page.mouse().move(n(b, "x") + n(b, "w") / 2, n(b, "y") + n(b, "h") / 2)
        page.mouse().down()
        page.mouse().up()
        page.evaluate(FRAME)
        page.mouse().move(1.0, 1.0)
        page.evaluate(FRAME)
    }

    private fun filterOf(r: Map<*, *>): Any? = (r["card"] as Map<*, *>)["filter"]

    /** The computed `filter` of a probe group in the picture given [value] through the CSSOM (attached while read). */
    private fun probeFilter(
        page: Page,
        value: String,
    ): String =
        page.evaluate(
            "v => { var svg = window.__h.svg(); var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');" +
                " svg.appendChild(g); g.style.filter = v; var f = getComputedStyle(g).filter; svg.removeChild(g); return f; }",
            value,
        ) as String

    private companion object {
        const val FULL_W = 640
        const val FULL_H = 400
        const val SMALL_W = 80
        const val SMALL_H = 24
        const val MOVE_W = 90
        const val VIEW_W = 720
        const val VIEW_H = 480
        const val MOVE_H = 60
        const val MORE_H = 110
        const val CUT_W = 120
        const val CUT_H = 60
        const val EIGHT = 8
        const val SIX = 6
        const val TEXT = 12.0
        const val TOLERANCE = 1.0
        const val TAP_INTO_BAR = 6.0
        const val EDGE = 4.0
        const val SETTLE_MS = 450
        const val LONG = "Northern distribution region of the company"
        const val LONG_PREFIX = 8
        const val TABLES = "/js/js-rendered/06-tables.js"
        const val TIP_RADIUS = "6"
        const val SECOND_JOIN = " / "
        const val SECOND_SHADOW = "drop-shadow(rgb(255, 0, 0) 0px 2px 4px)"

        val TIP_TOKENS =
            linkedMapOf(
                "--chart-tip-surface" to "#fefae0",
                "--chart-tip-ink" to "#3d405b",
                "--chart-tip-border" to "#81b29a",
                "--chart-tip-radius" to TIP_RADIUS,
            )

        val FIELD_DEFAULTS =
            mapOf(
                "tipDistance" to 14.0,
                "tipLine" to 1.3,
                "tipPad" to 0.33,
                "tipKey" to 0.67,
                "tipGap" to 0.5,
                "tipMax" to 36.0,
                "tipValueWeight" to 600.0,
                "crosshairDot" to 0.5,
                "focusGap" to 0.25,
            )

        val FIELD_VALUES =
            linkedMapOf(
                "tipDistance" to 40.0,
                "tipLine" to 2.6,
                "tipPad" to 0.66,
                "tipKey" to 2.0,
                "tipGap" to 1.5,
                "tipMax" to 12.0,
                "tipValueWeight" to 800.0,
                "crosshairDot" to 3.0,
                "focusGap" to 1.0,
            )

        /** The look table's card fields, read by name on the page. */
        const val LOOK = """() => { var L = window.JsRendered.tables.look; return { tipLine: L('tipLine'), tipPad: L('tipPad') }; }"""

        /** The colours the engine resolves for the four tip tokens the test sets (the page's own resolver). */
        const val EXPECTED_TIP =
            """() => { var t = window.JsRendered.theme.resolve({ base: 'light', tokens: {
                 '--chart-tip-surface': [254, 250, 224], '--chart-tip-ink': [61, 64, 91], '--chart-tip-border': [129, 178, 154] } });
               return { surface: t.css('--chart-tip-surface'), ink: t.css('--chart-tip-ink'), border: t.css('--chart-tip-border') }; }"""

        /**
         * The draw context (D1, G15) at the layer itself: a line chart drawn into a picture of the page's own, the layer
         * attached, the keys on the long series; then ONE update whose theme sets another tip surface and whose measure
         * doubles each width: the card after it is read.
         */
        const val DRAW_CONTEXT =
            """async (a) => {
              var R = window.JsRendered, L = window.DatapipelinesChartInteractions;
              var frame = { w: 360, h: 240 };
              var doc = { display_name: 'Context', config: { mark: { type: 'line' }, encoding: {
                x: { field: 'x', type: 'quantitative', title: 'X' }, y: { field: 'v', type: 'quantitative', aggregate: 'sum', title: 'Value' },
                color: { field: 's', type: 'nominal', sort: null, title: 'Series' } }, title: { text: 'Context' } } };
              var columns = { x: [0, 1, 0, 1], s: [a.long, a.long, 'B', 'B'], v: [5, 7, 2, 3] };
              var types = { x: 'DECIMAL', s: 'STRING', v: 'DECIMAL' };
              var base = R.measure.measurer(null).fn;
              var draw = function (theme, measure) {
                var out = R.render({ type: 'line', document: doc, columns: columns, types: types, frame: frame, theme: theme, measure: measure, hidden: [] });
                return { output: out, theme: theme, measure: measure, frame: frame };
              };
              var host = document.createElement('div');
              host.style.position = 'fixed'; host.style.left = '24px'; host.style.top = '24px'; host.style.zIndex = '2147483001';
              var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
              svg.setAttribute('class', 'dp-chart');
              host.appendChild(svg); document.body.appendChild(host);
              var first = draw('light', base);
              R.writeDom(first.output.primitives, svg, first.theme);
              var handle = L.attach(svg, first, {});
              var frameP = function () { return new Promise(function (r) { requestAnimationFrame(function () { requestAnimationFrame(function () { r(true); }); }); }); };
              svg.focus();
              svg.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
              await frameP();
              var cardOf = function () {
                var g = svg.querySelector('g.dp-tooltip');
                if (!g) return { fill: null, name: null };
                var name = Array.from(g.querySelectorAll('text')).map(function (t) { return t.textContent; }).filter(function (t) { return t.indexOf(a.long.slice(0, 8)) === 0; })[0] || null;
                return { fill: g.querySelector('rect').getAttribute('fill'), name: name };
              };
              var one = cardOf();
              var before = handle.listeners();
              var theme = { base: 'light', tokens: { '--chart-tip-surface': [254, 250, 224] } };
              var doubled = function (t, s, w) { return 2 * base(t, s, w); };
              var next = draw(theme, doubled);
              R.writeDom(next.output.primitives, svg, next.theme);
              handle.update(next);
              await frameP();
              var two = cardOf();
              var result = { firstName: one.name, firstFill: one.fill, secondName: two.name, secondFill: two.fill,
                defaultSurface: R.theme.resolve('light').css('--chart-tip-surface'), newSurface: R.theme.resolve(theme).css('--chart-tip-surface'),
                same: host.querySelector('svg') === svg, listenersBefore: before, listenersAfter: handle.listeners() };
              handle.detach(); host.remove();
              return result;
            }"""
    }
}
