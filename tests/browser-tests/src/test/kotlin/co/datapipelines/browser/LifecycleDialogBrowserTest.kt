package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 102's ONE golden path per entity (ui-screens §4.3d): every lifecycle verb driven through
 * its DIALOG, asserting the tree badge and the toast after each — the §3.5.2 rows the
 * dialogs render ("the fallback catches the draft", "purge-of-current falls back") are
 * asserted through the TOAST TEXT, which is the service's own outcome, never a guess.
 *
 * The screenshot set (every dialog, light and dark, 1280 and 390) rides the same fixture —
 * the dialogs are opened by the same clicks the golden path makes.
 */
class LifecycleDialogBrowserTest : BrowserSuite() {
    private var shotIndex = 0

    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("lc102-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("lcws-" + generatedPassword("w").take(8).lowercase())
    }

    /** The in-page REST seeding of ExplorerDetailBrowserTest, widened with method/If-Match. */
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

    private fun createDraftPipeline(name: String): String {
        val (status, body) =
            send(
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"102 golden path","nodes":[{"id":"fq","type":"CALCULATOR",""" +
                    """"kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
            )
        status shouldBe 201
        return Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(body!!)!!.groupValues[1]
    }

    /** Releases the pipeline's draft over REST (If-Match the draft's hash — §4.2). */
    private fun releaseViaRest(id: String) {
        val (gs, gbody) = send("GET", "/api/v1/pipelines/$id")
        gs shouldBe 200
        val hash = Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(gbody!!)!!.groupValues[1]
        val (rs) = send("POST", "/api/v1/pipelines/$id/release", ifMatch = hash)
        rs shouldBe 200
    }

    /** A draft over the current release — the PUT's copy-on-write first write (§5.1). */
    private fun openDraftOverRelease(id: String) {
        val name = "test/" + id
        val (gs, gbody) = send("GET", "/api/v1/pipelines/$id")
        gs shouldBe 200
        val hash = Regex("\"body_hash\"\\s*:\\s*\"([0-9a-f]+)\"").find(gbody!!)!!.groupValues[1]
        val ps =
            send(
                "PUT",
                "/api/v1/pipelines/$id",
                """{"schema_version":1,"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"102 golden path (draft)","parameters":{},""" +
                    """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter",""" +
                    """"context_key":"run_fiscal_quarter","inputs":{"date":"2026-08-14","fiscal_start":"09-15"}}]}""",
                ifMatch = hash,
            ).first
        ps shouldBe 200
    }

    private fun pipelineId(name: String): String =
        send("GET", "/api/v1/pipelines?prefix=${name.substringBeforeLast('/')}/")
            .second!!
            .let { Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(it)!!.groupValues[1] }

    private fun createDraftTemplate(id: String) {
        val (status) =
            send(
                "POST",
                "/api/v1/templates",
                """{"id":"$id","type":"sql","dialect":"POSTGRES",""" +
                    """"display_name":"${id.substringAfterLast('/')}","description":"102 golden path",""" +
                    """"body":"SELECT 1"}""",
            )
        status shouldBe 201
    }

    private fun openDraftOverReleaseTemplate(id: String) {
        val (gs, gbody) = send("GET", "/api/v1/templates?name=$id")
        gs shouldBe 200
        val hash = Regex("\"body_hash\"\\s*:\\s*\"([0-9a-f]+)\"").find(gbody!!)!!.groupValues[1]
        val ps =
            send(
                "PUT",
                "/api/v1/templates",
                """{"id":"$id","body":"SELECT 2","display_name":"drafted","description":"102 draft","dialect":"POSTGRES","imports":[]}""",
                ifMatch = hash,
            ).let { if (it.first != 200) throw AssertionError("template PUT ${it.first}: ${it.second?.take(300)}") else it.first }
        ps shouldBe 200
    }

    // ------------------------------------------------------------ the dialogs' helpers

    private fun openDialog(button: Locator): Locator {
        button.click()
        return page
            .locator("#px-dialog [data-lifecycle-dialog], #tx-dialog [data-lifecycle-dialog]")
            .first()
            .also { it.waitFor() }
    }

    private fun rowMenu(versionBadge: String): Locator {
        val row = page.locator(".tplx-vrow").filter(Locator.FilterOptions().setHasText(versionBadge)).first()
        row.locator("details.tplx-vmenu summary").click()
        return row.locator(".tplx-vmenu-list")
    }

    /**
     * The success toast lands OOB in the stack; this waits for a NEW one (the previous
     * toast may still be inside its 6s auto-dismiss window) and hands its text back. A
     * refusal shows up here too: the stack's full text rides the timeout message, so a
     * Shape C refusal names its code instead of just timing out.
     */
    private fun successToast(): String {
        val before = toastCount()
        page.waitForFunction(
            "(n) => document.querySelectorAll('#toast .ds-toast').length > n",
            before,
        )
        // The NEW toast is the one at index `before` — earlier toasts may still be inside
        // their 6s window, and reading `.last()` would hand back a stale sibling's text.
        return page.locator("#toast .ds-toast").nth(before).innerText()
    }

    private fun toastCount(): Int = page.locator("#toast .ds-toast").count()

    /**
     * The redirect legs' toast: it arrives from the flash BIN via toast.js's init adoption,
     * which races this call on a freshly navigated document — so this waits for ANY toast
     * rather than counting past a baseline the adoption may already have passed.
     */
    private fun flashToast(): String {
        page.locator("#toast .ds-toast").first().waitFor()
        return page.locator("#toast .ds-toast").first().innerText()
    }

    private fun badgeOf(name: String): String? {
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val leaf = page.locator("button.tpl-leaf", Page.LocatorOptions().setHasText(name.substringAfterLast('/'))).first()
        val version = leaf.locator(".tpl-leaf-version")
        return if (version.count() > 0) version.innerText() else null
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "reports", "102-screenshots")
        Files.createDirectories(dir)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(dir.resolve("102-$name.png"))
                .setFullPage(false),
        )
        shotIndex += 1
    }

    // ---------------------------------------------------------------- the pipelines path

    @Test
    fun `the pipelines golden path - every verb through its dialog, badge and toast after each`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        val probeId = createDraftPipeline("test/lifecycle_probe")

        page.setViewportSize(1280, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeafOf("test/lifecycle_probe")

        // 1 — Release v1 (the header's dialog): the toast names it, the tree loses the draft
        //      badge, and the pointer chip is the released number.
        val releaseDialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v1")))
        releaseDialog.innerText() shouldContain "Releasing makes v"
        releaseDialog.locator("button[type=submit]").click()
        successToast() shouldContain "Released v1"
        badgeOf("test/lifecycle_probe") shouldBe "v1"

        // 2 — A draft over the release (§5.1 copy-on-write), then Discard the CURRENT release:
        //      the §3.5.2 fallback catches the draft, and the TOAST says so (row 286).
        openDraftOverRelease(probeId)
        page.navigate("$baseUrl/pipelines")
        selectLeafOf("test/lifecycle_probe")
        badgeOf("test/lifecycle_probe") shouldBe "v2"
        val discardDialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Discard v1")))
        discardDialog.innerText() shouldContain "v2 becomes current"
        shot("pipelines-discard-1280-light")
        discardDialog.locator("button[type=submit]").click()
        successToast() shouldContain "v2 is current now."

        // 3 — Restore v1: below the pointer, so the pointer stays (§3.4) — the toast's words.
        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Restore v1")).click()
        page.locator("#px-dialog [data-lifecycle-dialog='pipeline-restore'] button[type=submit]").click()
        successToast() shouldContain "the pointer stays at v2"

        // 4 — Switch to v1 (the row's Switch-to, when not current): endpoints follow.
        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Switch to v1")).click()
        val switchDialog = page.locator("#px-dialog [data-lifecycle-dialog='pipeline-switch']")
        switchDialog.waitFor()
        switchDialog.locator("button[type=submit]").click()
        successToast() shouldContain "Switched to v1"

        // 5 — Purge the draft v2, which IS the pointer: purge-of-current falls back (§3.5.2
        //      row 330), through the typed confirm the whole way.
        rowMenu("v2").locator("button", Locator.LocatorOptions().setHasText("Purge v2")).click()
        val purgeDialog = page.locator("#px-dialog [data-lifecycle-dialog='pipeline-purge']")
        purgeDialog.waitFor()
        purgeDialog.innerText() shouldContain "cannot be undone"
        val typed = purgeDialog.locator("[data-confirm-input]")
        typed.fill("v2")
        purgeDialog.locator("button[data-typed-confirm]").click()
        successToast() shouldContain "Purged v2"
        badgeOf("test/lifecycle_probe") shouldBe "v1"

        // 6 — The {D} shape's entity purge: a never-released pipeline, the NAME typed, the
        //      tree loses the leaf, and the flash toast lands after the redirect.
        createDraftPipeline("test/lifecycle_chaff")
        page.navigate("$baseUrl/pipelines")
        selectLeafOf("test/lifecycle_chaff")
        val entityDialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Purge pipeline")))
        entityDialog.waitFor()
        entityDialog.locator("[data-confirm-input]").fill("test/lifecycle_chaff")
        entityDialog.locator("button[data-typed-confirm]").click()
        page.waitForURL("**/pipelines?ok=entity_purged")
        flashToast() shouldContain "Pipeline purged"
        page.locator("button.tpl-leaf", Page.LocatorOptions().setHasText("lifecycle_chaff")).count() shouldBe 0
    }

    // --------------------------------------------------------------- the templates path

    @Test
    fun `the templates golden path - the twin, name-addressed, no switch anywhere`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        createDraftTemplate("test/lifecycle_probe.sql")

        page.setViewportSize(1280, 900)
        page.navigate("$baseUrl/templates")
        selectLeafOf("test/lifecycle_probe.sql")

        val releaseDialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v1")))
        releaseDialog.innerText() shouldContain "pin without a number"
        releaseDialog.locator("button[type=submit]").click()
        successToast() shouldContain "Released v1"

        // A draft over the release, then Discard the resolved release: the twin's fallback.
        openDraftOverReleaseTemplate("test/lifecycle_probe.sql")
        page.navigate("$baseUrl/templates")
        selectLeafOf("test/lifecycle_probe.sql")
        val discardDialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Discard v1")))
        shot("templates-discard-1280-light")
        discardDialog.locator("button[type=submit]").click()
        successToast() shouldContain "v2 is the resolved version now."

        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Restore v1")).click()
        page.locator("#tx-dialog [data-lifecycle-dialog='template-restore'] button[type=submit]").click()
        successToast() shouldContain "Restored v1"

        // The version purge through its typed confirm; the template stays (v1 remains).
        rowMenu("v2").locator("button", Locator.LocatorOptions().setHasText("Purge v2")).click()
        val purgeDialog = page.locator("#tx-dialog [data-lifecycle-dialog='template-purge']")
        purgeDialog.waitFor()
        purgeDialog.locator("[data-confirm-input]").fill("v2")
        purgeDialog.locator("button[data-typed-confirm]").click()
        successToast() shouldContain "Purged v2"

        // The entity purge on a fresh {D} template: typed NAME, redirect, leaf gone.
        createDraftTemplate("test/lifecycle_chaff.sql")
        page.navigate("$baseUrl/templates")
        selectLeafOf("test/lifecycle_chaff.sql")
        val entityDialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Purge template")))
        entityDialog.locator("[data-confirm-input]").fill("test/lifecycle_chaff.sql")
        entityDialog.locator("button[data-typed-confirm]").click()
        page.waitForURL("**/templates?ok=template_purged")
        flashToast() shouldContain "Template purged"
        page.locator("button.tpl-leaf", Page.LocatorOptions().setHasText("lifecycle_chaff")).count() shouldBe 0
    }

    // -------------------------------------------------- every dialog, light and dark, two widths

    @Test
    fun `the dialog screenshot set - every dialog at 1280 and 390, light and dark`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        val probeId = createDraftPipeline("test/lifecycle_probe")
        releaseViaRest(probeId)
        openDraftOverRelease(probeId)
        createDraftTemplate("test/lifecycle_probe_twin.sql")

        listOf("light", "dark").forEach { mode ->
            if (mode == "dark") {
                page.setViewportSize(1280, 900)
                page.navigate("$baseUrl/pipelines")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
                page.locator("html[data-theme='dark']").waitFor()
            }
            listOf(1280, 390).forEach { w ->
                page.setViewportSize(w, 900)
                page.navigate("$baseUrl/pipelines")
                selectLeafOf("test/lifecycle_probe")

                // The {R,D} shape's header dialogs.
                openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v"))).let {
                    shot("pipelines-release-$w-$mode")
                }
                page.keyboard().press("Escape")
                openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Discard v"))).let {
                    shot("pipelines-discard-$w-$mode")
                }
                page.keyboard().press("Escape")
                rowMenu("v2").locator("button", Locator.LocatorOptions().setHasText("Purge v2")).click()
                page.locator("#px-dialog [data-lifecycle-dialog='pipeline-purge']").let {
                    it.waitFor()
                    shot("pipelines-purge-$w-$mode")
                }
                page.keyboard().press("Escape")
                openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Switch"))).let {
                    shot("pipelines-switch-$w-$mode")
                }
                page.keyboard().press("Escape")

                // The template twin's release + purge-entity ({D} shape on a fresh template).
                page.navigate("$baseUrl/templates")
                selectLeafOf("test/lifecycle_probe_twin.sql")
                openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Purge template"))).let {
                    shot("templates-purge-entity-$w-$mode")
                }
                page.keyboard().press("Escape")
                openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v"))).let {
                    shot("templates-release-$w-$mode")
                }
                page.keyboard().press("Escape")
            }
        }
        // 2 modes x 2 widths x 6 dialogs: the set the handback ships.
    }

    // ------------------------------------------------------------------ shared helpers

    private fun selectLeafOf(name: String) {
        openDrawerIfPresent()
        page.waitForSelector("summary.tpl-summary")
        if (page.locator("details.tpl-folder[open]").count() == 0) {
            page.waitForResponse({ it.url().contains("prefix=test") }) {
                page.locator("summary.tpl-summary").first().click()
            }
        }
        page.waitForSelector("button.tpl-leaf")
        val leaf = page.locator("button.tpl-leaf", Page.LocatorOptions().setHasText(name.substringAfterLast('/'))).first()
        page.waitForResponse({ it.url().contains("/detail") || it.url().contains("/versions") }) { leaf.click() }
        page.waitForSelector(".tplx-detail-header")
    }

    private fun openDrawerIfPresent() {
        val browse = page.locator("[data-explorer-drawer-open]")
        if (browse.count() > 0 && browse.first().isVisible) {
            browse.first().click()
            page.locator(".tplx-body.is-drawer-open").waitFor()
        }
    }
}
