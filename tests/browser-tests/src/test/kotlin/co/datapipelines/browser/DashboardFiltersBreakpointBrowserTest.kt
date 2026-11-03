package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #492 — the filters presentation follows the board region while the viewport stays fixed. #554: the
 * region is narrowed by the resource browser's separator (app.css's `--app-browser-min`–
 * `--app-browser-max` range, in 16 px steps) at a 1470 px viewport, one region step per key until it
 * crosses the 820 container query. #788: the crossed region widths derive from the range the page
 * reports; the step count from the floor to the crossing stays pinned (TO_820).
 */
class DashboardFiltersBreakpointBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `resizing the browser switches the filters between column and drawer and clears an open drawer`() {
        startTrace()
        val root = ready("dpbreak")
        page.setViewportSize(VIEWPORT, 800)
        page.navigate("$baseUrl/dashboards/${seedParameterisedBoard(root)}")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        resizeBrowser("Home")
        assertPresentation("column")

        repeat(TO_820) { resizeBrowser("ArrowRight") }
        withClue("the narrow board must show a drawer at the unchanged $VIEWPORT px viewport") {
            assertPresentation("drawer")
        }
        page.click("#dp-board-filters-trigger")
        page.waitForFunction("() => document.activeElement.id === 'dp-board-filters-close'")
        documentOverflowsX() shouldBe false
        page.keyboard().press("Escape")
        page.waitForFunction("() => document.activeElement.id === 'dp-board-filters-trigger'")
        assertClosed()

        // Widen the BOARD while its drawer is open, without a window resize. Returning to a
        // column must clear the state, so the next narrowing cannot resurrect an open drawer.
        page.click("#dp-board-filters-trigger")
        page.waitForFunction("() => document.activeElement.id === 'dp-board-filters-close'")
        resizeBrowser("Home")
        assertPresentation("column")
        page.waitForFunction("() => document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'")
        assertClosed()
        repeat(TO_820) { resizeBrowser("ArrowRight") }
        assertPresentation("drawer")
        assertClosed()

        // At the browser's maximum the remaining board region still uses the filters drawer.
        resizeBrowser("End")
        assertPresentation("drawer")
        resizeBrowser("Home")
        assertPresentation("column")
        assertClosed()
    }

    @Test
    fun `the 820 px board region is a drawer and the next browser step above it is a column`() {
        startTrace()
        val root = ready("dpbound")
        page.setViewportSize(VIEWPORT, 800)
        page.navigate("$baseUrl/dashboards/${seedParameterisedBoard(root)}")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        resizeBrowser("Home")
        val range = browserRange(page)
        repeat(TO_820) { resizeBrowser("ArrowRight") }
        regionWidth() shouldBe regionAt(range.min + TO_820 * 16).toDouble()
        assertPresentation("drawer")
        resizeBrowser("ArrowLeft")
        regionWidth() shouldBe regionAt(range.min + (TO_820 - 1) * 16).toDouble()
        assertPresentation("column")
    }

    /** The board region's width at a browser width: the viewport less the expanded menu, the
     *  browser column and this band's `--app-main-pad` on both sides. */
    private fun regionAt(browserWidth: Int): Int = VIEWPORT - 202 - browserWidth - 2 * 32

    private fun regionWidth(): Double =
        (page.evaluate("() => document.querySelector('.dp-board-layout').getBoundingClientRect().width") as Number).toDouble()

    private fun resizeBrowser(key: String) {
        val separator = page.locator("#browser-boundary")
        separator.focus()
        page.keyboard().press(key)
        // Force a layout read after the synchronous keyboard control changes the browser token.
        page.evaluate("() => document.querySelector('.dp-board-page').getBoundingClientRect().width")
    }

    private fun assertPresentation(
        expected: String,
        checkOverflow: Boolean = true,
    ) {
        val geometry =
            page.evaluate(
                """() => { const panel = document.getElementById('dp-board-filters');
                  const layout = document.querySelector('.dp-board-layout');
                  const board = document.getElementById('dp-board');
                  const toolbar = document.querySelector('.dp-board-toolbar');
                  return { viewport: innerWidth, rail: document.getElementById('app-rail').getBoundingClientRect().width,
                    browser: document.getElementById('app-browser').getBoundingClientRect().width,
                    region: layout.getBoundingClientRect().width,
                    presentation: getComputedStyle(panel).position === 'fixed' ? 'drawer' : 'column',
                    toolbar: getComputedStyle(toolbar).display,
                    beforeBoard: panel.getBoundingClientRect().right <= board.getBoundingClientRect().left }; }""",
            ) as Map<*, *>
        println("492-filters-$expected $geometry")
        geometry["viewport"] shouldBe VIEWPORT
        geometry["presentation"] shouldBe expected
        geometry["toolbar"] shouldBe if (expected == "drawer") "flex" else "none"
        if (expected == "column") geometry["beforeBoard"] shouldBe true
        if (checkOverflow) documentOverflowsX() shouldBe false
    }

    private fun assertClosed() {
        (page.evaluate("() => document.getElementById('dp-board-filters').classList.contains('is-open')") as Boolean) shouldBe false
        page.getAttribute("#dp-board-filters-trigger", "aria-expanded") shouldBe "false"
    }

    private companion object {
        const val VIEWPORT = 1470

        /** The drawer query (820) pins the STEP COUNT from the range's floor — seven 16 px keys
         *  today, the first count that puts the region under 820; the region widths it implies
         *  derive from the page's range in the test (#788). */
        const val TO_820 = 7
    }
}
