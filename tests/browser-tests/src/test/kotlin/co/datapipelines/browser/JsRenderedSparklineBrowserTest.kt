package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Sparkline's bare DOM, end value, crosshair and selected-point cards with both inputs (#1076, LA15/LA17). */
class JsRenderedSparklineBrowserTest {
    @Test
    fun `the DOM equals primitives at the two smallest frames and the fit rule keeps its value`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            for (fixture in listOf("rising", "flat", "null-gap", "one-point", "dot-only", "last-none", "hostile")) {
                for ((w, h) in listOf(160 to 32, 80 to 24)) {
                    draw(opened, fixture, w, h)
                    withClue("$fixture $w x $h") { opened.page.evaluate(DOM_JS).shouldBeEmptyResult() }
                    opened.page.locator(".dp-axis, .dp-legend, .dp-title").count() shouldBe 0
                }
            }
            draw(opened, "rising", 160, 32)
            opened.page.locator("text.dp-guide").allTextContents() shouldBe listOf("40")
            opened.page.evaluate(
                """() => {
                  const input = ChartSparkline.input(); input.columns.v = input.columns.v.map(() => 1700000);
                  return ChartSparkline.drawInput(input, {w:80,h:24});
                }""",
            )
            opened.page.locator("text.dp-guide").count() shouldBe 0
            opened.page.locator("circle.dp-mark").count() shouldBe 1
            clean(opened)
        } finally {
            opened.context.close()
        }
    }

    @Test
    fun `pointer and keys retain an interior value with the crosshair at every card budget`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            val page = opened.page
            val cases = listOf(24, 39, 55, 70, 86, 320).flatMap { h -> listOf(h to "key", h to "pointer") }
            for ((h, input) in cases) {
                page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
                draw(opened, "rising", 480, h)
                if (input == "key") {
                    page.locator("#chart").focus()
                    page.keyboard().press("Escape")
                    repeat(5) { page.keyboard().press("ArrowRight") }
                } else {
                    val point = page.evaluate(POINT_JS) as List<*>
                    page.mouse().move((point[0] as Number).toDouble(), (point[1] as Number).toDouble())
                }
                val expected =
                    when (h) {
                        24, 39 -> listOf("v", "30")
                        55, 70 -> listOf("2024-05-01", "v", "30", "+3 more")
                        else -> listOf("2024-05-01", "v", "30", "Last", "40", "Highest", "40", "Lowest", "12")
                    }
                withClue("$h $input") {
                    page.locator("text.dp-tooltip").allTextContents() shouldBe expected
                    page.evaluate(CROSSHAIR_JS).shouldBeEmptyResult()
                }
                page.keyboard().press("Escape")
            }
            page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
            page.locator(".dp-tooltip, circle.dp-guide, line.dp-guide").count() shouldBe 0
            clean(opened)
        } finally {
            opened.context.close()
        }
    }

    @Test
    fun `keys walk the smallest frame and up and down keep the selected point`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            val page = opened.page
            draw(opened, "rising", 80, 24)
            page.locator("#chart").focus()
            for (v in listOf("12", "18", "15", "22", "30", "28", "35", "31", "26", "24", "29", "40")) {
                page.keyboard().press("ArrowRight")
                page.locator("text.dp-tooltip").allTextContents() shouldBe listOf("v", v)
            }
            for (key in listOf("ArrowRight", "ArrowDown", "ArrowUp")) {
                page.keyboard().press(key)
                page.locator("text.dp-tooltip").allTextContents() shouldBe listOf("v", "40")
            }
            clean(opened)
        } finally {
            opened.context.close()
        }
    }

    private fun Any?.shouldBeEmptyResult() {
        @Suppress("UNCHECKED_CAST")
        (this as List<String>).shouldBeEmpty()
    }

    private fun draw(
        opened: ChartTestPage.Opened,
        fixture: String,
        w: Int,
        h: Int,
    ) {
        opened.page.evaluate("([f,w,h]) => ChartSparkline.draw(f + '/960x540.input.json', {w,h})", listOf(fixture, w, h))
    }

    private fun clean(opened: ChartTestPage.Opened) {
        opened.errors.shouldBeEmpty()
        opened.violations.shouldBeEmpty()
        opened.off.shouldBeEmpty()
        opened.page.locator("[style]").count() shouldBe 0
    }

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private val EMITTED = Regex("""sparkline/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-sparkline.test",
                resources = "chart-sparkline",
                script = "chart-sparkline.js",
                testTypes = emptyList(),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/$r" else null },
            )
        private var playwright: Playwright? = null
        private lateinit var browser: Browser
        private val DOM_JS =
            """
            () => {
              const out = ChartSparkline.last(), svg = document.getElementById('chart'), bad = [];
              const els = [...svg.querySelectorAll('*')].filter(e => !['title','desc','defs','linearGradient','stop'].includes(e.localName));
              const tags = {group:'g',rect:'rect',path:'path',text:'text',line:'line',circle:'circle'};
              if (els.length !== out.primitives.length) bad.push('element count');
              out.primitives.forEach((p,i) => {
                const e=els[i]; if (!e || e.localName !== tags[p.k]) {bad.push('tag '+i);return;}
                if (!(e.getAttribute('class')||'').split(' ').includes('dp-'+p.role)) bad.push('role '+i);
                if (p.k==='text' && e.textContent !== (p.shown ?? p.text)) bad.push('text '+i);
                const attrs=p.k==='circle'?{cx:p.cx,cy:p.cy,r:p.r}:p.k==='rect'?{x:p.x,y:p.y,width:p.w,height:p.h}:{};
                Object.entries(attrs).forEach(([k,v])=>{if(Math.abs(Number(e.getAttribute(k))-v)>.011)bad.push(k+' '+i);});
                if(p.k==='path' && e.getAttribute('d') !== JsRendered.write.pathText(p.d)) bad.push('path '+i);
              });
              return bad;
            }
            """.trimIndent()
        private const val POINT_JS = """() => {
          const out=ChartSparkline.last(), b=document.getElementById('chart').getBoundingClientRect();
          return [b.left+out.index.series[0].at[4],b.top+out.index.plot.y+out.index.plot.h/2];
        }"""
        private const val CROSSHAIR_JS = """() => {
          const out=ChartSparkline.last(), svg=document.getElementById('chart'), p=out.index.plot;
          const dot=svg.querySelector('circle.dp-guide'), line=svg.querySelector('line.dp-guide'), bad=[];
          const x=out.index.series[0].at[4], y=p.y+p.h*(40-30)/(40-12);
          if(!dot||!line)return ['missing crosshair'];
          if(Math.abs(Number(dot.getAttribute('cx'))-x)>.02||Math.abs(Number(dot.getAttribute('cy'))-y)>.02)bad.push('dot');
          if(Math.abs(Number(line.getAttribute('x1'))-x)>.02||Math.abs(Number(line.getAttribute('x2'))-x)>.02)bad.push('line');
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
