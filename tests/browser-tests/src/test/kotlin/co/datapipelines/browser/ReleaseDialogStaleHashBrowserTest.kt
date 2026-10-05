package co.datapipelines.browser

import com.microsoft.playwright.Locator
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** Six independently runnable walks on actual product pages, authenticated forms and production persistence. */
class ReleaseDialogStaleHashBrowserTest : VisualizationBrowserSuite() {
    @Test
    fun `template stale dialog A refuses 409 and preserves B - fresh B releases`() {
        witness("template", stale = true)
    }

    @Test
    fun `dashboard stale dialog A refuses 409 and preserves B - fresh B releases`() {
        witness("dashboard", stale = true)
    }

    @Test
    fun `visualization stale dialog A with GREEN B refuses 409 and preserves B - fresh B releases`() {
        witness("visualization", stale = true)
    }

    @Test
    fun `template fresh dialog independently releases its displayed hash`() {
        witness("template", stale = false)
    }

    @Test
    fun `dashboard fresh dialog independently releases its displayed hash`() {
        witness("dashboard", stale = false)
    }

    @Test
    fun `visualization fresh dialog with GREEN evidence independently releases its displayed hash`() {
        witness("visualization", stale = false)
    }

    private fun witness(
        family: String,
        stale: Boolean,
    ) {
        val root = ready("release453")
        val name = "$root/${family}_probe" + if (family == "template") ".sql" else ""
        ReleaseDialogStaleHashFixtures(family, name, activeWorkspaceName()).use { fixture ->
            val created = boundedApi("POST", "/api/v1/${family}s", ReleaseDialogStaleHashFixtures.document(family, name, "A"))
            val id = if (family == "template") name else created.at("data", "id") as String
            val before = fixture.state()
            before[0] shouldBe "DRAFT"
            before[2] shouldBe null
            if (family == "visualization") greenEvidence(id, requireNotNull(before[1]), fixture)
            val original = openDialog(family, id)
            val hashField = if (family == "visualization") "body_hash" else "bodyHash"
            val displayedHash = original.locator("input[name='$hashField']").inputValue()
            displayedHash shouldBe before[1]
            println("event=release453.open family=$family state=$before displayed_hash=$displayedHash")
            val originalDocument = page.evaluate("() => performance.timeOrigin")
            var expectedHash = displayedHash
            if (stale) {
                val path = if (family == "template") "/api/v1/templates" else "/api/v1/${family}s/$id"
                boundedApi("PUT", path, ReleaseDialogStaleHashFixtures.document(family, name, "B"), displayedHash)
                val changed = fixture.state()
                changed[0] shouldBe "DRAFT"
                changed[2] shouldBe null
                expectedHash = requireNotNull(changed[1])
                (expectedHash == displayedHash) shouldBe false
                if (family == "visualization") greenEvidence(id, expectedHash, fixture)
                // The original form is still in the same document; neither reload nor reconstruction occurred.
                original.locator("input[name='$hashField']").inputValue() shouldBe displayedHash
                page.evaluate("() => performance.timeOrigin") shouldBe originalDocument
                println("event=release453.changed family=$family state=$changed posted_hash=$displayedHash document_unchanged=true")
                val refused =
                    page.waitForResponse({ it.url().contains("/lifecycle/release") && it.request().method() == "POST" }) {
                        original.locator("button[type=submit]").click()
                    }
                withClue("stale $family must refuse before any unseen body releases; state=${fixture.state()}") {
                    refused.status() shouldBe 409
                }
                page.locator("#toast .ds-toast").first().waitFor()
                page.locator("#toast .ds-toast").first().innerText() shouldContain "$family.version.conflict"
                fixture.state() shouldBe changed
                println("event=release453.stale family=$family status=${refused.status()} state=$changed")
            }
            val fresh = if (stale) openDialog(family, id) else original
            fresh.locator("input[name='$hashField']").inputValue() shouldBe expectedHash
            fresh.locator("button[type=submit]").click()
            page.waitForURL("**/*ok=released*")
            page.locator("#toast .ds-toast").first().waitFor()
            page.locator("#toast .ds-toast").first().innerText() shouldContain "Released"
            fixture.state() shouldBe listOf("RELEASED", expectedHash, "1", "1")
            println("event=release453.fresh family=$family state=${fixture.state()}")
        }
    }

    private fun openDialog(
        family: String,
        id: String,
    ): Locator {
        val location =
            when (family) {
                "template" -> "/templates/editor?name=$id&tab=versions"
                "dashboard" -> "/dashboards/$id?version=1&tab=versions"
                "visualization" -> "/visualizations/$id?tab=versions"
                else -> error("unknown fixture family")
            }
        page.navigate("$baseUrl$location")
        page.locator("[data-verb='$family-release']").first().click()
        val container = if (family == "template") "#tx-dialog" else "#dp-dialog"
        val dialog = page.locator("$container [data-lifecycle-dialog='$family-release']")
        dialog.locator("button[type=submit]").waitFor()
        return dialog
    }

    /** The suite's in-page REST convention with an explicit fetch deadline and no capability-bearing body in diagnostics. */
    private fun boundedApi(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Map<*, *> {
        val result =
            page.evaluate(
                """async (args) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
              if (args.ifMatch) headers['If-Match'] = args.ifMatch;
              const init = { method: args.method, credentials: 'same-origin', headers, signal: AbortSignal.timeout(90000) };
              if (args.body !== null) init.body = args.body;
              const response = await fetch(args.path, init);
              const text = await response.text();
              return { status: response.status, json: text ? JSON.parse(text) : null };
            }""",
                mapOf("method" to method, "path" to path, "body" to body, "ifMatch" to ifMatch),
            ) as Map<*, *>
        val status = (result["status"] as Number).toInt()
        withClue("$method $path HTTP $status") { (status in 200..299) shouldBe true }
        return result["json"] as Map<*, *>
    }

    private fun greenEvidence(
        id: String,
        hash: String,
        fixture: ReleaseDialogStaleHashFixtures,
    ) {
        val started = boundedApi("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        started.at("data", "body_hash") shouldBe hash
        val sid = started.at("data", "session_id") as String
        val previewPath = (started.at("data", "preview_url") as String).substringAfter("/visualizations/")
        val preview = newSession()
        try {
            preview.page.navigate("$baseUrl/visualizations/$previewPath")
            preview.page.locator("section[data-dp-case='one month'][data-dp-ready='true']").waitFor()
            preview.page
                .locator("section[data-dp-case='one month'] .plotly .main-svg")
                .first()
                .waitFor()
            preview.page.evaluate("() => document.querySelector('.js-plotly-plot')._fullData.length") shouldBe 1
        } finally {
            preview.close()
        }
        val completed =
            boundedApi(
                "POST",
                "/api/v1/visualizations/$id/tests/sessions/$sid/results",
                """{"cases":[{"name":"one month","verdict":"green"}],"environment":{"browser":"chromium (playwright)","theme":"dark"}}""",
            )
        completed.at("data", "status") shouldBe "GREEN"
        completed.at("data", "mechanical", "ok") shouldBe true
        fixture.greenRunCount(hash) shouldBe 1
        val read = boundedApi("GET", "/api/v1/visualizations/$id", null)
        read.at("data", "draft", "body_hash") shouldBe hash
    }
}
