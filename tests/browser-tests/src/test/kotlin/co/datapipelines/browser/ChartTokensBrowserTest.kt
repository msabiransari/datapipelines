package co.datapipelines.browser

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #987 (js-rendered F3, D1a, D6) — the chart's tokens on the page, look contract §10 LA4 (G9), LA6 (G10) and the two
 * rows of LA11 that start from a stylesheet. Every value is set on an ANCESTOR of the chart (the preview's case
 * section) through the CSS object model, which no policy governs, and read back three ways: what the adapter's resolver
 * hands the engine (`DatapipelinesDashboardChart._internal.resolveTheme` at the chart's element, then the engine's own
 * `theme.resolve`, which supplies each default), the warnings the adapter publishes for it (`warningsOf`), and, where
 * the contract says what is DRAWN, the drawn element's computed style after a redraw.
 *
 * LA4's one clause on the tooltip's `filter` (`--chart-tip-shadow`) is lane 990's: the tooltip does not exist here.
 */
class ChartTokensBrowserTest : ChartPageSuite() {
    /** LA4 / G9 — every row of the contract's §3.7 example table, the three cases, and the opaque kind. */
    @Test
    fun `LA4 - every row of the contract's example table resolves to its one result, refused values each name their token`() {
        startTrace()
        val root = ready("jsrla4")
        val agent = chartContext()
        try {
            val page = preview(agent, documentOf("$root/charts/tokens", ChartBrowserFixtures.fixture("bar-vertical")))
            withClue("the root font size the rem rows assume") { page.evaluate("() => getComputedStyle(document.documentElement).fontSize") shouldBe "16px" }
            val results = page.evaluate(RESOLVE_ROWS, mapOf("sel" to CASE, "rows" to TokenRows.RESOLVER)) as List<*>
            report("la4-resolver.txt", results.map { it.toString() })
            withClue("non-vacuity: every row ran") { results.size shouldBe TokenRows.RESOLVER.size }
            assertSoftly {
                results.forEach { result ->
                    val row = result as Map<*, *>
                    withClue("row ${row["row"]}: $row") {
                        row["error"] shouldBe null
                        row["declared"] shouldBe true
                        row["ok"] shouldBe true
                        row["warningsOk"] shouldBe true
                    }
                }
            }
            page.close()
        } finally {
            agent.close()
        }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * LA4 / G9 — a token declared NOWHERE: the page's stylesheet declares every chart token, so this row runs on the
     * suite's host page with the design system's sheets only (0 chart tokens in them, LA12): no chart token reaches
     * the engine and nothing is refused. The one member of the engine's token list those sheets do declare is the
     * design system's own `--font-sans` (contract §5.1: "the design system's own token and is not set again"),
     * so it is the one token handed over.
     */
    @Test
    fun `LA4 - a token declared nowhere is not declared - nothing is handed to the engine, nothing is refused`() {
        ready("jsrla4n")
        installHostPage(theme = "light", stylesheets = HOST_STYLESHEETS.filter { it.startsWith("/vendor/") })
        page.navigate("$baseUrl/test/dashboards/host")
        page.waitForFunction("() => !!window.DatapipelinesDashboardChart && !!window.JsRendered")
        val read =
            page.evaluate(
                """() => { var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); document.body.appendChild(svg);
                  var r = window.DatapipelinesDashboardChart._internal.resolveTheme(svg); svg.remove();
                  return { tokens: Object.keys(r.theme.tokens).sort(), refused: r.refused.length, known: Object.keys(window.JsRendered.theme.TOKENS).length }; }""",
            ) as Map<*, *>
        report("la4-nowhere.txt", listOf(read.toString()))
        withClue("declared nowhere: $read") {
            read["tokens"] shouldBe listOf("--font-sans")
            read["refused"] shouldBe 0
            ((read["known"] as Number).toInt() > 0) shouldBe true
        }
    }

    /**
     * LA4 / G9 — what is DRAWN: a refused grid colour draws the default grid colour (never the text colour the element
     * inherits), a refused text size draws the default size, and the chart's card pads by CSS's own reading of
     * `--chart-pad` (16 px from the stylesheet's fallback; 0 for a value `padding` does not take).
     */
    @Test
    fun `LA4 - a refused grid colour and text size are drawn at their defaults, and the card pads by CSS's reading of --chart-pad`() {
        startTrace()
        val root = ready("jsrla4d")
        val agent = chartContext()
        try {
            val page = preview(agent, documentOf("$root/charts/drawn", ChartBrowserFixtures.fixture("bar-vertical")))
            val drawn = mutableListOf<String>()
            assertSoftly {
                for ((decls, expected) in TokenRows.DRAWN) {
                    page.evaluate(SET, mapOf("sel" to CASE, "decls" to decls))
                    page.evaluate(REDRAW)
                    val read = page.evaluate(DRAWN_STYLE, CASE) as Map<*, *>
                    drawn += "$decls $read"
                    withClue("$decls drawn: $read") {
                        read["error"] shouldBe null
                        read["grid"] shouldBe expected.grid
                        read["tickSize"] shouldBe expected.tickSize
                    }
                    page.evaluate(UNSET, mapOf("sel" to CASE, "decls" to decls))
                }
                for ((decls, padding) in TokenRows.PADDING) {
                    page.evaluate(SET, mapOf("sel" to CASE, "decls" to decls))
                    val read = page.evaluate(PADDING, CASE) as Map<*, *>
                    drawn += "$decls $read"
                    withClue("$decls padding: $read") {
                        read["declared"] shouldBe decls.isNotEmpty()
                        read["padding"] shouldBe padding
                    }
                    page.evaluate(UNSET, mapOf("sel" to CASE, "decls" to decls))
                }
            }
            report("la4-drawn.txt", drawn)
            page.close()
        } finally {
            agent.close()
        }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * LA6 / G10 — the hostile stylesheet (a constructed sheet over the case, CSSOM): `--chart-grid: url(…)` and
     * `--chart-text: expression(1)` are refused and drawn at their defaults, the font name is inert text in an
     * attribute; 0 script elements in the picture, 0 requests to the address, 0 policy violations, no dialog.
     */
    @Test
    fun `LA6 - a hostile stylesheet draws the defaults, its font name inert - no script, no request, no violation`() {
        startTrace()
        val root = ready("jsrla6")
        val agent = chartContext()
        val requests = CopyOnWriteArrayList<String>()
        val dialogs = CopyOnWriteArrayList<String>()
        try {
            agent.onRequest { if (it.url().contains(HOSTILE_HOST)) requests += it.url() }
            agent.onPage { it.onDialog { dialog -> dialogs += dialog.message().also { dialog.dismiss() } } }
            val page = preview(agent, documentOf("$root/charts/hostile_sheet", ChartBrowserFixtures.fixture("bar-vertical")))
            page.evaluate(ADOPT, mapOf("sel" to CASE, "rule" to TokenRows.HOSTILE_SHEET))
            page.evaluate(REDRAW)
            page.waitForTimeout(SETTLE_MS)
            val read = page.evaluate(HOSTILE_READ, CASE) as Map<*, *>
            report("la6.txt", listOf("$read", "requests=$requests", "dialogs=$dialogs"))
            withClue("the hostile sheet's outcome: $read") {
                read["error"] shouldBe null
                read["refused"] shouldBe listOf("--chart-grid", "--chart-text")
                read["grid"] shouldBe TokenRows.DEFAULT_GRID
                read["tickSize"] shouldBe "12"
                read["fontInert"] shouldBe true
                read["scripts"] shouldBe 0
            }
            page.close()
        } finally {
            agent.close()
        }
        withClue("requests to the hostile address") { requests.shouldBeEmpty() }
        withClue("dialogs") { dialogs.shouldBeEmpty() }
        drainCspViolations().shouldBeEmpty()
    }

    /**
     * LA11's two rows that start from a stylesheet: a transparent on-fill ink and a series colour carrying alpha reach
     * the engine as their defaults, each with one `chart.token-refused` naming it (no type of this lane draws a text on
     * a fill; lane T2 repeats the rows on a drawn label).
     */
    @Test
    fun `LA11 - a transparent on-fill ink and a series colour with alpha reach the engine as their defaults, each reported`() {
        startTrace()
        val root = ready("jsrla11")
        val agent = chartContext()
        try {
            val page = preview(agent, documentOf("$root/charts/inks", ChartBrowserFixtures.fixture("bar-vertical")))
            val results = page.evaluate(RESOLVE_ROWS, mapOf("sel" to CASE, "rows" to TokenRows.LA11)) as List<*>
            report("la11.txt", results.map { it.toString() })
            results.size shouldBe TokenRows.LA11.size
            results.forEach { result ->
                val row = result as Map<*, *>
                withClue("row ${row["row"]}: $row") {
                    row["error"] shouldBe null
                    row["ok"] shouldBe true
                    row["warningsOk"] shouldBe true
                    (row["refused"] as List<*>).size shouldBe 1
                }
            }
            page.close()
        } finally {
            agent.close()
        }
        drainCspViolations().shouldBeEmpty()
    }

    private fun report(
        name: String,
        lines: List<String>,
    ) {
        Files.createDirectories(REPORTS)
        Files.write(REPORTS.resolve(name), lines)
    }

    private companion object {
        val REPORTS: Path = Paths.get("build", "reports", "js-rendered-page")
        const val HOSTILE_HOST = "example.invalid"
        const val SETTLE_MS = 500.0

        /** Sets each declaration of `decls` on the case section (an ancestor of the chart) through the CSS object model. */
        const val SET =
            """(args) => { var el = document.querySelector(args.sel);
              Object.keys(args.decls).forEach(function (k) { if (k === 'font-size') el.style.fontSize = args.decls[k]; else el.style.setProperty(k, args.decls[k]); });
              return true; }"""

        /** Takes the declarations of `decls` off the case section again. */
        const val UNSET =
            """(args) => { var el = document.querySelector(args.sel);
              Object.keys(args.decls).forEach(function (k) { el.style.removeProperty(k); }); return true; }"""

        /**
         * Each row: its declarations set, the resolver run at the chart's element, the engine's value for each expected
         * token (the resolver's value, else the engine's default), the refused list against the row's, and the adapter's
         * warnings: one `chart.token-refused` per refused token, naming it. A row's `expect` is a number or an
         * `[r, g, b(, a)]` list per token.
         */
        const val RESOLVE_ROWS =
            """(args) => {
              var el = document.querySelector(args.sel), svg = el.querySelector('svg.dp-chart'), A = window.DatapipelinesDashboardChart._internal;
              return args.rows.map(function (row) {
                var out = { row: row.label };
                Object.keys(row.decls).forEach(function (k) { if (k === 'font-size') el.style.fontSize = row.decls[k]; else el.style.setProperty(k, row.decls[k]); });
                try {
                  out.declared = Object.keys(row.decls).every(function (k) { return k === 'font-size' || Array.from(el.style).indexOf(k) >= 0; });
                  var r = A.resolveTheme(svg), engine = window.JsRendered.theme.resolve(r.theme);
                  out.values = {}; out.refused = r.refused.slice().sort();
                  var ok = JSON.stringify(out.refused) === JSON.stringify(row.refused.slice().sort());
                  Object.keys(row.expect).forEach(function (k) { out.values[k] = engine.n(k); if (JSON.stringify(engine.n(k)) !== JSON.stringify(row.expect[k])) ok = false; });
                  out.ok = ok;
                  var warned = A.warningsOf({ warnings: [] }, r.refused).filter(function (w) { return w.code === 'chart.token-refused'; }).map(function (w) { return w.token; }).sort();
                  out.warningsOk = JSON.stringify(warned) === JSON.stringify(out.refused);
                } catch (e) { out.error = String(e); }
                Object.keys(row.decls).forEach(function (k) { el.style.removeProperty(k); });
                return out;
              });
            }"""

        /** The drawn grid line's computed stroke and the first tick's font size, after the redraw. */
        const val DRAWN_STYLE =
            """(sel) => { var svg = document.querySelector(sel + ' svg.dp-chart'), line = svg.querySelector('line.dp-grid'), tick = svg.querySelector('text.dp-tick');
              return { error: window.__drawn && window.__drawn.error ? window.__drawn.error : null,
                grid: line ? getComputedStyle(line).stroke : null, tickSize: tick ? tick.getAttribute('font-size') : null }; }"""

        /** The chart card's computed padding (top and left) and whether the section declares `--chart-pad`. */
        const val PADDING =
            """(sel) => { var el = document.querySelector(sel), card = el.querySelector('svg.dp-chart').closest('.dp-dashboard-card'), s = getComputedStyle(card);
              return { declared: Array.from(el.style).indexOf('--chart-pad') >= 0, padding: s.paddingTop + ' ' + s.paddingLeft }; }"""

        /** Adopts a constructed sheet holding `rule`'s declarations for the case section (CSSOM; no policy governs it). */
        const val ADOPT =
            """(args) => { var sheet = new CSSStyleSheet(); sheet.replaceSync(args.sel + ' { ' + args.rule + ' }');
              document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]); return true; }"""

        /** The hostile sheet's outcome on the page. */
        const val HOSTILE_READ =
            """(sel) => { var el = document.querySelector(sel), svg = el.querySelector('svg.dp-chart');
              var r = window.DatapipelinesDashboardChart._internal.resolveTheme(svg);
              var line = svg.querySelector('line.dp-grid'), tick = svg.querySelector('text.dp-tick');
              var family = tick ? tick.getAttribute('font-family') : '';
              return { error: window.__drawn && window.__drawn.error ? window.__drawn.error : null, refused: r.refused.slice().sort(),
                grid: line ? getComputedStyle(line).stroke : null, tickSize: tick ? tick.getAttribute('font-size') : null,
                family: family, fontInert: family.indexOf('<script>') >= 0 && tick.children.length === 0,
                scripts: svg.querySelectorAll('script').length }; }"""
    }
}
