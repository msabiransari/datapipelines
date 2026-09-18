package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/**
 * T251 (owner screenshot, 2026-09-13): the multi-output CALCULATOR's card carried its whole
 * value set in the footer — `= last_quarter_end="2024-12-31", last_quarter_start="2024-10-01"
 * · 2 ms` — which wrapped to three lines inside a footer laid out for `rows · ms`, floated the
 * Done dot mid-card, and read as "values mixed between fields"; and its outgoing edge said
 * `0 rows`. The contract now (pipeline-editor.md §5.3/§6.2): the footer and the edge COUNT what
 * the node wrote; the values are the Details pane's.
 *
 * Measured on a REAL run, not a fixture: a two-key `period_bounds` window node feeding a
 * `fiscal_quarter` node, executed through the editor's own button, then the footer text, the
 * footer's HEIGHT against the one-line footer beside it (the symptom), and that the edge out of it
 * carries no count at all (151: an arrow is an ordering).
 */
class CalculatorCardFooterBrowserTest : BrowserSuite() {
    @Test
    fun `a calculator's footer counts its keys on one line, and its edge never says 0 rows`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("t251-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("t251" + generatedPassword("w").take(8).lowercase())

        val name = "test/t251_" + generatedPassword("p").take(8).lowercase()
        val pipelineId = createTwoCalculatorPipeline(page, name)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card").first().waitFor()

        page.locator("[data-verb='pipeline-execute']").click()
        page
            .locator("[data-verb='pipeline-execute']:not([disabled])")
            .waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()

        val window = page.locator(".pe-card:has(.pe-card-id[title='window'])")
        val fq = page.locator(".pe-card:has(.pe-card-id[title='fq'])")
        window.locator(".pe-card-rt").innerText().trim() shouldMatch Regex("2 keys · \\d+(\\.\\d+)? ?(ms|s)")
        fq.locator(".pe-card-rt").innerText().trim() shouldMatch Regex("1 key · \\d+(\\.\\d+)? ?(ms|s)")

        // The symptom itself: the two-key footer is exactly as tall as the one-key footer —
        // one line, never three.
        fun footerHeight(card: Locator): Double =
            (card.locator(".pe-card-foot").evaluate("el => el.getBoundingClientRect().height") as Number).toDouble()
        footerHeight(window) shouldBe footerHeight(fq)

        // 151 (#127): NO count rides any edge any more — the arrow out of the window node is
        // an ordering. So the T251 defect ("0 rows" on a calculator's edge) cannot recur by
        // construction: the dependency edge carries no label and no rowLabel data at all,
        // and the count lives on the card (`2 keys · N ms`, asserted above).
        page.evaluate(
            "() => { const cy = window.__peInstance.cy; const e = cy.edges('.dependency')[0];" +
                " return { rowLabel: e.data('rowLabel') === undefined ? 'absent' : e.data('rowLabel')," +
                " label: String(e.style('label') || '') }; }",
        ) shouldBe mapOf("rowLabel" to "absent", "label" to "")
    }

    /** `window` (period_bounds → window_start/window_end) feeding `fq` (fiscal_quarter), created in-page. */
    @Suppress("UNCHECKED_CAST")
    private fun createTwoCalculatorPipeline(
        page: Page,
        name: String,
    ): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/pipelines', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                      'Content-Type': 'application/json',
                      'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                    },
                    body: JSON.stringify({
                      name: args.name,
                      display_name: args.name,
                      nodes: [
                        {
                          id: 'window',
                          type: 'CALCULATOR',
                          kind: 'period_bounds',
                          context_keys: { start: 'window_start', end: 'window_end' },
                          inputs: { date: '${'$'}current_date', unit: 'quarter' },
                        },
                        {
                          id: 'fq',
                          type: 'CALCULATOR',
                          kind: 'fiscal_quarter',
                          context_key: 'run_fiscal_quarter',
                          inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
                          depends_on: ['window'],
                        },
                      ],
                    }),
                  });
                  const body = await res.text();
                  return { status: res.status, body: body };
                }""",
                mapOf("name" to name),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        return Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(result["body"] as String)!!.groupValues[1]
    }

    private companion object {
        /** A calculator run is engine-internal (no datasource); 60 s is already generous. */
        const val EXECUTION_TIMEOUT_MS = 60_000.0
    }
}
