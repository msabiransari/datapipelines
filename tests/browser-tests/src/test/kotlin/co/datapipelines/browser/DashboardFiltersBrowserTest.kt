package co.datapipelines.browser

import com.google.gson.JsonObject
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
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
 *
 * Recovery 473b (after #460's persistent shell): the board is re-mounted on in-shell navigation
 * without a document load, so the drawer case first leaves and returns twice and counts the
 * document's keydown listeners through DevTools (DOMDebugger.getEventListeners) — the code under
 * test reports nothing about itself. A board with no visible parameter reserves no panel.
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

    /** Listeners of [type] registered on `document`, read through DevTools, not through the page's own seams. */
    private fun documentListenerCount(type: String): Int {
        val cdp = page.context().newCDPSession(page)
        try {
            val document = cdp.send("Runtime.evaluate", JsonObject().apply { addProperty("expression", "document") })
            val objectId = document.getAsJsonObject("result").get("objectId").asString
            val listeners =
                cdp.send("DOMDebugger.getEventListeners", JsonObject().apply { addProperty("objectId", objectId) })
            return listeners.getAsJsonArray("listeners").count { it.asJsonObject.get("type").asString == type }
        } finally {
            cdp.detach()
        }
    }

    /** The filters panel's and the board's rectangles, with the panel's computed display. */
    @Suppress("UNCHECKED_CAST")
    private fun regions(): Map<String, Any?> =
        page.evaluate(
            """() => { const panel = document.getElementById('dp-board-filters').getBoundingClientRect();
              const board = document.getElementById('dp-board').getBoundingClientRect();
              const layout = document.querySelector('.dp-board-layout').getBoundingClientRect();
              return { panelLeft: panel.left, panelRight: panel.right, panelWidth: panel.width,
                boardLeft: board.left, boardWidth: board.width, layoutLeft: layout.left, layoutWidth: layout.width,
                panelDisplay: getComputedStyle(document.getElementById('dp-board-filters')).display,
                panelPosition: getComputedStyle(document.getElementById('dp-board-filters')).position,
                panelTransform: getComputedStyle(document.getElementById('dp-board-filters')).transform,
                triggerDisplay: getComputedStyle(document.querySelector('.dp-board-toolbar')).display,
                filters: document.querySelector('.dp-board-page').getAttribute('data-dp-filters'),
                fields: document.querySelectorAll('#dp-board-filters .dp-dashboard-parameter').length }; }""",
        ) as Map<String, Any?>

    private fun Any?.px(): Double = (this as Number).toDouble()

    /**
     * #460's persistent shell: leave in-shell and come back twice (a cached restoration re-mounts
     * the board without a document load). Each visit's Escape listener must leave with its board,
     * so the document holds exactly the first visit's keydown count.
     */
    private fun leaveAndReturnHoldsOneListenerSet() {
        val firstMount = documentListenerCount("keydown")
        repeat(2) { visit ->
            page.evaluate("() => { window.__leftBoard = window.__dpPage; }")
            page.click(".app-nav-link[data-nav-section='/templates']")
            page.waitForSelector("#template-list-wrapper")
            page.goBack()
            page.waitForFunction("() => window.__dpPage !== window.__leftBoard && window.__dpPage.ready === true")
            val count = documentListenerCount("keydown")
            println("473b-keydown-listeners visit=${visit + 2} first=$firstMount now=$count")
            count shouldBe firstMount
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

        // Measured at both desktop sizes: the panel ends at or before the board begins, it holds
        // the set's field, and the board keeps a usable width.
        for ((width, height) in listOf(1440 to 900, 1280 to 800)) {
            page.setViewportSize(width, height)
            page.waitForFunction("() => document.querySelector('.dp-board-page').getAttribute('data-dp-filters') === 'some'")
            val r = regions()
            println("473b-filters-geometry ${width}x$height $r")
            r["panelDisplay"] shouldBe "block"
            r["fields"].px() shouldBe 1.0
            r["panelRight"].px() shouldBeLessThanOrEqual r["boardLeft"].px()
            (r["boardWidth"].px() >= r["layoutWidth"].px() / 2) shouldBe true
            documentOverflowsX() shouldBe false
        }

        // A board with NO parameter set: no panel, no trigger, the board takes the whole row.
        openBoard(seedBoard(root))
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        val bare = regions()
        println("473b-filters-none $bare")
        bare["filters"] shouldBe "none"
        bare["panelDisplay"] shouldBe "none"
        bare["triggerDisplay"] shouldBe "none"
        bare["boardLeft"].px() shouldBe (bare["layoutLeft"].px() plusOrMinus 1.0)
        bare["boardWidth"].px() shouldBe (bare["layoutWidth"].px() plusOrMinus 1.0)
        documentOverflowsX() shouldBe false
    }

    @Test
    @Order(2)
    fun `the narrow drawer opens from the toolbar and closes on Escape with focus returned`() {
        startTrace()
        val root = ready("dpdraw")
        // A parameterised board: the trigger exists only while a parameter is visible.
        val board = seedParameterisedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        leaveAndReturnHoldsOneListenerSet()
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

        // Open again, then cross back to desktop while it is open: the panel is a column beside
        // the board again and the drawer state is gone with the trigger — nothing still claims
        // to be expanded, and the next narrowing starts closed.
        page.click("#dp-board-filters-trigger")
        page.waitForFunction("() => document.getElementById('dp-board-filters').classList.contains('is-open')")
        page.setViewportSize(1280, 900)
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              return !panel.classList.contains('is-open') &&
                document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'; }""",
        )
        // The panel settles INTO its grid column: in flow, at the layout's left edge, not left
        // displaced by a drawer transform carried across the boundary.
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              const layout = document.querySelector('.dp-board-layout');
              return Math.abs(panel.getBoundingClientRect().left - layout.getBoundingClientRect().left) <= 1; }""",
        )
        val wide = regions()
        println("473b-filters-resize-back $wide")
        wide["panelDisplay"] shouldBe "block"
        wide["panelPosition"] shouldBe "relative"
        wide["panelLeft"].px() shouldBe (wide["layoutLeft"].px() plusOrMinus 1.0)
        wide["triggerDisplay"] shouldBe "none"
        wide["panelRight"].px() shouldBeLessThanOrEqual wide["boardLeft"].px()
        documentOverflowsX() shouldBe false
        page.setViewportSize(900, 900)
        page.waitForFunction("() => getComputedStyle(document.querySelector('.dp-board-toolbar')).display !== 'none'")
        (page.evaluate("() => document.getElementById('dp-board-filters').classList.contains('is-open')") as Boolean) shouldBe false
        page.setViewportSize(1280, 900)
    }
}
