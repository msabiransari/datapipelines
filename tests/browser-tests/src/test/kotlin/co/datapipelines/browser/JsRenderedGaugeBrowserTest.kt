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

/** Gauge primitives, pointer card, keyboard and key-only legend on the real CSP page (#1075). */
class JsRenderedGaugeBrowserTest {
    @Test
    fun `DOM matches primitives across levels and hostile text stays text`() {
        session { page ->
            for (fixture in listOf("plain", "bands-target", "target-only", "low-end", "outside-high", "hostile")) {
                for ((w, h) in listOf(360 to 240, 240 to 140, 80 to 24)) {
                    draw(page, fixture, w, h)
                    (page.evaluate(DOM) as List<*>).shouldBeEmpty()
                }
            }
            page.locator("#chart script, #chart img, #chart foreignObject").count() shouldBe 0
        }
    }

    @Test
    fun `hover and six keys show the gauge card and pointer outside the plot hides it`() {
        session { page ->
            draw(page)
            val point = page.evaluate(POINT) as List<*>
            page.mouse().move((point[0] as Number).toDouble(), (point[1] as Number).toDouble())
            shown(page) shouldBe CARD
            page.mouse().move(1.0, 1.0)
            shown(page).shouldBeEmpty()
            page.locator("#chart").focus()
            for (key in listOf("ArrowRight", "ArrowDown", "ArrowUp", "ArrowLeft", "Home", "End")) {
                page.keyboard().press(key)
                shown(page) shouldBe CARD
            }
            draw(page, w = 80, h = 24)
            page.locator("#chart").focus()
            page.keyboard().press("ArrowRight")
            shown(page) shouldBe listOf("Now", "62")
        }
    }

    @Test
    fun `tone and target keys never switch hide dim or take focus and dark tones follow tokens`() {
        session { page ->
            draw(page)
            page.evaluate(LEGEND) shouldBe true
            page.evaluate(PLANT)
            page.evaluate(LEGEND) shouldBe false
            draw(page)
            for (label in listOf("Bad", "Target")) {
                val before = page.evaluate(REST)
                page.evaluate(CLICK, label)
                page.evaluate(REST) shouldBe before
                page.evaluate(LEGEND) shouldBe true
                page.evaluate("() => ChartGauge.asked().length") shouldBe 0
            }
            page.evaluate(DARK)
            page.evaluate(TONES) shouldBe true
        }
    }

    private fun draw(
        page: Page,
        fixture: String = "bands-target",
        w: Int = 360,
        h: Int = 240,
    ) {
        page.evaluate(DRAW, listOf("$fixture/960x540.input.json", w, h)) shouldBe "rendered"
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
        private val CARD = listOf("Uptime", "Now", "62", "Target", "70", "Bad", "to 50", "Watch", "to 80", "Good", "to 100")
        private val EMITTED = Regex("""gauge/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-gauge.test",
                resources = "chart-gauge",
                script = "chart-gauge.js",
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

        private const val DRAW = """async ([f,w,h]) => (await ChartGauge.draw(f,{w:w,h:h})).state"""
        private val DARK =
            """
            async () => (await ChartGauge.draw('bands-target/960x540.input.json',{w:360,h:240,theme:'dark'})).state
            """.trimIndent()
        private const val SHOWN = """() => [...document.querySelectorAll('text.dp-tooltip')].map(t=>t.textContent)"""
        private val CLICK =
            """
            label => [...document.querySelectorAll('g.dp-legend')].find(e=>e.textContent===label).dispatchEvent(new MouseEvent('click',{bubbles:true}))
            """.trimIndent()
        private val PLANT =
            """
            () => { const g=document.querySelector('g.dp-legend');g.setAttribute('role','button');g.setAttribute('aria-pressed','true'); }
            """.trimIndent()
        private val REST =
            """
            () => [...document.querySelectorAll('#chart .dp-mark,#chart .dp-guide,#chart .dp-legend')].map(e=>e.outerHTML)
            """.trimIndent()
        private val LEGEND =
            """
            () => {
              const keys=[...document.querySelectorAll('g.dp-legend')];
              return keys.length===4 && keys.every(e=>!e.hasAttribute('role') && !e.hasAttribute('aria-pressed') && !e.hasAttribute('data-legend') && !e.hasAttribute('tabindex'))
                && !document.querySelector('#chart .dp-off') && ChartGauge.last().summary.marks===1;
            }
            """.trimIndent()
        private val TONES =
            """
            () => {
              const R=JsRendered,t=R.theme.resolve('dark'),o=ChartGauge.last();
              return ['--chart-good','--chart-warn','--chart-bad'].every(k=>o.primitives.some(p=>p.role==='guide' && p.stroke===k && p.strokeColor===t.css(k)));
            }
            """.trimIndent()
        private val POINT =
            """
            () => {
              const s=document.getElementById('chart'),p=s.createSVGPoint(),a=ChartGauge.last().primitives.find(p=>p.role==='mark' && p.k==='path');
              p.x=a.d[1];p.y=a.d[2];const m=s.getScreenCTM();return [m.a*p.x+m.c*p.y+m.e,m.b*p.x+m.d*p.y+m.f];
            }
            """.trimIndent()
        private val DOM =
            """
            () => {
              const o=ChartGauge.last(),svg=document.getElementById('chart'),bad=[],R=JsRendered;
              const els=[...svg.querySelectorAll('*')].filter(e=>!['title','desc','defs','linearGradient','stop'].includes(e.localName) && !e.closest('.dp-tooltip'));
              const tags={group:'g',rect:'rect',text:'text',path:'path',line:'line'};
              if(els.length!==o.primitives.length)bad.push('element count');
              o.primitives.forEach((p,i)=>{
                const e=els[i];if(!e || e.localName!==tags[p.k]){bad.push('tag '+i);return;}
                if(!e.classList.contains('dp-'+p.role))bad.push('role '+i);
                if(p.k==='text' && e.textContent!==(p.shown===null?p.text:p.shown))bad.push('text '+i);
                const attrs=p.k==='line'?{x1:p.x1,y1:p.y1,x2:p.x2,y2:p.y2}:p.k==='text'?{x:p.x,y:p.y}:{};
                for(const [a,v] of Object.entries(attrs))if(Math.abs(Number(e.getAttribute(a))-v)>0.011)bad.push(a+' '+i);
                if(p.stroke && (e.getAttribute('stroke')!==p.strokeColor || Math.abs(Number(e.getAttribute('stroke-width'))-p.strokeWidth)>0.011))bad.push('stroke '+i);
                if(p.k==='path' && e.getAttribute('d')!==R.write.pathText(p.d))bad.push('path '+i);
              });return bad;
            }
            """.trimIndent()
    }
}
