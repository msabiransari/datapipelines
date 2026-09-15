package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager

/**
 * 142 — the release cascade through its dialog (ui-screens §4.3d), the browser-side twin of
 * `ReleaseCascadeE2eTest`'s REST path:
 *
 * 1. two DRAFT templates over an in-JVM H2 datasource the workspace owns; pipeline A pins
 *    both, pipeline B pins the first — so the first is a SHARED object;
 * 2. the Release dialog on A shows the consent group CHECKED, one row per draft pin, the
 *    shared one saying "also pinned by 1 other draft pipeline", and an ENABLED Release;
 * 3. unchecking the box withholds Release and the rows fall back to the pre-142 refused
 *    wording ("release the template first") — the order rule stays visible; re-checking
 *    arms it again (the second transition is the one only a live listener can produce);
 * 4. Release lands through the dialog's own POST: the toast names the templates released
 *    with the pipeline, and the audit table carries the two cascaded template events with
 *    their source plus the pipeline's event listing them — asserted at the TABLE.
 *
 * Falsification recorded in the handback: removing the checkbox's `name` makes the POST
 * carry no consent, the server refuses `template_not_released`, and step 4 goes red.
 */
class ReleaseCascadeBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("rc142-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("rcws-" + generatedPassword("w").take(8).lowercase())
    }

    /** The in-page REST seeding of ReleaseChecksBrowserTest: cookie session + the CSRF pair. */
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
                  const headers = {'Content-Type': 'application/json',
                                   'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  const res = await fetch(args.url, {
                    method: args.method, credentials: 'same-origin', headers,
                    body: args.body ?? undefined,
                  });
                  const text = await res.text();
                  return {status: res.status, body: text.length < 8192 ? text : null};
               }""",
                mapOf("method" to method, "url" to url, "body" to body),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    private fun registerH2(name: String) {
        val (status, body) =
            send(
                "POST",
                "/api/v1/datasources",
                """{"name":"$name","display_name":"Release cascade H2","dialect":"H2",""" +
                    """"jdbc_url":"jdbc:h2:mem:${name.replace("-", "_")};DB_CLOSE_DELAY=-1",""" +
                    """"username":"sa","password":"sa"}""",
            )
        if (status != 201) throw AssertionError("datasource POST $status: ${body?.take(400)}")
    }

    /** A template create lands DRAFT v1 (D55) — exactly the pin the cascade is for. */
    private fun createDraftTemplate(id: String) {
        val (status, body) =
            send(
                "POST",
                "/api/v1/templates",
                """{"id":"$id","type":"sql","dialect":"H2","display_name":"${id.substringAfterLast('/')}",""" +
                    """"description":"142 browser fixture","body":"SELECT 1 AS n"}""",
            )
        if (status != 201) throw AssertionError("template POST $status: ${body?.take(400)}")
    }

    private fun createDraftPipeline(
        name: String,
        datasource: String,
        templateIds: List<String>,
    ): String {
        val nodes =
            templateIds.mapIndexed { index, id ->
                // At most one node answers the caller (§12): the rest stage to tempdb.
                val output = if (index < templateIds.lastIndex) ""","output":{"target":"tempdb","table":"step_$index"}""" else ""
                """{"id":"n$index","type":"DQL","source":"$datasource","description":"One row",""" +
                    """"template":{"id":"$id","version":1},"depends_on":[]$output}"""
            }
        val (status, body) =
            send(
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"142 browser golden path","nodes":[${nodes.joinToString(",")}]}""",
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
    fun `the release dialog offers the draft pins checked, withholds Release unchecked, and the cascade lands audited`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")

        val fixture = seedFixture()
        val shared = fixture.shared
        val own = fixture.own

        page.setViewportSize(1280, 900)
        page.navigate("$baseUrl/pipelines")
        // The seeded user's default theme is whatever the deployment says; the pictures
        // below are labelled by theme, so pin it before the first one.
        ensureTheme("light")
        selectLeafOf(fixture.pipelineName)

        // 2 — the dialog: the consent group, checked, one row per draft pin, the shared one
        //     naming its other pinner; Release ENABLED (no checks on this version).
        val dialog = openDialog(page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v1")))
        // Located by the arm's hook, NOT by name — so stripping the checkbox's `name` (the
        // consent the POST carries) is caught at the release arm below, where it matters
        // (the server refuses template_not_released and no success toast lands), and not by
        // a selector that never finds the box.
        val consent = dialog.locator("[data-consent-input]")
        consent.waitFor()
        consent.isChecked shouldBe true
        dialog.innerText() shouldContain "Also release these 2 draft templates"
        dialog.locator("[data-cascade-pin='$shared@1']").waitFor()
        dialog.locator("[data-cascade-pin='$own@1']").waitFor()
        dialog.locator("[data-cascade-pin='$shared@1'] [data-cascade-shared]").innerText() shouldContain
            "also pinned by 1 other draft pipeline"
        dialog.locator("[data-cascade-pin='$own@1'] [data-cascade-shared]").count() shouldBe 0
        val release = dialog.locator("button[data-verb='pipeline-release-confirm']")
        release.waitFor()
        release.isDisabled shouldBe false
        // Checked: the consented wording is what the user sees; the refused one is hidden.
        dialog.locator("[data-cascade-pin='$shared@1'] .plc-cascade-on").isVisible shouldBe true
        dialog.locator("[data-cascade-pin='$shared@1'] .plc-cascade-off").isVisible shouldBe false

        // 3 — unchecking withholds Release and restores today's refused wording; checking
        //     again arms it (the live-listener transition).
        consent.uncheck()
        page.waitForFunction(
            "() => document.querySelector(\"#px-dialog button[data-verb='pipeline-release-confirm']\").disabled === true",
        )
        dialog.locator("[data-cascade-pin='$shared@1'] .plc-cascade-off").isVisible shouldBe true
        dialog.locator("[data-cascade-pin='$shared@1'] .plc-cascade-off").innerText() shouldContain "release the template first"
        dialog.locator("[data-cascade-pin='$shared@1'] .plc-cascade-on").isVisible shouldBe false
        consent.check()
        page.waitForFunction(
            "() => document.querySelector(\"#px-dialog button[data-verb='pipeline-release-confirm']\").disabled === false",
        )

        // The handback's pictures: the checked and unchecked group, light and dark — taken
        // from the SAME dialog the assertions above just read, so they cannot drift from it.
        shot(dialog, "checked", "light")
        consent.uncheck()
        shot(dialog, "unchecked", "light")
        consent.check()
        closeDialog()
        val again = darkShotsThenReopen(shared)
        val releaseAgain = again.locator("button[data-verb='pipeline-release-confirm']")
        releaseAgain.waitFor()

        // 4 — the release lands through the dialog's own POST, and the toast names the cascade.
        val toast = successToastAfter { releaseAgain.click() }
        page.locator("#toast .ds-toast").first().waitFor()
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("142-release-applied-light.png")))
        toast shouldContain "Released v1"
        toast shouldContain "Also released: $shared@1, $own@1."
        toast shouldNotContain "release the template first"

        assertAuditedCascade(fixture.pipelineId, shared, own)
    }

    // ------------------------------------------------------------------ shared helpers

    /** The shared template, the own template, pipeline A's name and id. */
    private data class Fixture(
        val shared: String,
        val own: String,
        val pipelineName: String,
        val pipelineId: String,
    )

    /** Step 1: the datasource, two DRAFT templates, A pinning both, B pinning the shared one. */
    private fun seedFixture(): Fixture {
        val suffix = generatedPassword("d").take(8).lowercase()
        val datasource = "h2-rc142-$suffix"
        registerH2(datasource)
        val shared = "test/rc142_shared_$suffix.sql"
        val own = "test/rc142_own_$suffix.sql"
        createDraftTemplate(shared)
        createDraftTemplate(own)
        val pipelineName = "test/rc142_a_$suffix"
        val pipelineId = createDraftPipeline(pipelineName, datasource, listOf(shared, own))
        createDraftPipeline("test/rc142_b_$suffix", datasource, listOf(shared))
        return Fixture(shared, own, pipelineName, pipelineId)
    }

    private fun closeDialog() {
        page.keyboard().press("Escape")
        page.locator("#px-dialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
    }

    private fun releaseButton(): Locator = page.locator(".tplx-detail-actions button", Page.LocatorOptions().setHasText("Release v1"))

    /** The dark pictures of the same dialog, then the dialog reopened in light with the box checked again. */
    private fun darkShotsThenReopen(shared: String): Locator {
        ensureTheme("dark")
        val darkDialog = openDialog(releaseButton())
        darkDialog.locator("[data-cascade-pin='$shared@1']").waitFor()
        shot(darkDialog, "checked", "dark")
        darkDialog.locator("[data-consent-input]").uncheck()
        shot(darkDialog, "unchecked", "dark")
        closeDialog()
        ensureTheme("light")
        val again = openDialog(releaseButton())
        again.locator("[data-consent-input]").waitFor()
        again.locator("[data-consent-input]").isChecked shouldBe true
        return again
    }

    private fun shot(
        dialog: Locator,
        state: String,
        theme: String,
    ) {
        dialog
            .locator(".app-modal")
            .screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("142-release-dialog-$state-$theme.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "142-screenshots").also { it.toFile().mkdirs() }

    /** The audit rows at the TABLE: two template events with their source, one pipeline event listing them. */
    private fun assertAuditedCascade(
        pipelineId: String,
        shared: String,
        own: String,
    ) {
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement ->
                listOf(shared, own).forEach { template ->
                    // jsonb re-spaces the stored text, so the fields are read with the jsonb
                    // operators — never substring-matched against punctuation.
                    val rows =
                        statement.executeQuery(
                            "SELECT details_json->>'cascade_from_pipeline_id' AS src," +
                                " (details_json->>'cascade_from_version')::int AS src_version," +
                                " details_json->>'via' AS via, (details_json->>'version')::int AS version" +
                                " FROM audit_log WHERE event = 'template.version.released'" +
                                " AND details_json->>'template_id' = '$template'",
                        )
                    rows.next() shouldBe true
                    rows.getString("src") shouldBe pipelineId
                    rows.getInt("src_version") shouldBe 1
                    rows.getString("via") shouldBe "session"
                    rows.getInt("version") shouldBe 1
                    rows.next() shouldBe false
                }
                val pipeline =
                    statement.executeQuery(
                        "SELECT t->>'template_id' AS template_id, (t->>'version')::int AS version" +
                            " FROM audit_log, jsonb_array_elements(details_json->'templates_released') AS t" +
                            " WHERE event = 'pipeline.version.released'" +
                            " AND details_json->>'pipeline_id' = '$pipelineId'",
                    )
                val released = mutableListOf<Pair<String, Int>>()
                while (pipeline.next()) released.add(pipeline.getString("template_id") to pipeline.getInt("version"))
                released shouldBe listOf(shared to 1, own to 1)
            }
        }
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
