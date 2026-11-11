package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [ApiVersionsBrowserTest]'s own fixtures (owner ruling 2026-11-08: one browser class per family, fixtures in its own
 * helper — no shared-suite helper changes). Seeding goes through the product's REST routes from inside the signed-in
 * page (session cookie + the CSRF header), the LifecycleDialogBrowserTest pattern.
 */
class ApiVersionsFixtures(
    private val page: Page,
) {
    /** A one-node CALCULATOR pipeline (read-only, so publishable), released at v1; its id and v1's body hash. */
    fun seedReleasedPipeline(name: String): Pair<String, String> {
        val (status, body) = send("POST", "/api/v1/pipelines", calculatorBody(name, "fq"))
        status shouldBe 201
        val id = requireNotNull(Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(requireNotNull(body))).groupValues[1]
        val hash = hashOf(send("GET", "/api/v1/pipelines/$id"))
        send("POST", "/api/v1/pipelines/$id/release", ifMatch = hash).first shouldBe 200
        return id to hash
    }

    /** Saves a v2 draft of the pipeline on top of [baseHash] and releases it: the pipeline's current moves to v2. */
    fun releaseNewPipelineVersion(
        pipelineId: String,
        name: String,
        baseHash: String,
    ) {
        val v2Hash = hashOf(send("PUT", "/api/v1/pipelines/$pipelineId", calculatorBody(name, "fq_v2"), ifMatch = baseHash))
        send("POST", "/api/v1/pipelines/$pipelineId/release", ifMatch = v2Hash).first shouldBe 200
    }

    /** Creates the API at [path], its v1 a DRAFT pinned to [pipelineName]@[pipelineVersion]; its id. */
    fun createEndpoint(
        path: String,
        pipelineName: String,
        pipelineVersion: Int,
    ): String {
        val (status, body) =
            send("POST", "/api/v1/endpoints", """{"path":"$path","pipeline":"$pipelineName","pipeline_version":$pipelineVersion}""")
        status shouldBe 201
        return requireNotNull(Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(requireNotNull(body))).groupValues[1]
    }

    /** Clicks the page verb [verb] and waits for its dialog to land in `#ax-dialog`. */
    fun openDialog(
        verb: String,
        dialog: String,
    ) {
        page.waitForResponse({ it.url().contains("/lifecycle/") }) { page.locator("[data-api-verb='$verb']").click() }
        page.locator("#ax-dialog [data-lifecycle-dialog='$dialog']").waitFor()
    }

    /** Submits the open dialog and waits for the HX-Redirect back onto the page carrying [code]. */
    fun submitAndWaitForPage(code: String) {
        page.locator("#ax-dialog [data-api-submit]").click()
        page.waitForURL("**ok=$code*")
        page.locator("#ax-dialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
    }

    fun shot(name: String) {
        val dir = Paths.get("build", "reports", "786-screenshots")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("786-$name.png")).setFullPage(false))
    }

    private fun calculatorBody(
        name: String,
        nodeId: String,
    ): String =
        """{"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
            """"description":"786 fixture","nodes":[{"id":"$nodeId","type":"CALCULATOR",""" +
            """"kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
            """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""

    private fun hashOf(response: Pair<Int, String?>): String {
        response.first shouldBe 200
        return requireNotNull(Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(requireNotNull(response.second))).groupValues[1]
    }

    @Suppress("UNCHECKED_CAST")
    private fun send(
        method: String,
        url: String,
        body: String? = null,
        ifMatch: String? = null,
    ): Pair<Int, String?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = {'Content-Type': 'application/json',
                                   'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const res = await fetch(args.url, {
                    method: args.method, credentials: 'same-origin', headers,
                    body: args.body ?? undefined,
                  });
                  const text = await res.text();
                  return {status: res.status, body: text.length < 4096 ? text : null};
               }""",
                mapOf("method" to method, "url" to url, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }
}
