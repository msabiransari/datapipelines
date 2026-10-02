package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #10 L3b — the implementation spec's §10.5 PAGE half: the record's acceptance scenarios run
 * against `/dashboards/{id}` — the page a person opens, not the conformance host — over the real
 * transport, the real CSP and the real vendored bytes. The frame-level freshness and lock gates
 * stay in the node tests and the HOST half (`DashboardConformanceBrowserTest`), where they are
 * deterministic; what a SECOND host (this one) adds is the proof that the product page mounts
 * the same runtime the same way: every scenario the host passes, the page passes too.
 *
 * Scenarios (the record's numbering, as §10.5 maps them): 1 bootstrap order; 3 freshness across
 * overlapping refreshes (bounded here — the frame-level gate is the node tests'); 4 the
 * parameter lock and its deadline; 10–14 notifications, abort, connection loss, disposal, two
 * instances; 17–18 the bundles and the screens (17's bundle pair is judged by the render test's
 * one-tag assertion and the runtime's bootstrap judgement; 18's screens are
 * [DashboardPagesBrowserTest]'s handback shots).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardPageConformanceBrowserTest : DashboardBrowserSuite() {
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
    fun `bootstrap order - the initial action renders chart, table and kpi on the real page`() {
        startTrace()
        val root = ready("dpcboot")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.locator("#dp-board .dp-dashboard-table tbody tr").first().waitFor()
        page.locator("#dp-board .dp-dashboard-kpi-number").first().waitFor()
        page.locator("#dp-board .dp-dashboard-kpi-number").first().textContent() shouldBe "42"
    }

    @Test
    @Order(2)
    fun `freshness across overlapping refreshes - a slow target and then all - the board stays coherent`() {
        startTrace()
        val root = ready("dpcover")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")

        // Overlap: a slow targeted refresh, then an all refresh on top of it. The frame-level
        // freshness gate (the newest refresh owns the occurrence; a finished run cannot
        // overwrite a newer view) is the node tests'; what the page proves is the REAL
        // transport carrying both streams at once and the board settling coherent — every
        // occurrence ends in a settled state and no error surfaced.
        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['slowchart'] })")
        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'all' })")
        page.waitForFunction(
            "() => window.__dpPage.notifications.filter(function (n) { return n.code === 'refresh.completed'; }).length >= 2",
            null,
            com.microsoft.playwright.Page
                .WaitForFunctionOptions()
                .setTimeout(30_000.0),
        )
        page.waitForTimeout(3_500.0) // the slow source's own sleep runs out under both streams
        val errorCount =
            page.evaluate(
                "() => window.__dpPage.notifications.filter(function (n) { return n.severity === 'error'; }).length",
            ) as Number
        errorCount.toInt() shouldBe 0
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
    }

    @Test
    @Order(3)
    fun `the parameter lock refuses actions while an evaluation is pending`() {
        startTrace()
        val root = ready("dpclock")
        val board = seedBoard(root)
        // The parameters route is HELD for this case: an evaluation stays pending, deterministically.
        page.route("**/runtime/parameters") { route ->
            val response = route.fetch()
            Thread.sleep(1_500)
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setResponse(response),
            )
        }
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        val refused =
            page.evaluate(
                """
                async () => {
                  const pending = window.__dpPage.instance._evaluateParameters('parent_change').catch(function (e) { return 'pending-failed:' + (e && e.code); });
                  try { await window.__dpPage.instance.refresh({ scope: 'all' }); return 'unlocked'; } catch (e) {
                    return e && e.code;
                  } finally { await pending; }
                }
                """.trimIndent(),
            ) as String
        refused shouldBe "actions.locked"
    }

    @Test
    @Order(4)
    fun `abort through the page ends durably ABORTED and the acknowledged chip flips`() {
        startTrace()
        val root = ready("dpcabort")
        // The 60 s hang source: the refresh is RUNNING BY CONSTRUCTION when the page aborts it —
        // the old 3 s board let the abort lose the race against the sleep's end on a loaded runner,
        // and the then-optimistic chip never matched the server's word (#370's CI red).
        val board = seedHungBoard(root)
        openBoard(board)
        val refreshId =
            page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['hungcells'] })") as String
        page.waitForSelector("[data-dp-viz='hungcells'] .dp-dashboard-status[data-dp-state='in-progress']")
        // The RUNNING row before the abort, as the host half's case has always done: the abort's
        // acknowledgement is only meaningful for a refresh the read route just reported RUNNING.
        page.waitForFunction(
            """async () => {
              const res = await fetch('/api/v1/dashboards/$board/refreshes/$refreshId', { credentials: 'same-origin' });
              if (!res.ok) return false;
              const doc = await res.json();
              return doc.data && doc.data.status === 'RUNNING';
            }""",
        )
        // A false ack names the row's status and the client path that answered, not a bare boolean (#366 red 2).
        abortAndNameAck("window.__dpPage.instance", board, refreshId)
        page.waitForSelector("[data-dp-viz='hungcells'] .dp-dashboard-status[data-dp-state='abort']")
        // The DURABLE outcome, read off the real route.
        page.waitForFunction(
            """async () => {
              const res = await fetch('/api/v1/dashboards/$board/refreshes/$refreshId', { credentials: 'same-origin' });
              if (!res.ok) return false;
              const doc = await res.json();
              return doc.data && doc.data.status === 'ABORTED';
            }""",
        )
    }

    @Test
    @Order(8)
    fun `a late abort of a delivered refresh ends DONE - the page's chip settles and the row stays COMPLETED`() {
        startTrace()
        val root = ready("dpclate")
        // The OLD 3 s case, kept as its own truth (#370 + the #356 terminal frame): the abort lands
        // AFTER the refresh delivered. The page's chip was settled by the terminal frame's word, the
        // client's abort() short-circuits on its ended record — nothing turns the refresh into an
        // abort: the chip never reads abort and the row stays COMPLETED.
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        val refreshId =
            page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['slowchart'] })") as String
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed' &&" +
                " n.refreshId === '$refreshId'; })",
        )
        chipStateIsNot("slowchart", "abort")
        val delivered =
            page.evaluate(
                "() => document.querySelector('[data-dp-viz=\\'slowchart\\'] .dp-dashboard-status').getAttribute('data-dp-state')",
            ) as String
        (delivered == "success" || delivered == "ready") shouldBe true

        // The late abort, its expected shape named: the client's record has the refresh ended, so
        // abort() answers false without a POST — the page shows the delivered truth, never a click.
        val acked = page.evaluate("() => window.__dpPage.instance.abort('$refreshId')") as Map<*, *>
        val client = readClientRefresh("window.__dpPage.instance", refreshId)
        client["ended"] shouldBe true
        acked["abort_requested"] shouldBe false
        chipStateIsNot("slowchart", "abort")
        page.waitForFunction(
            """async () => {
              const res = await fetch('/api/v1/dashboards/$board/refreshes/$refreshId', { credentials: 'same-origin' });
              if (!res.ok) return false;
              const doc = await res.json();
              return doc.data && doc.data.status === 'COMPLETED';
            }""",
        )
    }

    @Test
    @Order(5)
    fun `connection loss retains the content and offers retry through the page's sink`() {
        startTrace()
        val root = ready("dpcloss")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        page.route("**/runtime/visualizations") { route -> route.abort("connectionrefused") }
        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'transport.network'; })")
        // The page's sink showed it (the refusal region is the notification region).
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.recover === 'retry'; })")
    }

    @Test
    @Order(6)
    fun `two instances of one dashboard on one page keep isolated state`() {
        startTrace()
        val root = ready("dpctwo")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        page.evaluate(
            """
            () => {
              const second = document.createElement('div');
              second.id = 'dp-board-2';
              document.body.appendChild(second);
              window.__dpPage2 = { ready: false, instance: null };
              const instance = window.DatapipelinesDashboard.init({
                server: { baseUrl: '', credentials: 'session' },
                dashboard: { id: '$board', version: 'released' },
                container: second,
                adapter: window.DatapipelinesDashboard.adapters(second),
              });
              window.__dpPage2.instance = instance;
              instance.ready.then(function () { window.__dpPage2.ready = true; }, function () {});
            }
            """.trimIndent(),
        )
        page.waitForFunction("() => window.__dpPage2 && window.__dpPage2.ready === true")
        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['revenue'] })")
        page.evaluate("() => window.__dpPage2.instance.refresh({ scope: 'targets', targets: ['revenue'] })")
        page.waitForFunction("() => document.querySelectorAll('#dp-board .plotly, #dp-board-2 .plotly').length >= 2")
        page.evaluate("() => window.__dpPage.instance.dispose()")
        page.evaluate("() => window.__dpPage2.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => document.querySelectorAll('#dp-board-2 .plotly .main-svg').length > 0")
    }

    @Test
    @Order(7)
    fun `a second init on the page's container is refused - DashboardAlreadyMounted`() {
        startTrace()
        val root = ready("dpcmount")
        val board = seedBoard(root)
        openBoard(board)
        val already =
            page.evaluate(
                """
                () => {
                  try {
                    window.DatapipelinesDashboard.init({
                      server: { baseUrl: '', credentials: 'session' },
                      dashboard: { id: '$board', version: 'released' },
                      container: document.getElementById('dp-board'),
                      adapter: window.DatapipelinesDashboard.adapters(document.getElementById('dp-board')),
                    });
                    return 'mounted-twice';
                  } catch (e) { return e.name; }
                }
                """.trimIndent(),
            ) as String
        already shouldBe "DashboardAlreadyMounted"
    }
}
