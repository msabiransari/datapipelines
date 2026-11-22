package co.datapipelines.browser

import com.microsoft.playwright.Browser
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * #1081 — a DATE cell of a dashboard table shows its own day to a viewer west of UTC. The board is the suite's
 * [seedBoard] board with its table's source answering `SELECT DATE '2026-09-01'` from the suite's PostgreSQL (a real
 * query, no transform), so the frame carries what the server writes for a DATE: the text `2026-09-01`. The table
 * visualization is re-stamped by SQL with a DATE input and a column of format `date` (the suite seeds by SQL; its
 * helpers are called, never edited). The real board page is opened in a context whose clock is `America/New_York`,
 * carrying the suite page's session, where `new Date('2026-09-01').toLocaleDateString()` reads the 31st of August.
 *
 * The expected texts are built in the page — the 1st of September 2026 and the 31st of August 2026, each written with
 * the zone fixed to UTC and no locale named, as the renderer names none — so the test names no locale's spelling.
 */
class DashboardTableDateBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `a DATE cell reads its own day on the board page in New York, not the day before`() {
        startTrace()
        val root = ready("tbldate")
        // First, so seedBoard's own create of the same template id answers 409 and the table pipeline reads this body.
        createTemplate("test/${root}_table.sql", "SELECT DATE '2026-09-01' AS d")
        val board = seedBoard(root)
        val cells =
            """{"display_name":"Cells","renderer":{"kind":"table","version":"1"},""" +
                """"inputs":{"main":{"columns":[{"name":"d","type":"DATE","nullable":false}]}},""" +
                """"config":{"columns":[{"label":"Day","values":"d","format":"date"}]},"bindings":{"d":"d"}}"""
        sql(
            "UPDATE visualization_versions SET body_json = '$cells'::jsonb WHERE visualization_id = " +
                "(SELECT v.id FROM visualizations v JOIN dashboards b ON b.workspace_id = v.workspace_id " +
                "WHERE b.id = '$board'::uuid AND v.name = '$root/visualizations/cells')",
        )

        val viewer =
            newBrowserContext(
                Browser
                    .NewContextOptions()
                    .setTimezoneId(ZONE)
                    .setStorageState(page.context().storageState()),
            )
        try {
            val board2 = viewer.newPage()
            withClue("the viewer's clock is New York, where a date-only text read as UTC midnight is the previous day") {
                board2.navigate("$baseUrl/login")
                board2.evaluate("() => Intl.DateTimeFormat().resolvedOptions().timeZone") shouldBe ZONE
                board2.evaluate("() => new Date('2026-09-01').getDate()") shouldBe 31
            }
            board2.navigate("$baseUrl/dashboards/$board")
            board2.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
            withClue("the board page booted") { board2.evaluate("() => window.__dpPage.code") shouldBe null }
            board2.waitForFunction(
                "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
            )
            val cell = board2.locator("#dp-board [data-dp-viz=\"cells\"] .dp-dashboard-table tbody td").first()
            cell.waitFor()
            val shown = cell.textContent()
            val ownDay = board2.evaluate(UTC_DAY, listOf(2026, 8, 1)) as String
            val dayBefore = board2.evaluate(UTC_DAY, listOf(2026, 7, 31)) as String
            withClue("the two candidate texts differ, so the comparison means something") { ownDay shouldNotBe dayBefore }
            withClue("the DATE cell 2026-09-01 in New York") {
                shown shouldBe ownDay
                shown shouldNotBe dayBefore
            }
        } finally {
            viewer.close()
        }
    }

    private companion object {
        const val ZONE = "America/New_York"

        /** A calendar day [year, month0, day] written with the zone fixed to UTC and no locale named. */
        const val UTC_DAY = "(a) => new Date(Date.UTC(a[0], a[1], a[2])).toLocaleDateString(undefined, { timeZone: 'UTC' })"
    }
}
