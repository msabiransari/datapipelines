package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #492 — the filters presentation follows the board region while the viewport stays fixed. #554: the
 * region is narrowed by the resource browser's separator (240–480 in 16 px steps) at a 1470 px
 * viewport, where 202 + 384 leaves main 884 and, less `--app-main-pad` (32 px in this band since
 * #573) on both sides, the region exactly 820.
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
        repeat(TO_820) { resizeBrowser("ArrowRight") }
        regionWidth() shouldBe 820.0
        assertPresentation("drawer")
        resizeBrowser("ArrowLeft")
        regionWidth() shouldBe 836.0
        assertPresentation("column")
    }

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

        /** Nine 16 px steps from the 240 floor: a 384 px browser, main 1470 - 202 - 384 = 884, the region 884 - 2 × 32 = 820 (#573). */
        const val TO_820 = 9
    }
}
