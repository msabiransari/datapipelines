package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.RequestOptions
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * #353 (L4b) — the visualization test PREVIEW in a real browser, and the whole workflow a Playwright agent runs,
 * driven by the test: an author starts a session over REST; a SEPARATE browser context with no cookie — the agent's
 * browser — opens the preview URL, which mounts the vendored runtime (L3a's artifact) in its fixture mode under the
 * app's LIVE CSP; the data case's rows are read off the drawn table and the empty case shows the empty state; both
 * themes are shot; the agent's screenshot is uploaded with the single-use capability ALONE; the author reads it back
 * and releases.
 *
 * What this does NOT claim: that the server rendered anything — the screenshots are REVIEW evidence (the issue's
 * sentence; the server-side headless check is L6). The CSP measurement is the suite's collector (zero violations
 * across every page every context of this test opened, asserted in `closePage`). #860: the document is a `table` — the
 * preview's surface is the same for every kind, and the chart family's proofs (marks, ticks, hover, the Plotly host
 * marker) moved to `BarChartBrowserTest` with the Plotly figures they read.
 */
class VisualizationPreviewBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `an agent's browser previews every case in both themes, uploads its screenshot, and the author releases`() {
        startTrace()
        val root = ready("vprev")
        val created = api("POST", "/api/v1/visualizations", document("$root/charts/preview"))
        val id = created.at("data", "id") as String
        val hash = created.at("data", "body_hash") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val sessionId = started.at("data", "session_id") as String
        val runId = started.at("data", "run_id") as String
        previewUrl shouldStartWith "/visualizations/$id/preview?session=" // no base-url configured: root-relative

        // ---- the agent's browser: a fresh context, no cookie, every request recorded ----
        val agent = newBrowserContext()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        agent.onRequest { requested += it.url() }
        val agentPage = agent.newPage()
        val shots = mutableMapOf<String, ByteArray>()
        for (theme in listOf("light", "dark")) shots[theme] = previewIn(agentPage, previewUrl, theme)
        withClue("the agent's browser holds no cookie — the capability is the page's only credential") { agent.cookies().shouldBeEmpty() }
        withClue("no request from the preview reached /api/v1 — the runtime ran on its fixtures") {
            requested.filter { it.contains("/api/v1/") }.shouldBeEmpty()
        }

        // ---- the agent submits (here: the author's session — the SAME principal that started) ----
        val submitted =
            api(
                "POST",
                "/api/v1/visualizations/$id/tests/sessions/$sessionId/results",
                """{"cases":[{"name":"three months","verdict":"green","notes":"three rows in both themes"},
                   {"name":"empty","verdict":"green"}],
                   "environment":{"browser":"chromium (playwright)","theme":"light+dark","viewport":"1280x720"}}""",
            )
        submitted.at("data", "status") shouldBe "GREEN"
        val upload = submitted.at("data", "upload") as Map<*, *>

        // The preview is revoked by the submit: the agent's reload is the one unavailable page.
        val revoked = agentPage.navigate("$baseUrl$previewUrl")
        revoked?.status() shouldBe 404

        // ---- the upload, from the agent's cookie-less context, with the capability ALONE ----
        val image = shots.getValue("light")
        val stored =
            agentPage.request().post(
                "$baseUrl${upload["url"]}?case=three%20months",
                RequestOptions
                    .create()
                    .setHeader(upload["header"] as String, upload["token"] as String)
                    .setHeader("Content-Type", "image/png")
                    .setData(image),
            )
        withClue(stored.text().take(EXCERPT)) { stored.status() shouldBe 201 }
        agent.close()

        // ---- the author reads the evidence back and releases ----
        val readBack = page.request().get("$baseUrl/api/v1/visualizations/$id/tests/runs/$runId/screenshot")
        readBack.status() shouldBe 200
        sha256(readBack.body()) shouldBe sha256(image)
        val run = api("GET", "/api/v1/visualizations/$id/tests/runs/$runId", null)
        run.at("data", "preview_revoked") shouldBe true
        (run.at("data", "upload_capability") as Map<*, *>).containsKey("consumed_at") shouldBe true
        val released = api("POST", "/api/v1/visualizations/$id/release", "", ifMatch = hash)
        released.at("data", "status") shouldBe "RELEASED"
    }

    /**
     * One theme of the preview, as an agent checks it: both cases mounted, the data case's chip at success/ready and the
     * empty case's at no-data, the theme applied, the data case's drawn rows, and the shots —
     * the full page into the evidence directory; the data case's card returned for the upload.
     */
    private fun previewIn(
        agentPage: Page,
        previewUrl: String,
        theme: String,
    ): ByteArray {
        agentPage.navigate("$baseUrl$previewUrl&theme=$theme")
        agentPage.waitForSelector("section[data-dp-case-index='0'][data-dp-ready='true']")
        agentPage.waitForSelector("section[data-dp-case-index='1'][data-dp-ready='true']")
        agentPage.waitForFunction(
            "() => { const c = document.querySelector(\"section[data-dp-case-index='0'] .dp-dashboard-status\");" +
                " return c && (c.getAttribute('data-dp-state') === 'success' || c.getAttribute('data-dp-state') === 'ready'); }",
        )
        agentPage.waitForFunction(
            "() => { const c = document.querySelector(\"section[data-dp-case-index='1'] .dp-dashboard-status\");" +
                " return c && c.getAttribute('data-dp-state') === 'no-data'; }",
        )
        withClue("the document's theme is the one asked for") {
            agentPage.evaluate("() => document.documentElement.getAttribute('data-theme')") shouldBe theme
        }
        val rows =
            agentPage.evaluate(
                "() => Array.from(document.querySelectorAll(\"section[data-dp-case-index='0'] tbody tr\"))" +
                    ".map(tr => tr.firstElementChild.textContent)",
            ) as List<*>
        withClue("case 'three months' draws its three rows ($theme)") { rows shouldBe listOf("Jan", "Feb", "Mar") }
        agentPage.waitForTimeout(SETTLE_MS)
        val full = agentPage.screenshot(Page.ScreenshotOptions().setFullPage(true))
        Files.createDirectories(SHOTS)
        Files.write(SHOTS.resolve("visualization-preview-$theme.png"), full)
        return agentPage.locator("section[data-dp-case-index='0']").screenshot()
    }

    /** One REST call from the signed-in page (cookie + the CSRF double-submit), the JSON body back; non-2xx fails. */
    private fun api(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const init = { method: args.method, credentials: 'same-origin', headers };
                  if (args.body !== null) init.body = args.body;
                  const response = await fetch(args.path, init);
                  const text = await response.text();
                  let json = null;
                  try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }
                  return { status: response.status, text: text, json: json };
                }""",
                mapOf("method" to method, "path" to path, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        val text = result["text"] as String
        withClue("$method $path -> $status ${text.take(EXCERPT)}") { (status in 200..299) shouldBe true }
        return result["json"] as Map<*, *>
    }

    /** A nested read of a JSON object the page parsed. */
    private fun Map<*, *>.at(vararg keys: String): Any? = keys.fold<String, Any?>(this) { node, key -> (node as Map<*, *>)[key] }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * A table over one input, two cases: three months (three rows) and an empty one (`no_data`). The preview's surface —
     * the capability, the agent's cookie-less context, the upload and the release — is the same for every kind; the chart
     * family's proofs (marks, ticks, hover, the Plotly host marker) are `BarChartBrowserTest`'s.
     */
    private fun document(name: String): String =
        """
        {"name": "$name", "display_name": "Preview conformance", "description": "",
         "renderer": {"kind": "table", "version": "1"},
         "inputs": {"sales": {"columns": [{"name": "month", "type": "STRING", "nullable": false},
                                          {"name": "units", "type": "INTEGER", "nullable": false}]}},
         "config": {"columns": [{"label": "Month", "values": []}, {"label": "Units", "values": []}]},
         "bindings": {"columns[0].values": "month", "columns[1].values": "units"},
         "presentation": {"title": "Units by month"},
         "tests": {"cases": [
            {"name": "three months", "fixtures": {"sales": [{"month": "Jan", "units": 3},
                                                           {"month": "Feb", "units": 5},
                                                           {"month": "Mar", "units": 2}]},
             "assertions": [{"kind": "rendered"}, {"kind": "text_visible", "text": "Feb"}]},
            {"name": "empty", "fixtures": {"sales": []}, "assertions": [{"kind": "no_data"}]}]}}
        """.trimIndent()

    private companion object {
        const val EXCERPT = 600
        const val SETTLE_MS = 400.0
        val SHOTS = Paths.get("build", "reports", "visualization-preview")
    }
}
