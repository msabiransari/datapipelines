package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 7d (#7, transform-nodes design §9.3, §10.10) — the transform editor face in a real browser,
 * against the real app: 7b's save gate, the evaluation pool, the JSONata engine, the lifecycle
 * dialogs. Two walks, both through the app's own doors (the explorer's search, the result row,
 * Open in editor — never a typed editor URL):
 *
 *  - an AUTHOR creates a jsonata template through the create modal (the record's example
 *    prefilled), finds it under the `jsonata` type filter and NOT under `html` (the filter's
 *    falsification: an html template with the same stem is seeded beside it), opens the
 *    editor, edits the body so a case goes red, runs the suite — red, with the first
 *    difference's path and both sides — fixes the expectation, runs it green, saves the draft
 *    (the hash changes), and releases it through the dialog;
 *  - a VIEWER opens the same kind of template: four read-only panes and no verb at all.
 *
 * Owner ruling 2026-09-25: Run suite evaluates the panes AS TYPED — a saved draft is always
 * green, because the save gate runs the suite — so the red leg runs BEFORE the save.
 *
 * Screenshots under build/reports/7d-screenshots (the per-class shot convention of
 * ExplorerDetailBrowserTest): the face at 1100/1440/1920 in light and dark, the red and green
 * result lists, the saved face, the released face, the viewer's face.
 */
class TransformFaceBrowserTest : BrowserSuite() {
    @Test
    fun `an author creates a jsonata template, runs its suite red then green, saves and releases it`() {
        startTrace()
        val author =
            seedLocalUser(
                uniqueEmail("tf-author-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "author",
            )
        login(author.email, author.oneTimePassword)
        page.waitForURL("**/dashboard")
        val stem = "tf7d_" + suffix()
        val name = "test/$stem.jsonata"
        // The filter's falsification partner: same stem, the html type.
        send("POST", "/api/v1/templates", htmlTemplateJson("test/${stem}_page.html")).first shouldBe 201

        createThroughTheModal(name)
        filterFinds(stem, type = "jsonata", present = name, absent = "test/${stem}_page.html")
        filterFinds(stem, type = "html", present = "test/${stem}_page.html", absent = name)
        openInEditor(stem)

        // The face: four panes, the example's body, the draft's hash as the save precondition.
        listOf("#tf-body", "#tf-contract", "#tf-invariants", "#tf-tests").forEach { page.locator(it).waitFor() }
        page.locator("#tf-body").inputValue() shouldContain "customer_id missing"
        val hashBefore = page.locator("[data-tf-hash]").getAttribute("title")
        facesShots("face-author")

        redThenGreen()
        saveThenRelease(hashBefore)
        themedShots("released")
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `a viewer reads a transform template as four read-only panes and no verb`() {
        startTrace()
        val stem = "tf7dv_" + suffix()
        val name = "test/$stem.jsonata"
        // The author seeds it over REST (a session — no key reaches /api/v1).
        val author =
            seedLocalUser(uniqueEmail("tf-seed-" + suffix()), generatedPassword("pw"), mustChange = false, isAdmin = false, role = "author")
        login(author.email, author.oneTimePassword)
        page.waitForURL("**/dashboard")
        send("POST", "/api/v1/templates", transformTemplateJson(name)).let { (status, body) ->
            check(status == 201) { "transform create $status ${body?.take(400)}" }
        }

        val viewer =
            seedLocalUser(
                uniqueEmail("tf-viewer-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "viewer",
            )
        page.context().clearCookies()
        login(viewer.email, viewer.oneTimePassword)
        page.waitForURL("**/dashboard")
        openInEditor(stem)

        page.locator("#tf-body-ro").waitFor()
        page.locator("#template-source pre[id^='tf-'][id$='-ro']").count() shouldBe 4
        page.locator("#template-source textarea").count() shouldBe 0
        page.locator("#app-main [data-verb]").count() shouldBe 0
        page.locator("[data-role-note='read-only']").isVisible shouldBe true
        page.locator("#tf-contract-ro").innerText() shouldContain "\"mode\": \"row\""
        themedShots("face-viewer")
        drainCspViolations().shouldBeEmpty()
    }

    // ------------------------------------------------------------------ walks

    /** An unsaved edit breaks one case (red, with the difference), the expectation is fixed (green). */
    private fun redThenGreen() {
        // An unsaved edit that breaks one case: the reject reason changes in the body only.
        page.locator("#tf-body").fill(page.locator("#tf-body").inputValue().replace("\"customer_id missing\"", "\"no customer\""))
        page.locator("#tf-dirty").isVisible shouldBe true
        runSuite()
        page.locator("#tf-result [data-tf-outcome='red']").waitFor()
        val red = page.locator("#tf-result li.tf-case[data-tf-passed='false']")
        red.count() shouldBe 1
        red.locator(".tf-case-name").innerText() shouldBe "missing customer is rejected"
        red.locator("[data-tf-diff-path]").innerText() shouldBe "$.rejects[0].reason"
        red.locator("[data-tf-expected]").innerText() shouldBe "\"customer_id missing\""
        red.locator("[data-tf-actual]").innerText() shouldBe "\"no customer\""
        page.locator("#tf-result").innerText() shouldContain "Save would refuse them with template.test_failed"
        // The verdict is on screen, not below the four panes' fold (template-transform-face.js).
        page.evaluate(RESULT_IN_VIEW) shouldBe true
        themedShots("suite-red")

        // Fix the expectation, run it green.
        page.locator("#tf-tests").fill(page.locator("#tf-tests").inputValue().replace("\"customer_id missing\"", "\"no customer\""))
        runSuite()
        page.locator("#tf-result [data-tf-outcome='green']").waitFor()
        page.locator("#tf-result").innerText() shouldContain "3 of 3 cases pass"
        page.locator("#tf-result li.tf-case[data-tf-passed='false']").count() shouldBe 0
        themedShots("suite-green")
    }

    /** Save draft (the hash changes), then Release through the existing dialog — read-only after. */
    private fun saveThenRelease(hashBefore: String?) {
        // Save draft: 7b's gate accepts, the face re-renders over the stored draft with a NEW hash.
        val save = page.waitForResponse({ it.url().contains("/partials/templates/transform-face/save") }) { page.click("#tf-save") }
        save.status() shouldBe 200
        page.locator("[data-tf-outcome='saved']").waitFor()
        val hashAfter = page.locator("[data-tf-hash]").getAttribute("title")
        hashAfter shouldNotBe hashBefore
        page.locator("#tf-dirty").isVisible shouldBe false
        page.locator("#tf-body").inputValue() shouldContain "\"no customer\""
        themedShots("saved")

        // Release through the existing dialog: the page reloads on the released version, read-only.
        page.click("#tpl-release-draft")
        val dialog = page.locator("#te-dialog [data-lifecycle-dialog]").first()
        dialog.waitFor()
        dialog.locator("button[type=submit]").click()
        // The editor's release answers HX-Redirect to the editor with `ok=released` (102).
        page.waitForURL({ url -> url.contains("/templates/editor") && url.contains("ok=released") })
        page.locator("#tf-body-ro").waitFor()
        page.locator("#template-source").innerText() shouldContain "RELEASED"
        page.locator("#template-source [data-verb='template-edit']").count() shouldBe 1
        page.locator("#template-source [data-verb='transform-save']").count() shouldBe 0
    }

    private fun createThroughTheModal(name: String) {
        page.navigate("$baseUrl/templates")
        page.click("text=Create Template")
        page.locator("#create-template-modal").isVisible shouldBe true
        page.fill("#create-template-modal input[name=name]", name)
        page.selectOption("#create-template-type", "jsonata")
        // The type's blocks: shown, enabled, prefilled with the record's example; no dialect.
        page.locator("#create-template-blocks-field").isVisible shouldBe true
        page.locator("#create-template-contract").isEnabled shouldBe true
        page.locator("#create-template-dialect-field").isVisible shouldBe false
        page.locator("#create-template-body").inputValue() shouldContain "rejects"
        page.fill("#create-template-modal input[name=description]", "7d browser fixture")
        shot("create-modal-jsonata-1440-" + mode(), viewport = 1440)
        val create =
            page.waitForResponse({ it.url().endsWith("/partials/templates") && it.request().method() == "POST" }) {
                page.click("#create-template-modal button[type=submit]")
            }
        create.status() shouldBe 200
        page.waitForFunction("() => getComputedStyle(document.getElementById('create-template-modal')).display === 'none'")
    }

    /** The search box plus the type filter: [present] is listed, [absent] is not. */
    private fun filterFinds(
        stem: String,
        type: String,
        present: String,
        absent: String,
    ) {
        page.navigate("$baseUrl/templates?q=$stem")
        page.waitForResponse({ it.url().contains("/partials/templates") && it.url().contains("type=$type") }) {
            page.selectOption("#template-filter-type", type)
        }
        page.locator("button.tpl-result span.tpl-path", Page.LocatorOptions().setHasText(present)).first().waitFor()
        val paths = page.locator("button.tpl-result span.tpl-path").allInnerTexts()
        paths.contains(present) shouldBe true
        paths.contains(absent) shouldBe false
    }

    private fun openInEditor(stem: String) {
        page.navigate("$baseUrl/templates?q=$stem")
        page
            .locator("button.tpl-result", Page.LocatorOptions().setHasText("$stem.jsonata"))
            .first()
            .click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/templates/editor**")
    }

    private fun runSuite() {
        val res =
            page.waitForResponse(
                { it.url().contains("/partials/templates/transform-face/run-suite") },
            ) { page.click("#tf-run-suite") }
        res.status() shouldBe 200
    }

    // ------------------------------------------------------------------ fixtures

    private fun suffix(): String = generatedPassword("s").takeLast(8).lowercase()

    private fun htmlTemplateJson(id: String): String =
        """{"id":"$id","type":"html","display_name":"page","description":"7d filter partner","body":"<p>page</p>"}"""

    /** The design record's §2.2 example, as the create modal prefills it. */
    private fun transformTemplateJson(id: String): String =
        """
        {"id":"$id","type":"jsonata","display_name":"order lines","description":"7d browser fixture",
         "body":"{ \"rows\": [ rows[customer_id != null].{ \"order_id\": order_id, \"amount\": amount_cents / 100, \"customer_id\": customer_id } ], \"rejects\": [ rows[customer_id = null].{ \"row\": ${'$'}, \"reason\": \"customer_id missing\" } ] }",
         "contract":{"mode":"row","inputs":{"orders":{"kind":"table","columns":[{"name":"order_id","type":"INTEGER"},{"name":"amount_cents","type":"INTEGER"},{"name":"customer_id","type":"STRING","nullable":true}]}},
                     "output":{"kind":"table","columns":[{"name":"order_id","type":"INTEGER"},{"name":"amount","type":"DECIMAL","precision":12,"scale":2},{"name":"customer_id","type":"STRING"}]},"rejects":true},
         "invariants":[{"name":"one_to_one","expr":"${'$'}count(rows) + ${'$'}count(rejects) = ${'$'}count(inputs.orders)","message":"every input row is accepted or rejected, never lost"}],
         "tests":[{"name":"empty input","input":{"rows":[]},"expect":{"output":{"rows":[],"rejects":[]}}}]}
        """.trimIndent()

    /** The in-page REST call of the sibling suites: the session's cookie and the CSRF pair. */
    @Suppress("UNCHECKED_CAST")
    private fun send(
        method: String,
        url: String,
        body: String? = null,
    ): Pair<Int, String?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = {'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  const res = await fetch(args.url, { method: args.method, credentials: 'same-origin', headers, body: args.body ?? undefined });
                  const text = await res.text();
                  return { status: res.status, body: text.length < 4096 ? text : null };
                }""",
                mapOf("method" to method, "url" to url, "body" to body),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    // ------------------------------------------------------------------ screenshots

    private fun mode(): String =
        if ((page.locator("#theme-link").first().getAttribute("href") ?: "").contains("/light.css")) "light" else "dark"

    /** The face at the explorer's desktop widths (the editor is desktop-first; below 768 it asks for a wider screen). */
    private fun facesShots(name: String) {
        val overflowing = mutableListOf<String>()
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            listOf(1100, 1440, 1920).forEach { width ->
                shot("$name-$width-$theme", viewport = width)
                // The page must not be wider than the window: no sideways scroll, and no <main> wider
                // than the window and clipped (which a scroll-width check alone cannot see). Asked at
                // 1100 too since #237: the shell's top bar sized the column 43px past that window on
                // every template editor, and this guard carried a tolerance for it until the fix.
                val widths = page.evaluate(PAGE_WIDTHS) as List<*>
                if (widths.any { (it as Number).toInt() > width }) overflowing += "$width/$theme: scroll, main.right = $widths"
            }
        }
        overflowing.shouldBeEmpty()
        page.setViewportSize(1440, 900)
    }

    private fun themedShots(name: String) {
        val back = mode()
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            shot("$name-1440-$theme", viewport = 1440)
        }
        ensureTheme(back)
    }

    /**
     * The theme switch that each light/dark pair needs leaves a "Theme updated" toast over the
     * header for 6 s; every visible toast is dismissed through its own × before a shot, so the
     * picture shows the screen and not the switch that took it.
     */
    private fun dismissToasts() {
        page.locator("#toast .ds-toast-close").all().forEach { close -> if (close.isVisible) close.click() }
        page.waitForFunction("() => document.querySelectorAll('#toast .ds-toast').length === 0")
    }

    private companion object {
        /** The document's scroll width and `<main>`'s right edge — both must be within the window. */
        const val PAGE_WIDTHS =
            "() => [document.documentElement.scrollWidth, Math.round(document.querySelector('main').getBoundingClientRect().right)]"

        /** Whether the result region is on screen — not below the four panes' fold. */
        const val RESULT_IN_VIEW =
            "() => { const r = document.getElementById('tf-result').getBoundingClientRect(); " +
                "return r.top < window.innerHeight && r.bottom > 0; }"
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "7d-screenshots").also { it.toFile().mkdirs() }

    /** A full-page shot at [viewport] px wide — the editor's panes scroll inside the page. */
    private fun shot(
        name: String,
        viewport: Int,
    ) {
        page.setViewportSize(viewport, 1000)
        // The reflow a resize starts has landed before the picture is taken.
        page.waitForFunction("(w) => window.innerWidth === w && document.documentElement.clientWidth <= w", viewport)
        dismissToasts()
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("7d-$name.png")).setFullPage(true))
    }
}
