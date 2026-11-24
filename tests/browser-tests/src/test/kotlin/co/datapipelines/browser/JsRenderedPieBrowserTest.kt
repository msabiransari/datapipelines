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

/** Pie's type-lane browser check: primitive parity, outward movement, the card, legend and key walk (#1068 A.7). */
class JsRenderedPieBrowserTest {
    @Test
    fun `the page draws the primitives at every level, moves a held slice, switches and walks slices`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        opened.context.use {
            val page = opened.page
            for (fixture in listOf("one-slice", "eight-slices", "folded", "pie-inner-0", "labels-value", "hostile", "tiny-slices")) {
                for ((w, h) in listOf(960 to 540, 240 to 160, 80 to 24)) {
                    draw(page, fixture, w, h)
                    withClue("$fixture at $w x $h") { page.evaluate(DOM_JS) shouldBe emptyList<String>() }
                }
            }
            draw(page, "four-unit", 960, 540)
            val first = place(page, "string:S1")
            hover(page, first)
            withClue("slice one moves by five pixels and shows its own row") {
                page.evaluate(HOVER_JS, "string:S1") shouldBe
                    mapOf("card" to listOf("Payments by method", "S1", "50 payments (50%)"), "move" to true)
            }
            for (where in listOf("hole", "outside")) {
                val point = point(page, where)
                hover(page, point)
                withClue(where) { page.evaluate(CARD_JS) shouldBe emptyList<String>() }
                page.evaluate("() => document.querySelectorAll('path.dp-mark[transform]').length") shouldBe 0
            }
            // S1 has half the shown total. Hiding it makes S2's share 60%, while its colour slot stays the same.
            val legend = point(page, "legend")
            page.mouse().click(legend[0], legend[1])
            page.mouse().move(LEAVE_X, LEAVE_Y)
            page.evaluate("() => window.ChartPie.asked()") shouldBe listOf("string:S1")
            page.evaluate(DOM_JS) shouldBe emptyList<String>()
            page.evaluate(HIDDEN_JS) shouldBe true
            hover(page, place(page, "string:S2"))
            page.evaluate(CARD_JS) shouldBe listOf("Payments by method", "S2", "30 payments (60%)")
            draw(page, "four-unit", 960, 540)
            page.mouse().move(LEAVE_X, LEAVE_Y)
            page.locator("#chart").focus()
            for ((key, name, value) in KEY_PLAN) {
                page.keyboard().press(key)
                withClue("$key: $name") {
                    page.evaluate(CARD_JS) shouldBe listOf("Payments by method", name, value)
                }
            }
            page.keyboard().press("Escape")
            page.evaluate(CARD_JS) shouldBe emptyList<String>()
            opened.errors.shouldBeEmpty()
            opened.violations.shouldBeEmpty()
            opened.off.shouldBeEmpty()
            page.evaluate("() => document.querySelectorAll('[style]').length") shouldBe 0
        }
    }

    private fun draw(
        page: Page,
        fixture: String,
        w: Int,
        h: Int,
    ) {
        page.evaluate(
            "([f,w,h]) => window.ChartPie.draw(f + '/960x540.input.json', {w,h})",
            listOf(fixture, w, h),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun point(
        page: Page,
        where: String,
    ): List<Double> = (page.evaluate(POINT_JS, where) as List<Number>).map { it.toDouble() }

    @Suppress("UNCHECKED_CAST")
    private fun place(
        page: Page,
        id: String,
    ): List<Double> = (page.evaluate(PLACE_JS, id) as List<Number>).map { it.toDouble() }

    private fun hover(
        page: Page,
        point: List<Double>,
    ) = page.mouse().move(point[0], point[1])

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private const val LEAVE_X = 1276.0
        private const val LEAVE_Y = 896.0
        private val KEY_PLAN =
            listOf(
                Triple("ArrowRight", "S1", "50 payments (50%)"),
                Triple("ArrowRight", "S2", "30 payments (30%)"),
                Triple("ArrowDown", "S2", "30 payments (30%)"),
                Triple("ArrowRight", "S3", "15 payments (15%)"),
                Triple("End", "S4", "5 payments (5%)"),
                Triple("Home", "S1", "50 payments (50%)"),
            )
        private val EMITTED = Regex("""pie/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-pie.test",
                resources = "chart-pie",
                script = "chart-pie.js",
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

        private const val CARD_JS = "() => [...document.querySelectorAll('text.dp-tooltip')].map(t => t.textContent)"
        private val HELPERS =
            """
            const R = window.JsRendered, out = window.ChartPie.last(), svg = document.getElementById('chart');
            const vb = svg.viewBox.baseVal, box = svg.getBoundingClientRect();
            const screen = (x,y) => [box.left + x*box.width/vb.width, box.top + y*box.height/vb.height];
            """.trimIndent()
        private val PLACE_JS =
            "(id) => { $HELPERS" +
                "const s=out.index.marks.find(m=>m.series===id).shape, a=(s.from+s.to)/2*Math.PI/180;" +
                "const r=(s.r0+s.r1)/2; return screen(s.cx+Math.sin(a)*r,s.cy-Math.cos(a)*r); }"
        private val POINT_JS =
            "(where) => { $HELPERS" +
                "const s=out.index.marks[0].shape;" +
                "if(where==='hole') return screen(s.cx,s.cy);" +
                "if(where==='outside') return screen(1,1);" +
                "const e=svg.querySelector('g.dp-legend[data-legend=\"string:S1\"] rect').getBoundingClientRect();" +
                "return [e.x+e.width/2,e.y+e.height/2]; }"
        private val HOVER_JS =
            """
            (id) => {
              const o=window.ChartPie.last(), svg=document.getElementById('chart');
              const s=o.index.marks.find(m=>m.series===id).shape, a=(s.from+s.to)/2*Math.PI/180;
              const p=svg.querySelector('g.dp-mark[data-series="'+id+'"] path.dp-mark');
              const tr=p.getAttribute('transform'), n=tr && tr.match(/[-\d.]+/g).map(Number);
              return {card:[...svg.querySelectorAll('text.dp-tooltip')].map(t=>t.textContent),
                move:!!n && Math.abs(n[0]-5*Math.sin(a))<0.02 && Math.abs(n[1]+5*Math.cos(a))<0.02};
            }
            """.trimIndent()
        private val HIDDEN_JS =
            """
            () => {
              const R=window.JsRendered, input=window.ChartPie.input(); input.hidden=['string:S1'];
              const expected=R.render(input), actual=window.ChartPie.last();
              return JSON.stringify(expected.primitives)===JSON.stringify(actual.primitives)
                && actual.index.marks.length===3 && actual.index.marks[0].shape.to===216;
            }
            """.trimIndent()
        private val DOM_JS =
            """
            () => {
              const R=window.JsRendered, o=window.ChartPie.last(), svg=document.getElementById('chart');
              const els=[...svg.querySelectorAll('*')].filter(e=>!['title','desc','defs','linearGradient','stop'].includes(e.localName));
              const tags={group:'g',path:'path',text:'text',rect:'rect',line:'line',circle:'circle'}, bad=[];
              if(els.length!==o.primitives.length) bad.push('element count');
              o.primitives.forEach((p,i)=>{
                const e=els[i];
                if(!e || e.localName!==tags[p.k] || !e.classList.contains('dp-'+p.role)) {bad.push('element '+i);return;}
                if(p.k==='text' && e.textContent!==(p.shown ?? p.text)) bad.push('text '+i);
                if(p.k==='path' && e.getAttribute('d')!==R.write.pathText(p.d)) bad.push('path '+i);
                if(p.k==='text' && (Number(e.getAttribute('x'))!==p.x || Number(e.getAttribute('y'))!==p.y)) bad.push('position '+i);
                if(p.fill && e.getAttribute('fill')!==p.color) bad.push('fill '+i);
                if(p.stroke && e.getAttribute('stroke')!==p.strokeColor) bad.push('stroke '+i);
              });
              return bad;
            }
            """.trimIndent()
    }
}
