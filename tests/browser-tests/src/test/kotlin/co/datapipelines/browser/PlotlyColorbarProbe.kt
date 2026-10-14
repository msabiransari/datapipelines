package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * #525 — what a Plotly colorbar must show under the enforced policy, shared by the pages that load a Plotly bundle:
 * the visualization workspace (the 3d superset) and the session-less test preview (the 2d bundle).
 *
 * Plotly's colorbar reset wrote `.attr('style', '')` on its fill rectangles, a style ATTRIBUTE the policy refuses; the
 * vendored patch removes the attribute instead. The probes below are the evidence that the removal neither left the
 * bar blank ([assertPainted]) nor skipped the path a second draw takes over existing rectangles ([redraw]).
 * The CSP verdict itself is the suite's collector (`drainCspViolations`), read by the calling test after each step.
 */
internal object PlotlyColorbarProbe {
    private const val SETTLE_MS = 300.0
    private const val SAMPLE = 3

    /**
     * The colorbar inside [caseSection] (a CSS selector for one case's card) is DRAWN and PAINTED: its `rect.cbfill`
     * fills exist (a floor, so an absent bar cannot pass) and each resolves to a real paint, not `none` or transparent.
     */
    fun assertPainted(
        page: Page,
        where: String,
        caseSection: String,
    ) {
        val selector = "$caseSection .js-plotly-plot rect.cbfill"
        page.waitForSelector(selector, Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED))
        page.waitForTimeout(SETTLE_MS)

        @Suppress("UNCHECKED_CAST")
        val fills =
            page.evaluate(
                "sel => [...document.querySelectorAll(sel)].map(r => getComputedStyle(r).fill)",
                selector,
            ) as List<String>
        withClue("$where: the colorbar's fill rectangles") { fills.shouldNotBeEmpty() }
        val unpainted = fills.filter { it.isBlank() || it == "none" || it.contains("rgba(0, 0, 0, 0)") }
        withClue("$where: ${fills.size} fills, these are not painted: $unpainted (first: ${fills.take(SAMPLE)})") {
            unpainted.shouldBeEmpty()
        }
        println("525 colorbar $where: ${fills.size} fills, first=${fills.first()}")
    }

    /**
     * Plotly's own redraw re-runs the colorbar draw over the EXISTING fill rectangles — the path the old
     * `.attr('style', '')` reset took on every draw after the first (a resize to an unchanged size skips the draw: it
     * measured 0 mutations). The attribute mutations under the colorbar group are counted as the floor: a redraw
     * that touched nothing proves nothing.
     */
    fun redraw(
        page: Page,
        where: String,
        caseSection: String,
    ) {
        val mutations =
            page.evaluate(
                """async (section) => {
                  const gd = document.querySelector(section + ' .js-plotly-plot');
                  const bar = gd && gd.querySelector('.colorbar');
                  if (!bar) return -1;
                  let seen = 0;
                  const observer = new MutationObserver(list => { seen += list.length; });
                  observer.observe(bar, { subtree: true, attributes: true });
                  await window.Plotly.redraw(gd);
                  await new Promise(done => setTimeout(done, $SETTLE_MS));
                  observer.disconnect();
                  return seen;
                }""",
                caseSection,
            ) as Number
        withClue("$where: the redraw reached the colorbar group (-1 = no colorbar)") { mutations.toInt() shouldBeGreaterThan 0 }
        println("525 colorbar redraw $where: $mutations attribute mutations under .colorbar")
    }
}
