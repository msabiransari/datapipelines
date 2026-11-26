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

/** Funnel's actual writer and interaction layer, on its own CSP page without an application (#1074 A.7). */
class JsRenderedFunnelBrowserTest {
    @Test
    fun `DOM equals primitives at full compact and minimal levels`() {
        session { page ->
            for (fixture in listOf("basic", "all-zero", "steep", "rising", "twelve", "hostile", "long-names")) {
                for ((w, h) in listOf(480 to 320, 240 to 140, 80 to 24)) {
                    draw(page, fixture, w, h)
                    withClue("$fixture $w x $h") {
                        @Suppress("UNCHECKED_CAST")
                        val problems = page.evaluate(DOM) as List<String>
                        problems.shouldBeEmpty()
                    }
                }
            }
        }
    }

    @Test
    fun `hover on bar or name shows stage card and outside plot shows none`() {
        session { page ->
            draw(page, "basic")
            for (name in listOf(false, true)) {
                val point = page.evaluate(POINT, name) as List<*>
                page.mouse().move((point[0] as Number).toDouble(), (point[1] as Number).toDouble())
                shown(page) shouldBe SECOND_CARD
            }
            // Inside the SVG but outside the none frame's plot.
            val point = page.evaluate(OUTSIDE) as List<*>
            page.mouse().move((point[0] as Number).toDouble(), (point[1] as Number).toDouble())
            shown(page).shouldBeEmpty()
        }
    }

    @Test
    fun `Down and Up walk stages and Right walks cut names with Left returning to stage zero`() {
        session { page ->
            draw(page, "basic")
            page.locator("#chart").focus()
            val headings = listOf("Visited", "Signed up", "Activated", "Subscribed", "Renewed")
            page.keyboard().press("ArrowRight")
            shown(page).first() shouldBe headings.first()
            for (heading in headings.drop(1)) {
                page.keyboard().press("ArrowDown")
                shown(page).first() shouldBe heading
            }
            page.keyboard().press("ArrowUp")
            shown(page).first() shouldBe "Subscribed"
            draw(page, "long-names", 360, 240)
            page.locator("#chart").focus()
            page.keyboard().press("ArrowDown")
            page.keyboard().press("ArrowRight")
            val cuts = page.evaluate(CUTS) as List<*>
            cuts.size shouldBe CUT_COUNT
            shown(page) shouldBe listOf(cuts[0])
            page.keyboard().press("ArrowRight")
            shown(page) shouldBe listOf(cuts[1])
            page.keyboard().press("ArrowLeft")
            page.keyboard().press("ArrowLeft")
            val first = page.evaluate("() => ChartFunnel.last().index.marks[0].card.heading")
            shown(page).first() shouldBe first
        }
    }

    private fun draw(
        page: Page,
        fixture: String,
        w: Int = 480,
        h: Int = 320,
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
        private const val CUT_COUNT = 2
        private val SECOND_CARD = listOf("Signed up", "Sum of v", "5,300", "Of the first step", "42.7%", "Of the step before", "42.7%")
        private val EMITTED = Regex("""funnel/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-funnel.test",
                resources = "chart-funnel",
                script = "chart-funnel.js",
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

        private const val DRAW = """async ([f,w,h]) => (await ChartFunnel.draw(f,{w:w,h:h})).state"""
        private const val SHOWN = """() => [...document.querySelectorAll('text.dp-tooltip')].map(t=>t.textContent)"""
        private val CUTS =
            """
            () => ChartFunnel.last().primitives.filter(p=>p.k==='text' && p.shown && p.shown!==p.text).map(p=>p.text)
            """.trimIndent()
        private val POINT =
            """
            (name) => {
              const o=ChartFunnel.last(), svg=document.getElementById('chart');
              const p=name ? o.primitives.filter(p=>p.k==='text' && p.role==='tick')[1]
                : o.primitives.filter(p=>p.k==='rect' && p.role==='mark')[1];
              const q=svg.createSVGPoint();
              q.x=name ? p.x-10 : p.x+p.w/2; q.y=name ? p.y-4 : p.y+p.h/2;
              const m=svg.getScreenCTM(); return [m.a*q.x+m.c*q.y+m.e,m.b*q.x+m.d*q.y+m.f];
            }
            """.trimIndent()
        private val OUTSIDE =
            """
            () => {
              const s=document.getElementById('chart'),p=s.createSVGPoint(); p.x=1;p.y=1;
              const m=s.getScreenCTM();return [m.a*p.x+m.c*p.y+m.e,m.b*p.x+m.d*p.y+m.f];
            }
            """.trimIndent()
        private val DOM =
            """
            () => {
              const o=ChartFunnel.last(), svg=document.getElementById('chart'), bad=[];
              const els=[...svg.querySelectorAll('*')].filter(e=>!['title','desc','defs','linearGradient','stop'].includes(e.localName) && !e.closest('.dp-tooltip'));
              const tags={group:'g',rect:'rect',text:'text'};
              if(els.length!==o.primitives.length) bad.push('element count');
              o.primitives.forEach((p,i)=>{
                const e=els[i]; if(!e || e.localName!==tags[p.k]) {bad.push('tag '+i);return;}
                if(!e.classList.contains('dp-'+p.role)) bad.push('role '+i);
                if(p.k==='text' && e.textContent!==(p.shown===null?p.text:p.shown)) bad.push('text '+i);
                const attrs=p.k==='rect'?{x:p.x,y:p.y,width:p.w,height:p.h,rx:p.rx}:p.k==='text'?{x:p.x,y:p.y}:{};
                for(const [a,v] of Object.entries(attrs)) if(Math.abs(Number(e.getAttribute(a))-v)>0.011) bad.push(a+' '+i);
                if(p.fill && e.getAttribute('fill')!==p.color) bad.push('fill '+i);
              });
              return bad;
            }
            """.trimIndent()
    }
}
