package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * 7e (#7, transform-nodes design §8.2, §10.10) — the `needs_review` marker, LIT. 7d built every
 * face of it against a flag that read false until this lane; here a jsonata script cites a
 * WORKSPACE rule through the REAL API (`implements` on the create, so the §2.3 citation check
 * runs), a pipeline pins it beside a SQL stage, and the rule is SUPERSEDED. The walk then reads
 * the mark wherever a person meets the version — the sidebar's REST tree leaf (#533), the
 * catalog's search row, the template workspace's header chip, the pipeline editor's SCRIPT card and
 * its Details row — and the release dialog, whose warning names the retired rule and its
 * successor above a confirm that stays enabled; the release then lands (a warning, never a
 * refusal). The SQL stage, which cites nothing, is never marked.
 *
 * The rule and the citing script are seeded by [NeedsReviewFixtures] (the rule by SQL, the
 * script over REST in the session). Screenshots under build/reports/7e-screenshots.
 */
class ScriptImplementsBrowserTest : BrowserSuite() {
    @Test
    fun `toast dismissal closes every member of a shrinking stack`() {
        startTrace()
        page.setDefaultTimeout(TOAST_PROBE_TIMEOUT_MS)
        page.setContent(
            """<div id="toast">
              <div class="ds-toast"><button class="ds-toast-close">Dismiss first</button></div>
              <div class="ds-toast"><button class="ds-toast-close">Dismiss second</button></div>
              <div class="ds-toast"><button class="ds-toast-close">Dismiss third</button></div>
            </div>""",
        )
        // An exit can finish before the next locator RPC. Immediate removal makes that
        // supported interleaving deterministic, without waiting for an animation or timer.
        page.evaluate(
            """() => document.querySelectorAll('#toast .ds-toast-close').forEach(button => {
              button.addEventListener('click', () => button.closest('.ds-toast').remove());
            })""",
        )
        page.locator("#toast .ds-toast").count() shouldBe TOAST_PROBE_COUNT
        dismissToasts()
        page.locator("#toast .ds-toast").count() shouldBe 0
    }

    @Test
    fun `a script citing a superseded rule is marked wherever it is read, and its release warns and proceeds`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("tim-" + suffix()), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val slug = "tim" + suffix()
        val datasource = "tim-src-" + suffix()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource).shouldBeEmpty()
        val folder = "test/$slug"
        val stage = "$folder/orders.sql"
        val script = "$folder/active_orders.jsonata"
        val rule = recordRule(user.email, datasource, "An active order is an order that names its customer.", supersedes = null)
        seedPipeline(folder, datasource, stage, script, rule)

        // Before the drift the citation is live: nothing is marked — in the catalog, nor on the
        // sidebar's two leaves (#533), read from the REST tree the sidebar renders.
        leafMarks(folder) shouldBe mapOf(stage to false, script to false)
        sidebarLeavesUnmarked(folder, stage, script)

        val successor = recordRule(user.email, datasource, "An active order names its customer and has a paid invoice.", supersedes = rule)

        // 1 — the navigating surfaces mark the script, never the stage. The sidebar FIRST
        //     (#533): the tree caches every complete level and nothing about the template changed
        //     (a fact did), so no artifact-change event refreshes it — the panel's own Refresh
        //     re-reads the open levels, and the wait is on the script leaf's marker itself.
        //     Then the catalog's rows (#398: links, `a.tpl-result`).
        sidebarMarksScriptOnly(stage, script)
        leafMarks(folder) shouldBe mapOf(stage to false, script to true)
        page.navigate("$baseUrl/templates?q=$slug")
        val row = page.locator("a.tpl-result", Page.LocatorOptions().setHasText("active_orders"))
        row.locator("[data-needs-review]").waitFor()
        page.locator("a.tpl-result", Page.LocatorOptions().setHasText("orders.sql")).locator("[data-needs-review]").count() shouldBe 0

        // 2 — the template's workspace: the row is a link, followed; the header chip names the
        //     viewed version's mark (workspace.html's `.tw-crumb`), and the stage's own is clean.
        row.click()
        page.waitForURL("**/templates/$script")
        page.waitForSelector(".tw-root")
        page.locator(".tw-crumb [data-needs-review]").waitFor()
        themedShots("template-workspace", listOf(1440))
        page.navigate("$baseUrl/templates/$stage")
        page.waitForSelector(".tw-root")
        page.locator(".tw-crumb [data-needs-review]").count() shouldBe 0

        // 3 — the pipeline editor: the card's kind line and the Details row, off the pin's read.
        openPipelineWorkspace(slug)
        page.waitForFunction(
            "() => (document.querySelector(\".pe-card[data-node-id='active_orders']\") || {}).innerText?.includes('needs review')",
        )
        page.locator(".pe-card[data-node-id='stage_orders']").innerText() shouldNotContain "needs review"
        detailsOf("active_orders")["Needs review"] shouldBe "a fact this template version cites was retired — re-verify it"
        themedShots("node-card", listOf(1440))

        // 4 — the release dialog: one warning row, for the script, naming the retired rule and
        //     its successor; the confirm is ENABLED; the release lands through the dialog's POST.
        //     #350: from the WORKSPACE's own Release (the explorer's detail pane is gone), whose
        //     POST answers HX-Redirect — the success is the layout's flash on the reloaded page.
        openPipelineWorkspace(slug)
        releaseDialogShots()
        val dialog = openDialog(releaseButton())
        val warning = dialog.locator("[data-release-needs-review]")
        warning.waitFor()
        warning.locator("[data-needs-review-pin]").count() shouldBe 1
        warning.locator("[data-needs-review-pin='$script@1']").innerText() shouldContain "$rule — superseded by $successor"
        warning.innerText() shouldContain "Releasing is not blocked"
        val confirm = dialog.locator("button[data-verb='pipeline-release-confirm']")
        confirm.isDisabled shouldBe false
        val toast = successToastAfter { confirm.click() }
        toast shouldContain "Released"
        // #407: a cascaded release's flash names what it released, server-derived — the pinned
        //     script among them (ReleaseCascadeBrowserTest owns the consume and crafted-?ok= arms).
        toast shouldContain "Also released: "
        toast shouldContain "$script@1"
        drainCspViolations().shouldBeEmpty()
    }

    // ------------------------------------------------------------------ fixtures

    private fun suffix(): String = generatedPassword("s").takeLast(8).lowercase()

    /** A WORKSPACE definition in `default` (the membership [seedLocalUser] grants), via [NeedsReviewFixtures.recordRule]. */
    private fun recordRule(
        email: String,
        datasource: String,
        text: String,
        supersedes: UUID?,
    ): UUID = NeedsReviewFixtures.recordRule(email, datasource, text, supersedes, workspace = DEFAULT_WORKSPACE)

    /** The SQL stage, the script citing [rule] (7b's gate runs its suite), and the pipeline pinning both. */
    private fun seedPipeline(
        folder: String,
        datasource: String,
        stage: String,
        script: String,
        rule: UUID,
    ) {
        EditorRunFixtures.createTemplate(
            page,
            stage,
            "SELECT g AS order_id, CASE WHEN g % 2 = 0 THEN 'c' || g ELSE NULL END AS customer_id FROM generate_series(1, 6) g",
        )
        NeedsReviewFixtures.writeCitingScript(page, script, rule)
        EditorRunFixtures.postPipeline(
            page,
            folder,
            """[
              { "id": "stage_orders", "type": "DQL", "source": "$datasource", "template": { "id": "$stage", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_orders" }, "depends_on": [] },
              { "id": "active_orders", "type": "SCRIPT", "template": { "id": "$script", "version": 1 },
                "inputs": { "orders": "stg_orders" }, "output": { "target": "caller" }, "depends_on": ["stage_orders"] }
            ]""",
        )
    }

    /**
     * The catalog's rows for the folder [prefix] as the catalog's own search renders them: each
     * row's full name (`data-leaf-id`) → whether it carries the needs-review marker. A fetch of
     * the partial with `q=<prefix>/`, not a click through `test/`'s shared, paged list — the
     * folder is this test's own, so its two templates are the whole answer.
     */
    @Suppress("UNCHECKED_CAST")
    private fun leafMarks(prefix: String): Map<String, Boolean> =
        page.evaluate(
            """async (prefix) => {
              const r = await fetch('/partials/templates?q=' + encodeURIComponent(prefix + '/'), { credentials: 'same-origin' });
              const doc = new DOMParser().parseFromString(await r.text(), 'text/html');
              return Object.fromEntries([...doc.querySelectorAll('a.tpl-result[data-leaf-id]')]
                .map(b => [b.dataset.leafId, b.querySelector('[data-needs-review]') !== null]));
            }""",
            prefix,
        ) as Map<String, Boolean>

    // ------------------------------------------------------------------ the sidebar's REST tree (#533)

    /** Shows the resource browser's Templates panel (#554) and waits for its root level. */
    private fun openTemplatesTree() = openBrowserTree("templates")

    /** Expands the folder at [path] (when closed) and waits for its level to be complete. */
    private fun expandTree(path: String) {
        val folder = "$TREE_PANEL [data-tree-key='folder:$path']"
        if (page.locator(folder).getAttribute("aria-expanded") != "true") page.click("$folder > .dp-tree-line button")
        page.waitForFunction(
            "key => window.DatapipelinesSidebarTrees.get('templates').state.levels.get(key)?.complete",
            "folder:$path",
        )
    }

    private fun treeLeaf(path: String) = "$TREE_PANEL a[href='/templates/$path']"

    /** Opens the panel down to [folder] and reads neither leaf marked. */
    private fun sidebarLeavesUnmarked(
        folder: String,
        stage: String,
        script: String,
    ) {
        openTemplatesTree()
        expandTree("test")
        expandTree(folder)
        page.locator(treeLeaf(script)).waitFor()
        page.locator("${treeLeaf(script)} [data-needs-review]").count() shouldBe 0
        page.locator("${treeLeaf(stage)} [data-needs-review]").count() shouldBe 0
    }

    /**
     * Refreshes the open levels, waits on the script leaf's marker itself and reads the stage
     * leaf clean. Even with the full path as its display name, the marker must lie inside
     * the panel's visible box in both themes, without scrolling to reveal it (#541).
     */
    private fun sidebarMarksScriptOnly(
        stage: String,
        script: String,
    ) {
        page.click("$TREE_PANEL button[aria-label='Refresh tree']")
        val marker = page.locator("${treeLeaf(script)} [data-needs-review]")
        marker.waitFor()
        marker.innerText() shouldBe "needs review"
        page.locator("${treeLeaf(stage)} [data-needs-review]").count() shouldBe 0
        page.setViewportSize(1440, 1000)
        val back = mode()
        for (theme in listOf("light", "dark")) {
            ensureTheme(theme)
            val metrics =
                marker.evaluate(
                    """(el, panel) => {
                      const rect = node => { const r = node.getBoundingClientRect();
                        return {left:r.left, right:r.right, top:r.top, bottom:r.bottom}; };
                      const p = rect(document.querySelector(panel)), r = rect(el);
                      return {panel:p, marker:r, inside:r.left >= p.left - 0.5 && r.right <= p.right + 0.5
                        && r.top >= p.top - 0.5 && r.bottom <= p.bottom + 0.5};
                    }""",
                    TREE_PANEL,
                ) as Map<*, *>
            println("541-marker|$theme|$metrics")
            io.kotest.assertions.withClue("$theme needs review inside panel: $metrics") { metrics["inside"] shouldBe true }
        }
        ensureTheme(back)
        themedShots("sidebar-leaf", listOf(1440))
    }

    /** #350: the catalog's search, its one row followed — the pipeline's workspace with its verbs. */
    private fun openPipelineWorkspace(slug: String) {
        page.navigate("$baseUrl/pipelines?q=$slug")
        page.locator("#pipeline-list-wrapper a.tpl-result").first().click()
        page.waitForURL(PipelineWorkspaceUrl.PATTERN)
        page.waitForSelector(".pe-root")
    }

    private fun releaseButton(): Locator = page.locator(".pe-topbar [data-verb='pipeline-release']")

    private fun openDialog(button: Locator): Locator {
        button.click()
        return page
            .locator("#pe-dialog [data-lifecycle-dialog]")
            .first()
            .also { it.waitFor() }
    }

    /** The success toast wait of LifecycleDialogBrowserTest — the baseline is counted BEFORE the action. */
    private fun successToastAfter(action: () -> Unit): String {
        val before = page.locator("#toast .ds-toast").count()
        action()
        page.waitForFunction("(n) => document.querySelectorAll('#toast .ds-toast').length > n", before)
        return page.locator("#toast .ds-toast").nth(before).innerText()
    }

    /** Selects [nodeId] through its card's own Details affordance and reads the pane's rows. */
    @Suppress("UNCHECKED_CAST")
    private fun detailsOf(nodeId: String): Map<String, String> {
        page.locator(".pe-card-open[data-node-open='$nodeId']").click()
        page.locator("#pe-pane-details .pe-details-id", Page.LocatorOptions().setHasText(nodeId)).waitFor()
        return page.evaluate(
            """() => Object.fromEntries([...document.querySelectorAll('#pe-pane-details .pe-kv-row')]
                 .map(r => [r.querySelector('dt').innerText.trim(), r.querySelector('dd').innerText.trim()]))""",
        ) as Map<String, String>
    }

    // ------------------------------------------------------------------ screenshots

    private fun mode(): String =
        if ((page.locator("#theme-link").first().getAttribute("href") ?: "").contains("/light.css")) "light" else "dark"

    private fun themedShots(
        name: String,
        widths: List<Int>,
    ) {
        val back = mode()
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            widths.forEach { width ->
                page.setViewportSize(width, 1000)
                dismissToasts()
                page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("7e-$name-$width-$theme.png")))
            }
        }
        ensureTheme(back)
        page.setViewportSize(1440, 1000)
    }

    /**
     * The release dialog in both themes and both widths. The theme toggle sits under the modal's
     * backdrop, so each theme is its own opening, closed again with Escape (the
     * ReleaseCascadeBrowserTest shape); the asserted release runs in a fresh opening after.
     */
    private fun releaseDialogShots() {
        val back = mode()
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            dismissToasts()
            openDialog(releaseButton()).locator("[data-release-needs-review]").waitFor()
            listOf(1440, 1920).forEach { width ->
                page.setViewportSize(width, 1000)
                page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("7e-release-dialog-$width-$theme.png")))
            }
            page.keyboard().press("Escape")
            page.locator("#pe-dialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
        }
        ensureTheme(back)
        page.setViewportSize(1440, 1000)
    }

    /** Each theme switch leaves a "Theme updated" toast over the header; dismissed through its own ×. */
    private fun dismissToasts() {
        // Locator.all() returns live indexes; hold the nodes in one browser turn instead.
        page.locator("#toast .ds-toast-close").evaluateAll("buttons => buttons.forEach(button => button.click())")
        page.waitForFunction("() => document.querySelectorAll('#toast .ds-toast').length === 0")
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "7e-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        const val TOAST_PROBE_TIMEOUT_MS = 5000.0
        const val TOAST_PROBE_COUNT = 3

        const val TREE_PANEL = "#nav-tree-templates"

        /** The `default` workspace every [seedLocalUser] membership lands in (V29's seeded row). */
        val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    }
}
