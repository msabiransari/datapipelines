package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #473 — the board's parameter FILTERS: their own panel BESIDE the chart container (never inside
 * it, so no adapter's mount scope can claim them), announced from a toolbar trigger, and at
 * narrow widths a drawer — opened from the toolbar, closed on Escape, focus returned.
 *
 * The behaviour of the controls themselves (evaluation, the lock, the wire) is the conformance
 * suites'; what the page adds is WHERE the panel lives and that the drawer is keyboard-reachable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardFiltersBrowserTest : DashboardBrowserSuite() {
    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        val code = page.evaluate("() => window.__dpPage.code") as String?
        if (code != null) {
            val error = page.evaluate("() => String(window.__dpPage.error)") as String
            throw AssertionError("the board page failed to boot: $code — $error")
        }
    }

    @Test
    @Order(1)
    fun `the filters are a panel beside the board - holding the controls, never inside the chart container`() {
        startTrace()
        val root = ready("dpfilt")
        // A parameterISED board: without a set the runtime renders no pane at all (the bootstrap
        // parameter step is an explicit no-op), and WHERE the pane mounts is this test's question.
        val board = seedParameterisedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")

        val where =
            page.evaluate(
                """() => { const panel = document.getElementById('dp-board-filters');
                  const params = panel.querySelector('.dp-dashboard-parameters');
                  const board = document.getElementById('dp-board');
                  return { panel: !!panel, params: !!params,
                    insideBoard: params ? board.contains(params) : true,
                    insideRuntime: params ? !!params.closest('[data-datapipelines-dashboard]') : true,
                    beforeBoard: params ? !!(params.compareDocumentPosition(board) & Node.DOCUMENT_POSITION_FOLLOWING) : false }; }""",
            ) as Map<*, *>
        where["panel"] shouldBe true
        where["params"] shouldBe true
        where["insideBoard"] shouldBe false
        where["insideRuntime"] shouldBe false
        where["beforeBoard"] shouldBe true

        // The workspace page never scrolls sideways, at the wide layout either.
        documentOverflowsX() shouldBe false
    }

    @Test
    @Order(2)
    fun `the narrow drawer opens from the toolbar and closes on Escape with focus returned`() {
        startTrace()
        val root = ready("dpdraw")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        page.setViewportSize(900, 900)

        page.waitForFunction(
            "() => document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'",
        )
        page.click("#dp-board-filters-trigger")
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              return panel.classList.contains('is-open') &&
                document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'true'; }""",
        )
        // Focus moved INTO the drawer (the close button) — a keyboard user is inside, not behind it.
        page.waitForFunction("() => document.activeElement && document.activeElement.id === 'dp-board-filters-close'")
        documentOverflowsX() shouldBe false

        page.keyboard().press("Escape")
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              return !panel.classList.contains('is-open') &&
                document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'; }""",
        )
        // Focus returned to the trigger that opened the drawer.
        page.waitForFunction("() => document.activeElement && document.activeElement.id === 'dp-board-filters-trigger'")
        page.setViewportSize(1280, 900)
    }
}
