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
                "() => { const el = document.querySelector('.js-plotly-plot .plotly');" +
                    " return el ? getComputedStyle(el).fontFamily : null; }",
            ) as String?
        (fontFamily != null && fontFamily.contains("Open Sans")) shouldBe false
    }

    @Test
    @Order(4)
    fun `abort is acknowledged by the real handler, ends durably ABORTED, and a finished refresh is a genuine 404`() {
        val root = ready("dpabort")
        installHostPage()
        val board = seedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 3")

        // A slow refresh (the 3 s sleep source), targeted at the slow chart.
        val refreshId =
            page.evaluate("() => window.__dp.instance.refresh({ scope: 'targets', targets: ['slowchart'] })") as String
        chipStateIs("slowchart", "in-progress")
        // The abort needs the server's row to exist: an abort that outruns the stream POST's row
        // write is 404 not_found (the finding recorded on #10) — wait for the row, deterministically.
        page.waitForFunction(
            """async () => {
              const res = await fetch('/api/v1/dashboards/$board/refreshes/$refreshId', { credentials: 'same-origin' });
              if (!res.ok) return false;
              const doc = await res.json();
              return doc.data && doc.data.status === 'RUNNING';
            }""",
        )
        // Abort it; the chip flips to abort locally and the REAL handler answers 202 (the runtime's
        // own abort promise resolving is the acknowledgement, not a stub's).
        val acked =
            page.evaluate(
                "() => window.__dp.instance.abort('$refreshId')",
            ) as Map<*, *>
        acked["abort_requested"] shouldBe true
        chipStateIs("slowchart", "abort")
        // The DURABLE outcome: the refresh row the server owns reads ABORTED (poll the real read route).
        page.waitForFunction(
            """async () => {
              const res = await fetch('/api/v1/dashboards/$board/refreshes/$refreshId', { credentials: 'same-origin' });
              if (!res.ok) return false;
              const doc = await res.json();
              return doc.data && doc.data.status === 'ABORTED';
            }""",
        )
        // The fast occurrences' state was untouched by the abort of another refresh.
        val revenueState =
            page.evaluate(
                "() => document.querySelector('[data-dp-viz=\\'revenue\\'] .dp-dashboard-status').getAttribute('data-dp-state')",
            ) as String
        (revenueState == "ready" || revenueState == "success") shouldBe true
        // A genuine ALREADY-FINISHED abort against the real route: the finished refresh is the 404
        // idempotence — no substring stub answers for the handler here.
        val finished =
            page.evaluate(
                """async () => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/dashboards/$board/runtime/refreshes/$refreshId/abort', {
                    method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify({ instance_id: window.__dp.instance._instanceId }) });
                  const doc = await res.json();
                  return { status: res.status, code: (doc.error && doc.error.code) || null };
                }""",
            ) as Map<*, *>
        (finished["status"] as Number).toInt() shouldBe 404
        finished["code"] shouldBe "dashboard.refresh.not_found"
    }

    @Test
    @Order(5)
    fun `an abort before the row is a 202 and the released start ends ABORTED on the page`() {
        val root = ready("dpwindow")
        installHostPage()
        val board = seedHungBoard(root)
        openHost(board)

        // The workspace's refresh places (the deployment default is four) are HELD by four hung
        // refreshes started as raw stream POSTs, so the instance's own refresh blocks INSIDE
        // admission — after its start marker, before the row: the #356 window, forced, not raced.
        val holders = startHolderStreams(board)
        awaitHolderRows(board, holders, "RUNNING")

        // The instance's own refresh: minted, claimed, and blocked inside admission with no row.
        val refreshId = page.evaluate("() => window.__dp.instance.refresh({ scope: 'all' })") as String
        awaitStartMarker(board, refreshId)

        // The immediate abort: a 202 with the requested reason on the chip — the click does not
        // claim the refresh ended.
        val acked = page.evaluate("() => window.__dp.instance.abort('$refreshId')") as Map<*, *>
        acked["abort_requested"] shouldBe true
        chipStateIs("hungcells", "abort")

        // Release: one holder's abort frees its place; the held start admits, its row is inserted
        // and its recorded intent ends it ABORTED before any source ran.
        abortHolder(board, holders.first())
        awaitRefreshStatus(board, refreshId, "ABORTED")
        chipStateIs("hungcells", "abort")
        page.waitForFunction(
            "() => window.__dp.notifications.some(function (n) { return n.code === 'refresh.aborted'; })",
        )
        val row = readRow(board, refreshId)
        (row["finished"] as String) shouldBe "true"
        drainCspViolations().filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") } shouldBe emptyList()

        // Cleanup: every holder aborted, so no place outlives this case for the others.
        val remaining = holders.drop(1)
        remaining.forEach { abortHolder(board, it) }
        awaitHolderRows(board, remaining, "ABORTED")
    }

    @Test
    @Order(6)
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

    @Test
    @Order(11)
    fun `a parameterized dashboard renders controls, commits, executes the accepted revision and resets`() {
        val root = ready("dpparam")
        installHostPage()
        val board = seedParameterisedBoard(root)
        // Pass-through interception: the request BODIES are read for the assertions while the real
        // routes flow untouched (the transport is the oracle, never a callback count).
        val evaluateBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val refreshBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        page.route("**/runtime/parameters") { route ->
            route.request().postData()?.let(evaluateBodies::add)
            route.resume()
        }
        page.route("**/runtime/visualizations") { route ->
            route.request().postData()?.let(refreshBodies::add)
            route.resume()
        }
        openHost(board)
        // The control rendered from the REAL evaluate response (flat definition + state), the
        // default option selected.
        page.waitForFunction(
            "() => document.querySelectorAll('#board .dp-dashboard-parameter select').length === 1",
        )
        val selections =
            page.evaluate(
                "() => window.__dp.instance._adapter.readSelections()",
            ) as Map<*, *>
        selections["country"] shouldBe "USA"
        page.waitForFunction("() => window.__dp.renders.length >= 1") // the initial action ran with the default
        // The bootstrap evaluate POST carried the bootstrap intent and the empty first selections
        // (the body string is parsed where a JSON parser already lives — the page itself).
        val bootstrap =
            page.evaluate(
                """(body) => {
                  const doc = JSON.parse(body);
                  return { intent: doc.intent, selections: JSON.stringify(doc.selections) };
                }""",
                evaluateBodies.first(),
            ) as Map<*, *>
        bootstrap["intent"] shouldBe "bootstrap"
        bootstrap["selections"] shouldBe "{}"
        // Commit CAN through the control: one change gesture, then a programmatic refresh.
        page.selectOption("#board .dp-dashboard-parameter select", "1") // the CAN option (its INDEX)
        page.evaluate("() => window.__dp.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => window.__dp.renders.length >= 2")
        // The refresh carried the COMMITTED typed selection and the ACCEPTED revision (1).
        val refresh =
            page.evaluate(
                """(body) => {
                  const doc = JSON.parse(body);
                  return { selections: JSON.stringify(doc.selections), revision: doc.parameter_revision };
                }""",
                refreshBodies.last(),
            ) as Map<*, *>
        refresh["selections"] shouldBe """{"country":"CAN"}"""
        (refresh["revision"] as Number).toInt() shouldBe 1
        // The pipeline EXECUTED with the selection: the chart's bound column carries CAN's data.
        page.waitForFunction(
            """() => {
              const plot = document.querySelector('#board .js-plotly-plot');
              return plot && plot.data && plot.data[0] && plot.data[0].x && plot.data[0].x[0] === 'CAN';
            }""",
        )
        // Reset restores the APPLIED baseline: the control returns to the committed default.
        page.evaluate("() => window.__dp.instance.reset()")
        page.waitForFunction(
            "() => window.__dp.instance._adapter.readSelections().country === 'USA'",
        )
    }

    @Test
    @Order(12)
    fun `light and dark - the handback screenshots of the PARAMETERIZED board`() {
        val root = ready("dpparamshot")
        installHostPage(theme = "light")
        val board = seedParameterisedBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 1")
        page.waitForTimeout(500.0) // Plotly's draw settle
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-parameterized-light.png"),
                ),
        )
        installHostPage(theme = "dark")
        page.navigate("$baseUrl/test/dashboards/host?id=$board")
        page.waitForFunction("() => window.__dp && window.__dp.renders && window.__dp.renders.length >= 1")
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-parameterized-dark.png"),
                ),
        )
    }

    /** The slow chart's status chip has reached [state]; the wait synchronises on the chip, never on time. */
    private fun chipStateIs(
        occurrence: String,
        state: String,
    ) {
        page.waitForFunction(
            """() => (function () {
              const el = document.querySelector('[data-dp-viz="$occurrence"] .dp-dashboard-status');
              return el ? el.getAttribute('data-dp-state') : null;
            })() === "$state"""",
        )
    }

    /** The selections map of the LAST intercepted stream body, parsed where a parser lives — the page. */
    private fun lastSelections(bodies: MutableList<String>): Map<*, *> =
        page.evaluate(
            "(body) => JSON.parse(body).selections",
            bodies.last(),
        ) as Map<*, *>

    @Test
    @Order(13)
    fun `a boolean input keeps null null, commits the visible boolean as a wire boolean and reset restores the baseline`() {
        val root = ready("dpbool")
        installHostPage()
        val board = seedControlsBoard(root)
        // Pass-through interception: the stream bodies are read for the wire assertions while the
        // real routes flow untouched (case 11's mould — the transport is the oracle).
        val refreshBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        page.route("**/runtime/visualizations") { route ->
            route.request().postData()?.let(refreshBodies::add)
            route.resume()
        }
        openHost(board)
        page.waitForFunction(
            "() => document.querySelectorAll('#board [data-dp-parameter=\"enabled\"] select').length === 1",
        )
        // The initial action's refresh SETTLES before the first gesture: the bootstrap's parameter
        // round trip can delay it until after this case's own refresh, and the freshness rule —
        // the newest claim owns the occurrence — would then (correctly) drop this case's data
        // frame. Synchronise on the event, never on the interleaving.
        page.waitForFunction(
            "() => window.__dp.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
        // The unresolved null: displayed as the unset option, read as null — never as false — and
        // the control offers exactly the three wire states.
        val initial =
            page.evaluate(
                "() => { const s = document.querySelector('#board [data-dp-parameter=\"enabled\"] select');" +
                    " return { displayed: s.value, options: Array.from(s.options).map(function (o) { return o.value; })," +
                    " read: window.__dp.instance._adapter.readSelections().enabled }; }",
            ) as Map<*, *>
        initial["displayed"] shouldBe ""
        initial["read"] shouldBe null
        @Suppress("UNCHECKED_CAST")
        val options = initial["options"] as List<*>
        options shouldBe listOf("", "true", "false")
        // The visible edit travels as the WIRE BOOLEAN true on the real stream POST (not "true").
        page.selectOption("#board [data-dp-parameter=\"enabled\"] select", "true")
        page.evaluate("() => window.__dp.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => window.__dp.renders.length >= 2")
        lastSelections(refreshBodies)["enabled"] shouldBe true
        // Unset again: the wire carries null, not false.
        page.selectOption("#board [data-dp-parameter=\"enabled\"] select", "")
        page.evaluate("() => window.__dp.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => window.__dp.renders.length >= 3")
        lastSelections(refreshBodies)["enabled"] shouldBe null
        // Reset restores the APPLIED baseline: the control is unset and reads null again.
        page.evaluate("() => window.__dp.instance.reset()")
        page.waitForFunction(
            "() => window.__dp.instance._adapter.readSelections().enabled === null &&" +
                " document.querySelector('#board [data-dp-parameter=\"enabled\"] select').value === ''",
        )
    }

    @Test
    @Order(14)
    fun `two instances keep independent radio groups in one real document`() {
        val root = ready("dpradio")
        installHostPage()
        val board = seedControlsBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.ready")
        // A second instance of the SAME board in the SAME document (case 8's mould): two adapters,
        // one parameter name, the collision the delivered group names had.
        mountSecondBoard(board)
        page.waitForFunction("() => window.__dp2.ready")
        page.waitForFunction(
            "() => document.querySelectorAll('#board [data-dp-parameter=\"granularity\"] input[type=radio]').length === 3" +
                " && document.querySelectorAll('#board-2 [data-dp-parameter=\"granularity\"] input[type=radio]').length === 3",
        )
        // The two instances' groups carry DIFFERENT name attributes in the real DOM.
        val names =
            page.evaluate(
                "() => ({ a: document.querySelector('#board [data-dp-parameter=\"granularity\"] input').name," +
                    " b: document.querySelector('#board-2 [data-dp-parameter=\"granularity\"] input').name })",
            ) as Map<*, *>
        (names["a"] as String).isNotEmpty() shouldBe true
        org.junit.jupiter.api.Assertions
            .assertNotEquals(names["a"], names["b"], "the group names differ across instances")
        // Real clicks: instance 1 picks MONTH (third radio), instance 2 picks DAY (first).
        page.click("#board [data-dp-parameter=\"granularity\"] input[type=radio] >> nth=2")
        page.click("#board-2 [data-dp-parameter=\"granularity\"] input[type=radio] >> nth=0")
        val reads =
            page.evaluate(
                "() => ({ a: window.__dp.instance._adapter.readSelections().granularity," +
                    " b: window.__dp2.instance._adapter.readSelections().granularity })",
            ) as Map<*, *>
        reads["a"] shouldBe "MONTH"
        reads["b"] shouldBe "DAY"
        // Native grouping WITHIN one instance: exactly one radio checked per group, and instance 1's
        // group never moved instance 2's.
        val checked =
            page.evaluate(
                "() => ({ a: document.querySelectorAll('#board [data-dp-parameter=\"granularity\"] input:checked').length," +
                    " b: document.querySelectorAll('#board-2 [data-dp-parameter=\"granularity\"] input:checked').length })",
            ) as Map<*, *>
        (checked["a"] as Number).toInt() shouldBe 1
        (checked["b"] as Number).toInt() shouldBe 1
        // A re-render of one instance (its re-evaluation) keeps its group and its committed
        // selection; the other instance's selection stands untouched.
        page.evaluate("() => window.__dp2.instance._evaluateParameters('parent_change')")
        page.waitForFunction(
            "() => document.querySelectorAll('#board-2 [data-dp-parameter=\"granularity\"] input').length === 3" +
                " && window.__dp2.instance._adapter.readSelections().granularity === 'DAY'",
        )
        page.evaluate("() => window.__dp.instance._adapter.readSelections().granularity") shouldBe "MONTH"
        // Disposal of the first leaves the second's group fully functional.
        page.evaluate("() => window.__dp.instance.dispose()")
        page.waitForFunction("() => document.querySelectorAll('#board .dp-dashboard').length === 0")
        page.click("#board-2 [data-dp-parameter=\"granularity\"] input[type=radio] >> nth=2")
        page.waitForFunction("() => window.__dp2.instance._adapter.readSelections().granularity === 'MONTH'")
    }

    /** Case 8's mould: a second instance of [board] on a fresh container, signalled at `window.__dp2`. */
    private fun mountSecondBoard(board: String) {
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
    }

    @Test
    @Order(15)
    fun `light and dark - the handback screenshots of the controls board`() {
        val root = ready("dpctrlshot")
        installHostPage(theme = "light")
        val board = seedControlsBoard(root)
        openHost(board)
        page.waitForFunction("() => window.__dp.renders.length >= 1")
        page.waitForTimeout(500.0) // Plotly's own draw settle before the shutter
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-controls-light.png"),
                ),
        )
        installHostPage(theme = "dark")
        page.navigate("$baseUrl/test/dashboards/host?id=$board")
        page.waitForFunction("() => window.__dp && window.__dp.renders && window.__dp.renders.length >= 1")
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-controls-dark.png"),
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
