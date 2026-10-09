package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

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
}
