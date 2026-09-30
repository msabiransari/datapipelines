package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #336 D8 in a real browser — the failure paths the scripts used to swallow, with Playwright
 * route interception over the product's own surface (no bypass of auth; the routes are scoped
 * with a wildcard and unregistered in a finally):
 *
 * 1. **A refused cancel** (a 500 carrying a catalogued envelope) toasts the server's message,
 *    never arms the abort fallback, and the run COMPLETES on the still-open stream — the run is
 *    never presented as cancelled. (Red on the pre-336 sse.js: no toast, and the fallback armed.)
 * 2. **A malformed lifecycle block refuses the run**: the execute request never leaves the page
 *    and the error modal carries fixed copy — running would have targeted the latest RELEASED
 *    version while the person edits a draft.
 * 3. **A failed runs refresh** keeps the runs shown and MARKS them stale, with the toast once,
 *    until a refresh answers — then the marker clears.
 */
class SilentFailurePathsBrowserTest : SchedulesBrowserSuite() {
    @Test
    fun `a refused cancel toasts the catalogued message and the run still completes`() {
        startTrace()
        val root = ready("d8cancel")
        val datasource = "d8-src-" + suffix()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val name = "$root/jobs/sleep"
        val pipelineId = sleepPipeline(name, datasource)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card").first().waitFor()

        page.locator("[data-verb='pipeline-execute']").click()
        // The Cancel button replaces Execute while the stream is open; the pg_sleep node holds the run.
        page.locator("[data-verb='execution-cancel']").waitFor(Locator.WaitForOptions().setTimeout(20_000.0))

        var deleteHits = 0
        page.route("**/api/v1/executions/*") { route ->
            if (route.request().method() == "DELETE") {
                deleteHits++
                route.fulfill(
                    com.microsoft.playwright.Route
                        .FulfillOptions()
                        .setStatus(500)
                        .setBody("""{"error":{"code":"internal","message":"catalogued refusal for the browser proof"}}"""),
                )
            } else {
                route.resume()
            }
        }
        try {
            page.locator("[data-verb='execution-cancel']").click()
            val toast = page.locator("#toast .ds-toast-danger")
            toast.waitFor(Locator.WaitForOptions().setTimeout(10_000.0).setState(WaitForSelectorState.VISIBLE))
            toast.innerText() shouldContain "catalogued refusal for the browser proof"
            deleteHits shouldBe 1
            // The stream was never aborted — the proof is the run COMPLETING below, after the
            // old script's 5 s fuse had long passed: on the pre-336 code the fallback armed
            // here, aborted the reader and the run ended ABORTED (presented as cancelled).
        } finally {
            page.unroute("**/api/v1/executions/*")
        }
        page.locator(".pe-status:has-text('Completed')").waitFor(
            Locator.WaitForOptions().setTimeout(60_000.0),
        )
    }

    @Test
    fun `a malformed lifecycle block refuses the run with the error modal`() {
        startTrace()
        val root = ready("d8draft")
        val name = "$root/jobs/plain"
        val datasource = "d8-src-" + suffix()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val pipelineId = sleepPipeline(name, datasource)
        // The store-side fault, delivered the way a bad deploy or a manual JSONB edit would
        // land: the page is SERVED with the lifecycle block already unreadable, so draft.js's
        // init parses the broken JSON exactly as a real editor page would.
        page.route("**/pipelines/*/editor") { route ->
            val body = route.fetch().text()
            val tampered = body.replaceFirst("\"hasDraft\":true", "\"hasDraft\":broken-")
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setBody(tampered)
                    .setContentType("text/html"),
            )
        }
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card").first().waitFor()

        var executeHits = 0
        page.route("**/api/v1/pipelines/*/execute") { route ->
            executeHits++
            route.abort()
        }
        try {
            page.locator("[data-verb='pipeline-execute']").click()

            val modal = page.locator(".pe-modal-backdrop:visible")
            modal.waitFor(Locator.WaitForOptions().setTimeout(10_000.0))
            modal.innerText() shouldContain "could not read the pipeline's lifecycle state"
            executeHits shouldBe 0 // nothing was sent: the version pin is unknown, so nothing runs
        } finally {
            page.unroute("**/api/v1/pipelines/*/execute")
        }
    }

    @Test
    fun `a failed runs refresh marks the runs stale until one answers`() {
        startTrace()
        val root = ready("d8stale")
        val pipeline = "$root/jobs/rows"
        ScheduleFixtures.releasedPipeline(page, pipeline)
        val id = ScheduleFixtures.createSchedule(page, "$root/nightly/rows", pipeline)
        openSchedules("?id=$id")
        detail().waitFor()
        val staleNote = page.locator("[data-slot='runs-stale']")
        staleNote.shouldBeHidden()

        page.route("**/api/v1/schedules/*/runs**") { route ->
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(500)
                    .setBody("server down"),
            )
        }
        try {
            page.locator("[data-sch-action='refresh-runs']").click()
            staleNote.waitFor(Locator.WaitForOptions().setTimeout(10_000.0).setState(WaitForSelectorState.VISIBLE))
            staleNote.innerText() shouldContain "out of date"
        } finally {
            page.unroute("**/api/v1/schedules/*/runs**")
        }
        // The next answering refresh HIDES the marker again (the slot is a fixed part of the
        // panel — hidden, never detached) — the poll's own cadence or this click.
        page.locator("[data-sch-action='refresh-runs']").click()
        page.waitForFunction(
            "() => { const n = document.querySelector(\"[data-slot='runs-stale']\"); return n && n.hidden; }",
            null,
            Page.WaitForFunctionOptions().setTimeout(10_000.0),
        )
    }

    /** A one-node caller pipeline whose single statement holds the run for [sleepSeconds]. */
    private fun sleepPipeline(
        name: String,
        datasource: String,
        sleepSeconds: Int = 12,
    ): String {
        val slug = name.split("/")[1]
        EditorRunFixtures.createTemplate(
            page,
            "test/${slug}_q.sql",
            "SELECT g, pg_sleep($sleepSeconds) AS held FROM generate_series(1, 1) g",
        )
        return EditorRunFixtures.postPipeline(
            page,
            name,
            """[ { "id": "q", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_q.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": [] } ]""",
        )
    }

    private fun com.microsoft.playwright.Locator.shouldBeHidden() {
        (this.getAttribute("hidden") != null) shouldBe true
    }

    private infix fun String?.shouldContain(needle: String) {
        check(this != null && this.contains(needle)) { "expected to contain '$needle': ${this?.take(300)}" }
    }
}
