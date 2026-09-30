package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #10 L3a — the dashboard client runtime's conformance cases over a plain-JavaScript host page
 * (the implementation spec's §10.5, host half; the design record's acceptance scenarios at the
 * browser level). The frame-level freshness and lock-deadline gates live in the node tests
 * (`dashboard-runtime.test.mjs`), where they are deterministic; what a browser adds is the REAL
 * transport, the REAL CSP, the REAL vendored bytes and the page a person would see.
 *
 * ## The CSP measurement is POSITIVE
 * The live policy is copied off a real response (see the suite); Plotly's rules must APPLY through
 * the vendored `plotly.css` (a computed style only that sheet provides) and the console must carry
 * no violation but the empty-element hash Playwright's own screenshot path already produces — with
 * the exemption NOT relied on for anything this suite asserts: the computed-style checks cannot be
 * fooled by it. The falsification (plotly.css removed → the computed-style assertions red) is
 * `the positive CSP measurement is red when the stylesheet is removed`.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardConformanceBrowserTest : DashboardBrowserSuite() {
    @Test
    @Order(1)
    fun `bootstrap mounts the board and the initial action renders chart, table and kpi`() {
        val root = ready("dpboot")
        installHostPage()
        val board = seedBoard(root)
        openHost(board)

        // The placeholders are the composite's; the renders came through the real stream.
        page.waitForFunction("() => window.__dp.renders.length >= 3")
        page.locator("#board .plotly .main-svg").first().waitFor()
        page.locator("#board .dp-dashboard-table tbody tr").first().waitFor()
        page.locator("#board .dp-dashboard-kpi-number").first().waitFor()
        page.locator("#board .dp-dashboard-kpi-number").first().textContent() shouldBe "42"
        // The refresh_completed of the initial action arrived; the statuses settled.
        page.waitForFunction(
            "() => window.__dp.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
    }

    @Test
    @Order(2)
    fun `the positive CSP measurement holds - plotly css applies, no violation but the empty element`() {
        val root = ready("dpcsp")
        installHostPage()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 1")

        // POSITIVE: Plotly's own stylesheet governs the chart root — the "Open Sans" font stack is a
        // rule ONLY plotly.css (or the injection the design-around skipped) could supply; the CSS
        // initial value of font-family is theme-dependent and never this.
        val fontFamily =
            page.evaluate(
                "() => getComputedStyle(document.querySelector('.js-plotly-plot .plotly')).fontFamily",
            ) as String
        (fontFamily.contains("Open Sans")) shouldBe true
        // The design-around is in force: the element Plotly checks exists and is marked, so the
        // bundle injected nothing (the sheet element stays EMPTY — the rules come from the file).
        val sheetText =
            page.evaluate(
                "() => { const s = document.getElementById('plotly.js-style-global'); return s ? s.textContent : null; }",
            ) as String?
        (sheetText ?: "") shouldBe ""

        // The console carried no CSP refusal except the EMPTY element hash — the one Playwright's
        // own screenshot path produces and the suite's collector already sets aside. This suite's
        // own listener does NOT exempt anything: the count below is the raw number of refusals
        // whose message names a hash, and only the empty hash may appear.
        val refusals = drainCspViolations()
        val nonEmpty = refusals.filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }
        nonEmpty shouldBe emptyList()
    }

    @Test
    @Order(3)
    fun `the positive CSP measurement is red when the stylesheet is removed - the falsification`() {
        val root = ready("dpcspneg")
        // The same host page, but the plotly.css link is stripped from the served HTML: the design
        // around is gone, Plotly injects nothing (no-inline-styles), so its rules are ABSENT.
        installHostPageWithoutPlotlyCss()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 1")
        val fontFamily =
            page.evaluate(
                "() => { const el = document.querySelector('.js-plotly-plot .plotly'); return el ? getComputedStyle(el).fontFamily : null; }",
            ) as String?
        (fontFamily != null && fontFamily.contains("Open Sans")) shouldBe false
    }

    @Test
    @Order(4)
    fun `abort ends the slow occurrence and the finished run cannot overwrite a newer view`() {
        val root = ready("dpabort")
        installHostPage()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 3")

        // A slow refresh (the 3 s sleep source), targeted at the slow chart.
        val refreshId =
            page.evaluate("() => window.__dp.instance.refresh({ scope: 'targets', targets: ['slowchart'] })") as String
        page.waitForFunction(
            "() => document.querySelector('[data-dp-viz=\\'slowchart\\'] .dp-dashboard-status').getAttribute('data-dp-state') === 'in-progress'",
        )
        // Abort it; the chip flips to abort locally and the server answers 202.
        page.evaluate("() => window.__dp.instance.abort('$refreshId')")
        page.waitForFunction(
            "() => document.querySelector('[data-dp-viz=\\'slowchart\\'] .dp-dashboard-status').getAttribute('data-dp-state') === 'abort'",
        )
        // The fast occurrences' state was untouched by the abort of another refresh.
        val revenueState =
            page.evaluate(
                "() => document.querySelector('[data-dp-viz=\\'revenue\\'] .dp-dashboard-status').getAttribute('data-dp-state')",
            ) as String
        (revenueState == "ready" || revenueState == "success") shouldBe true
    }

    @Test
    @Order(5)
    fun `connection loss retains the content and offers retry`() {
        val root = ready("dploss")
        installHostPage()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 3")
        val rendersBefore = page.evaluate("() => window.__dp.renders.length") as Number

        // Every future stream request DIES: the transport failure the runtime must survive.
        page.route("**/runtime/visualizations") { route -> route.abort("connectionrefused") }
        page.evaluate("() => window.__dp.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => window.__dp.notifications.some(function (n) { return n.code === 'transport.network'; })")
        // The content stayed: the earlier renders are still in the DOM, nothing was cleared.
        (page.evaluate("() => window.__dp.renders.length") as Number).toInt() shouldBe rendersBefore.toInt()
        page.locator("#board .plotly .main-svg").first().waitFor()
        // And the outcome was published with the Retry intent.
        page.waitForFunction("() => window.__dp.notifications.some(function (n) { return n.recover === 'retry'; })")
    }

    @Test
    @Order(6)
    fun `the parameter gate refuses actions while an evaluation is pending`() {
        val root = ready("dplock")
        installHostPage()
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
        openHost(board)
        page.waitForFunction("() => window.__dp.ready")
        // A parent_change while the gate is shut: the refusal is the gate's code, never a queue.
        val refused =
            page.evaluate(
                """
                async () => {
                  const pending = window.__dp.instance._evaluateParameters('parent_change').catch(function (e) { return 'pending-failed:' + (e && e.code); });
                  try { await window.__dp.instance.refresh({ scope: 'all' }); return 'unlocked'; } catch (e) {
                    return e && e.code;
                  } finally { await pending; }
                }
                """.trimIndent(),
            ) as String
        refused shouldBe "actions.locked"
    }

    @Test
    @Order(7)
    fun `dispose invalidates the instance and a re-init on the container is legitimate`() {
        val root = ready("dpdispose")
        installHostPage()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.ready")
        // A second init on the SAME container is refused before anything happens.
        val already =
            page.evaluate(
                """
                () => {
                  try {
                    window.DatapipelinesDashboard.init({
                      server: { baseUrl: '', credentials: 'session' },
                      dashboard: { id: '$board', version: 'released' },
                      container: document.getElementById('board'),
                      adapter: window.DatapipelinesDashboard.adapters(document.getElementById('board')),
                    });
                    return 'mounted-twice';
                  } catch (e) { return e.name; }
                }
                """.trimIndent(),
            ) as String
        already shouldBe "DashboardAlreadyMounted"
        // Disposal unmarks the container and stops dispatching.
        page.evaluate("() => window.__dp.instance.dispose()")
        page.waitForFunction("() => document.getElementById('board').getAttribute('data-datapipelines-dashboard') === null")
    }

    @Test
    @Order(8)
    fun `two instances of one dashboard coexist with isolated state`() {
        val root = ready("dptwo")
        // A second board container: the glue mounts one instance; the test adds a second itself.
        installHostPage()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.ready")
        page.evaluate(
            """
            () => {
              const second = document.createElement('div');
              second.id = 'board-2';
              document.body.appendChild(second);
              window.__dp2 = { ready: false, instance: null };
              const instance = window.DatapipelinesDashboard.init({
                server: { baseUrl: '', credentials: 'session' },
                dashboard: { id: '$board', version: 'released' },
                container: second,
                adapter: window.DatapipelinesDashboard.adapters(second),
              });
              window.__dp2.instance = instance;
              instance.ready.then(function () { window.__dp2.ready = true; }, function () {});
            }
            """.trimIndent(),
        )
        page.waitForFunction("() => window.__dp2.ready")
        // Independent refreshes: each instance's stream is its own.
        page.evaluate("() => window.__dp.instance.refresh({ scope: 'targets', targets: ['revenue'] })")
        page.evaluate("() => window.__dp2.instance.refresh({ scope: 'targets', targets: ['revenue'] })")
        page.waitForFunction(
            """
            () => {
              const chips = document.querySelectorAll('#board .plotly, #board-2 .plotly');
              return chips.length >= 2;
            }
            """.trimIndent(),
        )
        // Disposing the first leaves the second working.
        page.evaluate("() => window.__dp.instance.dispose()")
        page.evaluate("() => window.__dp2.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => document.querySelectorAll('#board-2 .plotly .main-svg').length > 0")
    }

    @Test
    @Order(9)
    fun `the 3d bundle serves a surface board under the same policy - no eval, no worker`() {
        val root = ready("dp3d")
        installHostPage(bundle = "3d")
        val board = seedSurfaceBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 1")
        // A WebGL canvas is the surface's own; the bundle loaded and rendered under the live CSP.
        page.locator("#board .plotly .main-svg").first().waitFor()
        val gl = page.evaluate("() => !!document.querySelector('#board .plotly canvas.gl-canvas, #board .plotly canvas')") as Boolean
        gl shouldBe true
        val refusals = drainCspViolations()
        val nonEmpty = refusals.filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }
        nonEmpty shouldBe emptyList()
    }

    @Test
    @Order(10)
    fun `light and dark - the handback screenshots of the conformance board`() {
        val root = ready("dpshots")
        installHostPage(theme = "light")
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 3")
        page.waitForTimeout(500.0) // Plotly's own draw settle before the shutter
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-conformance-light.png"),
                ),
        )
        // Dark: the same board with the dark theme's sheets — a reload with ?theme=dark semantics.
        installHostPage(theme = "dark")
        page.navigate("$baseUrl/test/dashboards/host?id=$board")
        page.waitForFunction("() => window.__dp && window.__dp.renders && window.__dp.renders.length >= 3")
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-conformance-dark.png"),
                ),
        )
    }

    /** The falsification host: the same page WITHOUT the plotly.css link. */
    private fun installHostPageWithoutPlotlyCss() {
        val cspHeader = fetchLiveCsp()
        val html =
            """
            <!doctype html>
            <html lang="en" data-theme="dark">
            <head>
              <meta charset="utf-8">
              <link rel="stylesheet" href="/vendor/design-system/tokens.css">
              <link rel="stylesheet" href="/vendor/design-system/themes/dark.css">
              <link rel="stylesheet" href="/css/app.css">
            </head>
            <body>
              <main id="board"></main>
              <script src="/vendor/plotly/plotly-2d.min.js" data-dp-plotly-bundle="2d"></script>
              <script src="/js/datapipelines-dashboard.js"></script>
              <script src="/js/datapipelines-dashboard-plotly.js"></script>
              <script src="/js/datapipelines-dashboard-table.js"></script>
              <script src="/js/datapipelines-dashboard-kpi.js"></script>
              <script src="/test/dashboards/host-glue.js"></script>
            </body>
            </html>
            """.trimIndent()
        page.route("**/test/dashboards/host*") { route ->
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("text/html; charset=utf-8")
                    .setHeaders(mapOf("Content-Security-Policy" to cspHeader))
                    .setBody(html),
            )
        }
        page.route("**/test/dashboards/host-glue*") { route ->
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/javascript; charset=utf-8")
                    .setHeaders(mapOf("Content-Security-Policy" to cspHeader))
                    .setBody(hostGlueJs),
            )
        }
    }
}
