package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #273 — the pipelines DISCARD dialog's schedules evidence (ui-screens §4.3d): the dialog
 * lists the live schedules whose target names the pipeline — name, condition, next run —
 * because a discard SUCCEEDS and each of them then blocks at its next run. The read is the
 * Usage tab's by-target read (lensed for the caller); the verb and the refusal branch are
 * unchanged.
 *
 * The roles half of the guard: an author (the dialog's audience) sees the evidence and the
 * confirm; a promoter — `pipeline.version.manage` is not theirs — renders no Discard verb at
 * all, so the dialog (evidence included) never opens for one.
 */
class DiscardDialogSchedulesBrowserTest : BrowserSuite() {

    @Test
    fun `the discard dialog lists the two schedules that run the pipeline - name, state, next run`() {
        startTrace()
        val wsName = "dd273-" + generatedPassword("w").take(8).lowercase()
        val email = uniqueEmail("dd273-" + generatedPassword("u").take(8))
        val user = seedLocalUser(email, generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(wsName)
        val pipelineName = "test/dd273_probe"
        seedReleasedPipeline(pipelineName)
        // Two schedules, two states: the enabled one with a stored next run, a paused one
        // with none (its row says so — a next run it will never reach would lie).
        seedSchedule(wsName, email, "dd273/nightly", pipelineName, enabled = true, dueInHours = 24)
        seedSchedule(wsName, email, "dd273/backfill", pipelineName, enabled = false, dueInHours = null)

        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeafOf(page, pipelineName)
        // Both themes BEFORE the dialog opens — the mode toggle sits in the top bar, behind
        // the open dialog's backdrop. Light first (the deployment default is dark), then dark;
        // each shot re-opens the same dialog from the same button.
        ensureTheme("light")
        page.waitForResponse("**/lifecycle/discard*") {
            page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Discard v1")).click()
        }
        val dialog = page.locator("#px-dialog [data-lifecycle-dialog='pipeline-discard']")
        dialog.waitFor()

        val text = dialog.innerText()
        text.lowercase() shouldContain "schedules that run this pipeline"
        text shouldContain "dd273/nightly"
        text shouldContain "enabled"
        text shouldContain "next run"
        text shouldContain "dd273/backfill"
        text shouldContain "paused"
        // The consequence the evidence exists to surface, stated once, above the confirm.
        text.lowercase() shouldContain "blocks (the pointer is gone) until repointed or deleted"
        // The verb is unchanged: one confirm, the version in its words.
        dialog.locator("button[data-verb='pipeline-discard-confirm']").count() shouldBe 1

        shot("discard-schedules-1440-light")
        page.keyboard().press("Escape")
        page.locator("#px-dialog [data-lifecycle-dialog]").waitFor(
            com.microsoft.playwright.Locator
                .WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED),
        )
        ensureTheme("dark")
        page.waitForResponse("**/lifecycle/discard*") {
            page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Discard v1")).click()
        }
        page.locator("#px-dialog [data-lifecycle-dialog='pipeline-discard']").waitFor()
        shot("discard-schedules-1440-dark")
    }

    @Test
    fun `a promoter renders no Discard verb - the dialog and its evidence never open for one`() {
        startTrace()
        val wsName = "dd273p-" + generatedPassword("w").take(8).lowercase()
        // The author seeds the workspace and its pipeline BEFORE the promoter's session exists:
        // AuthCache holds a user's memberships for its TTL, so the membership is granted first.
        val author = seedLocalUser(uniqueEmail("dd273a-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(author.email, author.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(wsName)
        seedReleasedPipeline("test/dd273p_probe")
        // The promoter's USER row first (a password hash, so the login can succeed), then the
        // membership in [wsName] — every membership a session will see is seeded BEFORE login
        // (AuthCache holds them for its TTL, which reads exactly like a missing grant).
        val promoterEmail = uniqueEmail("dd273p-" + generatedPassword("u").take(8))
        val promoter = seedLocalUser(promoterEmail, generatedPassword("pw"), mustChange = false, isAdmin = false, role = "promoter")
        seedPromoterMember(wsName, promoterEmail)

        // A fresh session, the promoter's own (the RoleVisibilityBrowserTest pattern).
        val promoterSession = newSession()
        val ppage = promoterSession.page
        ppage.navigate("$baseUrl/login")
        ppage.fill("#login-email", promoter.email)
        ppage.fill("#login-password", promoter.oneTimePassword)
        ppage.click("form button[type=submit]")
        ppage.waitForURL("**/dashboard")
        // Into the author's workspace through the chrome's switcher; the wait is on the badge's
        // workspace NAME changing, so the next navigation cannot race the re-minted cookie.
        ppage.waitForSelector("#workspace-switcher")
        ppage.selectOption("#workspace-switcher", arrayOf(wsName), Page.SelectOptionOptions().setForce(true))
        ppage.waitForFunction("() => document.querySelector('.app-ws b')?.textContent?.trim() === '$wsName'")

        ppage.navigate("$baseUrl/pipelines")

        // The promoter lens fails closed without a promotion target (the ViewerAccessBrowserTest
        // precedent): the explorer is empty and says why in the promotion page's own sentence —
        // no leaf, no detail header, and therefore no Discard verb and no dialog anywhere. The
        // verb's template contract for a promoter's booleans is RoleVisibilityRenderTest's arm;
        // what is pinned here is the reachable walk: the dialog (evidence included) never opens.
        ppage.waitForSelector("#app-main .ds-empty, #app-main .app-alert")
        val main = ppage.locator("#app-main").first().innerText()
        withClue("the lens's own no-target sentence") {
            main shouldContain "no_target_configured"
        }
        ppage.locator("#app-main [data-verb='pipeline-discard']").count() shouldBe 0
        ppage.locator("#px-dialog [data-lifecycle-dialog='pipeline-discard']").count() shouldBe 0
    }

    // ------------------------------------------------------------------ fixtures

    /** A released calculator pipeline through the browser session's REST (the 102 shape). */
    private fun seedReleasedPipeline(name: String) {
        val (status, body) =
            send(
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"273 fixture","nodes":[{"id":"fq","type":"CALCULATOR",""" +
                    """"kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
            )
        status shouldBe 201
        val id = Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(body!!)!!.groupValues[1]
        val (gs, gbody) = send("GET", "/api/v1/pipelines/$id")
        gs shouldBe 200
        val hash = Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(gbody!!)!!.groupValues[1]
        send("POST", "/api/v1/pipelines/$id/release", ifMatch = hash).first shouldBe 200
    }

    /** The in-page REST seeding of LifecycleDialogBrowserTest (session cookie + CSRF header). */
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

    /**
     * A live schedule of EXACTLY [workspaceName] whose target names [pipelineName] — the
     * ExplorerDetailBrowserTest seed (the scheduler adapter's own row shape), widened with the
     * enabled flag: [dueInHours] null seeds `next_due_at = NULL` (a paused schedule's row).
     */
    private fun seedSchedule(
        workspaceName: String,
        userEmail: String,
        scheduleName: String,
        pipelineName: String,
        enabled: Boolean,
        dueInHours: Int?,
    ) {
        val due = if (dueInHours == null) "NULL" else "NOW() + interval '$dueInHours hours'"
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json,
                                               target_ref, cron, timezone, enabled, next_due_at, created_by, updated_by)
                        SELECT gen_random_uuid(), w.id, '$scheduleName', 'pipeline', 1,
                               jsonb_build_object('pipeline', '$pipelineName', 'version', 'current'),
                               'pipeline:$pipelineName', '0 30 2 * * *', 'UTC', $enabled, $due, u.id, u.id
                          FROM workspaces w
                          CROSS JOIN users u
                         WHERE w.name = '$workspaceName'
                           AND u.email = '$userEmail'
                        """.trimIndent(),
                    )
                }
            }
    }

    /**
     * The promoter's membership in [workspaceName], granted before their first login (AuthCache).
     * The USER row already exists — [seedLocalUser] made it, with the password hash the login
     * needs; this adds only the `workspace_members` row.
     */
    private fun seedPromoterMember(
        workspaceName: String,
        promoterEmail: String,
    ) {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        SELECT w.id, u.id, 'promoter'
                          FROM workspaces w
                          CROSS JOIN users u
                         WHERE w.name = '$workspaceName'
                           AND u.email = '$promoterEmail'
                        ON CONFLICT (workspace_id, user_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
    }

    private fun selectLeafOf(
        on: Page,
        name: String,
    ) {
        val browse = on.locator("[data-explorer-drawer-open]")
        if (browse.count() > 0 && browse.first().isVisible) {
            browse.first().click()
            on.locator(".tplx-body.is-drawer-open").waitFor()
        }
        try {
            on.waitForSelector("summary.tpl-summary")
        } catch (e: Exception) {
            throw AssertionError(
                "no pipelines tree at ${on.url()} — main reads: " +
                    on.locator("#app-main").first().innerText().take(500),
                e,
            )
        }
        if (on.locator("details.tpl-folder[open]").count() == 0) {
            on.waitForResponse({ it.url().contains("prefix=test") }) {
                on.locator("summary.tpl-summary").first().click()
            }
        }
        on.waitForSelector("button.tpl-leaf")
        val leaf = on.locator("button.tpl-leaf", Page.LocatorOptions().setHasText(name.substringAfterLast('/'))).first()
        on.waitForResponse({ it.url().contains("/detail") || it.url().contains("/versions") }) { leaf.click() }
        on.waitForSelector(".tplx-detail-header")
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "reports", "301-screenshots")
        Files.createDirectories(dir)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(dir.resolve("301-$name.png"))
                .setFullPage(false),
        )
    }
}
