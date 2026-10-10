package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #473 — the chart CARD, on the real board page: every chart is a bordered article whose heading
 * names the pinned visualization, and a failure speaks INSIDE the card — loud border first, then
 * the quiet issue border with a persistent message — with no visible "ready" badge at rest.
 *
 * The DOM contract's unit half lives in `modules/web/src/test/js/dashboard-cards.test.mjs`; what
 * the page adds is the real vendored bytes, the real stream and the abort harness's deterministic
 * loud state (the hung board races nothing — the 60 s sleep outlives the case).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardCardsBrowserTest : DashboardBrowserSuite() {
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
    fun `every chart is a card whose heading names the pinned visualization - and no ready badge shows`() {
        startTrace()
        val root = ready("dpcards")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.locator("#dp-board .dp-dashboard-table tbody tr").first().waitFor()
        page.locator("#dp-board .dp-dashboard-kpi-number").first().waitFor()

        // The card carries the pin (moved off the viz div), and the heading its label points at
        // reads the pinned VERSION's display name — the fixture's plotly body says "Chart", the
        // table and KPI bodies say their own names.
        val headings =
            page.evaluate(
                """() => Array.from(document.querySelectorAll('#dp-board article.dp-dashboard-card[data-dp-viz]'))
                  .map(function (card) {
                    const heading = document.getElementById(card.getAttribute('aria-labelledby'));
                    return { viz: card.getAttribute('data-dp-viz'), heading: heading ? heading.textContent : null };
                  })""",
            ) as List<*>
        val byViz = headings.filterIsInstance<Map<*, *>>().associateBy { it["viz"] as String }
        headings.size shouldBe 4
        byViz.keys shouldBe setOf("revenue", "cells", "total", "slowchart")
        byViz["cells"]!!["heading"] shouldBe "Cells"
        byViz["total"]!!["heading"] shouldBe "Total"
        page.evaluate(
            """() => {
              const card = document.querySelector('#dp-board article.dp-dashboard-card[data-dp-viz="total"]');
              const walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
              let count = 0;
              while (walker.nextNode()) {
                if (walker.currentNode.textContent.trim() === 'Total') count++;
              }
              return count;
            }""",
        ) shouldBe 1

        byViz["revenue"]!!["heading"] shouldBe "Chart"

        // Settled boards are quiet: no error voice is on any card, and a VISIBLE chip is never
        // the ready state (in-progress/success show briefly by design; ready never shows —
        // the no-badge rule). The read runs after the completed notification, so the chips
        // this read sees settled or still counting down are both legitimate here.
        val resting =
            page.evaluate(
                """() => Array.from(document.querySelectorAll('#dp-board article.dp-dashboard-card'))
                  .map(function (card) {
                    const chip = card.querySelector('.dp-dashboard-status');
                    const state = chip ? chip.getAttribute('data-dp-state') : null;
                    return {
                      state: state,
                      shown: chip ? getComputedStyle(chip).display !== 'none' : false,
                      loud: card.className.indexOf('--error') !== -1 || card.className.indexOf('--abort') !== -1,
                    };
                  })""",
            ) as List<*>
        resting.filterIsInstance<Map<*, *>>().forEach {
            (it["loud"] as Boolean) shouldBe false
            if ((it["shown"] as Boolean) == true) (it["state"] as String?) shouldBe "success"
        }
    }

    @Test
    @Order(2)
    fun `an abort raises the loud card, then the quiet issue border with a persistent message`() {
        startTrace()
        val root = ready("dpcabort2")
        val board = seedHungBoard(root)
        openBoard(board)
        val refreshId =
            page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['hungcells'] })") as String
        page.waitForSelector("[data-dp-viz='hungcells'] .dp-dashboard-status[data-dp-state='in-progress']")
        awaitRefreshStatus(board, refreshId, "RUNNING")

        // LOUD: the abort acknowledged through the page's own instance flips the card red with
        // a message inside it — the card's own classes, not the chip's.
        abortAndNameAck("window.__dpPage.instance", board, refreshId)
        chipStateIs("hungcells", "abort")
        val loud =
            page.evaluate(
                """() => { const card = document.querySelector("[data-dp-viz='hungcells']");
                  const message = card.querySelector('.dp-dashboard-card-message');
                  return { card: card.className, message: message ? message.textContent : null,
                           inside: message ? card.contains(message) : false }; }""",
            ) as Map<*, *>
        (loud["card"] as String).contains("--abort") shouldBe true
        (loud["message"] as String?)!!.isNotEmpty() shouldBe true
        (loud["inside"] as Boolean) shouldBe true

        // QUIET: after the accent window the red border retires; the issue border and the
        // message persist — the reader still knows the chart is not current. The chip itself
        // answers to the SERVER's later word (the cancelled target's stale/no-data frame re-renders
        // it, #356's abort chip stands only until then), so the settled-state check is that the
        // card stopped loading — the message and border carry the rest.
        page.waitForFunction(
            """() => (function () {
              const card = document.querySelector("[data-dp-viz='hungcells']");
              return card.className.indexOf('--has-issue') !== -1 && card.className.indexOf('--abort') === -1;
            })()""",
            null,
            com.microsoft.playwright.Page
                .WaitForFunctionOptions()
                .setTimeout(8_000.0),
        )
        val quiet =
            page.evaluate(
                """() => { const card = document.querySelector("[data-dp-viz='hungcells']");
                  return { card: card.className,
                           message: (card.querySelector('.dp-dashboard-card-message') || {}).textContent || '' }; }""",
            ) as Map<*, *>
        (quiet["card"] as String).contains("--has-issue") shouldBe true
        (quiet["message"] as String).isNotEmpty() shouldBe true
        chipStateIsNot("hungcells", "in-progress")
    }
}
