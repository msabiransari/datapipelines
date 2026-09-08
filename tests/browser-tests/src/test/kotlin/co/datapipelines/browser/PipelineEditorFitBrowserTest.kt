package co.datapipelines.browser

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 098 §B — "Fit graph to view" fits, in a real browser.
 *
 * The defect (093 §4), measured on the demo stack at 1440x900 with the dock open, on
 * `nyc/mobility/weather_sensitivity_by_borough`, after clicking Fit:
 *
 * ```
 * canvas box:  y 136 .. 604      cy.zoom() 0.75      content bounding box 638 x 828
 * card stage_daily_by_zone: y  61 .. 178   <- 75 px above the canvas, clipped by the top bar
 * card stage_calendar:      y 562 .. 679   <- 75 px below the canvas
 * ```
 *
 * `fitToView` took Cytoscape's own fit zoom and clamped it into `[0.75, 1.0]`. The ceiling is
 * a ruling (082/085: fit never zooms IN). The floor could only ever bind when the graph did
 * NOT fit, so it turned Fit into a no-op that then centred the overflow — which is why the
 * overhang was symmetric, and why the top card's expand button was unreachable
 * (`document.elementFromPoint` at its centre returned `#app-main`).
 *
 * `graph-fit.test.mjs` owns the arithmetic from the token geometry. This owns the thing only a
 * live page can say: after clicking Fit, EVERY `.pe-card` box is inside the canvas box.
 *
 * The pipeline is six INDEPENDENT one-node calculators — self-contained (no datasource, no
 * template, the [PipelineEditorDetailsBrowserTest] seeding contract), and independent so dagre
 * stacks them in one column. That is the shape that overflows a 900px window, and a chain
 * would not: it would spread sideways instead.
 */
class PipelineEditorFitBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("fit-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("fitws-" + generatedPassword("w").take(8).lowercase())
    }

    /** Six independent CALCULATOR nodes, through the REST surface, in-page for the session pair. */
    private fun seedTallPipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const calc = (id) => ({
                id,
                type: 'CALCULATOR',
                kind: 'fiscal_quarter',
                context_key: 'q_' + id,
                inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
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
                  nodes: ['a', 'b', 'c', 'd', 'e', 'f'].map(calc),
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

    /** Clicks Fit and waits for the canvas tween — a CANVAS tween, which no CSS reset zeroes. */
    private fun clickFit() {
        page.locator(".pe-graph-controls button[aria-label='Fit graph to view']").click()
        page.waitForTimeout(600.0)
    }

    /**
     * Every card's box against the canvas's, as one evaluate: reading them in two round trips
     * would compare a card measured before a tween frame with a canvas measured after it.
     */
    private fun cardsOutsideCanvas(): List<String> {
        @Suppress("UNCHECKED_CAST")
        val outside =
            page.evaluate(
                """() => {
                  const c = document.getElementById('cy-canvas').getBoundingClientRect();
                  return Array.from(document.querySelectorAll('.pe-card'))
                    .map(el => ({ id: el.getAttribute('data-node-id'), r: el.getBoundingClientRect() }))
                    .filter(({ r }) => r.top < c.top - 1 || r.bottom > c.bottom + 1
                                    || r.left < c.left - 1 || r.right > c.right + 1)
                    .map(({ id, r }) => id + ' [' + Math.round(r.top) + '..' + Math.round(r.bottom) + ']'
                          + ' outside canvas [' + Math.round(c.top) + '..' + Math.round(c.bottom) + ']');
                }""",
            ) as List<String>
        return outside
    }

    @Test
    fun `after Fit every card is inside the canvas, at both review widths`() {
        startTrace()
        loginReadyUser()
        val name = "fit/tall/" + generatedPassword("p").take(8).lowercase()
        seedTallPipeline(name) shouldBe 201

        openEditorFor(name)

        listOf(1440 to 900, 2560 to 1440).forEach { (w, h) ->
            page.setViewportSize(w, h)
            page.locator(".pe-card").first().waitFor()
            clickFit()
            cardsOutsideCanvas().shouldBeEmpty()
        }
    }

    /**
     * The other half of the ruling, and the reason the ceiling stays: Fit must never MAGNIFY.
     * A six-node column at 2560x1440 still needs to shrink, so the zoom is read on the wide
     * viewport where a naive fit would go past 1.
     */
    @Test
    fun `Fit never zooms in past 1`() {
        startTrace()
        loginReadyUser()
        val name = "fit/cap/" + generatedPassword("p").take(8).lowercase()
        seedTallPipeline(name) shouldBe 201

        openEditorFor(name)
        page.setViewportSize(2560, 1440)
        clickFit()

        val zoom =
            (
                page.evaluate(
                    "() => document.getElementById('cy-canvas')._cyreg.cy.zoom()",
                ) as Number
            ).toDouble()
        zoom shouldBeLessThanOrEqual 1.0
    }
}
