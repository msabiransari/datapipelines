package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * #503, re-cut by #554 — the resource browser's maximum leaves a usable main track on real dashboard
 * pages. The width the reader can give the browser is `min(480, viewport - menu - 320)`: at 900 px the
 * compact menu (64) allows the full 480, the expanded menu (202) only 378, and the clamp moves the
 * rendered width without ever rewriting the stored preference (record §4 "Browser resizing"). Before
 * #554 this pinned the rail's 960/1120 maximum over a 232 px floor.
 */
class BrowserMaximumBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `browser maximum preserves main on a dashboard with parameters`() {
        startTrace()
        val root = ready("dpbrowserparams")
        page.setViewportSize(BAND_VIEWPORT, 800)
        exerciseMaximum(seedParameterisedBoard(root))
    }

    @Test
    fun `browser maximum preserves main on a dashboard without parameters`() {
        startTrace()
        val root = ready("dpbrowserplain")
        page.setViewportSize(BAND_VIEWPORT, 800)
        exerciseMaximum(seedHungBoard(root))
    }

    private fun exerciseMaximum(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        awaitBoard()
        assertGeometry(BAND_DEFAULT, maximum = 480)
        resizeBrowser("End")
        assertGeometry(480, maximum = 480)
        preference() shouldBe "480px"

        // The expanded menu takes 138 px more: the clamp narrows the column to 378 and main keeps 320.
        page.click("#rail-collapse")
        assertGeometry(EXPANDED_MAXIMUM, maximum = EXPANDED_MAXIMUM)
        preference() shouldBe "480px"
        resizeBrowser("ArrowRight")
        assertGeometry(EXPANDED_MAXIMUM, maximum = EXPANDED_MAXIMUM)
        preference() shouldBe "480px"
        stored() shouldBe "480"
        page.click("#rail-collapse")
        assertGeometry(480, maximum = 480)
        resizeBrowser("Home")
        assertGeometry(240, maximum = 480)

        // A value outside 240–480 is not a preference: removed before the first paint, the band default shows.
        page.evaluate("() => localStorage.setItem('dp-browser-width:' + document.documentElement.dataset.dpWorkspace, '2000')")
        page.reload()
        awaitBoard()
        assertGeometry(BAND_DEFAULT, maximum = 480)
        preference() shouldBe ""
        stored() shouldBe null

        // A wider window moves the range, never the preference; a drag past the maximum stops there.
        page.setViewportSize(1440, 800)
        page.waitForFunction("() => document.getElementById('browser-boundary').getAttribute('aria-valuenow') === '300'")
        assertGeometry(300, maximum = 480)
        val handle = checkNotNull(page.locator("#browser-boundary").boundingBox())
        page.mouse().move(handle.x + handle.width / 2, handle.y + handle.height / 2)
        page.mouse().down()
        page.mouse().move(1430.0, handle.y + handle.height / 2)
        page.mouse().up()
        assertGeometry(480, maximum = 480)
        resizeBrowser("Home")
        assertGeometry(240, maximum = 480)
    }

    private fun awaitBoard() {
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        page.evaluate("() => window.__dpPage.code") shouldBe null
    }

    private fun resizeBrowser(key: String) {
        page.locator("#browser-boundary").focus()
        page.keyboard().press(key)
    }

    private fun preference() = page.evaluate("() => document.documentElement.style.getPropertyValue('--app-browser-preferred')")

    private fun stored() = page.evaluate("() => localStorage.getItem('dp-browser-width:' + document.documentElement.dataset.dpWorkspace)")

    private fun assertGeometry(
        expectedBrowser: Int,
        maximum: Int,
    ) {
        val geometry =
            page.evaluate(
                """() => { const browser = document.getElementById('app-browser');
                  const main = document.getElementById('app-main');
                  const separator = document.getElementById('browser-boundary');
                  return { browser: browser.getBoundingClientRect().width, main: main.clientWidth, scroll: main.scrollWidth,
                    now: separator.getAttribute('aria-valuenow'), max: separator.getAttribute('aria-valuemax') }; }""",
            ) as Map<*, *>
        withClue("browser/main geometry at $expectedBrowser px: $geometry") {
            ((geometry["main"] as Number).toInt() >= 320) shouldBe true
            geometry["scroll"] shouldBe geometry["main"]
            (abs((geometry["browser"] as Number).toDouble() - expectedBrowser) <= 1.0) shouldBe true
            geometry["now"] shouldBe expectedBrowser.toString()
            geometry["max"] shouldBe maximum.toString()
            documentOverflowsX() shouldBe false
        }
    }

    private companion object {
        /** In the 761–1100 band: the menu compact (64) by default, the browser 270. */
        const val BAND_VIEWPORT = 900
        const val BAND_DEFAULT = 270

        /** 900 - 202 - 320: the widest browser beside the expanded menu. */
        const val EXPANDED_MAXIMUM = 378
    }
}
