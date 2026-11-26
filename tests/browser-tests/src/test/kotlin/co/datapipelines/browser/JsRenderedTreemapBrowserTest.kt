package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Leaf cells, cards, rings and the single keyboard row through the real CSP page (#1078 A.7). */
class JsRenderedTreemapBrowserTest {
    @Test
    fun `DOM equals primitives at each level and hostile names remain text`() {
        session { page ->
            for (fixture in listOf("plain", "nested", "deep", "color-series", "shade", "hostile", "many-leaves")) {
                for ((w, h) in listOf(480 to 300, 240 to 160, 80 to 24)) {
                    draw(page, fixture, w, h)
                    (page.evaluate(DOM) as List<*>).shouldBeEmpty()
                }
            }
            draw(page, "hostile")
            page.locator("#chart script, #chart img, #chart foreignObject").count() shouldBe 0
            page.evaluate("() => document.getElementById('chart').textContent.includes('<script>alert(1)</script>')") shouldBe true
        }
    }

    @Test
    fun `hover rings a cell shows its own value and share dims others and gaps clear the card`() {
        session { page ->
            draw(page)
            move(page, "cell")
            shown(page) shouldBe listOf("North", "50 GB", "Share of all", "50%")
            page.evaluate(RING) shouldBe true
            page.evaluate(DIMMED) shouldBe 3
            move(page, "gap")
            shown(page).shouldBeEmpty()
            page.locator("#chart rect.dp-guide[stroke]").count() shouldBe 0
            draw(page, "nested")
            move(page, "cell")
            page.evaluate(CARD) shouldBe true
            draw(page, "shade")
            move(page, "cell")
            page.evaluate(DIMMED) shouldBe 0
        }
    }

    @Test
    fun `legend keys contribute no tab stop and clicking their own elements changes nothing`() {
        session { page ->
            draw(page)
            val before = page.evaluate(REST)
            page.evaluate(CLICK)
            page.evaluate(REST) shouldBe before
            page.evaluate("() => ChartTreemap.asked().length") shouldBe 0
            page.evaluate(LEGEND) shouldBe true
            page.locator("#chart").focus()
            page.keyboard().press("Tab")
            page.evaluate("() => !document.activeElement.closest('#chart g.dp-legend')") shouldBe true
        }
    }

    @Test
    fun `Right walks every leaf minimal cards retain values and larger cards show shares`() {
        session { page ->
            for ((w, h) in listOf(80 to 24, 160 to 100, 480 to 300)) {
                draw(page, w = w, h = h)
                page.locator("#chart").focus()
                page.keyboard().press("Escape")
                val names = listOf("North", "South", "East", "West")
                val values = listOf("50 GB", "30 GB", "15 GB", "5 GB")
                val shares = listOf("50%", "30%", "15%", "5%")
                names.indices.forEach { i ->
                    page.keyboard().press("ArrowRight")
                    shown(page) shouldBe
                        if (h == 24) listOf(names[i].take(1) + "…", values[i]) else listOf(names[i], values[i], "Share of all", shares[i])
                }
                page.keyboard().press("ArrowRight")
                page.keyboard().press("ArrowDown")
                shown(page).take(2) shouldBe listOf(if (h == 24) "W…" else "West", "5 GB")
                page.keyboard().press("Home")
                shown(page).take(2) shouldBe listOf(if (h == 24) "N…" else "North", "50 GB")
                page.keyboard().press("End")
                shown(page).take(2) shouldBe listOf(if (h == 24) "W…" else "West", "5 GB")
            }
            draw(page, "many-leaves", 80, 24)
            page.locator("#chart").focus()
            page.keyboard().press("Escape")
            val walked = mutableSetOf<Any?>()
            repeat(120) {
                page.keyboard().press("ArrowRight")
                walked += page.evaluate("() => [...document.querySelectorAll('text.dp-tooltip')].map(t=>t.textContent).join('|')")
            }
            walked.size shouldBe 120
        }
    }

    private fun draw(
        page: Page,
        fixture: String = "plain",
        w: Int = 480,
        h: Int = 300,
    ) {
        page.mouse().move(1.0, 1.0)
        page.evaluate(DRAW, listOf("$fixture/960x540.input.json", w, h)) shouldBe "rendered"
    }

    private fun move(
        page: Page,
        kind: String,
    ) {
        val point = page.evaluate(POINT, kind) as List<*>
        page.mouse().move((point[0] as Number).toDouble(), (point[1] as Number).toDouble())
    }

    private fun shown(page: Page): List<*> = page.evaluate(SHOWN) as List<*>

    private fun session(check: (Page) -> Unit) {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            check(opened.page)
            opened.errors.shouldBeEmpty()
            opened.violations.shouldBeEmpty()
            opened.off.shouldBeEmpty()
            opened.page.locator("[style]").count() shouldBe 0
        } finally {
            opened.context.close()
        }
    }

    companion object {
        private const val VIEW_W = 1200
        private const val VIEW_H = 800
        private val EMITTED = Regex("""treemap/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-treemap.test",
                resources = "chart-treemap",
                script = "chart-treemap.js",
                testTypes = emptyList(),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/$r" else null },
            )
        private var playwright: Playwright? = null
        private lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun start() {
            val pw = Playwright.create()
            playwright = pw
            browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            browser.close()
            playwright?.close()
            playwright = null
        }

        private const val DRAW = """async ([f,w,h]) => (await ChartTreemap.draw(f,{w:w,h:h})).state"""
        private const val SHOWN = """() => [...document.querySelectorAll('text.dp-tooltip')].map(t=>t.textContent)"""
        private val POINT =
            """
            kind => {
              const s=document.getElementById('chart'),b=ChartTreemap.last().index.marks[0].box;
              const p=s.createSVGPoint(); p.x=kind==='gap'?b.x+b.w+1:b.x+b.w/2;p.y=b.y+b.h/2;
              const m=s.getScreenCTM();return [m.a*p.x+m.c*p.y+m.e,m.b*p.x+m.d*p.y+m.f];
            }
            """.trimIndent()
        private val RING =
            """
            () => {
              const b=ChartTreemap.last().index.marks[0].box,r=document.querySelector('#chart rect.dp-guide[stroke]');
              return r && +r.getAttribute('rx')===4 && +r.getAttribute('x')<b.x && +r.getAttribute('width')>b.w;
            }
            """.trimIndent()
        private const val DIMMED = """() => document.querySelectorAll('#chart.dp-dimmed g.dp-mark:not(.dp-active)').length"""
        private val CARD =
            """
            () => {
              const c=ChartTreemap.last().index.marks[0].card,shown=[...document.querySelectorAll('text.dp-tooltip')].map(e=>e.textContent);
              return JSON.stringify(shown)===JSON.stringify([c.heading,...c.rows.flatMap(r=>[r.name,r.value])]);
            }
            """.trimIndent()
        private const val CLICK = """() => document.querySelector('g.dp-legend').dispatchEvent(new MouseEvent('click',{bubbles:true}))"""
        private val REST =
            """
            () => ({prims:ChartTreemap.last().primitives,summary:ChartTreemap.last().summary,
              svg:document.getElementById('chart').outerHTML,
              tabs:[...document.querySelectorAll('[tabindex],button,a[href]')].map(e=>[e.localName,e.getAttribute('tabindex')])})
            """.trimIndent()
        private val LEGEND =
            """
            () => {
              const keys=[...document.querySelectorAll('g.dp-legend')];
              return keys.length===4 && keys.every(e=>!e.hasAttribute('role') && !e.hasAttribute('aria-pressed') && !e.hasAttribute('data-legend') && !e.hasAttribute('tabindex'))
                && ChartTreemap.last().summary.series.every(s=>!s.hidden);
            }
            """.trimIndent()
        private val DOM =
            """
            () => {
              const o=ChartTreemap.last(),svg=document.getElementById('chart'),bad=[];
              const els=[...svg.querySelectorAll('*')].filter(e=>!['title','desc','defs','linearGradient','stop'].includes(e.localName) && !e.closest('.dp-tooltip'));
              const tags={group:'g',rect:'rect',text:'text',line:'line',path:'path'};
              if(els.length!==o.primitives.length)bad.push('element count');
              o.primitives.forEach((p,i)=>{
                const e=els[i];if(!e || e.localName!==tags[p.k]){bad.push('tag '+i);return;}
                if(!e.classList.contains('dp-'+p.role))bad.push('role '+i);
                if(p.k==='text' && e.textContent!==(p.shown===null?p.text:p.shown))bad.push('text '+i);
                const attrs=p.k==='rect'?{x:p.x,y:p.y,width:p.w,height:p.h,rx:p.rx}:p.k==='text'?{x:p.x,y:p.y}:{};
                for(const [a,v] of Object.entries(attrs))if(v!==null && Math.abs(Number(e.getAttribute(a))-v)>0.011)bad.push(a+' '+i);
                if(p.color && e.getAttribute('fill')!==p.color)bad.push('fill '+i);
              });return bad;
            }
            """.trimIndent()
    }
}
