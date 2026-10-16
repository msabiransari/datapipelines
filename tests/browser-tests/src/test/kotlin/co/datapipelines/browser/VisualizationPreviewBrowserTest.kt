package co.datapipelines.browser

import com.microsoft.playwright.BrowserContext
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
 * browser — opens the preview URL, which mounts the vendored runtime (L3a's artifact, the one Plotly bundle) in its
 * fixture mode under the app's LIVE CSP; the rendered trace count per case is read off Plotly's own graph div; both
 * themes are shot; the agent's screenshot is uploaded with the single-use capability ALONE; the author reads it back
 * and releases.
 *
 * What this does NOT claim: that the server rendered anything — the screenshots are REVIEW evidence (the issue's
 * sentence; the server-side headless check is L6). The CSP measurement is the suite's collector (zero violations
 * across every page every context of this test opened, asserted in `closePage`).
 */
class VisualizationPreviewBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `the head marker precedes Plotly's first lookup and the linked rules apply in both themes`() {
        startTrace()
        val root = ready("vmarker")
        val created = api("POST", "/api/v1/visualizations", document("$root/charts/marker"))
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val agent = newBrowserContext()
        recordMarkerAtLoad(agent)
        try {
            val preview = agent.newPage()
            preview.setDefaultTimeout(FIRST_RENDER_TIMEOUT_MS)
            for (theme in listOf("light", "dark")) {
                preview.navigate("$baseUrl$previewUrl&theme=$theme")
                preview.waitForSelector("section[data-dp-case-index='0'][data-dp-ready='true']")
                val observation =
                    preview.evaluate(
                        """() => {
                          const marker = document.getElementById('plotly.js-style-global');
                          return { atLoad: window.__plotlyMarkerAtLoad,
                            rules: marker && marker.sheet ? marker.sheet.cssRules.length : 0,
                            cssLoaded: Array.from(document.styleSheets).some(s => s.href && s.href.endsWith('/vendor/plotly/plotly.css')),
                            font: getComputedStyle(document.querySelector('.js-plotly-plot .plotly')).fontFamily };
                        }""",
                    ) as Map<*, *>
                println("Plotly marker ($theme): $observation")
                val atLoad = observation["atLoad"] as Map<*, *>
                withClue("parser-placed marker at the bundle's first lookup ($theme): $observation") {
                    atLoad["marked"] shouldBe true
                    atLoad["inHead"] shouldBe true
                    atLoad["beforeScript"] shouldBe true
                    atLoad["tag"] shouldBe "TEMPLATE"
                    atLoad["children"] shouldBe 0
                    atLoad["hasSheet"] shouldBe false
                    atLoad["rules"] shouldBe 0
                }
                observation["rules"] shouldBe 0
                observation["cssLoaded"] shouldBe true
                // Reuse DashboardConformanceBrowserTest's positive stylesheet probe.
                (observation["font"] as String).contains("Open Sans") shouldBe true
            }
        } finally {
            agent.close()
        }
    }

    /**
     * #526 — a host that does NOT pre-place the marker gets it from the runtime's fallback
     * (`_ensureStyleElement`), so the fallback's marker must be CSP-compliant too. Chromium
     * refuses EVERY script-created `<style>` element at insertion as an inline style under
     * `style-src 'self'` (with a text child, with an empty comment, or childless — each with the
     * empty-text hash; measured, the #526 lane's probe), so the fallback creates the marker as a
     * TEMPLATE — the same shape every first-party host pre-places (#456): found by id, matching
     * the class, no children, never processed as a stylesheet (no sheet — the host marker's own
     * shape), and ZERO `Applying inline style` refusals from the application's own scripts —
     * the narrowed collector fails the test on any of the latter in `closePage`. Plotly's
     * opt-out did not disable the vendored stylesheet: the positive probe of the conformance
     * case (`getComputedStyle('.js-plotly-plot .plotly').fontFamily`) still reads Open Sans.
     * The instance is reached through `window.DatapipelinesPreview.instances` — the preview's
     * own registry (`visualization-preview.js`), the same path the #456 probe used.
     */
    @Test
    fun `the missing-marker fallback creates a CSP-compliant marker in both themes`() {
        startTrace()
        val root = ready("vfallback")
        val created = api("POST", "/api/v1/visualizations", document("$root/charts/fallback"))
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val agent = newBrowserContext()
        try {
            val preview = agent.newPage()
            preview.setDefaultTimeout(FIRST_RENDER_TIMEOUT_MS)
            for (theme in listOf("light", "dark")) {
                preview.navigate("$baseUrl$previewUrl&theme=$theme")
                preview.waitForSelector("section[data-dp-case-index='0'][data-dp-ready='true']")
                val observation =
                    preview.evaluate(
                        """() => {
                          const host = document.getElementById('plotly.js-style-global');
                          if (host) host.remove();
                          window.DatapipelinesPreview.instances[0]._ensureStyleElement();
                          const el = document.getElementById('plotly.js-style-global');
                          return { exists: !!el,
                            inHead: !!el && el.parentElement === document.head,
                            tag: el ? el.tagName : null,
                            className: el ? el.className : null,
                            children: el ? el.childNodes.length : null,
                            hasSheet: !!el && !!el.sheet,
                            matches: !!el && el.matches('.no-inline-styles') };
                        }""",
                    ) as Map<*, *>
                println("Fallback marker ($theme): $observation")
                withClue("the fallback-created marker is CSP-compliant ($theme): $observation") {
                    observation["exists"] shouldBe true
                    observation["inHead"] shouldBe true
                    observation["tag"] shouldBe "TEMPLATE"
                    (observation["className"] as String).contains("no-inline-styles") shouldBe true
                    observation["children"] shouldBe 0
                    observation["matches"] shouldBe true
                    observation["hasSheet"] shouldBe false
                }
                // Falsification 4: the opt-out did not disable plotly.css (the conformance case's probe).
                val font =
                    preview.evaluate(
                        "() => getComputedStyle(document.querySelector('.js-plotly-plot .plotly')).fontFamily",
                    ) as String
                withClue("the vendored stylesheet still applies with the fallback-created marker ($theme)") {
                    font.contains("Open Sans") shouldBe true
                }
            }
        } finally {
            agent.close()
        }
    }

    private fun recordMarkerAtLoad(agent: BrowserContext) {
        // Record the marker at Plotly's FIRST opt-out lookup, before it can create one itself.
        // Final DOM order alone cannot distinguish a parser-placed marker from Plotly's late insertion.
        agent.addInitScript(
            """(() => {
              const original = document.getElementById.bind(document);
              document.getElementById = function (id) {
                const marker = original(id);
                if (id === 'plotly.js-style-global' && !window.__plotlyMarkerAtLoad) {
                  const script = document.querySelector('script');
                  window.__plotlyMarkerAtLoad = {
                    marked: !!marker && marker.classList.contains('no-inline-styles'),
                    inHead: !!marker && marker.parentElement === document.head,
                    beforeScript: !!marker && !!script && !!(marker.compareDocumentPosition(script) & Node.DOCUMENT_POSITION_FOLLOWING),
                    tag: marker ? marker.tagName : null,
                    children: marker ? (marker.content || marker).childNodes.length : null,
                    hasSheet: !!marker && !!marker.sheet,
                    rules: marker && marker.sheet ? marker.sheet.cssRules.length : 0
                  };
                }
                return marker;
              };
            })();""",
        )
    }

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
                """{"cases":[{"name":"two series","verdict":"green","notes":"two traces in both themes"},
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
                "$baseUrl${upload["url"]}?case=two%20series",
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

    @Test
    fun `#525 - a heatmap's colorbar draws on the 2d bundle under the policy, painted and redrawn, in both themes`() {
        startTrace()
        val root = ready("vheat")
        val created = api("POST", "/api/v1/visualizations", heatmapDocument("$root/charts/heat"))
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val agent = newBrowserContext()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        agent.onRequest { requested += it.url() }
        try {
            val preview = agent.newPage()
            preview.setDefaultTimeout(FIRST_RENDER_TIMEOUT_MS)
            for (theme in listOf("light", "dark")) {
                preview.navigate("$baseUrl$previewUrl&theme=$theme")
                preview.waitForSelector("section[data-dp-case-index='0'][data-dp-ready='true']")
                preview.waitForSelector("section[data-dp-case-index='1'][data-dp-ready='true']")
                // The page that loads the 2d bundle: a heatmap alone is no reason for the 3d superset.
                preview.locator("script[data-dp-plotly-bundle]").getAttribute("data-dp-plotly-bundle") shouldBe "2d"
                for (index in 0..1) {
                    PlotlyColorbarProbe.assertPainted(
                        preview,
                        "the heatmap's case $index in $theme",
                        "section[data-dp-case-index='$index']",
                    )
                    withClue("#531 - the default heatmap's case $index keeps crisp cells in $theme") {
                        preview.locator("section[data-dp-case-index='$index'] .heatmaplayer image")
                            .evaluate("image => getComputedStyle(image).imageRendering") shouldBe "pixelated"
                    }
                }
                preview.waitForTimeout(SETTLE_MS)
                Files.createDirectories(SHOTS)
                Files.write(
                    SHOTS.resolve("visualization-preview-heatmap-$theme.png"),
                    preview.screenshot(Page.ScreenshotOptions().setFullPage(true)),
                )
                withClue("CSP on the heatmap preview in $theme (the colorbar draws)") { drainCspViolations().shouldBeEmpty() }

                // The redraw runs the colorbar code again over the existing fill rectangles.
                for (index in 0..1) {
                    PlotlyColorbarProbe.redraw(preview, "the heatmap's case $index in $theme", "section[data-dp-case-index='$index']")
                    PlotlyColorbarProbe.assertPainted(
                        preview,
                        "the heatmap's case $index in $theme after a redraw",
                        "section[data-dp-case-index='$index']",
                    )
                }
                withClue("CSP on the heatmap preview in $theme after the redraws") { drainCspViolations().shouldBeEmpty() }
            }
        } finally {
            agent.close()
        }
        withClue("the preview loaded the 2d bundle and never the 3d superset") {
            requested.any { it.contains("/vendor/plotly/plotly-2d.min.js") } shouldBe true
            requested.none { it.contains("/vendor/plotly/plotly-3d.min.js") } shouldBe true
        }
    }

    /**
     * One theme of the preview, as an agent checks it: both cases mounted, the data case's chip at success/ready and the
     * empty case's at no-data, the theme applied, the trace count off Plotly's graph div and in the SVG, and the shots —
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
        // The rendered trace count, read off Plotly's own graph div (the renderer's state, not an impression).
        val traces =
            agentPage.evaluate(
                "() => { const g = document.querySelector(\"section[data-dp-case-index='0'] .js-plotly-plot\");" +
                    " return g && g._fullData ? g._fullData.length : -1; }",
            ) as Number
        withClue("case 'two series' renders its two traces ($theme)") { traces.toInt() shouldBe 2 }
        val layers =
            agentPage.evaluate(
                "() => { const s = document.querySelector(\"section[data-dp-case-index='0']\");" +
                    " return { traces: s.querySelectorAll('g.trace').length, bars: s.querySelectorAll('.barlayer .trace').length," +
                    " lines: s.querySelectorAll('.scatterlayer .trace').length, svgs: s.querySelectorAll('svg.main-svg').length }; }",
            ) as Map<*, *>
        withClue("both traces are drawn in the SVG ($theme): $layers") { ((layers["traces"] as Number).toInt() >= 2) shouldBe true }
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

    private fun document(name: String): String =
        """
        {"name": "$name", "display_name": "Preview conformance", "description": "",
         "renderer": {"kind": "plotly", "version": "4"},
         "inputs": {"sales": {"columns": [{"name": "month", "type": "STRING", "nullable": false},
                                          {"name": "units", "type": "INTEGER", "nullable": false},
                                          {"name": "target", "type": "INTEGER", "nullable": false}]}},
         "config": {"data": [{"type": "bar", "x": [], "y": []}, {"type": "scatter", "mode": "lines", "x": [], "y": []}],
                    "layout": {"title": {"text": "Units against target"}}},
         "bindings": {"data[0].x": "month", "data[0].y": "units", "data[1].x": "month", "data[1].y": "target"},
         "presentation": {"title": "Units against target"},
         "tests": {"cases": [
            {"name": "two series", "fixtures": {"sales": [{"month": "Jan", "units": 3, "target": 4},
                                                         {"month": "Feb", "units": 5, "target": 4},
                                                         {"month": "Mar", "units": 2, "target": 4}]},
             "assertions": [{"kind": "rendered"}, {"kind": "trace_count", "equals": 2}]},
            {"name": "empty", "fixtures": {"sales": []}, "assertions": [{"kind": "no_data"}]}]}}
        """.trimIndent()

    /**
     * A heatmap with its default colorbar: a 3 x 2 grid per case, the second case over a different value range.
     */
    private fun heatmapDocument(name: String): String =
        """
        {"name": "$name", "display_name": "Heatmap colorbar", "description": "",
         "renderer": {"kind": "plotly", "version": "4"},
         "inputs": {"sales": {"columns": [{"name": "month", "type": "STRING", "nullable": false},
                                          {"name": "units", "type": "INTEGER", "nullable": false},
                                          {"name": "target", "type": "INTEGER", "nullable": false}]}},
         "config": {"data": [{"type": "heatmap", "x": [], "y": [], "z": []}], "layout": {}},
         "bindings": {"data[0].x": "month", "data[0].y": "target", "data[0].z": "units"},
         "presentation": {"title": "Heatmap colorbar"},
         "tests": {"cases": [
            {"name": "grid", "fixtures": {"sales": [{"month": "Jan", "units": 3, "target": 4},
                                                   {"month": "Feb", "units": 5, "target": 4},
                                                   {"month": "Mar", "units": 2, "target": 4},
                                                   {"month": "Jan", "units": 6, "target": 2},
                                                   {"month": "Feb", "units": 1, "target": 2},
                                                   {"month": "Mar", "units": 4, "target": 2}]},
             "assertions": [{"kind": "rendered"}, {"kind": "trace_count", "equals": 1}]},
            {"name": "shifted", "fixtures": {"sales": [{"month": "Jan", "units": 30, "target": 4},
                                                      {"month": "Feb", "units": 50, "target": 4},
                                                      {"month": "Mar", "units": 20, "target": 4},
                                                      {"month": "Jan", "units": 60, "target": 2},
                                                      {"month": "Feb", "units": 10, "target": 2},
                                                      {"month": "Mar", "units": 40, "target": 2}]},
             "assertions": [{"kind": "rendered"}]}]}}
        """.trimIndent()

    private companion object {
        const val EXCERPT = 600
        const val SETTLE_MS = 400.0
        val SHOTS = Paths.get("build", "reports", "visualization-preview")
    }
}
