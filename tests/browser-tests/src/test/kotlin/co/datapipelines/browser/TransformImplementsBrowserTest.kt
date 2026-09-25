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
import java.sql.DriverManager
import java.util.UUID

/**
 * 7e (#7, transform-nodes design §8.2, §10.10) — the `needs_review` marker, LIT. 7d built every
 * face of it against a flag that read false until this lane; here a jsonata transform cites a
 * WORKSPACE rule through the REAL API (`implements` on the create, so the §2.3 citation check
 * runs), a pipeline pins it beside a SQL stage, and the rule is SUPERSEDED. The walk then reads
 * the mark wherever a person meets the version — the explorer's folder level and search row,
 * the detail's chips, the template editor's header, the pipeline editor's TRANSFORM card and
 * its Details row — and the release dialog, whose warning names the retired rule and its
 * successor above a confirm that stays enabled; the release then lands (a warning, never a
 * refusal). The SQL stage, which cites nothing, is never marked.
 *
 * The rule is written by SQL: `semantics_record` is an MCP tool and the MCP key reaches `/mcp`
 * only (owner ruling B2); the recording path is `TransformImplementsE2eTest`'s. The rows are
 * what `LearnedFactRecorder` writes for a WORKSPACE definition (no refs, the empty
 * fingerprint), and the supersession is its two writes: the successor carrying `supersedes`,
 * the predecessor retired with reason `superseded`. Screenshots under build/reports/7e-screenshots.
 */
class TransformImplementsBrowserTest : BrowserSuite() {
    @Test
    fun `a transform citing a superseded rule is marked wherever it is read, and its release warns and proceeds`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("tim-" + suffix()), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val slug = "tim" + suffix()
        val datasource = "tim-src-" + suffix()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource).shouldBeEmpty()
        val folder = "test/$slug"
        val stage = "$folder/orders.sql"
        val transform = "$folder/active_orders.jsonata"
        val rule = recordRule(user.email, datasource, "An active order is an order that names its customer.", supersedes = null)
        seedPipeline(folder, datasource, stage, transform, rule)

        // Before the drift the citation is live: nothing is marked.
        leafMarks(folder) shouldBe mapOf(stage to false, transform to false)

        val successor = recordRule(user.email, datasource, "An active order names its customer and has a paid invoice.", supersedes = rule)

        // 1 — the explorer: the folder level and the search row mark the transform, never the stage.
        leafMarks(folder) shouldBe mapOf(stage to false, transform to true)
        page.navigate("$baseUrl/templates?q=$slug")
        val row = page.locator("button.tpl-result", Page.LocatorOptions().setHasText("active_orders"))
        row.locator("[data-needs-review]").waitFor()
        page.locator("button.tpl-result", Page.LocatorOptions().setHasText("orders.sql")).locator("[data-needs-review]").count() shouldBe 0

        // 2 — the detail's chips, then the template editor's header.
        page.waitForResponse({ it.url().contains("/partials/templates/versions") }) { row.click() }
        page.locator("#template-detail [data-needs-review]").waitFor()
        themedShots("explorer", listOf(1440))
        page.navigate("$baseUrl/templates/editor?name=$transform")
        page.locator("[data-needs-review]").first().waitFor()
        themedShots("template-editor", listOf(1440))

        // 3 — the pipeline editor: the card's kind line and the Details row, off the pin's read.
        openPipelineDetail(slug)
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")
        page.waitForFunction(
            "() => (document.querySelector(\".pe-card[data-node-id='active_orders']\") || {}).innerText?.includes('needs review')",
        )
        page.locator(".pe-card[data-node-id='stage_orders']").innerText() shouldNotContain "needs review"
        detailsOf("active_orders")["Needs review"] shouldBe "a fact this template version cites was retired — re-verify it"
        themedShots("node-card", listOf(1440))

        // 4 — the release dialog: one warning row, for the transform, naming the retired rule and
        //     its successor; the confirm is ENABLED; the release lands through the dialog's POST.
        openPipelineDetail(slug)
        releaseDialogShots()
        val dialog = openDialog(releaseButton())
        val warning = dialog.locator("[data-release-needs-review]")
        warning.waitFor()
        warning.locator("[data-needs-review-pin]").count() shouldBe 1
        warning.locator("[data-needs-review-pin='$transform@1']").innerText() shouldContain "$rule — superseded by $successor"
        warning.innerText() shouldContain "Releasing is not blocked"
        val confirm = dialog.locator("button[data-verb='pipeline-release-confirm']")
        confirm.isDisabled shouldBe false
        val toast = successToastAfter { confirm.click() }
        toast shouldContain "Released v1"
        drainCspViolations().shouldBeEmpty()
    }

    // ------------------------------------------------------------------ fixtures

    private fun suffix(): String = generatedPassword("s").takeLast(8).lowercase()

    /**
     * A WORKSPACE definition in `default` (the membership [seedLocalUser] grants), as the recorder
     * writes it; with [supersedes], ALSO the recorder's second write — the predecessor retired
     * `superseded` — in the same transaction. Returns the new rule's id.
     */
    private fun recordRule(
        email: String,
        datasource: String,
        text: String,
        supersedes: UUID?,
    ): UUID {
        val id = UUID.randomUUID()
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.autoCommit = false
            connection
                .prepareStatement(
                    """
                    INSERT INTO learned_facts (id, scope, workspace_id, datasource_name, kind, fact, refs_json, trust,
                                               schema_fingerprint, recorded_by, recorded_via, recorded_in, supersedes)
                    SELECT CAST(? AS uuid), 'WORKSPACE', CAST(? AS uuid), ?, 'definition', ?, '[]'::jsonb, 'asserted', '', u.id, 'session',
                           CAST(? AS uuid), CAST(? AS uuid)
                      FROM users u WHERE u.email = ?
                    """.trimIndent(),
                ).use { insert ->
                    insert.setObject(1, id)
                    insert.setObject(2, DEFAULT_WORKSPACE)
                    insert.setString(3, datasource)
                    insert.setString(4, text)
                    insert.setObject(5, DEFAULT_WORKSPACE)
                    insert.setObject(6, supersedes)
                    insert.setString(7, email)
                    insert.executeUpdate() shouldBe 1
                }
            if (supersedes != null) {
                connection
                    .prepareStatement(
                        "UPDATE learned_facts SET trust = 'retired', retired_at = NOW(), retired_reason = 'superseded' WHERE id = ?",
                    ).use { retire ->
                        retire.setObject(1, supersedes)
                        retire.executeUpdate() shouldBe 1
                    }
            }
            connection.commit()
        }
        return id
    }

    /** The SQL stage, the transform citing [rule] (7b's gate runs its suite), and the pipeline pinning both. */
    private fun seedPipeline(
        folder: String,
        datasource: String,
        stage: String,
        transform: String,
        rule: UUID,
    ) {
        EditorRunFixtures.createTemplate(
            page,
            stage,
            "SELECT g AS order_id, CASE WHEN g % 2 = 0 THEN 'c' || g ELSE NULL END AS customer_id FROM generate_series(1, 6) g",
        )
        createCitingTransform(transform, rule)
        EditorRunFixtures.postPipeline(
            page,
            folder,
            """[
              { "id": "stage_orders", "type": "DQL", "source": "$datasource", "template": { "id": "$stage", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_orders" }, "depends_on": [] },
              { "id": "active_orders", "type": "TRANSFORM", "template": { "id": "$transform", "version": 1 },
                "inputs": { "orders": "stg_orders" }, "output": { "target": "caller" }, "depends_on": ["stage_orders"] }
            ]""",
        )
    }

    /** A jsonata template over REST in the session (no key reaches /api/v1), citing [rule]. */
    @Suppress("UNCHECKED_CAST")
    private fun createCitingTransform(
        id: String,
        rule: UUID,
    ) {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const r = await fetch('/api/v1/templates', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ id: args.id, type: 'jsonata', display_name: args.id, description: '7e browser fixture',
                      body: args.body, contract: JSON.parse(args.contract), invariants: [], tests: JSON.parse(args.tests),
                      implements: [args.rule] }) });
                  return { status: r.status, body: r.status === 201 ? '' : await r.text() };
                }""",
                mapOf(
                    "id" to id,
                    "body" to """[ inputs.orders[customer_id != null].{ "order_id": order_id } ]""",
                    "contract" to
                        """{"mode":"table","inputs":{"orders":{"kind":"table","columns":[$ORDER_ID,{"name":"customer_id","type":"STRING","nullable":true}]}},
                            "output":{"kind":"table","columns":[$ORDER_ID]}}""",
                    "tests" to """[{"name":"empty input","input":{"inputs":{"orders":[]}},"expect":{"output":[]}}]""",
                    "rule" to rule.toString(),
                ),
            ) as Map<String, Any?>
        check((result["status"] as Number).toInt() == 201) { "transform template $id: ${result["status"]} ${result["body"]}" }
    }

    /**
     * The explorer's folder level for [prefix] as the tree's own request renders it: each leaf's
     * full name (`data-leaf-name`) → whether it carries the needs-review marker. A fetch of the
     * partial, not a click through `test/`'s shared, paged level — the leaves here are this
     * test's own folder.
     */
    @Suppress("UNCHECKED_CAST")
    private fun leafMarks(prefix: String): Map<String, Boolean> =
        page.evaluate(
            """async (prefix) => {
              const r = await fetch('/partials/templates?prefix=' + encodeURIComponent(prefix), { credentials: 'same-origin' });
              const doc = new DOMParser().parseFromString(await r.text(), 'text/html');
              return Object.fromEntries([...doc.querySelectorAll('button.tpl-leaf[data-leaf-name]')]
                .map(b => [b.dataset.leafName, b.querySelector('[data-needs-review]') !== null]));
            }""",
            prefix,
        ) as Map<String, Boolean>

    /** The pipeline explorer's search, its one result selected — the detail pane with its verbs. */
    private fun openPipelineDetail(slug: String) {
        page.navigate("$baseUrl/pipelines?q=$slug")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.waitForSelector(".tplx-detail-header")
    }

    private fun releaseButton(): Locator = page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v1"))

    private fun openDialog(button: Locator): Locator {
        button.click()
        return page
            .locator("#px-dialog [data-lifecycle-dialog]")
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
            page.locator("#px-dialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
        }
        ensureTheme(back)
        page.setViewportSize(1440, 1000)
    }

    /** Each theme switch leaves a "Theme updated" toast over the header; dismissed through its own ×. */
    private fun dismissToasts() {
        page.locator("#toast .ds-toast-close").all().forEach { close -> if (close.isVisible) close.click() }
        page.waitForFunction("() => document.querySelectorAll('#toast .ds-toast').length === 0")
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "7e-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        const val ORDER_ID = """{"name":"order_id","type":"INTEGER"}"""

        /** The `default` workspace every [seedLocalUser] membership lands in (V29's seeded row). */
        val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    }
}
