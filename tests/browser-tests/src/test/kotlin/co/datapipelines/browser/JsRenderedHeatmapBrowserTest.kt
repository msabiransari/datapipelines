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

/** Heatmap's real Chromium checks: DOM/primitive parity, slot hover, fixed card, ring, grid keys and fitted labels. */
class JsRenderedHeatmapBrowserTest {
    @Test
    fun `A7 cells labels and chrome in the DOM equal the engine primitives`() {
        withPage { opened ->
            for (fixture in listOf("grid-4x3", "missing-cells", "labels-values", "labels-diverging", "hostile")) {
                for ((w, h) in listOf(960 to 540, 360 to 240, 80 to 24)) {
                    draw(opened.page, fixture, w, h)
                    withClue("$fixture at $w x $h") {
                        @Suppress("UNCHECKED_CAST")
                        val bad = opened.page.evaluate(DOM) as List<String>
                        bad.shouldBeEmpty()
                    }
                }
            }
            clean(opened)
        }
    }

    @Test
    fun `A7 a slot including its gap opens the cell card and colour ring and outside the plot clears them`() {
        withPage { opened ->
            val page = opened.page
            draw(page, "grid-4x3", DRAW_W, DRAW_H)
            for (gap in listOf(false, true)) {
                @Suppress("UNCHECKED_CAST")
                val p = page.evaluate(PLACE, gap) as List<Number>
                page.mouse().move(p[0].toDouble(), p[1].toDouble())
                withClue("slot gap=$gap") { checkCard(page, 0, listOf("r01, c01", "v", "0")) }
            }
            page.mouse().move((VIEW_W - 2).toDouble(), (VIEW_H - 2).toDouble())
            page.locator("text.dp-tooltip").count() shouldBe 0
            page.evaluate(
                "() => [...document.querySelectorAll('#chart .dp-guide')].filter(e => e.getAttribute('fill') === 'none').length",
            ) shouldBe
                0
            clean(opened)
        }
    }

    @Test
    fun `occupied cell hover dims only the other cells and clears on empty space and leave`() {
        withPage { opened ->
            val page = opened.page
            draw(page, "missing-cells", DRAW_W, DRAW_H)
            for (datum in listOf(0, 1, -2, 0, -1)) {
                @Suppress("UNCHECKED_CAST")
                val pos = page.evaluate(HOVER_PLACE, datum) as List<Number>
                page.mouse().move(pos[0].toDouble(), pos[1].toDouble())
                page.waitForFunction(OPACITY, datum)
                if (datum >= 0) {
                    @Suppress("UNCHECKED_CAST")
                    val expected = page.evaluate(EXPECTED_CARD, datum) as List<String>
                    checkCard(page, datum, expected)
                } else {
                    page.locator("text.dp-tooltip").count() shouldBe 0
                    page.locator("#chart.dp-dimmed, #chart .dp-active").count() shouldBe 0
                }
                println("hover datum=$datum: ${page.evaluate(OPACITY_READ)}")
            }
            clean(opened)
        }
    }

    @Test
    fun `fitted labels and cells fade together for pointer and keyboard selection in both themes`() {
        withPage { opened ->
            val page = opened.page
            for (theme in listOf("light", "dark")) {
                for (fixture in listOf("labels-values", "missing-cells")) {
                    page.evaluate(DRAW_LABELS, listOf(fixture, theme)) shouldBe "rendered"
                    page.waitForFunction(PAIR_OPACITY, -1)
                    for (datum in listOf(0, 1, -2, 0, -1)) {
                        @Suppress("UNCHECKED_CAST")
                        val pos = page.evaluate(HOVER_PLACE, datum) as List<Number>
                        page.mouse().move(pos[0].toDouble(), pos[1].toDouble())
                        checkPairs(page, theme, fixture, "pointer", datum)
                    }
                    page.locator("#chart").focus()
                    for ((key, datum) in listOf("ArrowRight" to 0, "ArrowRight" to 1, "ArrowDown" to -3, "Escape" to -1)) {
                        page.keyboard().press(key)
                        // Down follows the actual grid; it has a different column count in each fixture.
                        val selected = if (datum == -3) (if (fixture == "labels-values") 8 else 5) else datum
                        checkPairs(page, theme, fixture, key, selected)
                    }
                    page.keyboard().press("ArrowRight")
                    checkPairs(page, theme, fixture, "key before blur", 0)
                    page.locator("#chart").evaluate("e => e.blur()")
                    checkPairs(page, theme, fixture, "blur", -1)
                }
            }
            clean(opened)
        }
    }

    private fun checkPairs(
        page: Page,
        theme: String,
        fixture: String,
        action: String,
        datum: Int,
    ) {
        withClue("$theme $fixture $action datum=$datum: ${page.evaluate(PAIR_READ)}") {
            page.waitForFunction(PAIR_OPACITY, datum)
            if (datum >= 0) {
                @Suppress("UNCHECKED_CAST")
                val expected = page.evaluate(EXPECTED_CARD, datum) as List<String>
                checkCard(page, datum, expected)
            } else {
                page.locator("text.dp-tooltip").count() shouldBe 0
                page.locator("#chart.dp-dimmed, #chart .dp-active, #chart .dp-inactive").count() shouldBe 0
            }
        }
        println("pairs $theme $fixture $action datum=$datum: ${page.evaluate(PAIR_READ)}")
    }

    @Test
    fun `A7 keys walk rows and columns without wrapping and card and ring follow`() {
        withPage { opened ->
            val page = opened.page
            draw(page, "grid-4x3", DRAW_W, DRAW_H)
            page.evaluate(
                "() => window.ChartHeatmap.last().primitives.filter(p => p.k === 'text' && p.shown !== p.text && p.shown !== null).length",
            ) shouldBe
                0
            page.locator("#chart").focus()
            val plan =
                listOf(
                    "ArrowRight" to 0,
                    "ArrowRight" to 1,
                    "ArrowDown" to 5,
                    "ArrowUp" to 1,
                    "End" to 3,
                    "ArrowRight" to 3,
                    "Home" to 0,
                    "ArrowLeft" to 0,
                )
            for ((key, datum) in plan) {
                page.keyboard().press(key)
                val row = datum / COLS + 1
                val col = datum % COLS + 1
                checkCard(page, datum, listOf("r0$row, c0$col", "v", datum.toString()))
            }
            clean(opened)
        }
    }

    @Test
    fun `A7 minimal frame still steps cells and shows the kept value row`() {
        withPage { opened ->
            val page = opened.page
            draw(page, "grid-4x3", MIN_W, MIN_H)
            page.locator("#chart").focus()
            for ((key, datum) in listOf("ArrowRight" to 0, "ArrowRight" to 1, "ArrowDown" to 5)) {
                page.keyboard().press(key)
                checkCard(page, datum, listOf("v", datum.toString()))
            }
            clean(opened)
        }
    }

    private fun withPage(block: (ChartTestPage.Opened) -> Unit) {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        try {
            block(opened)
        } finally {
            opened.context.close()
        }
    }

    private fun draw(
        page: Page,
        fixture: String,
        w: Int,
        h: Int,
    ) {
        page.evaluate(DRAW, listOf("$fixture/960x540.input.json", w, h)) shouldBe "rendered"
    }

    private fun checkCard(
        page: Page,
        datum: Int,
        expected: List<String>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val read = page.evaluate(CARD, datum) as Map<String, Any?>
        withClue("datum $datum: $read") {
            read["texts"] shouldBe expected
            read["ring"] shouldBe true
        }
    }

    private fun clean(opened: ChartTestPage.Opened) {
        opened.errors.toList().shouldBeEmpty()
        opened.violations.toList().shouldBeEmpty()
        opened.off.toList().shouldBeEmpty()
        opened.page.locator("[style]").count() shouldBe 0
    }

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private const val DRAW_W = 960
        private const val DRAW_H = 540
        private const val COLS = 4
        private const val MIN_W = 80
        private const val MIN_H = 24
        private val EMITTED = Regex("""heatmap/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-heatmap.test",
                resources = "chart-heatmap",
                script = "chart-heatmap.js",
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

        private val DRAW =
            """
            async ([file,w,h]) => {
              const out = await window.ChartHeatmap.draw(file,{w,h});
              window.__heatmapWritten = new Set(document.querySelectorAll('#chart *'));
              return out.state;
            }
            """.trimIndent()
        private val DRAW_LABELS =
            """
            async ([fixture,theme]) => {
              const out=await window.ChartHeatmap.draw(fixture+'/960x540.input.json',{w:960,h:540},{theme,labels:'values'});
              window.__heatmapWritten=new Set(document.querySelectorAll('#chart *'));
              return out.state;
            }
            """.trimIndent()
        private val DOM =
            """
            () => {
              const out = window.ChartHeatmap.last(), svg = document.getElementById('chart'), bad = [];
              const els = [...svg.querySelectorAll('*')].filter(e => !(e.tagName === 'linearGradient' || e.tagName === 'stop' ||
                (e.parentNode === svg && (e.tagName === 'title' || e.tagName === 'desc'))));
              const tags = {rect:'rect',line:'line',path:'path',circle:'circle',text:'text',group:'g'};
              if (els.length !== out.primitives.length) bad.push('element count');
              out.primitives.forEach((p,i) => {
                const e = els[i];
                if (!e || e.tagName !== tags[p.k] || !e.classList.contains('dp-'+p.role)) {bad.push('primitive '+i); return;}
                if (p.k === 'text' && e.textContent !== (p.shown ?? p.text)) bad.push('text '+i);
                if (p.k === 'rect') for (const [a,k] of [['x','x'],['y','y'],['width','w'],['height','h'],['rx','rx']]) {
                  if (Math.abs(Number(e.getAttribute(a))-p[k]) > 0.011) bad.push(a+' '+i);
                }
              });
              const values = out.primitives.filter(p => p.k === 'text' && p.role === 'guide');
              if (out.level === 'minimal' && values.length) bad.push('minimal labels');
              const t = window.JsRendered.theme.resolve(out.theme);
              for (const p of values) {
                const e = els[out.primitives.indexOf(p)];
                if (!['--chart-on-fill-light','--chart-on-fill-dark'].includes(p.fill)) bad.push('on-fill token');
                if (getComputedStyle(e).fill !== 'rgb('+t.colour(p.fill).join(', ')+')') bad.push('computed label ink');
              }
              if (out.level === 'full' && window.ChartHeatmap.input().document.config.mark.labels === 'values' && !values.length) bad.push('no fitted labels');
              return bad;
            }
            """.trimIndent()
        private val PLACE =
            """
            gap => {
              const out=window.ChartHeatmap.last(), b=out.index.marks[0].shape;
              const svg=document.getElementById('chart'), r=svg.getBoundingClientRect();
              const x=gap ? b.x+b.w-0.25 : b.x+b.w/2, y=b.y+b.h/2;
              return [r.left+x*r.width/out.frame.w,r.top+y*r.height/out.frame.h];
            }
            """.trimIndent()
        private val CARD =
            """
            datum => {
              const out=window.ChartHeatmap.last(), svg=document.getElementById('chart');
              const box=out.index.marks.find(m=>m.datum===datum).shape;
              const mark=out.primitives.find(p=>p.role==='mark' && p.datum===datum);
              const rings=[...svg.querySelectorAll('rect.dp-guide')].filter(e=>!window.__heatmapWritten.has(e));
              const gap=0.25*window.JsRendered.theme.resolve(out.theme).n("--chart-text"), ring=rings.find(e=>Math.abs(Number(e.getAttribute('x'))-(box.x-gap))<0.02 &&
                Math.abs(Number(e.getAttribute('y'))-(box.y-gap))<0.02 && Math.abs(Number(e.getAttribute('width'))-(box.w+2*gap))<0.02 &&
                getComputedStyle(e).stroke === 'rgb('+window.JsRendered.theme.resolve(out.theme).colour(mark.fill).join(', ')+')');
              return {texts:[...svg.querySelectorAll('text.dp-tooltip')].map(e=>e.textContent),ring:!!ring};
            }
            """.trimIndent()
        private val HOVER_PLACE =
            """
            datum => {
              const out=window.ChartHeatmap.last(), svg=document.getElementById('chart'), r=svg.getBoundingClientRect();
              if (datum === -1) return [r.right+5,r.bottom+5];
              let b;
              if (datum === -2) {
                // A real empty slot in missing-cells; empty space above the plot for the full labels grid.
                const first=out.index.marks[0].shape;
                if (out.index.marks.some(m=>m.datum===3)) b={x:out.index.plot.x,y:0,w:1,h:1};
                else b={x:first.x+3*first.w,y:first.y,w:first.w,h:first.h};
              } else b=out.index.marks.find(m=>m.datum===datum).shape;
              return [r.left+(b.x+b.w/2)*r.width/out.frame.w,r.top+(b.y+b.h/2)*r.height/out.frame.h];
            }
            """.trimIndent()
        private val EXPECTED_CARD =
            """
            datum => {
              const card=window.ChartHeatmap.last().index.marks.find(m=>m.datum===datum).card;
              return [card.heading,card.rows[0].name,card.rows[0].value];
            }
            """.trimIndent()
        private val OPACITY_READ =
            """
            () => [...document.querySelectorAll('#chart rect.dp-mark')].map(e=>getComputedStyle(e).opacity)
            """.trimIndent()
        private val OPACITY =
            """
            datum => {
              const out=window.ChartHeatmap.last(), svg=document.getElementById('chart');
              const cells=[...svg.querySelectorAll('rect.dp-mark')];
              const marks=out.primitives.filter(p=>p.k==='rect' && p.role==='mark');
              const dim=Number(getComputedStyle(svg).getPropertyValue('--chart-dim'));
              if (!(dim>0 && dim<1) || cells.length<2 || out.index.series.length || out.summary.series.length) return false;
              if (!out.index.marks.every(m=>m.series===null)) return false;
              if (cells.length!==marks.length || !cells.every((e,i)=>Math.abs(Number(getComputedStyle(e).opacity)-
                (datum<0 || marks[i].datum===datum ? 1 : dim))<0.001)) return false;
              return [...svg.querySelectorAll('.dp-guide,.dp-tooltip')].every(e=>Number(getComputedStyle(e).opacity)===1);
            }
            """.trimIndent()

        // Derive label ownership independently from containment, then read the effective opacity through all ancestors.
        // This also detects double dimming of a group and its child, or a label left outside its cell's group.
        private val PAIR_READ =
            """
            () => {
              const svg=document.getElementById('chart'), out=window.ChartHeatmap.last();
              const opacity=e=>{let v=1; for(;e;e=e.parentElement) v*=Number(getComputedStyle(e).opacity); return v;};
              const cells=[...svg.querySelectorAll('rect.dp-mark')], labels=[...svg.querySelectorAll('text.dp-guide')];
              const pairs=cells.map((e,i)=>{
                const p=out.primitives.filter(p=>p.k==='rect' && p.role==='mark')[i];
                const label=labels.find(t=>{const x=Number(t.getAttribute('x')),y=Number(t.getAttribute('y'));
                  return x>=p.x && x<=p.x+p.w && y>=p.y && y<=p.y+p.h;});
                return {datum:p.datum,cell:opacity(e),label:label?opacity(label):null};
              });
              const other=[...svg.querySelectorAll('.dp-axis,.dp-grid,.dp-tick,.dp-title,.dp-legend,.dp-guide,.dp-tooltip,.dp-focus')]
                .filter(e=>!labels.includes(e)).map(opacity);
              return {pairs,labels:labels.length,other,dim:Number(getComputedStyle(svg).getPropertyValue('--chart-dim')),
                emptySeries:out.index.series.length===0 && out.summary.series.length===0 && out.index.marks.every(m=>m.series===null)};
            }
            """.trimIndent()
        private val PAIR_OPACITY =
            """
            datum => {
              const read=($PAIR_READ)();
              return read.emptySeries && read.pairs.length>=13 && read.labels===read.pairs.length && read.dim>0 && read.dim<1 &&
                read.pairs.every(p=>{const want=datum<0 || p.datum===datum?1:read.dim;
                  return p.label!==null && Math.abs(p.cell-want)<0.001 && Math.abs(p.label-want)<0.001;}) &&
                read.other.every(v=>Math.abs(v-1)<0.001);
            }
            """.trimIndent()
    }
}
