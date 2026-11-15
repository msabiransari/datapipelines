package co.datapipelines.browser

import co.datapipelines.browser.InteractionCharts.QUARTERS
import co.datapipelines.browser.InteractionCharts.SPARSE
import co.datapipelines.browser.InteractionCharts.bar
import co.datapipelines.browser.InteractionCharts.line
import co.datapipelines.browser.InteractionCharts.only
import co.datapipelines.browser.InteractionCharts.sparse
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #990 (js-rendered F6) — the keyboard (D5), the legend switch (D4), the card that shows what fits (D2a, LA13's browser
 * half) and the cut texts (D3a), on charts the harness mounts at exact frames ([ChartInteractionSuite]):
 *  - K1 to K4 by keys alone, on a 2-series bar and a 3-series line (K3 also on a 2-series line); at every step the
 *    picture holds focus and the chart holds ONE `tabindex`;
 *  - the sparse line at 80 × 24 (no legend): its four marks reached by keys, Down from A's mark at 1 reaching B's at 2;
 *  - LA13 at 80 × 24: the values of a 3-series line (two series sharing a point) and a 3-series bar by keys, by keys
 *    after the pointer was parked elsewhere, and by the pointer;
 *  - LA10's four sequences of the look contract's §4.2 in five executions (sequence 3 on bar and on line), by clicks and
 *    refreshes of ONE mounted chart;
 *  - the text card of a cut subtitle and of a cut category label, by pointer and by keys, and the hostile title.
 */
class ChartKeyboardBrowserTest : ChartInteractionSuite() {
    private val bar2 = bar(mapOf("A" to listOf(30, 50, 20), "B" to listOf(60, 90, 40)))
    private val line3 = line(mapOf("A" to listOf(10, 30, 20, 40), "B" to listOf(25, 15, 35, 30), "C" to listOf(5, 45, 10, 20)))
    private val line2 = line(mapOf("A" to listOf(10, 30, 20, 40), "B" to listOf(25, 15, 35, 30)))

    @Test
    fun `K1 and K2 - by keys alone every series is switched off and back, on a 2-series bar and a 3-series line`() {
        startTrace()
        val (context, page) = harnessPage("k12")
        val log = mutableListOf<String>()
        try {
            for ((label, chart) in listOf("bar" to bar2, "line" to line3)) {
                mount(page, chart, FULL_W, FULL_H)["ok"] shouldBe true
                val ids = seriesOf(chart)
                tabIn(page)
                step(page, log, "$label K1 tab in").also { it["ring"] shouldBe null }
                press(page, "ArrowRight")
                step(page, log, "$label K1 arrow").also { it["card"] shouldNotBe null }
                press(page, "End")
                press(page, "ArrowRight")
                withClue("$label K1: Right on the series' last mark reaches its legend entry") { ringOn(step(page, log, "$label K1 End Right")) shouldBe ids[0] }
                ids.forEachIndexed { i, id ->
                    if (i > 0) press(page, "ArrowRight")
                    press(page, "Enter")
                    val r = step(page, log, "$label K1 Enter on $id")
                    withClue("$label K1: the target stays on $id's entry after its switch") { ringOn(r) shouldBe id }
                }
                val off = step(page, log, "$label K1 all off")
                withClue("$label K1: 0 marks, the axes and the legend drawn: $off") {
                    off["drawnMarks"] shouldBe 0
                    off["state"] shouldBe "rendered"
                    (off["values"] as List<*>).size shouldBeGreaterThan 0
                    entries(off).map { it["id"] } shouldContainExactly ids
                    entries(off).all { it["off"] == true } shouldBe true
                    (off["hidden"] as List<*>).toSet() shouldBe ids.toSet()
                }
                press(page, "Tab")
                withClue("$label K2: Tab leaves the chart") { read(page)["focused"] shouldBe false }
                press(page, "Shift+Tab")
                press(page, "ArrowRight")
                val entered = step(page, log, "$label K2 Shift+Tab arrow")
                withClue("$label K2: with no mark drawn the first legend entry is the target") { ringOn(entered) shouldBe ids[0] }
                press(page, "Enter")
                val back = step(page, log, "$label K2 Enter")
                withClue("$label K2: ${ids[0]} is drawn again: $back") {
                    drawnOf(back, ids[0]) shouldBeGreaterThan 0
                    (back["hidden"] as List<*>).contains(ids[0]) shouldBe false
                }
                press(page, "Escape")
                withClue("$label: Escape clears the ring and the card, focus stays") {
                    val r = step(page, log, "$label Escape")
                    r["ring"] shouldBe null
                    r["card"] shouldBe null
                }
            }
        } finally {
            context.close()
            report("k1-k2.txt", log)
        }
    }

    @Test
    fun `K3 - a struck entry that a refresh left alone is reached by keys and brings its series back, on bar and on line`() {
        startTrace()
        val (context, page) = harnessPage("k3")
        val log = mutableListOf<String>()
        try {
            for ((label, chart) in listOf("bar" to bar2, "line" to line2)) {
                mount(page, chart, FULL_W, FULL_H)["ok"] shouldBe true
                tabIn(page)
                press(page, "ArrowRight")
                press(page, "End")
                press(page, "ArrowRight")
                press(page, "ArrowRight")
                withClue("$label K3: the keys reach B's entry") { ringOn(step(page, log, "$label to B")) shouldBe "B" }
                press(page, "Enter")
                step(page, log, "$label B off")["hidden"] shouldBe listOf("B")
                refresh(page, only(chart, "B"))["ok"] shouldBe true
                val alone = step(page, log, "$label refresh B alone")
                withClue("$label K3: the one struck entry B at the top above empty axes, the target on it: $alone") {
                    entries(alone).map { it["id"] to it["off"] } shouldBe listOf("B" to true)
                    alone["place"] shouldBe "top"
                    alone["drawnMarks"] shouldBe 0
                    ringOn(alone) shouldBe "B"
                }
                press(page, "Enter")
                val shown = step(page, log, "$label Enter")
                withClue("$label K3: B is drawn, the legend is gone, the target is B's first mark with its card: $shown") {
                    entries(shown).shouldBeEmpty()
                    drawnOf(shown, "B") shouldBeGreaterThan 0
                    shown["ring"] shouldNotBe null
                    names(shown) shouldContain "B"
                    values(shown) shouldContain "${chart.valueAt("B", 0)}"
                }
            }
        } finally {
            context.close()
            report("k3.txt", log)
        }
    }

    @Test
    fun `K4 - a refresh that removes the target mark's series puts the target where D5 says`() {
        startTrace()
        val (context, page) = harnessPage("k4")
        val log = mutableListOf<String>()
        try {
            mount(page, bar2, FULL_W, FULL_H)["ok"] shouldBe true
            tabIn(page)
            press(page, "ArrowRight")
            press(page, "ArrowDown")
            val onB = step(page, log, "B at Q1")
            withClue("K4: Down reaches B's mark at Q1 (the hovered row is bold): $onB") { boldName(onB) shouldBe "B" }
            refresh(page, only(bar2, "A"))["ok"] shouldBe true
            val after = step(page, log, "refresh A alone")
            withClue("K4: B is gone and has no entry: the target is as on entering, A's first mark: $after") {
                after["ring"] shouldNotBe null
                names(after) shouldContain "A"
                values(after) shouldContain "30"
                (after["card"] as Map<*, *>)["texts"].let { (it as List<*>).first() } shouldBe QUARTERS[0]
            }
        } finally {
            context.close()
            report("k4.txt", log)
        }
    }

    @Test
    fun `D5 - the sparse line at 80 x 24 - Down from A's mark at 1 is B's at 2, and the four marks are reached by keys`() {
        startTrace()
        val (context, page) = harnessPage("sparse")
        val log = mutableListOf<String>()
        val reached = mutableSetOf<String>()
        try {
            mount(page, sparse(), SMALL_W, SMALL_H)["ok"] shouldBe true
            tabIn(page)
            val keys = listOf("ArrowRight", "ArrowRight", "ArrowDown", "ArrowRight", "ArrowUp", "ArrowLeft")
            val expected = listOf("A" to 0, "A" to 1, "B" to 2, "B" to 3, "A" to 1, "A" to 0)
            keys.zip(expected).forEachIndexed { i, (key, mark) ->
                press(page, key)
                val r = step(page, log, "$i $key")
                withClue("sparse step $i ($key): the card is ${mark.first}'s at x ${mark.second}: $r") {
                    names(r) shouldContain mark.first
                    values(r) shouldContain "${SPARSE[mark.second]}"
                    entries(r).shouldBeEmpty()
                    (r["hidden"] as List<*>).shouldBeEmpty()
                    inside(r) shouldBe true
                }
                reached += "${mark.first}@${mark.second}"
            }
        } finally {
            context.close()
            report("sparse.txt", log + "reached=$reached")
        }
        withClue("the four marks are reached by keys (non-vacuity floor 4): $reached") { reached.size shouldBe SPARSE_MARKS }
    }

    @Test
    fun `LA13 - at 80 x 24 the card carries each series' name and WHOLE value, by keys, by keys after a parked pointer, and by the pointer`() {
        startTrace()
        val (context, page) = harnessPage("la13")
        val log = mutableListOf<String>()
        var read = 0
        try {
            // Two series share one point: A and B are both 5 at the second month.
            val shared = line(mapOf("A" to listOf(1, 5, 9), "B" to listOf(3, 5, 7), "C" to listOf(8, 2, 6)), title = "Shared")
            val bar3 = bar(mapOf("A" to listOf(3, 6, 9), "B" to listOf(5, 2, 7), "C" to listOf(8, 4, 1)), title = "Three")
            for ((label, chart, parked) in listOf(Triple("line", shared, false), Triple("line parked", shared, true), Triple("bar", bar3, false))) {
                mount(page, chart, SMALL_W, SMALL_H)["ok"] shouldBe true
                if (parked) hover(page, pointOf(page, "C", 2))
                tabIn(page)
                press(page, "ArrowRight")
                for (x in 0 until 3) {
                    if (x > 0) press(page, "ArrowRight")
                    for ((i, id) in listOf("A", "B", "C").withIndex()) {
                        if (i > 0) press(page, "ArrowDown")
                        val r = step(page, log, "$label keys $id@$x")
                        assertRow(r, label, id, chart.valueAt(id, x))
                        read++
                    }
                    press(page, "ArrowUp")
                    press(page, "ArrowUp")
                }
            }
            // By the pointer: every point that shares its place with no other (the line's A and B at month 2 do).
            for ((label, chart) in listOf("line" to shared, "bar" to bar3)) {
                mount(page, chart, SMALL_W, SMALL_H)["ok"] shouldBe true
                for (x in 0 until 3) {
                    for (id in listOf("A", "B", "C")) {
                        if (label == "line" && x == 1 && id != "C") continue
                        hover(page, pointOf(page, id, x))
                        assertRow(step(page, log, "$label pointer $id@$x", keys = false), label, id, chart.valueAt(id, x))
                        read++
                    }
                }
                page.mouse().move(1.0, 1.0)
            }
        } finally {
            context.close()
            report("la13.txt", log + "values read=$read")
        }
        withClue("values read at 80 x 24 (non-vacuity floor $LA13_FLOOR)") { read shouldBeGreaterThanOrEqual LA13_FLOOR }
    }

    @Test
    fun `LA10 - the four sequences of the look contract's 4-2 in five executions, one chart node throughout`() {
        startTrace()
        val (context, page) = harnessPage("la10")
        val log = mutableListOf<String>()
        var executions = 0
        try {
            // (1) Five lines A to E.
            val five = line(("ABCDE".map { it.toString() }).associateWith { id -> listOf(1, 2, 3).map { it * (id[0] - '@') } })
            mount(page, five, FULL_W, FULL_H)["ok"] shouldBe true
            val first = expectLegend(page, log, "1 start", "top", emptyList(), 5)
            for ((n, id) in listOf("E", "D", "C", "B", "A").withIndex()) {
                click(page, id)
                expectLegend(page, log, "1 hide $id", "top", listOf("E", "D", "C", "B", "A").take(n + 1), 5)
            }
            for ((n, id) in listOf("A", "B", "C", "D", "E").withIndex()) {
                click(page, id)
                expectLegend(page, log, "1 show $id", "top", listOf("B", "C", "D", "E").drop(n), 5)
            }
            withClue("(1) the same node from the first step to the last") { first["same"] shouldBe true }
            executions++

            // (2) Three lines A to C.
            val three = line(mapOf("A" to listOf(10, 30, 20), "B" to listOf(25, 15, 35), "C" to listOf(5, 45, 10)))
            mount(page, three, FULL_W, FULL_H)["ok"] shouldBe true
            expectLegend(page, log, "2 start", "lines", emptyList(), 3)
            click(page, "B")
            expectLegend(page, log, "2 hide B", "top", listOf("B"), 3)
            click(page, "A")
            click(page, "C")
            expectLegend(page, log, "2 hide A C", "top", listOf("B", "A", "C"), 3)["drawnMarks"] shouldBe 0
            click(page, "A")
            click(page, "B")
            expectLegend(page, log, "2 show A B", "top", listOf("C"), 3)
            click(page, "C")
            expectLegend(page, log, "2 show C", "lines", emptyList(), 3)
            executions++

            // (3) Two series A and B, as bars and as lines.
            for ((label, chart) in listOf("bar" to bar2, "line" to line2)) {
                mount(page, chart, FULL_W, FULL_H)["ok"] shouldBe true
                click(page, "B")
                expectLegend(page, log, "3 $label hide B", "top", listOf("B"), 2)
                refresh(page, only(chart, "B"))["ok"] shouldBe true
                expectLegend(page, log, "3 $label refresh B alone", "top", listOf("B"), 1)["drawnMarks"] shouldBe 0
                click(page, "B")
                val shown = expectLegend(page, log, "3 $label show B", "none", emptyList(), 0)
                withClue("(3) $label: B is drawn and the node is the same") {
                    drawnOf(shown, "B") shouldBeGreaterThan 0
                    shown["same"] shouldBe true
                }
                executions++
            }

            // (4) Three lines A to C.
            mount(page, three, FULL_W, FULL_H)["ok"] shouldBe true
            click(page, "B")
            expectLegend(page, log, "4 hide B", "top", listOf("B"), 3)
            refresh(page, only(three, "A", "C"))["ok"] shouldBe true
            val two = expectLegend(page, log, "4 refresh A C", "lines", emptyList(), 2)
            withClue("(4) B's id does nothing and stays on the list: $two") {
                two["hidden"] shouldBe listOf("B")
                drawnOf(two, "A") shouldBeGreaterThan 0
                drawnOf(two, "C") shouldBeGreaterThan 0
            }
            refresh(page, three.columns)["ok"] shouldBe true
            val again = expectLegend(page, log, "4 refresh A B C", "top", listOf("B"), 3)
            withClue("(4) the node is the same throughout") { again["same"] shouldBe true }
            executions++
        } finally {
            context.close()
            report("la10.txt", log + "executions=$executions")
        }
        withClue("LA10's five executions (1, 2, 3 bar, 3 line, 4)") { executions shouldBe LA10_EXECUTIONS }
    }

    @Test
    fun `D3a - a cut subtitle and a cut category label show their full text by pointer and by keys, a text not cut shows nothing, the hostile title is text`() {
        startTrace()
        val (context, page) = harnessPage("cut")
        val log = mutableListOf<String>()
        var revealed = 0
        try {
            val subtitle = engineChart("bar/look-long-subtitle")
            mount(page, subtitle, CUT_W, CUT_H)["ok"] shouldBe true
            val cuts = cutsOf(page)
            val sub = cuts.first { it["role"] == "title" }
            val full = sub["text"] as String
            withClue("the frame computed to hold the whole card: $full") { holdsWhole(page, full, CUT_W, CUT_H) shouldBe true }
            hover(page, centre(sub))
            val byPointer = step(page, log, "subtitle pointer", keys = false)
            withClue("D3a: the pointer on the cut subtitle shows its FULL text: $byPointer") { joined(byPointer) shouldBe full }
            revealed++
            val plain = (page.evaluate("() => window.__h.cuts()") as Map<*, *>)["plain"] as List<*>
            hover(page, centre(plain.map { it as Map<*, *> }.first { it["role"] == "title" }))
            withClue("a text that was not cut shows nothing") { step(page, log, "plain title pointer", keys = false)["card"] shouldBe null }
            page.mouse().move(1.0, 1.0)
            tabIn(page)
            press(page, "ArrowRight")
            press(page, "End")
            press(page, "ArrowRight")
            val byKeys = step(page, log, "subtitle keys")
            withClue("D3a: the keys reach the first cut text, its card the FULL text: $byKeys") { joined(byKeys) shouldBe full }
            revealed++
            withClue("the primitive keeps the full text, the DOM the shown one") { (sub["dom"] == full) shouldBe false }

            val labels = engineChart("bar/look-long-labels")
            mount(page, labels, LABEL_W, LABEL_H)["ok"] shouldBe true
            val label = cutsOf(page).first { it["role"] == "tick" }
            val category = label["text"] as String
            hover(page, centre(label))
            withClue("D3a: the pointer on the cut label shows the whole category") { joined(step(page, log, "label pointer", keys = false)) shouldBe category }
            revealed++
            val at = page.evaluate("t => window.__h.datum(t)", category) as Map<*, *>
            hover(page, pointOf(page, at["id"] as String, (at["datum"] as Number).toInt()))
            withClue("on its bar, the mark's card carries the whole category as its heading") {
                ((step(page, log, "label bar", keys = false)["card"] as Map<*, *>)["texts"] as List<*>).first() shouldBe category
            }

            page.mouse().move(1.0, 1.0)
            mount(page, hostileChart(), HOSTILE_W, HOSTILE_H)["ok"] shouldBe true
            val title = cutsOf(page).firstOrNull { it["role"] == "title" } ?: error("the hostile title is not cut at $HOSTILE_W x $HOSTILE_H")
            hover(page, centre(title))
            val hostile = step(page, log, "hostile title", keys = false)
            withClue("the hostile 200-character title is shown whole, as text: $hostile") {
                joined(hostile) shouldBe title["text"]
                hostile["scripts"] shouldBe 0
            }
            revealed++
        } finally {
            context.close()
            report("cut.txt", log + "revealed=$revealed")
        }
        withClue("cut texts revealed (non-vacuity floor 3)") { revealed shouldBeGreaterThanOrEqual CUT_FLOOR }
    }

    @Test
    fun `D3a - where the card holds fewer lines than the wrapped subtitle it ends in an ellipsis`() {
        startTrace()
        // The shipped look table draws this subtitle only in frames that hold its whole card (probed over frames and
        // text sizes, handback): a copy of the look table with a taller card line gives a frame with fewer lines.
        plantCopies = mapOf("/js/js-rendered/06-tables.js" to { text: String -> once(text, "tipLine: f(1.3,", "tipLine: f($TALL_LINE,") })
        val (context, page) = harnessPage("cutshort")
        val log = mutableListOf<String>()
        try {
            mount(page, engineChart("bar/look-long-subtitle"), CUT_W, CUT_H)["ok"] shouldBe true
            val sub = cutsOf(page).first { it["role"] == "title" }
            val full = sub["text"] as String
            hover(page, centre(sub))
            val r = step(page, log, "subtitle reduced", keys = false)
            val texts = (r["card"] as Map<*, *>)["texts"] as List<*>
            withClue("the card's last line ends in an ellipsis and its text is a beginning of the full string: $texts") {
                (texts.last() as String) shouldEndWith "…"
                full shouldStartWith joined(r).removeSuffix("…").trimEnd()
                inside(r) shouldBe true
            }
            withClue("the primitive still holds the full string") { (cutsOf(page).first { it["role"] == "title" }["text"]) shouldBe full }
        } finally {
            context.close()
            report("cut-short.txt", log)
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** One read, logged; [keys]: a step of the keyboard, where the picture must hold focus. Always ONE tabindex. */
    private fun step(
        page: Page,
        log: MutableList<String>,
        label: String,
        keys: Boolean = true,
    ): Map<*, *> {
        val r = read(page)
        log += "$label: focused=${r["focused"]} tabindex=${r["tabindex"]} ringOn=${r["ringOn"]} card=${(r["card"] as Map<*, *>?)?.get("texts")}" +
            " entries=${entries(r).map { "${it["id"]}${if (it["off"] == true) "(off)" else ""}" }} place=${r["place"]} marks=${r["drawnMarks"]} hidden=${r["hidden"]}"
        withClue("$label: the chart holds ONE tabindex${if (keys) " and the picture holds focus" else ""}: $r") {
            r["tabindex"] shouldBe 1
            if (keys) r["focused"] shouldBe true
        }
        return r
    }

    private fun assertRow(
        r: Map<*, *>,
        label: String,
        id: String,
        value: Int,
    ) {
        withClue("$label: the card carries $id's name and its WHOLE value $value, inside the picture ${r["box"]}: ${r["card"]}") {
            r["card"] shouldNotBe null
            names(r).any { it == id || (it.endsWith("…") && id.startsWith(it.dropLast(1))) } shouldBe true
            values(r).first() shouldBe "$value"
            inside(r) shouldBe true
        }
    }

    private fun click(
        page: Page,
        id: String,
    ) {
        val entry = entries(read(page)).first { it["id"] == id }
        hover(page, centre(entry))
        page.mouse().down()
        page.mouse().up()
        page.evaluate(FRAME)
        page.mouse().move(1.0, 1.0)
        page.evaluate(FRAME)
    }

    private fun expectLegend(
        page: Page,
        log: MutableList<String>,
        label: String,
        place: String,
        struck: List<String>,
        count: Int,
    ): Map<*, *> {
        val r = step(page, log, label, keys = false)
        withClue("LA10 $label: the legend is $place with $count entries, struck $struck: $r") {
            r["place"] shouldBe place
            entries(r).size shouldBe count
            entries(r).filter { it["off"] == true }.map { it["id"] }.toSet() shouldBe struck.toSet()
            (r["hidden"] as List<*>).toSet().containsAll(struck) shouldBe true
            r["same"] shouldBe true
            entries(r).forEach { e -> drawnOf(r, e["id"] as String).let { if (e["off"] == true) it shouldBe 0 } }
        }
        return r
    }

    private fun holdsWhole(
        page: Page,
        text: String,
        w: Int,
        h: Int,
    ): Boolean {
        // The test's own bound: the text's width at the card's size over the content width, plus one line for the
        // breaks between words, against the lines the frame holds (look table fields read by name, contract §4.4).
        val m = page.evaluate(MEASURES) as Map<*, *>
        val s = (m["s"] as Number).toDouble()
        val p = (m["tipPad"] as Number).toDouble() * s
        val line = (m["tipLine"] as Number).toDouble() * s
        val contentW = minOf(w.toDouble(), (m["tipMax"] as Number).toDouble() * s) - 2 * p
        val textW = (page.evaluate("t => window.__h.width(t)", text) as Number).toDouble()
        val k = Math.floor((h - 2 * p) / line)
        return Math.ceil(textW / contentW) + 1 <= k
    }

    private fun engineChart(path: String): Chart {
        val input = com.google.gson.JsonParser.parseString(emitted("$path/960x540.input.json")).asJsonObject
        val document = input.getAsJsonObject("document")
        val gson = com.google.gson.Gson()
        @Suppress("UNCHECKED_CAST")
        val columns = gson.fromJson(input.get("columns"), Map::class.java) as Map<String, List<Any?>>
        @Suppress("UNCHECKED_CAST")
        val types = gson.fromJson(input.get("types"), Map::class.java) as Map<String, String>
        return Chart(document.get("display_name").asString, document.get("config").toString(), types, columns)
    }

    private fun hostileChart(): Chart = engineChart("bar/hostile")

    private fun cutsOf(page: Page): List<Map<*, *>> {
        val read = page.evaluate("() => window.__h.cuts()") as Map<*, *>
        read["error"]?.let { error("the DOM's texts are not the text primitives: $it") }
        return (read["cuts"] as List<*>).map { it as Map<*, *> }
    }

    private fun centre(item: Map<*, *>): Pair<Double, Double> {
        val b = item["box"] as Map<*, *>
        val n = { k: String -> (b[k] as Number).toDouble() }
        return (n("x") + n("w") / 2) to (n("y") + n("h") / 2)
    }

    private fun joined(r: Map<*, *>): String = ((r["card"] as Map<*, *>?)?.get("texts") as List<*>? ?: emptyList<Any>()).joinToString(" ")

    private fun once(
        text: String,
        anchor: String,
        replacement: String,
    ): String {
        check(text.split(anchor).size == 2) { "the plant's anchor $anchor must occur exactly once" }
        return text.replace(anchor, replacement)
    }

    private companion object {
        const val FULL_W = 640
        const val FULL_H = 400
        const val SMALL_W = 80
        const val SMALL_H = 24
        const val CUT_W = 360
        const val CUT_H = 260
        const val LABEL_W = 420
        const val LABEL_H = 260
        const val HOSTILE_W = 480
        const val HOSTILE_H = 320
        const val TALL_LINE = 8
        const val SPARSE_MARKS = 4
        const val LA13_FLOOR = 30
        const val LA10_EXECUTIONS = 5
        const val CUT_FLOOR = 3

        /** The card's measures' inputs, read by field name from the page's look table and the default text size. */
        const val MEASURES =
            """() => { var L = window.JsRendered.tables.look; return { s: 12, tipPad: L('tipPad'), tipLine: L('tipLine'), tipMax: L('tipMax') }; }"""
    }
}

/** The series ids of [chart] in the data's order. */
internal fun seriesOf(chart: ChartInteractionSuite.Chart): List<String> =
    (chart.columns["s"] ?: listOf("v")).map { it.toString() }.distinct()

/** Series [id]'s value at x (or category) [x] in [chart]'s rows. */
internal fun ChartInteractionSuite.Chart.valueAt(
    id: String,
    x: Int,
): Int {
    val s = columns.getValue("s")
    val rows = s.indices.filter { s[it] == id }
    return (columns.getValue("v")[rows[x]] as Number).toInt()
}

/** The weight from which a card text is bold (the look table's tipValueWeight is 600). */
private const val BOLD = 600.0

internal fun entries(r: Map<*, *>): List<Map<*, *>> = (r["entries"] as List<*>).map { it as Map<*, *> }

internal fun ringOn(r: Map<*, *>): Any? = (r["ringOn"] as Map<*, *>?)?.get("id")

internal fun drawnOf(
    r: Map<*, *>,
    id: String,
): Int = (r["marks"] as List<*>).map { it as Map<*, *> }.filter { it["id"] == id }.sumOf { (it["drawn"] as Number).toInt() }

internal fun cardLines(r: Map<*, *>): List<Map<*, *>> =
    (((r["card"] as Map<*, *>?)?.get("lines")) as List<*>? ?: emptyList<Any>()).map { it as Map<*, *> }

/** The card's names: its start-anchored texts after the heading's (a row's name). */
internal fun names(r: Map<*, *>): List<String> = cardLines(r).filter { it["anchor"] == "start" }.map { it["text"] as String }

/** The card's values: its end-anchored texts. */
internal fun values(r: Map<*, *>): List<String> = cardLines(r).filter { it["anchor"] == "end" }.map { it["text"] as String }

internal fun boldName(r: Map<*, *>): String? =
    cardLines(r).firstOrNull { it["anchor"] == "start" && (it["weight"] as Number).toDouble() >= BOLD }?.get("text") as String?

/** The card's box and each of its texts inside the picture's box (one pixel of rounding). */
internal fun inside(r: Map<*, *>): Boolean {
    val pic = r["box"] as Map<*, *>
    val card = r["card"] as Map<*, *>? ?: return false
    val n = { m: Map<*, *>, k: String -> (m[k] as Number).toDouble() }
    val within = { b: Map<*, *> ->
        n(b, "x") >= n(pic, "x") - 1 && n(b, "y") >= n(pic, "y") - 1 &&
            n(b, "x") + n(b, "w") <= n(pic, "x") + n(pic, "w") + 1 && n(b, "y") + n(b, "h") <= n(pic, "y") + n(pic, "h") + 1
    }
    return within(card["box"] as Map<*, *>) && cardLines(r).all { within(it["box"] as Map<*, *>) }
}
