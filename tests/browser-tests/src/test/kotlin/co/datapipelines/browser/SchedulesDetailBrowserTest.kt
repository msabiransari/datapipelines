package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.PARAMETER
import co.datapipelines.browser.ScheduleFixtures.YEARLY
import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.etag
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2, A.2 — the selected schedule (ui-screens.md §4.20) in a real browser against the real
 * dispatcher: pause and resume, a block that names its cause with Unblock beside it, delete (soft —
 * the execution stays readable), the role ladder and a viewer's refused POST from the page's own
 * request path, light and dark, and no overflow at the three desktop widths.
 */
class SchedulesDetailBrowserTest : SchedulesBrowserSuite() {
    // ------------------------------------------------------------------ A.2 pause / resume / unblock

    @Test
    fun `pause and resume, and a blocked schedule names its cause and unblocks once the cause is gone`() {
        startTrace()
        val root = ready("schpause")
        val pipeline = "$root/jobs/rows"
        val pipelineId = releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)
        openSchedules("?id=$id")
        detail().waitFor()

        page.locator("[data-verb='schedule-pause']").click()
        page.waitForFunction("() => (document.querySelector('#schedule-detail [data-slot=condition]') || {}).textContent === 'paused'")
        page.locator("[data-verb='schedule-pause']").isVisible() shouldBe false
        page.locator("[data-verb='schedule-resume']").isVisible() shouldBe true
        page.locator("[data-verb='schedule-resume']").click()
        page.waitForFunction("() => (document.querySelector('#schedule-detail [data-slot=condition]') || {}).textContent === 'enabled'")

        // The pipeline loses its current version (its only release discarded): a Run now is
        // refused before anything starts, and the schedule BLOCKS with that cause (§5.2).
        send(page, "POST", "/api/v1/pipelines/$pipelineId/versions/1/discard").status shouldBe 200
        page.locator("[data-verb='schedule-run']").click()
        val blocked = page.locator("#schedule-detail [data-slot='blocked']:not([hidden])")
        blocked.waitFor()
        page.locator("#schedule-detail [data-slot='blocked-code']").textContent() shouldBe "pointer_null"
        page.locator("[data-verb='schedule-run']").isVisible() shouldBe false
        page.locator("#schedule-detail .sch-runrow").first().getAttribute("data-state") shouldBe "not_started"

        // Unblock re-validates first: still no current version, so it is refused and the block stays.
        page.waitForResponse({ it.url().endsWith("/unblock") }) { page.locator("#schedule-detail [data-verb='schedule-unblock']").click() }
        page.locator(".ds-toast-danger").first().waitFor()
        blocked.isVisible() shouldBe true
        // The cause fixed (the release restored), unblock clears it.
        send(page, "POST", "/api/v1/pipelines/$pipelineId/versions/1/restore").status shouldBe 200
        page.locator("#schedule-detail [data-verb='schedule-unblock']").click()
        page.waitForFunction("() => (document.querySelector('#schedule-detail [data-slot=condition]') || {}).textContent === 'enabled'")
        page.locator("#schedule-detail [data-slot='blocked']").isHidden() shouldBe true
    }

    // ------------------------------------------------------------------ A.2 delete

    @Test
    fun `delete - the schedule is gone from the tree and every read, and its execution stays readable`() {
        startTrace()
        val root = ready("schdel")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)
        openSchedules("?id=$id")
        detail().waitFor()
        page.locator("[data-verb='schedule-run']").click()
        page.waitForFunction(
            "() => (document.querySelector('#schedule-detail .sch-runrow[data-state=succeeded] [data-slot=execution]') || {}).href",
            null,
            Page.WaitForFunctionOptions().setTimeout(60_000.0),
        )
        val executionId =
            page
                .locator("#schedule-detail .sch-runrow [data-slot=execution]")
                .first()
                .getAttribute("href")!!
                .substringAfterLast('/')

        page.locator("[data-verb='schedule-delete']").click()
        page.locator("#sch-dialog [data-verb='schedule-delete-confirm']").click()
        page.locator("#schedule-detail .tplx-detail-empty-state, #schedule-detail").first().waitFor()
        page.waitForFunction("() => !document.querySelector('#schedule-detail [data-schedule-detail]')")
        page.locator("#schedule-list [data-empty='none']").waitFor()
        send(page, "GET", "/api/v1/schedules/$id").status shouldBe 404
        // Soft delete: its execution — the run's work — is still on the executions screen.
        page.navigate("$baseUrl/executions/$executionId")
        val main = page.locator("main").textContent()
        main shouldContain "SUCCESS"
        main shouldContain "SCHEDULE" // triggered via the scheduler, fired as the system identity
    }

    // ------------------------------------------------------------------ roles: the viewer

    @Test
    fun `a viewer reads a schedule with no verb, and the page's own POST is refused by the server`() {
        startTrace()
        // Authored in `default`, where the viewer is a member.
        val author =
            seedLocalUser(
                uniqueEmail("schv-author-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "author",
            )
        login(author.email, author.oneTimePassword)
        page.waitForURL("**/dashboard")
        val root = "schv" + suffix()
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)

        val viewer =
            seedLocalUser(
                uniqueEmail("schv-viewer-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "viewer",
            )
        val session = newSession()
        try {
            val v = session.page
            v.navigate("$baseUrl/login")
            v.fill("#login-email", viewer.email)
            v.fill("#login-password", viewer.oneTimePassword)
            v.click("form button[type=submit]")
            v.waitForURL("**/dashboard")
            v.navigate("$baseUrl/schedules?id=$id")
            v.locator("#schedule-detail [data-schedule-detail]").waitFor()
            v.locator("#schedule-detail [data-slot='leaf']").textContent() shouldBe "rows"
            // Absent, not hidden: no verb anywhere on the page.
            v.locator("[data-verb]").count() shouldBe 0
            // The server's row, not the missing button: the page's own request path is refused.
            @Suppress("UNCHECKED_CAST")
            val refused =
                v.evaluate(
                    """(id) => window.DpSchedulesApi.pause(id).then(() => ({status: 200}), e => ({status: e.status, code: e.code}))""",
                    id,
                ) as Map<String, Any?>
            (refused["status"] as Number).toInt() shouldBe 403
            refused["code"] shouldBe "auth.role_required"
            send(v, "GET", "/api/v1/schedules/$id").body shouldContain "\"condition\":\"enabled\""
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------------ roles: the ladder

    /**
     * Every role opens the page (`schedule.read` is every member's); the write verbs are exactly
     * the author's, the workspace admin's (and the super admin's, the suite's other tests) — the
     * five `schedule.*` write rows. A promoter reads through the LENS, and with no promotion target
     * configured here the lens admits nothing, so its page is the lens's empty copy (auth §11A.1).
     */
    @Test
    fun `each role gets exactly its verbs - author and workspace admin write, viewer and promoter read`() {
        startTrace()
        val author =
            seedLocalUser(
                uniqueEmail("schl-author-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "author",
            )
        login(author.email, author.oneTimePassword)
        page.waitForURL("**/dashboard")
        val root = "schl" + suffix()
        releasedPipeline(page, "$root/jobs/rows")
        val id = createSchedule(page, "$root/nightly/rows", "$root/jobs/rows")

        mapOf("author" to true, "workspace_admin" to true, "viewer" to false, "promoter" to false).forEach { (role, writes) ->
            val user =
                seedLocalUser(
                    uniqueEmail("schl-$role-" + suffix()),
                    generatedPassword("pw"),
                    mustChange = false,
                    isAdmin = false,
                    role = role,
                )
            val session = newSession()
            try {
                val p = session.page
                p.navigate("$baseUrl/login")
                p.fill("#login-email", user.email)
                p.fill("#login-password", user.oneTimePassword)
                p.click("form button[type=submit]")
                p.waitForURL("**/dashboard")
                p.navigate("$baseUrl/schedules?id=$id")
                p.locator("#schedule-list:not([aria-busy])").waitFor()
                p.locator("[data-nav-section='/schedules'].active").waitFor()
                withClue("$role's page") {
                    if (role == "promoter") {
                        p.locator("#schedule-list [data-empty='none']").textContent() shouldContain "released pipelines"
                    } else {
                        p.locator("#schedule-detail [data-schedule-detail]").waitFor()
                    }
                    (p.locator("[data-verb='schedule-create']").count() > 0) shouldBe writes
                    (p.locator("[data-verb='schedule-run']").count() > 0) shouldBe writes
                    (p.locator("[data-verb='schedule-edit']").count() > 0) shouldBe writes
                    (p.locator("[data-verb='schedule-delete']").count() > 0) shouldBe writes
                }
            } finally {
                session.close()
            }
        }
    }

    // ------------------------------------------------------------------ light and dark

    @Test
    fun `the page follows the theme - light and dark surfaces come from the tokens`() {
        startTrace()
        val root = ready("schtheme")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)
        val luminance = mutableMapOf<String, Double>()
        listOf("light", "dark").forEach { mode ->
            openSchedules("?id=$id")
            ensureTheme(mode)
            openSchedules("?id=$id")
            detail().waitFor()
            luminance[mode] =
                (
                    page.evaluate(
                        """() => { const c = getComputedStyle(document.querySelector('#schedule-detail .ds-card')).backgroundColor;
                              const [r, g, b] = c.match(/\d+(\.\d+)?/g).map(Number);
                              return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255; }""",
                    ) as Number
                ).toDouble()
        }
        withClue("card luminance by mode: $luminance") {
            (luminance.getValue("light") > 0.6) shouldBe true
            (luminance.getValue("dark") < 0.4) shouldBe true
        }
    }

    // ------------------------------------------------------------------ widths

    @Test
    fun `nothing in the detail ends past its column at 1100, 1440 and 1920, and the 320 rail holds its rows`() {
        startTrace()
        val root = ready("schwide")
        val pipeline = "$root/a_rather_long_folder_name/with_a_long_pipeline_name_to_wrap"
        releasedPipeline(page, pipeline)
        val id =
            createSchedule(
                page,
                "$root/a_rather_long_folder_name/nightly_revenue_rollup_for_every_region",
                pipeline,
                cron = "0 6 * * *",
                timezone = "America/Argentina/Buenos_Aires",
            )
        send(page, "POST", "/api/v1/schedules/$id/run", idempotencyKey = "wide-" + suffix()).status shouldBe 202
        val offenders = mutableListOf<String>()
        listOf(1100, 1440, 1920).forEach { width ->
            page.setViewportSize(width, 900)
            openSchedules("?id=$id")
            detail().waitFor()
            page.locator("#schedule-detail .sch-runrow").first().waitFor()
            listOf("#schedule-detail .tplx-read", "#schedule-detail .tplx-act", "#schedule-detail").forEach { sel ->
                val out = culprits(sel)
                if (out.isNotEmpty()) offenders += "$width $sel: $out"
            }
        }
        // The tree pane at its 260px floor (the 320 rail: the pane plus the page gutter).
        page.setViewportSize(1440, 900)
        page.evaluate("() => document.documentElement.style.setProperty('--tplx-tree-w', '260px')")
        culprits("#schedule-tree-pane").let { if (it.isNotEmpty()) offenders += "tree at 260: $it" }
        offenders shouldContainExactly emptyList()
    }

    private fun culprits(selector: String): String =
        page.evaluate(
            """
            (selector) => {
              const root = document.querySelector(selector);
              if (!root) return 'no ' + selector;
              const edge = root.getBoundingClientRect().right;
              return Array.from(root.querySelectorAll('*'))
                .filter(e => e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().right > edge + 1)
                .slice(0, 6)
                .map(e => e.tagName + '.' + String(e.className).trim().split(/\s+/).join('.') + ' ..' + Math.round(e.getBoundingClientRect().right) + ' vs ' + Math.round(edge))
                .join(' | ');
            }
            """.trimIndent(),
            selector,
        ) as String
}
