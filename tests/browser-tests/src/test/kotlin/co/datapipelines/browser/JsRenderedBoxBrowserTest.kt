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

/** Box DOM, cards, circle precedence and keyboard targets through the real interaction layer (#1072 A.6). */
class JsRenderedBoxBrowserTest {
    @Test
    fun `DOM equals the emitted primitives at full and small frames`() {
        PAGE.open(browser, VIEW_W, VIEW_H).use { s ->
            for (fixture in listOf("plain", "flat-iqr", "both-sides", "log", "domain", "hostile", "title-unit")) {
                for ((w, h) in listOf(960 to 540, 80 to 24)) {
                    s.page.evaluate(DRAW, listOf(fixture, w, h, false))
                    withClue("$fixture at $w x $h") {
                        s.page.evaluate(DOM) shouldBe emptyList<String>()
                    }
                }
            }
            s.errors.shouldBeEmpty()
            s.violations.shouldBeEmpty()
            s.off.shouldBeEmpty()
        }
    }

    @Test
    fun `pointer on a box and on outside points rings the right shape and shows its card`() {
        PAGE.open(browser, VIEW_W, VIEW_H).use { s ->
            for (overlap in listOf(false, true)) {
                s.page.evaluate(DRAW, listOf("flat-iqr", 480, 320, overlap))
                for (point in listOf(false, true)) {
                    @Suppress("UNCHECKED_CAST")
                    val place = s.page.evaluate(PLACE, point) as Map<String, Any>
                    s.page.mouse().move((place.getValue("x") as Number).toDouble(), (place.getValue("y") as Number).toDouble())
                    @Suppress("UNCHECKED_CAST")
                    val got = s.page.evaluate(HOVER) as Map<String, Any>
                    withClue("overlap=$overlap point=$point: $got") {
                        got["shape"] shouldBe if (point) "circle" else "rect"
                        got["lines"] shouldBe card(overlap, point)
                        got["ringOk"] shouldBe true
                    }
                }
            }
            s.errors.shouldBeEmpty()
            s.violations.shouldBeEmpty()
            s.off.shouldBeEmpty()
        }
    }

    @Test
    fun `keys walk boxes and never outside points at both frames`() {
        PAGE.open(browser, VIEW_W, VIEW_H).use { s ->
            for ((w, h) in listOf(480 to 320, 80 to 24)) {
                s.page.evaluate(DRAW, listOf("plain", w, h, false))
                s.page.mouse().move((VIEW_W - 1).toDouble(), (VIEW_H - 1).toDouble())
                s.page.locator("#chart").focus()
                val plan = listOf("ArrowRight" to 0, "ArrowRight" to 1, "ArrowRight" to 2, "ArrowRight" to 2, "ArrowDown" to 2, "ArrowUp" to 2, "Home" to 0, "End" to 2)
                for ((key, datum) in plan) {
                    s.page.keyboard().press(key)
                    @Suppress("UNCHECKED_CAST")
                    val got = s.page.evaluate(KEY, datum) as Map<String, Any>
                    withClue("$w x $h $key -> $datum: $got") {
                        got["ok"] shouldBe true
                        got["kept"] shouldBe "Middle"
                    }
                }
                s.page.keyboard().press("Escape")
            }
            s.errors.shouldBeEmpty()
            s.violations.shouldBeEmpty()
            s.off.shouldBeEmpty()
        }
    }

    private fun card(overlap: Boolean, point: Boolean): List<String> {
        val values = if (overlap) listOf("1", "1", "1", "1", "1", "1") else listOf("5", "5", "5", "5", "5", "2")
        val rows = listOf("Highest inside", "Upper quarter", "Middle", "Lower quarter", "Lowest inside", "Outside points")
        val head = if (overlap) "North" else "A"
        val own = if (point) listOf("This point", if (overlap) "1.01" else "9") else emptyList()
        return listOf(head) + own + rows.zip(values).flatMap { listOf(it.first, it.second) }
    }

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private var playwright: Playwright? = null
        private lateinit var browser: Browser
        private val EMITTED = Regex("""box/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE = ChartTestPage(
            origin = "https://chart-box.test",
            resources = "chart-box",
            script = "chart-box.js",
            testTypes = emptyList(),
            emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/$r" else null },
        )

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

        private val DRAW = """
            ([file,w,h,overlap]) => window.ChartBox.draw(file+"/960x540.input.json",{w,h},overlap).then(out => {
              window.boxOut=out;
              return out.state;
            })
        """.trimIndent()

        private val DOM = """
            () => {
              const svg=document.getElementById("chart"), tags={group:"g",path:"path",rect:"rect",line:"line",circle:"circle",text:"text"};
              const els=[...svg.querySelectorAll("*")].filter(e=>!['title','desc','linearGradient','stop','defs'].includes(e.localName));
              const ps=window.boxOut.primitives, bad=[];
              if(els.length!==ps.length)bad.push("element count");
              ps.forEach((p,i)=>{
                const e=els[i];if(!e||e.localName!==tags[p.k]){bad.push("tag "+i);return;}
                if(p.k==='text'&&e.textContent!==(p.shown??p.text))bad.push("text "+i);
                const fields=p.k==='circle'?{cx:p.cx,cy:p.cy,r:p.r}:p.k==='rect'?{x:p.x,y:p.y,width:p.w,height:p.h}:{};
                for(const [k,v] of Object.entries(fields))if(Math.abs(Number(e.getAttribute(k))-v)>.011)bad.push(k+" "+i);
              });return bad;
            }
        """.trimIndent()

        private val PLACE = """
            point => {
              const svg=document.getElementById("chart"), o=window.boxOut;
              const entry=o.index.marks.find(m=>m.shape.kind===(point?'point':'box')),p=entry.shape;
              const x=point?p.x:p.x+1,y=point?p.y:p.y+p.h/2;
              const r=svg.getBoundingClientRect(),v=svg.viewBox.baseVal;
              window.boxEntry=entry;
              return {x:r.left+x*r.width/v.width,y:r.top+y*r.height/v.height};
            }
        """.trimIndent()

        private val HOVER = """
            () => {
              const svg=document.getElementById("chart"), ring=svg.querySelector('.dp-guide'),s=window.boxEntry.shape;
              const near=(a,b)=>Math.abs(a-b)<.03,num=k=>Number(ring.getAttribute(k));
              const ringOk=ring&&(s.kind==='point'?near(num('cx'),s.x)&&near(num('cy'),s.y)&&near(num('r'),s.r+3):near(num('x'),s.x-3)&&near(num('y'),s.y-3)&&near(num('width'),s.w+6)&&near(num('height'),s.h+6));
              return {shape:ring?.localName,ringOk:!!ringOk,lines:[...svg.querySelectorAll('text.dp-tooltip')].map(e=>e.textContent)};
            }
        """.trimIndent()

        private val KEY = """
            datum => {
              const svg=document.getElementById("chart"),r=svg.querySelector('.dp-focus'),s=window.boxOut.index.marks.find(m=>m.datum===datum).shape;
              const lines=[...svg.querySelectorAll('text.dp-tooltip')].map(e=>e.textContent),num=k=>Number(r.getAttribute(k));
              const ok=r&&Math.abs(num('x')+num('width')/2-s.x-s.w/2)<.03&&Math.abs(num('y')+num('height')/2-s.y-s.h/2)<.03;
              return {ok:!!ok,kept:lines.includes('Middle')?'Middle':null};
            }
        """.trimIndent()
    }
}
