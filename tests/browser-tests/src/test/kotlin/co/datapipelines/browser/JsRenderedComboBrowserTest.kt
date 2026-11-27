package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Combo's real DOM, bar hover, layer-order keyboard plan, right axis and host switch (1069 A.7). */
class JsRenderedComboBrowserTest {
    @Test
    fun `A7 combo primitives hover keys axes and legend through the product interaction layer`() {
        val opened = PAGE.open(browser, VIEW_W, VIEW_H)
        val page = opened.page
        try {
            for (fixture in listOf("bar-line", "four-layers", "two-lines-two-axes", "temporal-months", "hostile")) {
                for ((w, h) in listOf(960 to 540, 360 to 240)) {
                    page.evaluate(DRAW, listOf(fixture, w, h)) shouldBe "rendered"
                    withClue("DOM $fixture $w x $h") { page.evaluate(DOM) shouldBe emptyList<String>() }
                }
            }
            page.evaluate(DRAW, listOf("bar-line", 640, 360)) shouldBe "rendered"
            page.evaluate(AXES) shouldBe emptyList<String>()
            @Suppress("UNCHECKED_CAST")
            val point = page.evaluate(BAR_POINT) as List<Number>
            page.mouse().move(point[0].toDouble(), point[1].toDouble())
            withClue("March bar: guide, card, focus box and dimming") {
                page.evaluate(HOVER) shouldBe emptyList<String>()
                page.locator("text.dp-tooltip").allTextContents() shouldBe listOf("Mar", "Revenue", "120", "Rate", "12%")
            }
            page.mouse().move(0.0, 0.0)
            page.locator("#chart").focus()
            page.keyboard().press("ArrowRight")
            page.evaluate(FOCUS) shouldBe emptyList<String>()
            page.keyboard().press("Escape")
            val plan =
                listOf(
                    "ArrowRight" to listOf("Jan", "Revenue", "100", "Rate", "10%"),
                    "ArrowRight" to listOf("Feb", "Revenue", "150", "Rate", "15%"),
                    "ArrowDown" to listOf("Feb", "Revenue", "150", "Rate", "15%"),
                    "End" to listOf("Jun", "Revenue", "210", "Rate", "21%"),
                    "Home" to listOf("Jan", "Revenue", "100", "Rate", "10%"),
                    "ArrowUp" to listOf("Jan", "Revenue", "100", "Rate", "10%"),
                )
            for ((key, card) in plan) {
                page.keyboard().press(key)
                withClue("key $key") { page.locator("text.dp-tooltip").allTextContents() shouldBe card }
            }
            page.keyboard().press("Escape")
            page.locator("g.dp-legend[data-legend='layer:0']").click()
            page.keyboard().press("Escape")
            page.mouse().move(0.0, 0.0)
            page.evaluate("() => ChartCombo.asked()") shouldBe listOf("layer:0")
            page.evaluate(HIDDEN) shouldBe emptyList<String>()
            page.evaluate(DOM) shouldBe emptyList<String>()
            page.evaluate(DRAW, listOf("bar-line", 640, 40)) shouldBe "rendered"
            @Suppress("UNCHECKED_CAST")
            val smallPoint = page.evaluate(BAR_POINT) as List<Number>
            page.mouse().move(smallPoint[0].toDouble(), smallPoint[1].toDouble())
            withClue("two lines keep the hovered bar row") {
                page.locator("text.dp-tooltip").allTextContents() shouldBe listOf("Mar", "Revenue", "120")
            }
            opened.errors.toList() shouldBe emptyList<String>()
            opened.violations.toList() shouldBe emptyList<String>()
            opened.off.toList() shouldBe emptyList<String>()
            page.locator("[style]").count() shouldBe 0
        } finally {
            opened.context.close()
        }
    }

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private val EMITTED = Regex("""combo/[a-z0-9-]+/\d+x\d+\.input\.json""")
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-combo.test",
                resources = "chart-combo",
                script = "chart-combo.js",
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
        }

        private val DRAW =
            """
            async ([fixture, w, h]) => (await ChartCombo.draw(fixture + "/960x540.input.json", {w, h})).state
            """.trimIndent()

        // Compare the DOM to a fresh detached write, including every attribute and text, also after hiding a layer.
        private val DOM =
            """
            () => {
              const out = ChartCombo.last(), R = JsRendered, svg = document.getElementById("chart");
              const expected = document.createElementNS(svg.namespaceURI, "svg");
              R.writeDom(out.primitives, expected, out.theme, R.write.describe(out, ChartCombo.input().document));
              const own = (root) => [...root.querySelectorAll("*")].filter(e => !e.closest(".dp-tooltip, .dp-guide"));
              const actual = own(svg), wanted = own(expected), bad = [];
              const attrs = e => [...e.attributes].filter(a => !["tabindex", "aria-pressed", "aria-hidden"].includes(a.name)).map(a => [a.name, a.value]).sort();
              if (actual.length !== wanted.length) bad.push("element count " + actual.length + "/" + wanted.length);
              wanted.forEach((e, j) => {
                const a = actual[j];
                if (!a || a.tagName !== e.tagName || JSON.stringify(attrs(a)) !== JSON.stringify(attrs(e)) || (e.tagName === "text" && a.textContent !== e.textContent)) bad.push("element " + j + ": " + (a ? JSON.stringify(attrs(a)) : "missing") + " / " + JSON.stringify(attrs(e)));
              });
              return bad;
            }
            """.trimIndent()

        private val BAR_POINT =
            """
            () => {
              const out = ChartCombo.last(), b = out.index.series[0].spans[2];
              const svg = document.getElementById("chart"), r = svg.getBoundingClientRect();
              return [r.left + (b.x + b.w / 2) * r.width / out.frame.w, r.top + (b.end + (b.foot - b.end) * .8) * r.height / out.frame.h];
            }
            """.trimIndent()

        private val HOVER =
            """
            () => {
              const out = ChartCombo.last(), svg = document.getElementById("chart"), b = out.index.series[0].spans[2], bad = [];
              const guides = [...svg.querySelectorAll(".dp-guide")];
              if (!guides.some(e => e.tagName === "line")) bad.push("no crosshair");
              if (guides.filter(e => e.tagName === "circle").length !== 1) bad.push("guide dots");
              if (!svg.classList.contains("dp-dimmed") || !svg.querySelector('g.dp-mark.dp-active[data-series="layer:0"]')) bad.push("bar not active");
              return bad;
            }
            """.trimIndent()

        private val FOCUS =
            """
            () => {
              const b = ChartCombo.last().index.series[0].spans[0], rings = [...document.querySelectorAll("#chart rect.dp-focus")];
              if (rings.length !== 1) return ["no bar focus ring"];
              const r = rings[0], x = +r.getAttribute("x"), y = +r.getAttribute("y"), w = +r.getAttribute("width"), h = +r.getAttribute("height");
              const near = (a,c) => Math.abs(a-c)<.02;
              return x < b.x && y < Math.min(b.foot,b.end) && x+w > b.x+b.w && y+h > Math.max(b.foot,b.end) && near(x+w/2,b.x+b.w/2) && near(y+h/2,(b.foot+b.end)/2) ? [] : ["ring does not hold bar box"];
            }
            """.trimIndent()

        private val AXES =
            """
            () => {
              const o = ChartCombo.last(), p = o.index.plot, bad = [];
              const ticks = o.primitives.filter(q => q.k === "text" && q.role === "tick");
              const left = ticks.filter(q => q.anchor === "end"), right = ticks.filter(q => q.anchor === "start" && q.x > p.x + p.w);
              if (right.length < 2 || right.length !== left.length) bad.push("right ticks");
              right.forEach(q => {if (!left.some(t => Math.abs(t.y-q.y)<.02)) bad.push("unaligned tick");});
              return bad;
            }
            """.trimIndent()

        private val HIDDEN =
            """
            () => {
              const R = JsRendered, expected = R.render({...ChartCombo.input(), hidden:["layer:0"]}), actual = ChartCombo.last();
              return JSON.stringify(actual.primitives) === JSON.stringify(expected.primitives) && !actual.index.series[0].on ? [] : ["hidden draw differs"];
            }
            """.trimIndent()
    }
}
