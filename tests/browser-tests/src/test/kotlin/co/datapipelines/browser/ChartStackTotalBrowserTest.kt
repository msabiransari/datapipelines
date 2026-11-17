package co.datapipelines.browser

import co.datapipelines.browser.InteractionCharts.JOIN
import co.datapipelines.browser.InteractionCharts.QUARTERS
import co.datapipelines.browser.InteractionCharts.bar
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #990 round 2 (D2, D3) — a stacked bar's card heads with the category, the look table's join (`tipTotalJoin`) and the
 * stack's total, which the engine writes into the index (`index.totals`) and the layer joins to the heading. Read off
 * the page ([ChartInteractionSuite]): every segment of a 2-series stack by pointer and by keys, each expected total
 * summed HERE from the chart's own values; the hostile fixture stacked, whose heading stays text (A8); and a second
 * value of the join served through a copy of the look table (A14's browser half for the field).
 */
class ChartStackTotalBrowserTest : ChartInteractionSuite() {
    private val stacked2 = bar(linkedMapOf("A" to listOf(30, 50, 20), "B" to listOf(60, 90, 40)), title = "Stack", stacked = true)

    /** The stack's total at each quarter, summed by the test from the chart's values. */
    private val totals = QUARTERS.indices.map { stacked2.valueAt("A", it) + stacked2.valueAt("B", it) }

    @Test
    fun `by pointer every segment's card heads with its category, the join and the stack's total`() {
        startTrace()
        val (context, page) = harnessPage("stack-pointer", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        var read = 0
        try {
            mount(page, stacked2, FULL_W, FULL_H)["ok"] shouldBe true
            withClue("the page's look table holds the join") { page.evaluate(JOIN_READ) shouldBe JOIN }
            for (id in listOf("A", "B")) {
                datums(page, id).forEachIndexed { c, datum ->
                    hover(page, pointOf(page, id, datum))
                    val r = read(page)
                    log += "$id@${QUARTERS[c]}: ${texts(r)}"
                    withClue("pointer on $id at ${QUARTERS[c]}: the heading is the category, the join and the total ${totals[c]}: $r") {
                        r["cards"] shouldBe 1
                        cardLines(r).first()["text"] shouldBe "${QUARTERS[c]}$JOIN${totals[c]}"
                        boldName(r) shouldBe id
                    }
                    read++
                }
            }
        } finally {
            context.close()
            report("stack-total-pointer.txt", log + "segments read=$read")
        }
        withClue("stacked segments read by pointer") { read shouldBe 2 * QUARTERS.size }
    }

    @Test
    fun `by keys every target's card heads with its category, the join and the stack's total`() {
        startTrace()
        val (context, page) = harnessPage("stack-keys", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        var seen = 0
        try {
            mount(page, stacked2, FULL_W, FULL_H)["ok"] shouldBe true
            tabIn(page)
            QUARTERS.forEachIndexed { c, quarter ->
                press(page, "ArrowRight")
                val r = read(page)
                log += "Right to A@$quarter: ${texts(r)}"
                withClue("keys on A at $quarter: the heading is $quarter, the join and the total ${totals[c]}: $r") {
                    r["focused"] shouldBe true
                    cardLines(r).first()["text"] shouldBe "$quarter$JOIN${totals[c]}"
                    boldName(r) shouldBe "A"
                }
                seen++
            }
            press(page, "ArrowDown")
            val down = read(page)
            log += "Down to B@${QUARTERS.last()}: ${texts(down)}"
            withClue("keys: the other segment of the same stack shows the same heading: $down") {
                cardLines(down).first()["text"] shouldBe "${QUARTERS.last()}$JOIN${totals.last()}"
                boldName(down) shouldBe "B"
            }
            seen++
        } finally {
            context.close()
            report("stack-total-keys.txt", log + "targets read=$seen")
        }
        withClue("stacked targets read by keys") { seen shouldBe QUARTERS.size + 1 }
    }

    @Test
    fun `the hostile chart stacked heads with its category text, the join and its total, by pointer and keys (A8)`() {
        startTrace()
        val (context, page) = harnessPage("stack-hostile", VIEW_W, VIEW_H)
        val log = mutableListOf<String>()
        var shown = 0
        var keyed = 0
        try {
            val plainChart = engineChart("bar/hostile")
            val chart = plainChart.copy(config = once(plainChart.config, SUM, "$SUM,\"stack\":\"zero\""))
            mount(page, chart, FULL_W, FULL_H)["ok"] shouldBe true
            val k = chart.columns.getValue("k")
            val v = chart.columns.getValue("v")
            k.forEachIndexed { i, category ->
                val at = page.evaluate("h => window.__h.datum(h)", category) as Map<*, *>
                hover(page, pointOf(page, at["id"] as String, (at["datum"] as Number).toInt()))
                val r = read(page)
                log += "hostile stack $category: ${texts(r)}"
                withClue("A8 with the total: the heading of $category is text, never markup: $r") {
                    r["scripts"] shouldBe 0
                    cardLines(r).first()["text"] shouldBe "$category$JOIN${whole(v[i])}"
                }
                shown++
            }
            val headings = k.indices.map { "${k[it]}$JOIN${whole(v[it])}" }
            page.mouse().move(1.0, 1.0)
            tabIn(page)
            val keyedHeadings = mutableSetOf<Any?>()
            HOSTILE_KEYS.forEachIndexed { step, key ->
                press(page, key)
                val r = read(page)
                log += "hostile stack keys $step $key: ${texts(r)}"
                withClue("A8 by keys, step $step ($key): the heading is a hostile category, the join and its total, as text: $r") {
                    r["focused"] shouldBe true
                    r["scripts"] shouldBe 0
                    headings shouldContain cardLines(r).first()["text"]
                }
                keyedHeadings += cardLines(r).first()["text"]
                keyed++
            }
            withClue("A8 by keys: every hostile segment's heading was reached") { keyedHeadings shouldBe headings.toSet() }
            withClue("A8: no CSP violation with the stacked hostile chart") { drainCspViolations().shouldBeEmpty() }
        } finally {
            context.close()
            report("stack-total-hostile.txt", log + "headings read=$shown keyed=$keyed")
        }
        withClue("hostile stacked headings read by pointer and by keys (floor 4 each)") {
            shown shouldBe HOSTILE_FLOOR
            keyed shouldBe HOSTILE_FLOOR
        }
    }

    @Test
    fun `a second value of the look table's join is the text between the category and the total (A14)`() {
        startTrace()
        val root = ready("stack-join")
        plantCopies = mapOf(TABLES to { text: String -> once(text, "tipTotalJoin: f(\"$JOIN\",", "tipTotalJoin: f(\"$SECOND_JOIN\",") })
        val (context, page) = openHarness(root, "join", VIEW_W, VIEW_H)
        plantCopies = emptyMap()
        try {
            mount(page, stacked2, FULL_W, FULL_H)["ok"] shouldBe true
            withClue("the served table holds the second join") { page.evaluate(JOIN_READ) shouldBe SECOND_JOIN }
            hover(page, pointOf(page, "B", datums(page, "B")[0]))
            val r = read(page)
            report("stack-total-join.txt", listOf("second join: ${texts(r)}"))
            withClue("A14 (tipTotalJoin): a second join moves the heading's text: $r") {
                cardLines(r).first()["text"] shouldBe "${QUARTERS[0]}$SECOND_JOIN${totals[0]}"
            }
        } finally {
            context.close()
        }
    }

    // -------------------------------------------------------------------------------------------------- helpers

    @Suppress("UNCHECKED_CAST")
    private fun datums(
        page: Page,
        id: String,
    ): List<Int> = (page.evaluate("id => window.__h.datums(id)", id) as List<Number>).map { it.toInt() }

    private fun texts(r: Map<*, *>): Any? = (r["card"] as Map<*, *>?)?.get("texts")

    /** A whole number as the engine's default format writes it (3, not 3.0). */
    private fun whole(v: Any?): String {
        val d = (v as Number).toDouble()
        check(d == Math.floor(d)) { "the hostile fixture's values are whole: $d" }
        return d.toLong().toString()
    }

    private companion object {
        const val FULL_W = 640
        const val FULL_H = 400
        const val VIEW_W = 720
        const val VIEW_H = 480
        const val TABLES = "/js/js-rendered/06-tables.js"
        const val SUM = "\"aggregate\":\"sum\""
        const val SECOND_JOIN = " / "
        const val HOSTILE_FLOOR = 4

        /** Two marks of the first series, then the nearest mark of the second and its next (as the sparse walk). */
        val HOSTILE_KEYS = listOf("ArrowRight", "ArrowRight", "ArrowDown", "ArrowRight")
        const val JOIN_READ = "() => window.JsRendered.tables.look('tipTotalJoin')"
    }
}
