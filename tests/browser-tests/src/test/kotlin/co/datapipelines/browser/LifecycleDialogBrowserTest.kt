package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

    /**
     * A draft over the current release — the PUT's copy-on-write first write (§5.1). [note] is the
     * body's description: an identical body opens no new draft, so a second draft needs its own.
     */
    private fun openDraftOverRelease(
        id: String,
        note: String = "102 golden path (draft)",
    ) {
        val name = "test/" + id
        val (gs, gbody) = send("GET", "/api/v1/pipelines/$id")
        gs shouldBe 200
        val hash = Regex("\"body_hash\"\\s*:\\s*\"([0-9a-f]+)\"").find(gbody!!)!!.groupValues[1]
        val ps =
            send(
                "PUT",
                "/api/v1/pipelines/$id",
                """{"schema_version":1,"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"$note","parameters":{},""" +
                    """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter",""" +
                    """"context_key":"run_fiscal_quarter","inputs":{"date":"2026-08-14","fiscal_start":"09-15"}}]}""",
                ifMatch = hash,
            ).first
        ps shouldBe 200
    }

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
        // BOTH versions rows are the house table (tr[data-version-row], #349/#398). The
        // menu inside either is the same ⋯ + popover shape.
        val row =
            page
                .locator("tr[data-version-row], .tplx-vrow")
                .filter(Locator.FilterOptions().setHasText(versionBadge))
                .first()
        row.locator("details.tplx-vmenu summary").click()
        return row.locator(".tplx-vmenu-list")
    }

    /**
     * The redirect legs' toast: it arrives from the flash BIN via toast.js's init adoption,
     * which races this call on a freshly navigated document — so this waits for ANY toast
     * rather than counting past a baseline the adoption may already have passed.
     */
    private fun flashToast(): String {
        page.locator("#toast .ds-toast").first().waitFor()
        return page.locator("#toast .ds-toast").first().innerText()
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

    /**
     * #350: the pipelines explorer's detail pane is gone — every pipeline verb lives on the
     * WORKSPACE now (its header and its Versions tab, `from=editor`), whose POSTs answer
     * HX-Redirect with a flash (#395 made Switch/Discard/Restore do so too). The tree badge the
     * explorer refreshed in place is the SIDEBAR leaf's now, re-rendered by each reload.
     */
    private fun openWorkspace(
        id: String,
        tab: String? = null,
    ) {
        page.navigate("$baseUrl/pipelines/$id" + (tab?.let { "?tab=$it" } ?: ""))
        page.waitForSelector(".pe-root")
        if (tab == "versions") page.waitForSelector("tr[data-version-row]")
    }

    private fun openSidebarTree() {
        page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
        page.waitForSelector("#pipeline-nav-root .tpl-tree")
    }

    /** The sidebar leaf of the page being viewed, its working-version badge (the tree is open). */
    private fun sidebarBadgeOf(name: String): String? {
        val leaf = page.locator("#nav-tree-pipelines a.tpl-leaf[aria-current='page']:has(span.tpl-label[title='$name'])")
        leaf.waitFor()
        val version = leaf.locator(".tpl-leaf-version")
        return if (version.count() > 0) version.innerText() else null
    }

    /** The service's own outcome, read back — the redirect's flash is generic by design. */
    private fun currentVersionOf(id: String): Int? {
        val (_, body) = send("GET", "/api/v1/pipelines/$id")
        return Regex(""""current_version"\s*:\s*(\d+)""")
            .find(body ?: "")
            ?.groupValues
            ?.get(1)
            ?.toInt()
    }

    private fun workspaceDialog(kind: String): Locator = page.locator("#pe-dialog [data-lifecycle-dialog='$kind']").also { it.waitFor() }

    @Test
    @Suppress("LongMethod") // the golden path IS the sequence: each verb's precondition is the previous verb's outcome
    fun `the pipelines golden path - every verb through its dialog on the workspace, the flash and the sidebar badge after each`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        val probeId = createDraftPipeline("test/lifecycle_probe")

        page.setViewportSize(1280, 900)
        openWorkspace(probeId)
        openSidebarTree()
        sidebarBadgeOf("test/lifecycle_probe") shouldBe "v1"

        // 1 — Release v1 (the header's dialog): the reload carries the flash, and the sidebar leaf
        //      is the released number with no draft badge.
        page.locator(".pe-topbar [data-verb='pipeline-release']").click()
        val releaseDialog = workspaceDialog("pipeline-release")
        releaseDialog.innerText() shouldContain "Releasing makes v"
        releaseDialog.locator("button[type=submit]").click()
        page.waitForURL("**/pipelines/$probeId?ok=released")
        flashToast() shouldContain "Released"
        sidebarBadgeOf("test/lifecycle_probe") shouldBe "v1"
        page.locator("#nav-tree-pipelines a.tpl-leaf[aria-current='page'] .tpl-leaf-draft").count() shouldBe 0

        // 2 — A draft over the release (§5.1 copy-on-write), then Discard the CURRENT release from
        //      the Versions tab: the §3.5.2 fallback catches the draft (the service's outcome).
        openDraftOverRelease(probeId)
        openWorkspace(probeId, "versions")
        sidebarBadgeOf("test/lifecycle_probe") shouldBe "v2"
        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Discard v1")).click()
        val discardDialog = workspaceDialog("pipeline-discard")
        discardDialog.innerText() shouldContain "v2 becomes current"
        shot("pipelines-discard-1280-light")
        discardDialog.locator("button[type=submit]").click()
        page.waitForURL("**/pipelines/$probeId?tab=versions&ok=discarded")
        flashToast() shouldContain "Version discarded"
        currentVersionOf(probeId) shouldBe 2

        // 3 — Restore v1: below the pointer, so the pointer stays (§3.4).
        page.waitForSelector("tr[data-version-row]")
        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Restore v1")).click()
        workspaceDialog("pipeline-restore").locator("button[type=submit]").click()
        page.waitForURL("**/pipelines/$probeId?tab=versions&ok=restored")
        flashToast() shouldContain "Version restored"
        currentVersionOf(probeId) shouldBe 2

        // 4 — Switch to v1 (the row's Switch-to, when not current): endpoints follow.
        page.waitForSelector("tr[data-version-row]")
        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Switch to v1")).click()
        workspaceDialog("pipeline-switch").locator("button[type=submit]").click()
        page.waitForURL("**/pipelines/$probeId?tab=versions&ok=switched")
        flashToast() shouldContain "Current version switched"
        currentVersionOf(probeId) shouldBe 1

        // 5 — Purge the draft v2, through the typed confirm the whole way.
        page.waitForSelector("tr[data-version-row]")
        rowMenu("v2").locator("button", Locator.LocatorOptions().setHasText("Purge v2")).click()
        val purgeDialog = workspaceDialog("pipeline-purge")
        purgeDialog.innerText() shouldContain "cannot be undone"
        purgeDialog.locator("[data-confirm-input]").fill("v2")
        purgeDialog.locator("button[data-typed-confirm]").click()
        page.waitForURL("**/pipelines/$probeId?ok=draft_purged")
        flashToast() shouldContain "Draft purged"
        sidebarBadgeOf("test/lifecycle_probe") shouldBe "v1"

        // 6 — The {D} shape's entity purge from the WORKSPACE header (#395 — the explorer's
        //      one-destructive rule): a never-released pipeline, the NAME typed; the catalog and
        //      the sidebar lose it, and the flash lands on the catalog.
        val chaffId = createDraftPipeline("test/lifecycle_chaff")
        openWorkspace(chaffId)
        page.locator(".pe-topbar [data-verb='pipeline-purge']").count() shouldBe 0
        page.locator(".pe-topbar [data-verb='pipeline-purge-entity']").click()
        val entityDialog = workspaceDialog("pipeline-purge-entity")
        entityDialog.locator("[data-confirm-input]").fill("test/lifecycle_chaff")
        entityDialog.locator("button[data-typed-confirm]").click()
        page.waitForURL("**/pipelines?ok=entity_purged")
        flashToast() shouldContain "Pipeline purged"
        page.locator("#pipeline-list-wrapper .tpl-path", Page.LocatorOptions().setHasText("lifecycle_chaff")).count() shouldBe 0
        page.waitForSelector("#pipeline-nav-root .tpl-tree")
        page.locator("#nav-tree-pipelines span[title='test/lifecycle_chaff']").count() shouldBe 0
    }

    // --------------------------------------------------------------- the templates path

    @Test
    fun `the templates golden path - the twin, name-addressed, no switch anywhere`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        createDraftTemplate("test/lifecycle_probe.sql")

        page.setViewportSize(1280, 900)
        selectLeafOf("test/lifecycle_probe.sql")

        val releaseDialog = openDialog(page.locator(".tw-topbar button", Page.LocatorOptions().setHasText("Release v1")))
        releaseDialog.innerText() shouldContain "pin without a number"
        // #398: the redirect flash uses the layout's released toast (title "Released"; the
        // body carries the "current version and locked" sentence). A template release never
        // shows a pipeline's held names: the generic sentence is the whole body (#407's
        // ReleaseFlash is bound to a pipeline id the template route does not carry).
        releaseDialog.locator("button[type=submit]").click()
        page.waitForURL("**/templates/test/lifecycle_probe.sql?tab=versions&ok=released")
        flashToast() shouldContain "Released"
        flashToast() shouldNotContain "Also released"

        // A draft over the release, then Discard the resolved release: the twin's fallback.
        // #398: every success lands back on ?tab=versions, so the tab is where the walk is.
        openDraftOverReleaseTemplate("test/lifecycle_probe.sql")
        selectLeafOf("test/lifecycle_probe.sql")
        page.locator("#tw-tab-versions").click()
        page.waitForSelector("#tw-pane-versions tr[data-version-row]")
        val discardDialog = openDialog(page.locator(".tw-topbar button", Page.LocatorOptions().setHasText("Discard v1")))
        shot("templates-discard-1280-light")
        discardDialog.locator("button[type=submit]").click()
        page.waitForURL("**/templates/test/lifecycle_probe.sql?tab=versions&ok=discarded")
        flashToast() shouldContain "Version discarded"
        // The flash is generic by design (the layout's `discarded` code serves every family; the
        // old Shape A toast named "v2 is the resolved version now"). That fact is the Versions
        // tab's now — the page the redirect lands on marks v2 as the version that resolves.
        page.waitForSelector("#tw-pane-versions tr[data-version-row='2'] .tplx-current")
        page.locator("#tw-pane-versions tr[data-version-row='1'] td:nth-child(2) .ds-badge").innerText() shouldBe "DISCARDED"

        rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Restore v1")).click()
        page.locator("#tx-dialog [data-lifecycle-dialog='template-restore'] button[type=submit]").click()
        page.waitForURL("**/templates/test/lifecycle_probe.sql?tab=versions&ok=restored")
        flashToast() shouldContain "Version restored"
        page.waitForSelector("#tw-pane-versions tr[data-version-row='1']")
        page.locator("#tw-pane-versions tr[data-version-row='1'] td:nth-child(2) .ds-badge").innerText() shouldBe "RELEASED"

        // The version purge through its typed confirm; the template stays (v1 remains).
        rowMenu("v2").locator("button", Locator.LocatorOptions().setHasText("Purge v2")).click()
        val purgeDialog = page.locator("#tx-dialog [data-lifecycle-dialog='template-purge']")
        purgeDialog.waitFor()
        purgeDialog.locator("[data-confirm-input]").fill("v2")
        purgeDialog.locator("button[data-typed-confirm]").click()
        page.waitForURL("**/templates/test/lifecycle_probe.sql?tab=versions&ok=draft_purged")
        flashToast() shouldContain "Draft purged"
        page.waitForSelector("#tw-pane-versions tr[data-version-row='1']")
        page.locator("#tw-pane-versions tr[data-version-row='2']").count() shouldBe 0

        // The entity purge on a fresh {D} template: typed NAME, redirect to the CATALOG
        // (the tree must lose the leaf), the leaf gone from the sidebar tree.
        createDraftTemplate("test/lifecycle_chaff.sql")
        selectLeafOf("test/lifecycle_chaff.sql")
        val entityDialog = openDialog(page.locator(".tw-topbar button", Page.LocatorOptions().setHasText("Purge template")))
        entityDialog.locator("[data-confirm-input]").fill("test/lifecycle_chaff.sql")
        entityDialog.locator("button[data-typed-confirm]").click()
        page.waitForURL("**/templates?ok=template_purged")
        flashToast() shouldContain "Template purged"
        page.click("[data-nav-tree-reveal='templates']")
        page.waitForSelector("#nav-tree-templates details.tpl-folder")
        page
            .locator("#nav-tree-templates a.tpl-leaf", Page.LocatorOptions().setHasText("lifecycle_chaff"))
            .count() shouldBe 0
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
        // #350: a SECOND release and a third draft, so the workspace's Versions tab offers every
        // pipeline dialog at once — v1 released (Discard, Switch to), v2 current, v3 draft (Purge).
        releaseViaRest(probeId)
        openDraftOverRelease(probeId, note = "350 third draft")
        createDraftTemplate("test/lifecycle_probe_twin.sql")

        listOf("light", "dark").forEach { mode ->
            // Theme-agnostic: the deployment default is dark, so BOTH halves ask for their mode.
            page.setViewportSize(1280, 900)
            page.navigate("$baseUrl/pipelines")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            ensureTheme(mode)
            listOf(1280, 390).forEach { w ->
                // #350: the pipeline dialogs open from the WORKSPACE (header + Versions tab) at the
                // desktop width, and are photographed at each width (the dialog is a fixed modal).
                page.setViewportSize(1280, 900)
                openWorkspace(probeId, "versions")
                listOf(
                    "release" to { rowMenu("v3").locator("button", Locator.LocatorOptions().setHasText("Release v3")).click() },
                    "discard" to { rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Discard v1")).click() },
                    "purge" to { rowMenu("v3").locator("button", Locator.LocatorOptions().setHasText("Purge v3")).click() },
                    "switch" to { rowMenu("v1").locator("button", Locator.LocatorOptions().setHasText("Switch to v1")).click() },
                ).forEach { (kind, open) ->
                    open()
                    workspaceDialog("pipeline-$kind")
                    page.setViewportSize(w, 900)
                    shot("pipelines-$kind-$w-$mode")
                    page.keyboard().press("Escape")
                    page
                        .locator(
                            "#pe-dialog [data-lifecycle-dialog]",
                        ).waitFor(Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED))
                    page.setViewportSize(1280, 900)
                }
                page.setViewportSize(w, 900)

                // The template twin's release + purge-entity ({D} shape on a fresh template),
                // opened from the WORKSPACE header (#398).
                selectLeafOf("test/lifecycle_probe_twin.sql")
                openDialog(page.locator(".tw-topbar button", Page.LocatorOptions().setHasText("Purge template"))).let {
                    shot("templates-purge-entity-$w-$mode")
                }
                page.keyboard().press("Escape")
                openDialog(page.locator(".tw-topbar button", Page.LocatorOptions().setHasText("Release v"))).let {
                    shot("templates-release-$w-$mode")
                }
                page.keyboard().press("Escape")
            }
        }
        // 2 modes x 2 widths x 6 dialogs: the set the handback ships.
    }

    // ------------------------------------------------------------------ shared helpers

    /** The template WORKSPACE, reached the way a reader does — a catalog row (#398). */
    private fun selectLeafOf(name: String) {
        page.navigate("$baseUrl/templates?q=" + name.substringAfterLast('/'))
        page.waitForSelector("#template-list-wrapper a.tpl-result")
        page
            .locator("a.tpl-result", Page.LocatorOptions().setHasText(name))
            .first()
            .click()
        page.waitForURL("**/templates/$name")
        page.waitForSelector(".tw-root")
    }
}
