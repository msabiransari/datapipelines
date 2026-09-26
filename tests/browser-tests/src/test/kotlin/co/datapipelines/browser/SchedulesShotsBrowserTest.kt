package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #9 slice 2 — the Schedules page's handback set (ui-screens.md §4.20): the explorer with a
 * selected schedule at the three desktop widths in light and dark, the form, a run with its
 * merged Messages, a blocked schedule, and the reader's (viewer's) view. The widths' geometry
 * is ASSERTED by `SchedulesBrowserTest`; this suite is the pictures, taken on a workspace that
 * looks like one in use: folders, a paused schedule, a blocked one, and runs that finished.
 *
 * Files land in `build/reports/schedules-screenshots/` as `schedules-<state>.png`.
 */
class SchedulesShotsBrowserTest : BrowserSuite() {
    private fun shotDir(): Path = Paths.get("build", "reports", "schedules-screenshots").also { it.toFile().mkdirs() }

    private fun shot(name: String) {
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("schedules-$name.png")))
    }

    private fun suffix(): String = generatedPassword("s").takeLast(6).lowercase()

    private fun open(query: String) {
        page.navigate("$baseUrl/schedules$query")
        page.locator("#schedule-list:not([aria-busy])").waitFor()
    }

    private fun awaitRunState(state: String) =
        page.waitForFunction(
            "(s) => (document.querySelector('#schedule-detail .sch-runrow') || {}).getAttribute && " +
                "document.querySelector('#schedule-detail .sch-runrow').getAttribute('data-state') === s",
            state,
            Page.WaitForFunctionOptions().setTimeout(60_000.0),
        )

    /** The three schedules the pictures are about, in a workspace that looks like one in use. */
    private data class Seeded(
        val main: String,
        val blocked: String,
    )

    @Test
    fun `the handback screenshots - explorer and detail at three widths in light and dark, the form, a run, a block`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("schshots-" + suffix()), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("finance" + suffix())

        open("")
        ensureTheme("light")
        open("")
        shot("empty-author-light")

        val seeded = seed()
        listOf("light", "dark").forEach { mode -> shootMode(mode, seeded) }
        shootViewer(seeded.main)
    }

    /**
     * Five schedules over two folders — one paused, one BLOCKED (its pipeline's release discarded,
     * then a Run now that cannot start) — and two finished runs on the main one, through the page.
     */
    private fun seed(): Seeded {
        val rollup = "finance/daily/revenue_rollup"
        releasedPipeline(page, rollup)
        val blockedPipeline = releasedPipeline(page, "finance/monthly/ledger_close")
        val main = createSchedule(page, "finance/daily/revenue", rollup, cron = "30 6 * * *", timezone = "America/New_York")
        createSchedule(page, "finance/daily/costs", rollup, cron = "0 7 * * 1", timezone = "Europe/London")
        val paused = createSchedule(page, "finance/weekly/summary", rollup, cron = "0 9 * * 1", timezone = "Europe/London")
        val blocked = createSchedule(page, "finance/monthly/close", "finance/monthly/ledger_close", cron = "0 3 1 * *")
        createSchedule(page, "ops/nightly/vacuum", rollup, cron = "15 2 * * *")
        send(page, "POST", "/api/v1/schedules/$paused/pause").status shouldBe 200

        open("?id=$main")
        repeat(2) {
            page.locator("[data-verb='schedule-run']").click()
            awaitRunState("succeeded")
        }
        send(page, "POST", "/api/v1/pipelines/$blockedPipeline/versions/1/discard").status shouldBe 200
        open("?id=$blocked")
        page.locator("[data-verb='schedule-run']").click()
        page.locator("#schedule-detail [data-slot='blocked']:not([hidden])").waitFor()
        return Seeded(main, blocked)
    }

    /** One theme: the detail at the three widths, the blocked callout, a run's Messages and the edit form. */
    private fun shootMode(
        mode: String,
        seeded: Seeded,
    ) {
        ensureTheme(mode)
        listOf(1100 to 900, 1440 to 900, 1920 to 1080).forEach { (w, h) ->
            page.setViewportSize(w, h)
            // The deep link opens the folders down to the selected leaf by itself.
            open("?id=${seeded.main}")
            page.locator("#schedule-tree-pane [data-leaf-name='finance/daily/revenue'][aria-selected='true']").waitFor()
            page.locator("#schedule-detail .sch-runrow").first().waitFor()
            shot("detail-$w-$mode")
        }
        page.setViewportSize(1440, 900)
        open("?id=${seeded.blocked}")
        page.locator("#schedule-detail [data-slot='blocked']:not([hidden])").waitFor()
        shot("blocked-1440-$mode")

        open("?id=${seeded.main}")
        page.locator("#schedule-detail .sch-runrow").first().waitFor()
        page.locator("#schedule-detail .sch-runrow [data-sch-action='open-run']").first().click()
        page.waitForFunction("() => document.querySelectorAll('#sch-dialog [data-slot=messages] tr[data-source=pipeline]').length > 0")
        shot("run-1440-$mode")
        page.keyboard().press("Escape")

        page.locator("[data-verb='schedule-edit']").click()
        page.locator("#sch-dialog [data-sch-form]").waitFor()
        page.locator("#sch-f-param-batch_size").waitFor()
        page.locator("[data-slot=preview][data-cron]").waitFor()
        shot("form-edit-1440-$mode")
        page.keyboard().press("Escape")
    }

    /** The reader's (viewer's) view of the main schedule, in the same workspace. */
    private fun shootViewer(main: String) {
        val viewer =
            seedLocalUser(
                uniqueEmail("schshots-viewer-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "viewer",
            )
        val ws = page.locator("#workspace-switcher option:checked").textContent().trim()
        seedMembership(viewer.email, ws)
        val session = newSession()
        try {
            val v = session.page
            v.setViewportSize(1440, 900)
            v.navigate("$baseUrl/login")
            v.fill("#login-email", viewer.email)
            v.fill("#login-password", viewer.oneTimePassword)
            v.click("form button[type=submit]")
            v.waitForURL("**/dashboard")
            // The switch is a form POST that re-issues the session: wait for ITS answer, not for a
            // URL the page is already on.
            v.waitForResponse({ it.url().contains("/workspace/switch") }) {
                v.selectOption("#workspace-switcher", arrayOf(ws), Page.SelectOptionOptions().setForce(true))
            }
            v.waitForLoadState()
            v.navigate("$baseUrl/schedules?id=$main")
            v.locator("#schedule-detail .sch-runrow").first().waitFor()
            v.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
            v.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("schedules-viewer-1440.png")))
        } finally {
            session.close()
        }
    }

    /** Adds [email] to the workspace named [workspace] as a viewer (a super admin's grant, in SQL). */
    private fun seedMembership(
        email: String,
        workspace: String,
    ) = java.sql.DriverManager
        .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    SELECT w.id, u.id, 'viewer' FROM workspaces w, users u
                     WHERE (w.name = '$workspace' OR w.display_name = '$workspace') AND u.email = '$email'
                    ON CONFLICT (workspace_id, user_id) DO NOTHING
                    """.trimIndent(),
                )
            }
        }
}
