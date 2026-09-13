package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * 121 — a multi-output CALCULATOR node in the editor, reached the way a user reaches it:
 * seeded through the REST surface (the same in-page fetch PipelineEditorDetailsBrowserTest
 * uses), opened EXPLORER → detail → "Open in editor" (never by URL — the owner's 2026-09-05
 * rule), and the card read for what the node writes.
 *
 * The card's fact is Part B's shape: `trailing_periods → window_end, window_start` — one
 * evaluation, both keys named, SORTED (body_json is JSONB, which does not preserve the
 * author's object key order, so the editor renders one deterministic order rather than a
 * storage-dependent one) — and the Details pane lists the mapping. A `trailing_periods` node
 * is self-contained: no datasource, no template, exactly like the suite's single-output
 * fixture.
 */
class PipelineEditorMultiOutputBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("pmo-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("pmows-" + generatedPassword("w").take(8).lowercase())
    }

    /** A one-node `trailing_periods` pipeline, created in-page (cookie + CSRF double-submit). */
    private fun seedWindowPipeline(name: String): Int =
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
                    id: 'window',
                    type: 'CALCULATOR',
                    kind: 'trailing_periods',
                    inputs: { date: '${'$'}current_date', unit: 'quarter' },
                    context_keys: { start: 'window_start', end: 'window_end' },
                  }],
                }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    @Test
    fun `a trailing_periods node's card says what it writes - every key`() {
        startTrace()
        loginReadyUser()
        val name = "test/browser_pmo_" + generatedPassword("p").take(6).lowercase()
        seedWindowPipeline(name) shouldBe 201

        // Explorer → detail → editor, the app's own links the whole way.
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")
        val card = page.locator(".pe-card").first()
        card.waitFor()

        // The card text: one evaluation, both keys named.
        card.innerText() shouldContain "trailing_periods → window_end, window_start"

        // …and the Details pane lists the mapping, output → key (sorted — body_json is
        // JSONB, which does not preserve the author's key order; the editor renders one
        // deterministic order rather than a storage-dependent one).
        page.locator(".pe-card-open").first().click()
        val details = page.locator("#pe-pane-details .pe-details")
        details.waitFor()
        details.innerText() shouldContain "end → window_end"
        details.innerText() shouldContain "start → window_start"
    }
}
