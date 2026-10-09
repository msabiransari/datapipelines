package co.datapipelines.browser

import com.microsoft.playwright.Request
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.Collections
import java.util.function.Consumer

/**
 * #473 — the activity DOCK at the bottom of the board page: three tabs (Events live, Errors the
 * error slice, History the server's refreshes), count badges that name their tab, a view-only
 * Clear, and a collapse that folds the dock to its head.
 *
 * The log's cap/severity/kind logic is the node suite's (`dashboard-events.test.mjs`); what the
 * page adds is the real stream landing in the real dock through the page's witness.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardEventsBrowserTest : DashboardBrowserSuite() {
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
    fun `the completed refresh lands in the Events tab with its count`() {
        startTrace()
        val root = ready("dpevts")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")

        // Events is the open tab; the live event row arrived through the real stream's witness.
        // The filter reads the row's SENTENCE span, not the whole row (whose textContent starts
        // with the time and includes the event kind — "source_completed" — which must not count):
        // the terminal refresh row's sentence is "Refresh <status> …" with the wire's UPPERCASE
        // status (the server sends "COMPLETED"), so the match is case-insensitive by design.
        page.waitForFunction(
            """() => { const pane = document.querySelector("[data-dp-dock-pane='events']");
              return document.querySelector("[data-dp-dock-tab='events']").getAttribute('aria-selected') === 'true' &&
                !pane.hidden && pane.querySelectorAll('.dp-board-event').length >= 1; }""",
        )
        val row =
            page.evaluate(
                """() => { const rows = Array.from(document.querySelectorAll("[data-dp-dock-pane='events'] .dp-board-event"));
                  return rows.filter(function (r) {
                    const t = r.querySelector('.dp-board-event-text');
                    return t && /^Refresh (ok|completed|failed|partial)\b/i.test(t.textContent);
                  }).length; }""",
            ) as Number
        row.toInt() shouldBe 1
        // The badge names the tab's whole log, and the tab holds every event the stream sent.
        val count = page.evaluate("() => document.querySelector(\"[data-dp-dock-count='events']\").textContent") as String
        val total = page.evaluate("() => window.__dpPage.dock.size()") as Number
        count.trim() shouldBe total.toString()
    }

    @Test
    @Order(2)
    fun `tabs split - the Errors tab is empty at rest - Clear empties - Collapse folds`() {
        startTrace()
        val root = ready("dpevt2")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")

        // Errors: the error slice of the same log — at rest zero, its badge naming the zero.
        page.click("[data-dp-dock-tab='errors']")
        page.waitForFunction(
            """() => { const pane = document.querySelector("[data-dp-dock-pane='errors']");
              return document.querySelector("[data-dp-dock-tab='errors']").getAttribute('aria-selected') === 'true' &&
                !pane.hidden && pane.querySelectorAll('.dp-board-event').length === 0; }""",
        )
        page.waitForFunction(
            "() => document.querySelector(\"[data-dp-dock-count='errors']\").getAttribute('data-dp-count-zero') === 'true'",
        )

        // Clear is the VIEW's button and empties the log the Events tab reads back to zero.
        page.click("[data-dp-dock-tab='events']")
        page.click("[data-dp-dock-clear]")
        page.waitForFunction(
            "() => document.querySelectorAll(\"[data-dp-dock-pane='events'] .dp-board-event\").length === 0",
        )
        page.waitForFunction(
            "() => document.querySelector(\"[data-dp-dock-count='events']\").getAttribute('data-dp-count-zero') === 'true'",
        )

        // Collapse folds the dock to its head; expanding again restores the body.
        page.click("[data-dp-dock-collapse]")
        page.waitForFunction(
            "() => document.getElementById('dp-board-dock').hasAttribute('data-dp-dock-collapsed')",
        )
        page.click("[data-dp-dock-collapse]")
        page.waitForFunction(
            "() => !document.getElementById('dp-board-dock').hasAttribute('data-dp-dock-collapsed')",
        )
    }

    /**
     * #476 — the board mounts the refreshes pane TWICE (the dock's History tab and the
     * workspace's Refreshes tab), and each copy used to carry its own `every 15s`: two timers
     * polling one read-only listing whenever both were mounted. The partial now declares only
     * the request (`hx-trigger="dp:refresh"`) and the page glue owns the ONE 15s timer, poking
     * whichever copies the reader can actually see — a trigger FILTER could not do this job,
     * because the enforced CSP keeps htmx's `allowEval` off and htmx never runs a filter
     * expression without it (measured live: both copies polled with the filter in the DOM).
     *
     * The wire is the verdict: with both copies mounted and exactly one visible, the refreshes
     * endpoint is requested at the shared poller's cadence and never twice per tick. A poller
     * that stops entirely fails the floor; either copy self-scheduling again (or the timer
     * poking hidden copies) fails the ceiling — the base measured one above it.
     */
    @Test
    @Order(3)
    fun `both refreshes copies mounted - only the visible one polls`() {
        startTrace()
        val root = ready("dppoll")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")

        // Mount the second copy: the workspace's Refreshes tab. The lazy loader unhides the
        // pane before fetching, so this copy is processed VISIBLE and its own triggers live.
        page.click("[data-dp-tab='refreshes']")
        page.waitForFunction(
            "() => { const p = document.getElementById('dp-pane-refreshes');" +
                " return !p.hidden && p.querySelector('.dp-refreshes') !== null; }",
        )
        // Both copies now exist. Re-hide the workspace copy, reveal the dock's History copy —
        // the state under test: one visible pane pair, its hidden twin silent.
        page.click("[data-dp-tab='board']")
        page.waitForFunction("() => document.getElementById('dp-pane-refreshes').hidden")
        page.click("[data-dp-dock-tab='history']")
        page.waitForFunction(
            "() => { const p = document.querySelector(\"[data-dp-dock-pane='history']\");" +
                " return !p.hidden && p.querySelector('.dp-refreshes') !== null; }",
        )

        // Count the endpoint's requests over the window that follows, on the wire.
        val polls = Collections.synchronizedList(ArrayList<String>())
        val requested =
            Consumer { request: Request ->
                if (request.url().contains("/partials/dashboards/$board/refreshes")) polls.add(request.url())
            }
        page.onRequest(requested)
        try {
            page.waitForTimeout(POLL_WINDOW_MS)
        } finally {
            page.offRequest(requested)
        }

        val count = polls.size
        println("476 poll count over ${(POLL_WINDOW_MS / 1000).toInt()}s window: $count")
        count shouldBeGreaterThanOrEqual VISIBLE_COPY_FLOOR
        count shouldBeLessThanOrEqual ONE_POLLER_CEILING
    }

    private companion object {
        const val POLL_WINDOW_MS = 35_000.0
        const val VISIBLE_COPY_FLOOR = 1
        const val ONE_POLLER_CEILING = 3
    }
}
