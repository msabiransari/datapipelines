package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Histogram's DOM, bin cards, gap hits, switches and series keyboard rows through the product layer (#1071 A.7). */
class JsRenderedHistogramBrowserTest {
    @Test
    fun `the DOM equals primitives across histogram forms and sizes`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            for (fixture in listOf("one-series", "many-series", "share", "skewed-thin", "hostile", "integers-small", "bins-50")) {
                for ((w, h) in listOf(960 to 540, 240 to 160, 80 to 24)) {
                    draw(opened, fixture, w, h)
                    withClue("$fixture $w x $h") { opened.page.evaluate(DOM_JS).shouldBeEmptyResult() }
                }
            }
            clean(opened)
        } finally {
            opened.context.close()
        }
    }

    @Test
    fun `cards include the gap and top-first counts then switches refit and dim`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            val page = opened.page
            draw(opened, "many-series", 960, 540)
            val point = page.evaluate(POINT_JS) as List<*>
            page.mouse().move((point[0] as Number).toDouble(), (point[1] as Number).toDouble())
            val expected =
                listOf(
                    "from 40 ms to 60 ms",
                    "Delta",
                    "5",
                    "Hills",
                    "3",
                    "Coast",
                    "4",
                    "Central",
                    "2",
                    "West",
                    "4",
                    "East",
                    "1",
                    "South",
                    "3",
                    "North",
                    "5",
                )
            page.locator("text.dp-tooltip").allTextContents() shouldBe expected
            page.locator("text.dp-tooltip[font-weight='600']").allTextContents() shouldBe
                listOf("5", "3", "4", "2", "4", "1", "3", "North", "5")
            // A pointer in the physically unpainted gap, within the North segments on each side of edge 60.
            val gap = page.evaluate(GAP_JS) as List<*>
            page.mouse().move((gap[0] as Number).toDouble(), (gap[1] as Number).toDouble())
            page.locator("text.dp-tooltip").allTextContents() shouldBe expected
            page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
            val ticks = page.locator("text.dp-tick[text-anchor='end']").allTextContents()
            val north = page.locator("g.dp-legend[data-legend='string:North']")
            north.hover()
            page.locator("#chart.dp-dimmed").count() shouldBe 1
            page.locator("g.dp-mark.dp-active").count() shouldBe 1
            north.click()
            page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
            page.locator("g.dp-mark[data-series='string:North']").count() shouldBe 0
            page.evaluate(HIDDEN_DOM_JS).shouldBeEmptyResult()
            page.locator("text.dp-tick[text-anchor='end']").allTextContents() shouldNotBe ticks
            clean(opened)
        } finally {
            opened.context.close()
        }
    }

    @Test
    fun `keys enter series-major and walk bins and every series at full and minimal`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            val page = opened.page
            for ((w, h) in listOf(960 to 540, 80 to 24)) {
                draw(opened, "many-series", w, h)
                page.locator("#chart").focus()
                for ((key, cell) in KEY_PLAN) {
                    page.keyboard().press(key)
                    withClue("$w x $h $key $cell") { page.evaluate(KEY_JS, cell).shouldBeEmptyResult() }
                }
                page.keyboard().press("Escape")
                page.keyboard().press("ArrowRight")
                for (series in listOf("South", "East", "West", "Central", "Coast", "Hills", "Delta")) {
                    page.keyboard().press("ArrowDown")
                    page.evaluate(KEY_JS, "$series/2").shouldBeEmptyResult()
                }
                page.keyboard().press("Escape")
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
        opened.page.evaluate("([f,w,h]) => ChartHistogram.draw(f + '/960x540.input.json', {w,h})", listOf(fixture, w, h))
        opened.page.evaluate("() => { window.drawn = ChartHistogram.last(); }")
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
        private val KEY_PLAN =
            listOf(
                "ArrowRight" to "North/2",
                "ArrowDown" to "South/2",
                "ArrowDown" to "East/2",
                "ArrowUp" to "South/2",
                "ArrowRight" to "South/3",
                "ArrowLeft" to "South/2",
                "End" to "South/7",
                "Home" to "South/1",
            )
        private val EMITTED = Regex("""histogram/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-histogram.test",
                resources = "chart-histogram",
                script = "chart-histogram.js",
                testTypes = emptyList(),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/$r" else null },
            )
        private var playwright: Playwright? = null
        private lateinit var browser: Browser

        private val DOM_BODY =
            """
            const svg = document.getElementById('chart'), bad = [];
            const els = [...svg.querySelectorAll('*')].filter(e => !['title','desc','defs','linearGradient','stop'].includes(e.localName));
            const tags = {group:'g',rect:'rect',path:'path',text:'text',line:'line',circle:'circle'};
            if (els.length !== out.primitives.length) bad.push('element count ' + els.length + '/' + out.primitives.length);
            out.primitives.forEach((p,i) => {
              const e = els[i]; if (!e || e.localName !== tags[p.k]) { bad.push('tag at ' + i); return; }
              if (!(e.getAttribute('class') || '').split(' ').includes('dp-' + p.role)) bad.push('role at ' + i);
              if (p.k === 'text' && e.textContent !== (p.shown ?? p.text)) bad.push('text at ' + i);
              const attrs = p.k === 'rect' ? {x:p.x,y:p.y,width:p.w,height:p.h} : p.k === 'line' ? {x1:p.x1,y1:p.y1,x2:p.x2,y2:p.y2} : {};
              Object.entries(attrs).forEach(([k,v]) => { if (Math.abs(Number(e.getAttribute(k))-v)>0.011) bad.push(k + ' at ' + i); });
            });
            return bad;
            """.trimIndent()
        private val DOM_JS = "() => { const out = ChartHistogram.last(); $DOM_BODY }"
        private val HIDDEN_DOM_JS =
            """
            () => {
              const input = ChartHistogram.input(); input.hidden = ['string:North'];
              const out = JsRendered.render(input);
              $DOM_BODY
            }
            """.trimIndent()
        private const val POINT_JS = """() => {
          const svg = document.getElementById('chart'), b = svg.getBoundingClientRect(), out = ChartHistogram.last();
          const e = out.index.marks[0].shape;
          return [b.left + e.x + e.w/2, b.top + e.y + e.h/2];
        }"""
        private const val GAP_JS = """() => {
          const svg = document.getElementById('chart'), b = svg.getBoundingClientRect(), marks = ChartHistogram.last().index.marks;
          const e = marks.find(e => e.series === 'string:North' && e.datum === 2).shape;
          const next = marks.find(e => e.series === 'string:North' && e.datum === 3).shape;
          return [b.left + e.x + e.w - 0.01, b.top + Math.max(e.y,next.y) + 0.1];
        }"""
        private const val KEY_JS = """cell => {
          const out = ChartHistogram.last(), svg = document.getElementById('chart'), [series,datum] = cell.split('/');
          const e = out.index.marks.find(e => e.series === 'string:' + series && e.datum === Number(datum));
          const f = svg.querySelector('.dp-focus'), bad = [];
          if (!e || !f) return ['missing cell or focus'];
          const b = e.shape, x = Number(f.getAttribute('x')), y = Number(f.getAttribute('y'));
          if (Math.abs(x + Number(f.getAttribute('width'))/2 - b.x - b.w/2)>0.02 || Math.abs(y + Number(f.getAttribute('height'))/2 - b.y - b.h/2)>0.02) bad.push('focus not on ' + cell);
          if (out.frame.h > 24) {
            const want = [e.card.heading].concat(e.card.rows.flatMap(r => [r.name,r.value]));
            const got = [...svg.querySelectorAll('text.dp-tooltip')].map(t => t.textContent);
            if (JSON.stringify(got) !== JSON.stringify(want)) bad.push('card for ' + cell + ': ' + got);
          }
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
