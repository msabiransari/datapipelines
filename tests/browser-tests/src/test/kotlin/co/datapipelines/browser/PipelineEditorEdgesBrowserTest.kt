package co.datapipelines.browser

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 105 — which way does the data flow? The live-canvas half of the edge-direction fix.
 *
 * The defect (owner's screenshot, 2026-09-08, `demo/holiday_airport_rush_hours`): `answer`
 * dragged ABOVE AND LEFT of `stage_tmax`, its dependency. The edge left `stage_tmax`'s right
 * port, arced over the cards, and the arrowhead slid behind the target — "the line just
 * disappears behind the node without giving any hint if it's connected to input (left) or
 * output (right)".
 *
 * What §A established (measured, not argued — see handbacks/105-edge-direction.md):
 * the edge DATA is right (dependency → dependent, from the stored body), and dagre NEVER
 * inverts it (0 inversions over the owner's exact body, headless and live). The defect was
 * the CURVE: applyEdgeCurves ran once at layoutstop, its (weight, distance) control points
 * are relative to the source→target vector, and a drag re-projects them onto the new,
 * reversed vector — an arc over the cards — while the fresh formula itself loops for
 * dx < 0 (k = max(60, dx/2) clamps to 60 and forces out-right/in-left on a backward edge).
 *
 * The fix under test: edgeRouteFor routes a backward edge (port dx < 60) as an orthogonal
 * detour BELOW both cards, and applyEdgeCurves re-runs on `dragfree` so a dragged node
 * re-takes its curves. `graph-edges.test.mjs` owns the pure function; this owns the three
 * things only a live page can say:
 *
 * 1. after layout, every edge's target node sits strictly RIGHT of its source, and both
 *    endpoints land on the port edges (±4px) — direction legible at rest;
 * 2. a target dragged 400px left of its source routes BELOW both cards (midpoint under
 *    both bottom edges — never behind a card) with the arrowhead clear of the target card;
 * 3. the arrowhead is ≥ 8px at zoom 1 (the 105 floor; 0.9 measured 3.91px).
 *
 * The pipeline is the owner's TOPOLOGY with self-contained CALCULATOR nodes (the
 * [PipelineEditorFitBrowserTest] seeding contract — no datasource, no template): three
 * rank-0 stages feeding `rank_days`, which with five more stages feeds `answer`. The
 * staging node ids keep the owner's names (`stage_tmax` among them) so a failure names
 * the card the reader knows.
 */
class PipelineEditorEdgesBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("edges-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("edgesws-" + generatedPassword("w").take(8).lowercase())
    }

    /** The owner's topology, as calculators: 3 stages → rank_days; rank_days + 5 stages → answer. */
    private fun seedOwnerShapedPipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const calc = (id, deps) => ({
                id,
                type: 'CALCULATOR',
                kind: 'fiscal_quarter',
                context_key: 'k_' + id,
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
                  nodes: [
                    calc('stage_calendar', []),
                    calc('stage_taxi_days', []),
                    calc('stage_rideshare_days', []),
                    calc('rank_days', ['stage_calendar', 'stage_taxi_days', 'stage_rideshare_days']),
                    calc('stage_taxi_hourly', []),
                    calc('stage_rideshare_hourly_2023', []),
                    calc('stage_rideshare_hourly_2024', []),
                    calc('stage_airport_zones', []),
                    calc('stage_tmax', []),
                    calc('answer', ['rank_days', 'stage_taxi_hourly', 'stage_rideshare_hourly_2023',
                                    'stage_rideshare_hourly_2024', 'stage_airport_zones', 'stage_tmax']),
                  ],
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
        // Fit first: the drag test needs every card on-screen, and the layout's second
        // height pass can re-fit a frame later — wait it out before measuring.
        page.locator(".pe-graph-controls button[aria-label='Fit graph to view']").click()
        page.waitForTimeout(600.0)
    }

    /** Direction and endpoints at rest: [{id, ok}] where ok lists the edge's violations. */
    private fun edgeViolations(): List<String> {
        @Suppress("UNCHECKED_CAST")
        return page.evaluate(
            """() => {
              const cy = document.getElementById('cy-canvas')._cyreg.cy;
              const out = [];
              cy.edges().forEach((e) => {
                const s = e.source(), t = e.target();
                const sp = s.position(), tp = t.position();
                const halfW = s.width() / 2;
                if (!(tp.x > sp.x)) out.push(e.id() + ': target not right of source');
                const te = e.targetEndpoint(), se = e.sourceEndpoint();
                if (Math.abs(te.x - (tp.x - halfW)) > 4) out.push(e.id() + ': target endpoint off the left port');
                if (Math.abs(se.x - (sp.x + halfW)) > 4) out.push(e.id() + ': source endpoint off the right port');
              });
              return out;
            }""",
        ) as List<String>
    }

    @Test
    fun `after layout every edge runs left to right and lands on its ports`() {
        startTrace()
        loginReadyUser()
        val name = "edges/dir/" + generatedPassword("p").take(8).lowercase()
        seedOwnerShapedPipeline(name) shouldBe 201

        openEditorFor(name)
        edgeViolations().shouldBeEmpty()
    }

    /**
     * The owner's interaction, on the fixed canvas: drag `answer` left of `stage_tmax`
     * and up as he did. The edge must re-take its curve (dragfree → applyEdgeCurves)
     * and route BELOW both cards, arrowhead in the clear.
     */
    @Test
    fun `a target dragged left of its source routes below both cards, arrow clear`() {
        startTrace()
        loginReadyUser()
        val name = "edges/drag/" + generatedPassword("p").take(8).lowercase()
        seedOwnerShapedPipeline(name) shouldBe 201

        openEditorFor(name)
        page.setViewportSize(1440, 900)
        // The resize crosses the stage's ResizeObserver: refreshCardMetrics re-lays-out
        // and re-fits — wait for that to settle, or the drag coordinates go stale mid-flight.
        page.waitForTimeout(1000.0)

        dragAnswerBy(-700.0, -200.0)

        val verdict = dragVerdict()
        // portDx < -400: the target is far left of its source (the owner's state)
        ((verdict["portDx"] as Number).toDouble() < -400.0).shouldBe(true)
        (verdict["midBelowSource"] as Boolean).shouldBe(true)
        (verdict["midBelowTarget"] as Boolean).shouldBe(true)
        (verdict["midInsideTarget"] as Boolean).shouldBe(false)
        (verdict["arrowBodyInsideCard"] as Boolean).shouldBe(false)
        (verdict["horizontalApproach"] as Boolean).shouldBe(true)
    }

    /**
     * A REAL mouse drag of `answer` by [modelDx]/[modelDy] model px: the card's screen
     * centre to the same point offset in model space, ten moves, Playwright-trusted events.
     */
    private fun dragAnswerBy(
        modelDx: Double,
        modelDy: Double,
    ) {
        @Suppress("UNCHECKED_CAST")
        val drag =
            page.evaluate(
                """(d) => {
                  const [dx, dy] = d;
                  const cy = document.getElementById('cy-canvas')._cyreg.cy;
                  const a = cy.getElementById('answer');
                  const rect = document.getElementById('cy-canvas').getBoundingClientRect();
                  const z = cy.zoom(), pan = cy.pan();
                  const from = a.renderedPosition();
                  const toModel = { x: a.position().x + dx, y: a.position().y + dy };
                  return {
                    fromX: rect.left + from.x, fromY: rect.top + from.y,
                    toX: rect.left + pan.x + toModel.x * z, toY: rect.top + pan.y + toModel.y * z,
                  };
                }""",
                arrayOf(modelDx, modelDy),
            ) as Map<String, Any>
        val fromX = drag["fromX"] as Double
        val fromY = drag["fromY"] as Double
        val toX = drag["toX"] as Double
        val toY = drag["toY"] as Double
        page.mouse().move(fromX, fromY)
        page.mouse().down()
        for (i in 1..10) {
            page.mouse().move(fromX + (toX - fromX) * i / 10, fromY + (toY - fromY) * i / 10)
        }
        page.mouse().up()
        page.waitForTimeout(400.0) // dragfree → applyEdgeCurves
    }

    /** The drag test's assertions as one evaluate: mid/arrow geometry in model space. */
    private fun dragVerdict(): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return page.evaluate(
            """() => {
              const cy = document.getElementById('cy-canvas')._cyreg.cy;
              const a = cy.getElementById('answer');
              const t = cy.getElementById('stage_tmax');
              const e = cy.edges("[source = 'stage_tmax'][target = 'answer']")[0];
              const util = window.PEGraphUtil;
              const mid = e.midpoint();
              const aBox = a.boundingBox(), tBox = t.boundingBox();
              // The arrowhead: the triangle's TIP sits ON the target endpoint (the
              // port — by design, it points INTO it), its body extending back along
              // the approach. Its bbox is [tip - size, tip]; the tip may KISS the
              // card's edge, so the body-clear check insets the card by 2px.
              const size = util.ARROW_SCALE * util.ARROW_BASE_PX;
              const tip = e.targetEndpoint();
              const arrow = { x1: tip.x - size, x2: tip.x, y1: tip.y - size / 2, y2: tip.y + size / 2 };
              const card = { x1: aBox.x1 + 2, x2: aBox.x2 - 2, y1: aBox.y1 + 2, y2: aBox.y2 - 2 };
              const hits = (b1, b2) => b1.x1 < b2.x2 && b2.x1 < b1.x2 && b1.y1 < b2.y2 && b2.y1 < b1.y2;
              // The APPROACH is the legibility claim: the final drawn segment must
              // come in HORIZONTALLY from the left of the card — the owner's arc
              // dropped onto the port from above, the arrow body flat against the card.
              const pts = e._private.rscratch.allpts;
              const n = pts.length;
              const last = { x: pts[n - 2], y: pts[n - 1] };
              const prev = { x: pts[n - 4], y: pts[n - 3] };
              const horizontalApproach =
                Math.abs(last.y - prev.y) <= 1 && last.x - prev.x >= 10;
              return {
                portDx: Math.round((a.position().x - a.width() / 2) - (t.position().x + t.width() / 2)),
                midBelowSource: mid.y > tBox.y2,
                midBelowTarget: mid.y > aBox.y2,
                midInsideTarget: mid.x > aBox.x1 && mid.x < aBox.x2 && mid.y > aBox.y1 && mid.y < aBox.y2,
                arrowBodyInsideCard: hits(arrow, card),
                horizontalApproach,
              };
            }""",
        ) as Map<String, Any>
    }

    /** 105 §B: the arrowhead clears 8px at zoom 1 (0.9 measured 3.91px — the invisible head). */
    @Test
    fun `the arrowhead is at least 8px at zoom 1`() {
        startTrace()
        loginReadyUser()
        val name = "edges/arrow/" + generatedPassword("p").take(8).lowercase()
        seedOwnerShapedPipeline(name) shouldBe 201

        openEditorFor(name)
        page.evaluate(
            """() => {
              const cy = document.getElementById('cy-canvas')._cyreg.cy;
              cy.zoom({ level: 1 });
              cy.center();
            }""",
        )
        page.waitForTimeout(300.0)

        @Suppress("UNCHECKED_CAST")
        val extent =
            page.evaluate(
                """() => {
                  const cy = document.getElementById('cy-canvas')._cyreg.cy;
                  const e = cy.edges()[0];
                  const withArrow = e.renderedBoundingBox(
                    { includeNodes: false, includeLabels: false, includeOverlays: false });
                  e.style('arrow-scale', 0.0001);
                  const bare = e.renderedBoundingBox(
                    { includeNodes: false, includeLabels: false, includeOverlays: false });
                  e.style('arrow-scale', window.PEGraphUtil.ARROW_SCALE);
                  const z = cy.zoom();
                  return { w: (withArrow.w - bare.w) / z, h: (withArrow.h - bare.h) / z };
                }""",
            ) as Map<String, Any>
        (extent["w"] as Number).toDouble() shouldBeGreaterThan 8.0
    }
}
