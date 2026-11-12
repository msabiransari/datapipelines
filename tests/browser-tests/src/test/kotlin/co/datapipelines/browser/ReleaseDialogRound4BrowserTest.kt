package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Predicate

/**
 * #876 round 4 — the three corrections the lander's verdict asked a browser to witness, each authored through the real
 * REST routes ([DependencyPaneBrowserSuite]) and each refusal asserted at the TABLES:
 *
 * - **R1** — a visualization whose cases and evidence pass (no native refusal, an enabled submit) has its transform pin
 *   DISCARDED or deleted (MISSING): the dialog renders the RELEASE PLAN with that pin as its blocking row (never the
 *   dialog model's own pin refusal); no enabled submit exists, and a forced POST of the plan the page rendered is
 *   refused `visualization.release.dependency_not_released` with nothing flipped. (The evidence gate re-runs the
 *   mechanical check over the pin NOW, so after the plant it also refuses natively — measured, printed, not asserted.)
 * - **R2** — while the pipeline dialog's checks run is HELD in flight, no release or override control is enabled, even
 *   after the plan's consent box is toggled (the placeholder is outside lifecycle-dialog.js's consent evaluator); once
 *   the run answers, the PASS footer's confirm and the FAIL footer's override follow the box and the reason.
 * - **R3** — a dashboard's DRAFT source pipeline with a failing check: the dialog's submit is refused without a reason
 *   for that row, and releases with the row's OWN reason typed into its collapsed override, the reason audited on the
 *   source's release row and on no other.
 */
class ReleaseDialogRound4BrowserTest : DependencyPaneBrowserSuite() {
    @Test
    fun `876 R1 - a DISCARDED or MISSING transform pin is the plan's blocking row - no enabled submit, a forced POST flips nothing`() {
        startTrace()
        val root = ready("rd4pin")
        for (status in listOf("DISCARDED", "MISSING")) {
            withClue(status) { blockedPin(root, status) }
        }
    }

    @Test
    fun `876 R2 - a held checks run keeps every release control disabled - then the PASS and FAIL footers follow the box and reason`() {
        startTrace()
        val root = ready("rd4hold")
        val datasource = registerDatasource()
        val query = sqlTemplate("$root/query.sql")
        val passing = checkedPipeline("$root/passing", query, datasource, expected = 1)
        val failing = checkedPipeline("$root/failing", query, datasource, expected = 2)

        heldThenFooter(passing) { dialog, box ->
            waitDisabled(CONFIRM, false)
            box.uncheck()
            waitDisabled(CONFIRM, true)
            box.check()
            waitDisabled(CONFIRM, false)
            dialog.locator(OVERRIDE).count() shouldBe 0
        }
        heldThenFooter(failing) { dialog, box ->
            page.locator(OVERRIDE).waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
            dialog.locator(CONFIRM).count() shouldBe 0
            waitDisabled(OVERRIDE, true)
            dialog.locator("details.plc-override summary").click()
            page.locator("#pe-dialog textarea[name='overrideChecksReason']").fill(REASON)
            waitDisabled(OVERRIDE, false)
            box.uncheck()
            waitDisabled(OVERRIDE, true)
            box.check()
            waitDisabled(OVERRIDE, false)
            page.locator("#pe-dialog textarea[name='overrideChecksReason']").fill("short")
            waitDisabled(OVERRIDE, true)
        }
    }

    @Test
    fun `876 R3 - a DRAFT source pipeline row's own override releases the dashboard - audited on the source alone`() {
        startTrace()
        val root = ready("rd4ovr")
        val datasource = registerDatasource()
        val query = sqlTemplate("$root/query.sql")
        releaseTemplate(query)
        val sourceId = checkedPipeline("$root/source", query, datasource, expected = 2)
        val setId =
            api(
                "POST",
                "/api/v1/parameter-sets",
                """{"name":"$root/filters","display_name":"Filters","description":"#876","parameters":[{"name":"region",""" +
                    """"label":"Region","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                    """"source":{"constants":[{"value":"east","display_value":"East","is_default":true}]}}]}""",
            ).data("id")
        api(
            "POST",
            "/api/v1/parameter-sets/$setId/release",
            null,
            ifMatch = scalar("SELECT body_hash FROM parameter_set_versions WHERE parameter_set_id = '$setId' AND version = 1"),
        )
        val boardId =
            api(
                "POST",
                "/api/v1/dashboards",
                """{"name":"$root/board","display_name":"Board","description":"#876",""" +
                    """"parameter_set":{"name":"$root/filters","version":1},""" +
                    """"sources":[{"name":"source","pipeline":{"name":"$root/source","version":1},"parameters":{}}],""" +
                    """"visualizations":[],"layout":{"grid":[]}}""",
            ).data("id")
        val audit = scalar("SELECT COALESCE(MAX(id), 0)::text FROM audit_log")!!.toLong()

        val dialog = openDashboardDialog(boardId)
        val row = dialog.locator("[data-release-row='draft'][data-name='$root/source']")
        row.locator("[data-consent-input]").isChecked shouldBe true
        val override = dialog.locator("details[data-release-override]")
        override.count() shouldBe 1
        override.getAttribute("data-node-id") shouldBe row.getAttribute("data-node-id")

        // Without the row's reason the source's own check gate refuses, and nothing flips.
        val refused =
            page.waitForResponse({ it.request().method() == "POST" && it.url().contains("/lifecycle/release") }) {
                dialog.locator("button[type=submit]").click()
            }
        refused.status() shouldBe 409
        page.locator("#toast .ds-toast").first().waitFor()
        page.locator("#toast .ds-toast").first().innerText() shouldContain "pipeline.check.failed"
        pipelineStatus(sourceId) shouldBe "DRAFT"
        scalar("SELECT status FROM dashboard_versions WHERE dashboard_id = '$boardId' AND version = 1") shouldBe "DRAFT"

        // The row's own override, typed into its collapsed field: released, the reason on the SOURCE's row alone.
        override.locator("summary").click()
        override.locator("textarea[name='check_override_reason']").fill(REASON)
        dialog.locator("button[type=submit]").click()
        page.waitForURL("**/dashboards/$boardId*ok=released*")
        pipelineStatus(sourceId) shouldBe "RELEASED"
        scalar("SELECT status FROM dashboard_versions WHERE dashboard_id = '$boardId' AND version = 1") shouldBe "RELEASED"
        scalar(
            "SELECT details_json->>'override_reason' FROM audit_log WHERE id > $audit AND event = 'pipeline.version.released'" +
                " AND details_json->>'pipeline_id' = '$sourceId'",
        ) shouldBe REASON
        scalar("SELECT count(*)::text FROM audit_log WHERE id > $audit AND details_json->>'override_reason' IS NOT NULL") shouldBe "1"
    }

    // ------------------------------------------------------------------ R1

    private fun blockedPin(
        root: String,
        status: String,
    ) {
        val prefix = "$root/${status.lowercase()}"
        val shape = jsonataTemplate("$prefix/shape.jsonata")
        val chartId = visualization("$prefix/chart", shape)
        val hash = scalar("SELECT body_hash FROM visualization_versions WHERE visualization_id = '$chartId' AND version = 1")!!
        greenEvidence(chartId, hash)
        // Before the plant: cases and evidence pass, so the dialog has no native refusal and an ENABLED submit.
        openVisualizationDialog(chartId).let { before ->
            before.locator("[data-release-refusals]").count() shouldBe 0
            before.locator("[data-release-row='draft'][data-name='$shape']").count() shouldBe 1
            before.locator("button[type=submit]:not([disabled])").count() shouldBe 1
            closeDialog()
        }
        // The plant: the pinned template version DISCARDED in place, or the template gone (MISSING).
        when (status) {
            "DISCARDED" -> {
                sql(
                    "UPDATE template_versions SET status = 'DISCARDED', discarded_at = NOW()" +
                        " WHERE template_id = (SELECT id FROM templates WHERE name = '$shape')",
                )
            }

            else -> {
                sql("DELETE FROM templates WHERE name = '$shape'")
            }
        }

        val dialog = openVisualizationDialog(chartId)

        // The evidence gate re-runs the mechanical check NOW, over the pin, so it may refuse natively beside the plan;
        // the pin's own refusal is the plan's blocking row, never the dialog model's.
        val refusals = dialog.locator("[data-release-refusals] [data-refusal-code]").all().map { it.getAttribute("data-refusal-code") }
        println("#876 R1 $status native refusals=$refusals")
        refusals.contains("visualization.release.dependency_not_released") shouldBe false
        val blocking = dialog.locator("[data-release-row='blocking'][data-status='$status']")
        blocking.count() shouldBe 1
        blocking.innerText() shouldContain "blocks the release"
        dialog.locator("[data-release-plan]").getAttribute("data-release-submittable") shouldBe "false"
        dialog.locator("[data-release-blocked]").count() shouldBe 1
        dialog.locator("button[type=submit]:not([disabled])").count() shouldBe 0

        // A forced POST of exactly the plan the page rendered: refused, no redirect, nothing flipped.
        val (code, body) = forcedPost(chartId, dialog)
        println("#876 R1 $status forced POST: status=$code body=${body.take(EXCERPT)}")
        code shouldBe 409
        body shouldContain "visualization.release.dependency_not_released"
        scalar("SELECT status FROM visualization_versions WHERE visualization_id = '$chartId' AND version = 1") shouldBe "DRAFT"
        scalar("SELECT current_version::text FROM visualizations WHERE id = '$chartId'") shouldBe null
        scalar(
            "SELECT count(*)::text FROM audit_log WHERE event = 'visualization.version.released'" +
                " AND details_json::text LIKE '%$chartId%'",
        ) shouldBe "0"
        closeDialog()
    }

    private fun openVisualizationDialog(chartId: String): Locator {
        page.navigate("$baseUrl/visualizations/$chartId?tab=versions")
        page.locator("[data-verb='visualization-release']").first().click()
        val dialog = page.locator("#dp-dialog [data-lifecycle-dialog='visualization-release']")
        dialog.locator("[data-release-plan]").waitFor()
        return dialog
    }

    private fun closeDialog() {
        page.keyboard().press("Escape")
        page.locator("#dp-dialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
    }

    /** A GREEN run for [hash]: a test session and its one case's verdict posted through the REST routes. */
    private fun greenEvidence(
        id: String,
        hash: String,
    ) {
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        (started["data"] as Map<*, *>)["body_hash"] shouldBe hash
        val sid = started.data("session_id")
        val completed =
            api(
                "POST",
                "/api/v1/visualizations/$id/tests/sessions/$sid/results",
                """{"cases":[{"name":"one month","verdict":"green"}],"environment":{"browser":"chromium (playwright)","theme":"dark"}}""",
            )
        completed.data("status") shouldBe "GREEN"
        ((completed["data"] as Map<*, *>)["mechanical"] as Map<*, *>)["ok"] shouldBe true
    }

    /** The dialog form's own fields (the plan it rendered), posted as the htmx form would, with the CSRF double-submit. */
    @Suppress("UNCHECKED_CAST")
    private fun forcedPost(
        id: String,
        dialog: Locator,
    ): Pair<Int, String> {
        dialog.locator("form").count() shouldBe 1
        val result =
            page.evaluate(
                """async (args) => {
                  const form = document.querySelector('#dp-dialog [data-lifecycle-dialog] form');
                  const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
                  const res = await fetch(args.path, {
                    method: 'POST', credentials: 'same-origin',
                    headers: { ...headers, 'HX-Request': 'true', 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: new URLSearchParams(new FormData(form)),
                  });
                  return { status: res.status, redirect: res.headers.get('HX-Redirect'), text: await res.text() };
                }""",
                mapOf("path" to "/partials/visualizations/$id/lifecycle/release"),
            ) as Map<String, Any?>
        result["redirect"] shouldBe null
        return (result["status"] as Number).toInt() to (result["text"] as String)
    }

    // ------------------------------------------------------------------ R2

    /**
     * Opens [pipelineId]'s release dialog with its checks run HELD: no release or override control is enabled before or
     * after the consent box is toggled; then the run is let through and [footer] judges the live footer.
     */
    private fun heldThenFooter(
        pipelineId: String,
        footer: (Locator, Locator) -> Unit,
    ) {
        val held = CopyOnWriteArrayList<Route>()
        val matcher = Predicate<String> { it.contains("/checks/run") }
        page.route(matcher) { route -> held.add(route) }
        page.navigate("$baseUrl/pipelines/$pipelineId")
        page.waitForSelector(".pe-root")
        page.locator(".pe-topbar [data-verb='pipeline-release']").click()
        val dialog = page.locator("#pe-dialog [data-lifecycle-dialog]").first()
        dialog.waitFor()
        page.waitForCondition({ held.isNotEmpty() }, Page.WaitForConditionOptions().setTimeout(HOLD_TIMEOUT_MILLIS))
        val box = dialog.locator("[data-consent-input]").first()
        box.isChecked shouldBe true

        assertNothingEnabled("the dialog swapped in, the run held")
        box.uncheck()
        assertNothingEnabled("the box unticked, the run held")
        box.check()
        assertNothingEnabled("the box ticked again, the run held")

        page.unroute(matcher)
        held.forEach { it.resume() }
        page.waitForFunction(
            "() => { const f = document.getElementById('plc-release-footer');" +
                " return f !== null && f.querySelector('[data-release-pending]') === null && f.querySelector('button') !== null; }",
        )
        footer(dialog, box)
        page.keyboard().press("Escape")
        page.locator("#pe-dialog [data-lifecycle-dialog]").waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
    }

    private fun assertNothingEnabled(moment: String) {
        @Suppress("UNCHECKED_CAST")
        val state =
            page.evaluate(
                """() => {
                  const footer = Array.from(document.querySelectorAll('#plc-release-footer button'));
                  const verbs = Array.from(document.querySelectorAll(
                    "#pe-dialog button[data-verb='pipeline-release-confirm'], #pe-dialog button[data-verb='pipeline-release-override']"));
                  return { footer: footer.length, enabled: footer.filter(b => !b.disabled).length, verbs: verbs.length };
                }""",
            ) as Map<String, Any?>
        withClue("$moment: $state") {
            (state["footer"] as Number).toInt() shouldBe 1
            (state["enabled"] as Number).toInt() shouldBe 0
            (state["verbs"] as Number).toInt() shouldBe 0
        }
    }

    private fun waitDisabled(
        selector: String,
        disabled: Boolean,
    ) {
        page.waitForFunction(
            "(args) => { const b = document.querySelector(args.sel); return b !== null && b.disabled === args.disabled; }",
            mapOf("sel" to selector, "disabled" to disabled),
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** A DRAFT one-node pipeline over [template] carrying one value check that passes when [expected] is 1. */
    private fun checkedPipeline(
        name: String,
        template: String,
        datasource: String,
        expected: Int,
    ): String =
        api(
            "POST",
            "/api/v1/pipelines",
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"#876","nodes":[""" +
                """{"id":"n0","type":"DQL","source":"$datasource","description":"One row",""" +
                """"template":{"id":"$template","version":1},"depends_on":[]}],""" +
                """"checks":[{"id":"one_row","name":"One is one","datasource":"$datasource",""" +
                """"sql":"SELECT 1","expected":{"kind":"value","value":$expected}}]}""",
        ).data("id")

    private fun openDashboardDialog(boardId: String): Locator {
        page.navigate("$baseUrl/dashboards/$boardId?version=1&tab=versions")
        page.locator("[data-verb='dashboard-release']").first().click()
        val dialog = page.locator("#dp-dialog [data-lifecycle-dialog='dashboard-release']")
        dialog.locator("button[type=submit]").waitFor()
        return dialog
    }

    private fun releaseTemplate(name: String) {
        api(
            "POST",
            "/api/v1/templates/release",
            """{"name":"$name"}""",
            ifMatch =
                scalar(
                    "SELECT v.body_hash FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                        " WHERE t.name = '$name' AND v.version = 1",
                ),
        )
    }

    private fun pipelineStatus(id: String): String? =
        scalar("SELECT status FROM pipeline_versions WHERE pipeline_id = '$id' AND version = 1")

    private companion object {
        const val OVERRIDE = "#pe-dialog button[data-verb='pipeline-release-override']"
        const val CONFIRM = "#pe-dialog button[data-verb='pipeline-release-confirm']"
        const val REASON = "Verified by hand against the source rollup."
        const val HOLD_TIMEOUT_MILLIS = 30_000.0
    }
}
