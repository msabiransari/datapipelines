package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
