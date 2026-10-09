package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** #492 — the filters presentation follows the board region while the viewport stays fixed. */
class DashboardFiltersBreakpointBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `resizing the rail switches the filters between column and drawer and clears an open drawer`() {
        startTrace()
        val root = ready("dpbreak")
        page.setViewportSize(1280, 800)
        page.navigate("$baseUrl/dashboards/${seedParameterisedBoard(root)}")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        resizeRail("Home")
        assertPresentation("column")

        repeat(6) { resizeRail("Shift+ArrowRight") }
        withClue("the narrow board must show a drawer at the unchanged 1280 px viewport") {
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
        resizeRail("Home")
        assertPresentation("column")
        page.waitForFunction("() => document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'")
        assertClosed()
        repeat(6) { resizeRail("Shift+ArrowRight") }
        assertPresentation("drawer")
        assertClosed()

        // The rail's actual maximum is the full viewport; main's content track has zero width.
        resizeRail("End")
        // Zero-width main-content overflow also occurs on the unchanged base; its shell
        // follow-up is #503. Here pin only the drawer's presentation at the maximum.
        assertPresentation("drawer", checkOverflow = false)
        resizeRail("Home")
        assertPresentation("column")
        assertClosed()
    }

    @Test
    fun `the 820 px board region is a drawer and the next rail step above it is a column`() {
        startTrace()
        val root = ready("dpbound")
        page.setViewportSize(1280, 800)
        page.navigate("$baseUrl/dashboards/${seedParameterisedBoard(root)}")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        resizeRail("Home")
        repeat(3) { resizeRail("Shift+ArrowRight") }
        repeat(3) { resizeRail("ArrowRight") }
        regionWidth() shouldBe 820.0
        assertPresentation("drawer")
        resizeRail("ArrowLeft")
        regionWidth() shouldBe 830.0
        assertPresentation("column")
    }

    private fun regionWidth(): Double =
        (page.evaluate("() => document.querySelector('.dp-board-layout').getBoundingClientRect().width") as Number).toDouble()

    private fun resizeRail(key: String) {
        val separator = page.locator("#rail-resize")
        separator.focus()
        page.keyboard().press(key)
        // Force a layout read after the synchronous keyboard control changes the rail token.
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
                    region: layout.getBoundingClientRect().width,
                    presentation: getComputedStyle(panel).position === 'fixed' ? 'drawer' : 'column',
                    toolbar: getComputedStyle(toolbar).display,
                    beforeBoard: panel.getBoundingClientRect().right <= board.getBoundingClientRect().left }; }""",
            ) as Map<*, *>
        println("492-filters-$expected $geometry")
        geometry["viewport"] shouldBe 1280
        geometry["presentation"] shouldBe expected
        geometry["toolbar"] shouldBe if (expected == "drawer") "flex" else "none"
        if (expected == "column") geometry["beforeBoard"] shouldBe true
        if (checkOverflow) documentOverflowsX() shouldBe false
    }

    private fun assertClosed() {
        (page.evaluate("() => document.getElementById('dp-board-filters').classList.contains('is-open')") as Boolean) shouldBe false
        page.getAttribute("#dp-board-filters-trigger", "aria-expanded") shouldBe "false"
    }
}
