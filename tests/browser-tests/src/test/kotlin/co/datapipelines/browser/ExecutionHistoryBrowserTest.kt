package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Golden path 7's page half: the execution history screen renders with its filter bar —
 * the pipeline dropdown and the status set — and shows the honest empty state when the
 * workspace has no runs. (The row/pagination half needs a real execution, which needs
 * the pipeline editor's Phase-2 rebuild — deferred in TEST-GAP-2026-09.md.)
 */
class ExecutionHistoryBrowserTest : BrowserSuite() {
    @Test
    fun `the history screen renders its filter bar and the empty state`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("hist-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("histws-" + generatedPassword("w").take(8).lowercase())

        page.navigate("$baseUrl/executions")
        page.waitForURL("**/executions")
        // The page model's two filter inputs: pipelines for the dropdown, statuses for the bar.
        page.locator("select").first().isVisible() shouldBe true
        // The empty state rides the htmx-loaded partial — wait for ITS arrival, not
        // the page's initial HTML.
        page.waitForSelector("text=No executions found")
    }
}
