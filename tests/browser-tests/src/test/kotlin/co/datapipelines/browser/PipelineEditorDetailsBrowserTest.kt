package co.datapipelines.browser

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/**
 * 080 — the editor v2 P0 shape: open the editor VIA THE EXPLORER, select a node,
 * and see its Details in the dock. The pipeline is a one-node CALCULATOR pipeline
 * seeded through the REST surface (cookie session + the double-submit CSRF pair,
 * the same contract the editor's own fetches use) — self-contained: no datasource,
 * no template, and its Details pane content is entirely client-side.
 */
class PipelineEditorDetailsBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("ped-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("pedws-" + generatedPassword("w").take(8).lowercase())
    }

    /** Creates the one-node pipeline through POST /api/v1/pipelines, in-page so the
     *  session cookie and the dp_csrf double-submit pair apply. Returns the API status. */
    private fun seedCalculatorPipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const res = await fetch('/api/v1/pipelines', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                  'Content-Type': 'application/json',
                  'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                },
                body: JSON.stringify({
                  name,
                  display_name: name,
                  nodes: [{
                    id: 'fq',
                    type: 'CALCULATOR',
                    kind: 'fiscal_quarter',
                    context_key: 'run_fiscal_quarter',
                    inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
                  }],
                }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    /**
     * Everything painted over the stage's bottom-left corner, as GEOMETRY. Elements
     * inside `#cy-canvas` are excluded: the graph layer pans under the whole stage and
     * a card legitimately drifts over the legend. A visually-hidden box (the clipped
     * picker is 1x1) is excluded too — it paints nothing; whether it is REVEALED is
     * asserted separately, by its own height.
     */
    private val overlapProbe =
        """() => {
          const legend = document.querySelector('.pe-legend');
          const canvas = document.getElementById('cy-canvas');
          const lr = legend.getBoundingClientRect();
          const bad = [];
          const clipped = (el) => {
            // A visually-hidden box (1x1 + clip) paints nothing, and neither do its
            // children — whose own rects are full size and sit at the clipped box's
            // corner. Geometry alone would report those as covering the legend.
            for (let n = el; n && n !== document.body; n = n.parentElement) {
              const r = n.getBoundingClientRect();
              if (r.width <= 1 || r.height <= 1) return true;
            }
            return false;
          };
          document.querySelectorAll('.pe-stage *').forEach((el) => {
            if (el === legend || legend.contains(el) || el.contains(legend)) return;
            if (el === canvas || canvas.contains(el)) return;
            const cs = getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return;
            const r = el.getBoundingClientRect();
            if (r.width <= 1 || r.height <= 1 || clipped(el)) return;
            if (r.left < lr.right && r.right > lr.left && r.top < lr.bottom && r.bottom > lr.top) {
              bad.push(el.id || String(el.className));
            }
          });
          return bad;
        }"""

    private fun overlappingTheLegend(): List<*> = page.evaluate(overlapProbe) as List<*>

    private fun pickerHeight(): Double {
        val probe = """() => document.getElementById('pe-node-list').getBoundingClientRect().height"""
        return page.evaluate(probe).toString().toDouble()
    }

    /** A two-CALCULATOR pipeline: the second depends on the first, so there is an EDGE
     *  to read a colour off. Still self-contained — no datasource, no template. */
    private fun seedEdgePipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const calc = (id, key, deps) => ({
                id, type: 'CALCULATOR', kind: 'fiscal_quarter', context_key: key,
                inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
                depends_on: deps,
              });
              const res = await fetch('/api/v1/pipelines', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                  'Content-Type': 'application/json',
                  'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                },
                body: JSON.stringify({
                  name,
                  display_name: name,
                  nodes: [calc('first', 'run_fiscal_quarter', []), calc('second', 'run_fiscal_quarter_2', ['first'])],
                }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    private fun openEditorFor(name: String) {
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")
        page.locator(".pe-card").first().waitFor()
    }

    /**
     * 082 addendum P1 — the dark theme used to break the canvas.
     *
     * `readDesignTokens` handed Cytoscape the RAW custom-property strings, and four of
     * 080's canvas tokens are `color-mix()` bridges (`app.css`). Cytoscape parses colours
     * itself, cannot read `color-mix(in srgb, …)`, and logged
     * "The style property `line-color: color-mix(…)` is invalid" before falling back —
     * so the re-render on a theme switch collapsed the edges into thick grey bands.
     *
     * The live assertion is the one a unit test cannot make: a REAL theme swap on a REAL
     * editor, then (1) Cytoscape said nothing, and (2) the edge's colour is a colour, and
     * a DIFFERENT one — which is the proof `updateTheme()` re-resolved rather than
     * silently keeping the old sheet.
     */
    @Test
    fun `a theme switch re-themes the canvas without a single Cytoscape complaint`() {
        startTrace()
        loginReadyUser()
        val name = "test/browser_theme_" + generatedPassword("p").take(6).lowercase()
        seedEdgePipeline(name) shouldBe 201

        val complaints = mutableListOf<String>()
        page.onConsoleMessage { message ->
            val text = message.text()
            if (text.contains("style property") || text.contains("color-mix") ||
                text.contains("wheelSensitivity") || text.contains("is invalid")
            ) {
                complaints += "${message.type()}: $text"
            }
        }

        openEditorFor(name)
        page.locator(".pe-card").nth(1).waitFor()
        // Cytoscape returns a parsed colour as either a string or an [r,g,b] array
        // depending on the property; normalise so the assertion is about the VALUE.
        val edgeColour =
            """() => {
                 const v = window.__peInstance.cy.edges()[0].style('line-color');
                 return Array.isArray(v) ? 'rgb(' + v.join(', ') + ')' : String(v);
               }"""
        val light = page.evaluate(edgeColour).toString()

        // A real theme swap: the top bar's mode toggle, the same route a user takes.
        page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        page.waitForFunction(
            "() => document.getElementById('theme-link').getAttribute('href').includes('/themes/dark.css')",
        )
        // updateTheme() runs on the NEW stylesheet's load event, so the colour changing
        // IS the completion signal — waiting on a timeout would be waiting on nothing.
        page.waitForFunction(
            """(before) => {
                 const v = window.__peInstance.cy.edges()[0].style('line-color');
                 return (Array.isArray(v) ? 'rgb(' + v.join(', ') + ')' : String(v)) !== before;
               }""",
            light,
        )
        val dark = page.evaluate(edgeColour).toString()

        // Both are colours a renderer can use — never a `color-mix(…)` declaration.
        light shouldMatch Regex("""^(rgb|#).*""")
        dark shouldMatch Regex("""^(rgb|#).*""")
        complaints.shouldBeEmpty()

        // The sharper half, and the one that catches the SECOND defect. `cy.style(array)`
        // — 080's re-apply — does not replace a live graph's stylesheet, it resets the
        // whole thing to Cytoscape's DEFAULTS (measured on Chrome 148: #999 lines, 30px
        // edges, #999 node fills — the owner's "thick grey bands"). A default is still a
        // parseable colour, so the assertions above pass on the broken code. Comparing
        // three properties with distinctive defaults against the TOKENS the graph is
        // holding is what actually proves the sheet applied.
        val applied =
            page.evaluate(
                """() => {
                     const ed = window.__peInstance;
                     const e = ed.cy.edges()[0], n = ed.cy.nodes()[0];
                     const f = (v) => (Array.isArray(v) ? 'rgb(' + v.join(', ') + ')' : String(v));
                     const norm = (s) => String(s).replace(/\s+/g, '');
                     return [
                       norm(f(e.style('line-color'))) === norm(ed.graph.tokens.edgeIdle),
                       norm(f(n.style('background-color'))) === norm(ed.graph.tokens.nodeSurface),
                       String(e.style('width')) === '2px',
                     ].join(',');
                   }""",
            ).toString()
        applied shouldBe "true,true,true"
    }

    /**
     * 082 §B — the stray node list. In 080's dark Details shot a floating
     * `od_matrix / stage_zones / briefing` list sits over the legend. It is the
     * keyboard node picker: the canvas tap selected with a11ySyncNode's DEFAULT
     * moveFocus, `.focus()` landed on a row, `:focus-within` revealed the picker —
     * on a plain mouse click, and anchored to the legend's own corner.
     *
     * Both halves are asserted here, because either one alone leaves the defect
     * reachable: a pointer never reveals the picker, and when a keyboard user DOES
     * reveal it, it still does not cover the legend (it stacks above it in
     * `.pe-stage-bl`). The unit-level half — the argument itself — is
     * `modules/web/src/test/js/node-picker-focus.test.mjs`.
     */
    @Test
    fun `the keyboard node picker never covers the legend - pointer or keyboard`() {
        startTrace()
        loginReadyUser()
        val name = "test/browser_pick_" + generatedPassword("p").take(6).lowercase()
        seedCalculatorPipeline(name) shouldBe 201

        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")
        val card = page.locator(".pe-card").first()
        card.waitFor()
        page.locator(".pe-legend-chip").first().waitFor()

        // A real pointer tap on the canvas under the card (the overlay is
        // pointer-events:none, so this is the click a user makes on the card itself).
        val box = card.boundingBox()
        page.mouse().click(box.x + box.width / 2, box.y + box.height / 2)
        page.locator("#pe-pane-details .pe-details").waitFor()

        pickerHeight() shouldBeLessThanOrEqual 1.0
        overlappingTheLegend().shouldBeEmpty()

        // The keyboard route still reveals it — and it stacks ABOVE the legend.
        page.locator("#pe-node-list").focus()
        page.waitForFunction(
            """() => document.getElementById('pe-node-list').getBoundingClientRect().height > 1""",
        )
        pickerHeight() shouldBeGreaterThan 1.0
        overlappingTheLegend().shouldBeEmpty()
    }

    @Test
    fun `explorer to editor to a selected node's Details - the P0 shape`() {
        startTrace()
        loginReadyUser()
        // 077: a pipeline name carries a folder; `test/` is the scratch convention.
        val name = "test/browser_ped_" + generatedPassword("p").take(6).lowercase()
        seedCalculatorPipeline(name) shouldBe 201

        // The explorer: find the pipeline, select it (search mode renders a FLAT
        // list of `tpl-result` rows; the tree presentation uses `tpl-leaf`), its
        // detail swaps into #pipeline-detail, then open the editor — a FULL
        // document load (hx-boost="false", 87c20d4), so this is a plain navigation wait.
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")

        // The canvas laid its cards out; the four-tab dock is there from page load.
        page.locator(".pe-card").first().waitFor()
        page.locator("#pe-dock-tab-details").waitFor()
        page.locator("#pe-dock-tab-events").waitFor()

        // Select the node through the card's own expand affordance (a tap on the
        // card selects too — the overlay is pointer-events:none, so the button is
        // the scriptable route) and the dock's Details tab fills.
        page.locator(".pe-card-open").first().click()
        val details = page.locator("#pe-pane-details .pe-details")
        details.waitFor()
        details.innerText() shouldContain "fq"
        details.innerText() shouldContain "fiscal_quarter"
        details.innerText() shouldContain "run_fiscal_quarter"
        // …and the tab really switched to Details.
        page.locator("#pe-dock-tab-details").getAttribute("aria-selected") shouldBe "true"
    }
}
