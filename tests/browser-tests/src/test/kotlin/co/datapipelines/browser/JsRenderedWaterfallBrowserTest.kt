package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Waterfall's bars, fixed cards, shape lookup and horizontal key walk in Chromium (#1073 A.7). */
class JsRenderedWaterfallBrowserTest {
    @Test
    fun `the DOM follows the primitives and the legend keys never switch bars`() {
        session { opened ->
            val page = opened.page
            for (fixture in listOf("basic", "negative-cross", "zero-steps", "fifty", "hostile")) {
                draw(page, fixture)
                withClue(fixture) { page.evaluate(DOM) shouldBe emptyList<String>() }
                val before = page.evaluate("() => JSON.stringify(ChartWaterfall.last().primitives)")
                page.locator(".dp-legend").first().click()
                page.evaluate("() => ChartWaterfall.asked()") shouldBe emptyList<String>()
                page.locator("[role=button]").count() shouldBe 0
                page.evaluate("() => JSON.stringify(ChartWaterfall.last().primitives)") shouldBe before
            }
        }
    }

    @Test
    fun `a bar shows its card and ring while the gaps show neither`() {
        session { opened ->
            val page = opened.page
            draw(page, "basic")
            for ((datum, card) in CARDS.withIndex()) {
                move(page, "bar", datum)
                withClue("bar $datum") { shown(page) shouldBe card }
                page.locator(".dp-focus").count() shouldBe 1
            }
            for (place in listOf("gap", "above")) {
                move(page, place, 1)
                withClue(place) { shown(page).shouldBeEmpty() }
                page.locator(".dp-focus").count() shouldBe 0
            }
        }
    }

    @Test
    fun `keys walk bars in drawing order and vertical arrows stay on the same bar`() {
        session { opened ->
            val page = opened.page
            draw(page, "basic")
            page.locator("#chart").focus()
            for (datum in CARDS.indices) {
                page.keyboard().press("ArrowRight")
                withClue("Right to $datum") { shown(page) shouldBe CARDS[datum] }
                for (key in listOf("ArrowDown", "ArrowUp")) {
                    page.keyboard().press(key)
                    withClue("$key on $datum") { shown(page) shouldBe CARDS[datum] }
                }
            }
            for ((key, datum) in listOf("ArrowRight" to 6, "ArrowLeft" to 5, "Home" to 0, "End" to 6)) {
                page.keyboard().press(key)
                withClue(key) { shown(page) shouldBe CARDS[datum] }
            }
        }
    }

    private fun session(check: (ChartTestPage.Opened) -> Unit) {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            check(opened)
            opened.errors.shouldBeEmpty()
            opened.violations.shouldBeEmpty()
            opened.off.shouldBeEmpty()
            opened.page.locator("[style]").count() shouldBe 0
        } finally {
            opened.context.close()
        }
    }

    private fun draw(
        page: Page,
        fixture: String,
    ) {
        page.evaluate("f => ChartWaterfall.draw(f + '/960x540.input.json', {w:960,h:540})", fixture)
        page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
    }

    private fun move(
        page: Page,
        place: String,
        datum: Int,
    ) {
        @Suppress("UNCHECKED_CAST")
        val xy = page.evaluate(PLACE, listOf(place, datum)) as List<Number>
        page.mouse().move(xy[0].toDouble(), xy[1].toDouble())
    }

    @Suppress("UNCHECKED_CAST")
    private fun shown(page: Page): List<String> =
        page.evaluate("() => [...document.querySelectorAll('text.dp-tooltip')].map(e => e.textContent)") as List<String>

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private val CARDS =
            listOf(
                listOf("Start", "Opening", "100 USD"),
                listOf("Sales", "Increase", "40 USD", "Running total", "140 USD"),
                listOf("Refunds", "Decrease", "−15 USD", "Running total", "125 USD"),
                listOf("Costs", "Decrease", "−30 USD", "Running total", "95 USD"),
                listOf("Tax", "Decrease", "−12 USD", "Running total", "83 USD"),
                listOf("Bonus", "Increase", "22 USD", "Running total", "105 USD"),
                listOf("Total", "Total", "105 USD"),
            )
        private val EMITTED = Regex("""waterfall/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-waterfall.test",
                resources = "chart-waterfall",
                script = "chart-waterfall.js",
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

        private val PLACE =
            """
            ([place, datum]) => {
              const out = ChartWaterfall.last(), svg = document.getElementById("chart"), b = out.index.marks[datum].shape;
              const p = out.index.plot, next = out.index.marks[Math.min(datum+1,out.index.marks.length-1)].shape;
              let x = b.x + b.w/2, y = b.y + b.h/2;
              if (place === "gap") x = (b.x + b.w + next.x)/2;
              if (place === "above") y = (p.y + b.y)/2;
              const r = svg.getBoundingClientRect(), vb = svg.viewBox.baseVal;
              return [r.left + x*r.width/vb.width, r.top + y*r.height/vb.height];
            }
            """.trimIndent()

        private val DOM =
            """
            () => {
              const out = ChartWaterfall.last(), svg = document.getElementById("chart"), problems = [];
              const els = [...svg.querySelectorAll("*")].filter(e => !["title","desc"].includes(e.tagName));
              if (els.length !== out.primitives.length) problems.push("element count");
              out.primitives.forEach((p,i) => {
                const el = els[i], tag = p.k === "group" ? "g" : p.k;
                if (!el || el.tagName !== tag || !el.classList.contains("dp-"+p.role)) {problems.push("kind " + i); return;}
                const attrs = p.k === "rect" ? {x:p.x,y:p.y,width:p.w,height:p.h} : p.k === "line" ? {x1:p.x1,y1:p.y1,x2:p.x2,y2:p.y2} : {};
                Object.entries(attrs).forEach(([k,v]) => {if(Math.abs(Number(el.getAttribute(k))-v)>0.011)problems.push(k+" "+i);});
                if(p.k === "text" && el.textContent !== (p.shown === null ? p.text : p.shown)) problems.push("text " + i);
                if(p.k === "path" && !el.getAttribute("d"))problems.push("path " + i);
              });
              return problems;
            }
            """.trimIndent()
    }
}
