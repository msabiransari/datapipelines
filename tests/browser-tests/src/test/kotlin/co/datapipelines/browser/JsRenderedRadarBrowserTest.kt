package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Radar's real DOM, dot cards, legend redraw and key walk on its own policy-constrained page (#1077). */
class JsRenderedRadarBrowserTest {
    @Test
    fun `the DOM equals the primitives at full compact and minimal`() {
        val s = PAGE.open(browser, VIEW_W, VIEW_H)
        s.context.use {
            for (fixture in listOf("two-series", "one-series", "six-series-twelve-axes", "no-fill", "months", "hostile")) {
                for ((w, h) in listOf(960 to 540, 240 to 160, 80 to 24)) {
                    draw(s, fixture, w, h)
                    (s.page.evaluate(DOM_JS) as List<*>).shouldBeEmpty()
                }
            }
            clean(s)
        }
    }

    @Test
    fun `hover at a dot and 20 px away shows axis card bold row and ring but the polygon interior does not`() {
        val s = PAGE.open(browser, VIEW_W, VIEW_H)
        s.context.use {
            draw(s, "two-series", FULL_W, FULL_H)
            for (dx in listOf(0, NEAR)) {
                @Suppress("UNCHECKED_CAST")
                val p = s.page.evaluate(POINT_JS, dx) as List<Number>
                s.page.mouse().move(p[0].toDouble(), p[1].toDouble())
                s.page.waitForFunction("() => document.querySelector('.dp-tooltip') !== null")
                s.page.evaluate("() => [...document.querySelectorAll('.dp-tooltip text')].map(e=>e.textContent)") shouldBe
                    listOf("Speed", "This quarter", "82", "Last quarter", "70")
                (s.page.evaluate(RING_JS) as List<*>).shouldBeEmpty()
            }
            @Suppress("UNCHECKED_CAST")
            val centre =
                s.page.evaluate(
                    """() => { const r=ChartRadar.last(),p=r.index.plot,b=document.getElementById('chart').getBoundingClientRect();
                return [b.left+p.x+p.w/2,b.top+p.y+p.h/2]; }""",
                ) as List<Number>
            s.page.mouse().move(centre[0].toDouble(), centre[1].toDouble())
            s.page.locator(".dp-tooltip").count() shouldBe 0
            clean(s)
        }
    }

    @Test
    fun `legend switch hides a series and redraws exactly the engine picture`() {
        val s = PAGE.open(browser, VIEW_W, VIEW_H)
        s.context.use {
            draw(s, "two-series", FULL_W, FULL_H)
            s.page
                .locator("g.dp-legend")
                .first()
                .click()
            s.page.mouse().move(OFF, OFF)
            s.page.evaluate("() => ChartRadar.asked()") shouldBe listOf("string:This quarter")
            s.page.evaluate(
                """() => JSON.stringify(ChartRadar.last().primitives) === JSON.stringify(JsRendered.render(
                Object.assign(ChartRadar.input(),{hidden:['string:This quarter']})).primitives)""",
            ) shouldBe true
            (s.page.evaluate(DOM_JS) as List<*>).shouldBeEmpty()
            clean(s)
        }
    }

    @Test
    fun `keys walk axes then nearest series and home end and legend`() {
        val s = PAGE.open(browser, VIEW_W, VIEW_H)
        s.context.use {
            draw(s, "two-series", FULL_W, FULL_H)
            s.page.mouse().move(OFF, OFF)
            s.page.locator("#chart").focus()
            for ((key, heading) in listOf(
                "ArrowRight" to "Speed",
                "ArrowRight" to "Cost",
                "ArrowLeft" to "Speed",
                "ArrowDown" to "Speed",
                "ArrowUp" to "Speed",
                "End" to "Uptime",
                "Home" to "Speed",
            )) {
                s.page.keyboard().press(key)
                s.page
                    .locator(".dp-tooltip text")
                    .first()
                    .textContent() shouldBe heading
                s.page.locator(".dp-focus").count() shouldBe 1
            }
            s.page.keyboard().press("End")
            s.page.keyboard().press("ArrowRight")
            s.page.locator(".dp-focus").count() shouldBe 1
            s.page.keyboard().press("Escape")
            clean(s)
        }
    }

    private fun draw(
        s: ChartTestPage.Opened,
        fixture: String,
        w: Int,
        h: Int,
    ) {
        s.page.evaluate("([f,w,h]) => ChartRadar.draw(f + '/960x540.input.json', {w,h})", listOf(fixture, w, h))
    }

    private fun clean(s: ChartTestPage.Opened) {
        s.errors.shouldBeEmpty()
        s.violations.shouldBeEmpty()
        s.off.shouldBeEmpty()
        s.page.locator("[style]").count() shouldBe 0
    }

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private const val FULL_W = 960
        private const val FULL_H = 540
        private const val NEAR = 20
        private const val OFF = 1200.0
        private val EMITTED = Regex("""radar/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-radar.test",
                resources = "chart-radar",
                script = "chart-radar.js",
                testTypes = emptyList(),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/$r" else null },
            )
        private var playwright: Playwright? = null
        private lateinit var browser: Browser
        private val DOM_JS =
            """
            () => {
              const out=ChartRadar.last(),svg=document.getElementById('chart'),bad=[];
              const els=[...svg.querySelectorAll('*')].filter(e=>!['title','desc','defs','linearGradient','stop'].includes(e.localName));
              const tags={group:'g',rect:'rect',path:'path',text:'text',line:'line',circle:'circle'};
              if(els.length!==out.primitives.length)bad.push('element count');
              out.primitives.forEach((p,i)=>{
                const e=els[i];if(!e||e.localName!==tags[p.k]){bad.push('tag '+i);return;}
                if(p.k==='text'&&e.textContent!==(p.shown??p.text))bad.push('text '+i);
                if(p.k==='path'&&e.getAttribute('d')!==JsRendered.write.pathText(p.d))bad.push('path '+i);
                if(p.k==='circle')for(const k of ['cx','cy','r'])if(Math.abs(Number(e.getAttribute(k))-p[k])>.011)bad.push(k+' '+i);
              });return bad;
            }
            """.trimIndent()
        private const val POINT_JS = """dx => {
          const p=ChartRadar.last().index.marks[0].shape,b=document.getElementById('chart').getBoundingClientRect();return [b.left+p.x+dx,b.top+p.y];}"""
        private const val RING_JS = """() => {
          const e=ChartRadar.last().index.marks[0],g=document.querySelector('circle.dp-guide'),t=JsRendered.theme.resolve('light'),bad=[];
          if(!g)return ['missing ring'];
          for(const [k,v] of Object.entries({cx:e.shape.x,cy:e.shape.y,r:e.shape.r+0.25*t.n('--chart-text')}))if(Math.abs(Number(g.getAttribute(k))-v)>.011)bad.push(k);
          if(g.getAttribute('stroke')!==t.css('--chart-series-1'))bad.push('colour');
          const texts=[...document.querySelectorAll('.dp-tooltip text')];
          if(!texts.some(e=>e.textContent==='82'&&Number(e.getAttribute('font-weight'))===t.n('--chart-title-weight')))bad.push('hovered row not bold');
          return bad;
        }"""

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
    }
}
