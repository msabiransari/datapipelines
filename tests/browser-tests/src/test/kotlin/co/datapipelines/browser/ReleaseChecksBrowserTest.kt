package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * 140 — the release-check gate through its UI (ui-screens §4.3d), the browser-side twin of
 * `ReleaseChecksE2eTest`'s REST/MCP path:
 *
 * 1. a datasource-backed draft carrying TWO checks over an in-JVM H2 datasource the
 *    workspace owns — `SELECT 1` expected `= 1` (passes) and `SELECT 1` expected `= 2`
 *    (fails): the simplest honest fixture, no seed data;
 * 2. the Release dialog opens and RUNS the checks itself — the list appears with one Pass
 *    and one Fail chip, and NO enabled Release submit exists (the run's own fragment decides
 *    the footer, never the dialog's initial markup);
 * 3. the Override disclosure arms "Release anyway" only at ≥ 10 characters of reason;
 * 4. the release lands, and the `pipeline.version.released` audit row carries
 *    `checks_overridden` + `override_reason` — asserted at the TABLE, because an in-memory
 *    fake cannot see a skipped audit hop (the 016 §F1 lesson);
 * 5. the version's latest-run read then shows the gate's own `via = release` runs, and the
 *    re-rendered detail pane's Checks section reads exactly those rows.
 */
class ReleaseChecksBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("rc140-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("rcws-" + generatedPassword("w").take(8).lowercase())
    }

    /** The in-page REST seeding of LifecycleDialogBrowserTest, widened with method/If-Match. */
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
                  return {status: res.status, body: text.length < 8192 ? text : null};
               }""",
                mapOf("method" to method, "url" to url, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    /** An H2 datasource bound to the active workspace — the bundled driver, no external db. */
    private fun registerH2(name: String) {
        val (status, body) =
            send(
                "POST",
                "/api/v1/datasources",
                """{"name":"$name","display_name":"Release checks H2","dialect":"H2",""" +
                    """"jdbc_url":"jdbc:h2:mem:${name.replace("-", "_")};DB_CLOSE_DELAY=-1",""" +
                    """"username":"sa","password":"sa"}""",
            )
        if (status != 201) throw AssertionError("datasource POST $status: ${body?.take(400)}")
    }

    /** A draft whose two checks share the fixture datasource: one passes, one fails. */
    private fun createCheckedDraft(
        name: String,
        datasource: String,
    ): String {
        val (status, body) =
            send(
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"140 browser golden path","nodes":[{"id":"fq","type":"CALCULATOR",""" +
                    """"kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}],""" +
                    """"checks":[""" +
                    """{"id":"one_passes","name":"One passes","datasource":"$datasource",""" +
                    """"sql":"SELECT 1","expected":{"kind":"value","value":1}},""" +
                    """{"id":"one_fails","name":"One fails","datasource":"$datasource",""" +
                    """"sql":"SELECT 1","expected":{"kind":"value","value":2}}]}""",
            )
        if (status != 201) throw AssertionError("pipeline POST $status: ${body?.take(400)}")
        return Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(body!!)!!.groupValues[1]
    }

    private fun openDialog(button: Locator): Locator {
        button.click()
        return page
            .locator("#px-dialog [data-lifecycle-dialog]")
            .first()
            .also { it.waitFor() }
    }

    /** The success toast wait of LifecycleDialogBrowserTest — baseline BEFORE the action. */
    private fun successToastAfter(action: () -> Unit): String {
        val before = page.locator("#toast .ds-toast").count()
        action()
        page.waitForFunction(
            "(n) => document.querySelectorAll('#toast .ds-toast').length > n",
            before,
        )
        return page.locator("#toast .ds-toast").nth(before).innerText()
    }

    @Test
    fun `the release dialog runs the checks, blocks Release, and the audited override releases`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")

        val suffix = generatedPassword("d").take(8).lowercase()
        val datasource = "h2-rc140-$suffix"
        registerH2(datasource)
        val pipelineName = "test/rc140_$suffix"
        val pipelineId = createCheckedDraft(pipelineName, datasource)

        page.setViewportSize(1280, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeafOf(pipelineName)

        // 1 — the dialog opens and fires the run itself: two rows, one Pass and one Fail.
        val dialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v1")))
        val rows = dialog.locator(".plc-check-row")
        rows.nth(1).waitFor()
        rows.count() shouldBe 2
        dialog.locator(".app-chip-ok", Locator.LocatorOptions().setHasText("Pass")).waitFor()
        dialog.locator(".app-chip-bad", Locator.LocatorOptions().setHasText("Fail")).waitFor()
        dialog.innerText() shouldContain "did not pass — releasing is blocked"

        // 2 — no enabled normal Release submit exists; the override disclosure is the only
        //    way forward, and its button starts disabled in the markup (no-JS is safe). The
        //    button lives inside the CLOSED disclosure, so attached — not visible — is the
        //    wait here.
        dialog.locator("button[data-verb='pipeline-release-confirm']").count() shouldBe 0
        val overrideButton = dialog.locator("button[data-verb='pipeline-release-override']")
        overrideButton.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
        overrideButton.isDisabled shouldBe true

        // 3 — the min-chars arm toggles the button BOTH ways: enabled at >= 10 characters,
        //    back to disabled below it (the second transition is the one only a live
        //    listener can produce — the markup's initial disabled could fake the first).
        dialog.locator("details.plc-override summary").click()
        val reason = dialog.locator("textarea[name='overrideChecksReason']")
        reason.fill("Verified by hand against the source rollup.")
        page.waitForFunction(
            "() => document.querySelector(\"#px-dialog button[data-verb='pipeline-release-override']\").disabled === false",
        )
        reason.fill("short")
        page.waitForFunction(
            "() => document.querySelector(\"#px-dialog button[data-verb='pipeline-release-override']\").disabled === true",
        )
        reason.fill("Verified by hand against the source rollup.")
        page.waitForFunction(
            "() => document.querySelector(\"#px-dialog button[data-verb='pipeline-release-override']\").disabled === false",
        )

        // 4 — the release lands through the SAME dialog POST.
        successToastAfter { overrideButton.click() } shouldContain "Released v1"

        assertAuditedOverrideAndRuns(pipelineId)
    }

    // ------------------------------------------------------------------ shared helpers

    /** Steps 5-6: the audit row at the TABLE, then the version's runs in REST and the pane. */
    private fun assertAuditedOverrideAndRuns(pipelineId: String) {
        // 5 — the audit row, asserted at the TABLE: the event, the overridden ids, the reason.
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement ->
                val audit =
                    statement.executeQuery(
                        "SELECT details_json FROM audit_log" +
                            " WHERE event = 'pipeline.version.released'" +
                            " AND details_json::text LIKE '%$pipelineId%' ORDER BY timestamp DESC LIMIT 1",
                    )
                audit.next() shouldBe true
                val details = audit.getString("details_json")
                details.contains("checks_overridden") shouldBe true
                details.contains("one_fails") shouldBe true
                details.contains("override_reason") shouldBe true
                details.contains("Verified by hand against the source rollup.") shouldBe true
            }
        }

        // 6 — the version's latest-run read shows the gate's own via=release runs …
        val (status, checksBody) = send("GET", "/api/v1/pipelines/$pipelineId/versions/1/checks")
        status shouldBe 200
        checksBody!! shouldContain """"via":"release""""
        checksBody shouldContain """"verdict":"fail""""

        // … and the re-rendered detail pane's Checks section reads exactly those rows.
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val section = page.locator("#pipeline-detail-checks .plc-check-row")
        section.nth(1).waitFor()
        section.count() shouldBe 2
        page.locator("#pipeline-detail-checks").innerText() shouldContain "via release"
    }

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
