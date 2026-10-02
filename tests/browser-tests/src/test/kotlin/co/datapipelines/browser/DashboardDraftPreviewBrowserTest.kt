package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #369 — the DRAFT PREVIEW page in a real browser (the implementation spec's §6.3): the preview
 * route renders the board page's template for a NAMED version — the banner with the way back to
 * the released view, the version riding the `data-dp-dashboard-version` attribute the glue reads,
 * a real chart rendered from the draft's own pins (R1: released pins only), the refusal block IN
 * PLACE for a draft whose pin is not released (with the release hint), and the light/dark
 * handback screens. The suite's after-each proves ZERO CSP violations for every page this test
 * opens — asserted there, never weaker here.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardDraftPreviewBrowserTest : DashboardBrowserSuite() {
    @Test
    @Order(1)
    fun `the preview page boots the draft - the banner, the version attribute and one rendered chart`() {
        startTrace()
        val root = ready("dpprev")
        val board = seedBoardWithGrid(root)
        seedDraftVersion(board)

        page.navigate("$baseUrl/dashboards/$board/preview?version=2")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
        page.locator("#dp-board .plotly .main-svg").first().waitFor()

        // The version travelled the data-attribute channel the glue reads, and the banner names
        // it with the way back to the released view.
        (page.evaluate("() => document.getElementById('dp-board').getAttribute('data-dp-dashboard-version')") as String?) shouldBe "2"
        (page.evaluate("() => document.getElementById('dp-board').getAttribute('data-dp-dashboard-id')") as String?) shouldBe board
        val banner = page.locator(".dp-board-preview-banner").innerText()
        banner shouldContain "Previewing version"
        banner shouldContain "2"
        banner shouldContain "Back to the released view"
        (page.evaluate("() => document.getElementById('dp-board-refusal').hidden") as Boolean) shouldBe true
    }

    @Test
    @Order(2)
    fun `a draft pinning a DRAFT visualization shows the refusal block in place, with the release hint`() {
        startTrace()
        val root = ready("dppin")
        // The board's RELEASED source and released-chart fixtures come from the standard seed;
        // the DRAFT pin is planted beside them (R1: the pin rule is RELEASED-only, draft or not).
        seedBoard(root)
        seedDraftBoardPinningDraftVisualization(root)

        page.navigate("$baseUrl/dashboards/${sqlToValue("SELECT id::text AS i FROM dashboards WHERE name = '${root}/boards/draftpin'")}/preview?version=1")
        page.waitForSelector("#dp-board-refusal:not([hidden])")
        val refusal = page.locator("#dp-board-refusal").innerText()
        refusal shouldContain "dashboard.runtime.dependency_missing"
        refusal shouldContain "release it first"
        // A refusal page loads no bundle and mounts no runtime: the region is the whole answer.
        (page.evaluate("() => window.__dpPage === undefined || window.__dpPage.instance === null") as Boolean) shouldBe true
    }

    @Test
    @Order(3)
    fun `light and dark - the handback screenshots of the preview page`() {
        startTrace()
        val root = ready("dpshot")
        val board = seedBoardWithGrid(root)
        seedDraftVersion(board)

        ensureTheme("light")
        page.navigate("$baseUrl/dashboards/$board/preview?version=2")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-preview-light.png"),
                ),
        )

        ensureTheme("dark")
        page.reload()
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-preview-dark.png"),
                ),
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** One DRAFT version copied off the board's RELEASED v1 — same body, new number, no release stamps. */
    private fun seedDraftVersion(boardId: String) {
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, created_by) " +
                "SELECT dashboard_id, 2, body_json, 'DRAFT', 'seeded-draft-2-' || dashboard_id, created_by " +
                "FROM dashboard_versions WHERE dashboard_id = '$boardId' AND version = 1",
        )
    }

    /** A DRAFT visualization and a DRAFT board pinning it — R1's refusal, planted for the browser. */
    private fun seedDraftBoardPinningDraftVisualization(root: String) {
        // The ACTIVE workspace's name — ready()'s returned root is an artifact-NAME prefix, never
        // the workspace's name (the same read the pages' suite and the host suite make).
        val workspaceSql = "(SELECT id FROM workspaces WHERE name = '${currentWorkspaceName()}')"
        val adminSql = "(SELECT created_by FROM dashboards WHERE name = '${root}/boards/draftpin' AND workspace_id = $workspaceSql)"
        val vizBody =
            """{"display_name":"Drafty","renderer":{"kind":"table","version":"1"},""" +
                """"inputs":{"main":{"columns":[{"name":"n","type":"INTEGER","nullable":false}]}},""" +
                """"config":{"columns":[{"label":"N","values":"n","format":"integer"}]},"bindings":{"n":"n"}}"""
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES (gen_random_uuid(), $workspaceSql, '$root/visualizations/drafty', '$root/visualizations/drafty', '', 1, " +
                "(SELECT created_by FROM visualizations WHERE name = '$root/visualizations/chart' AND workspace_id = $workspaceSql))",
        )
        sql(
            "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, created_by) " +
                "SELECT id, 1, '$vizBody'::jsonb, 'DRAFT', 'seeded-drafty', " +
                "(SELECT created_by FROM visualizations WHERE name = '$root/visualizations/chart' AND workspace_id = $workspaceSql) " +
                "FROM visualizations WHERE name = '$root/visualizations/drafty' AND workspace_id = $workspaceSql",
        )
        val boardBody =
            """{"display_name":"draftpin","sources":[{"name":"s1","pipeline":{"name":"$root/pipelines/chart","version":1},"parameters":{}}],""" +
                """"visualizations":[{"name":"v1","type":"visualization","visualization":{"name":"$root/visualizations/drafty","version":1},""" +
                """"inputs":{"main":{"source":"s1"}}}],"layout":{"columns":12,"grid":[]},"actions":[]}"""
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES (gen_random_uuid(), $workspaceSql, '${root}/boards/draftpin', '${root}/boards/draftpin', '', NULL, " +
                "(SELECT created_by FROM dashboards WHERE name = '${root}/boards/overview' AND workspace_id = $workspaceSql))",
        )
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, created_by) " +
                "SELECT id, 1, '${boardBody.replace("'", "''")}'::jsonb, 'DRAFT', 'seeded-draftpin', $adminSql " +
                "FROM dashboards WHERE name = '${root}/boards/draftpin' AND workspace_id = $workspaceSql",
        )
    }

    private fun sqlToValue(query: String): String {
        var value: String? = null
        // `sql()` executes; to READ one row the test drives the same JDBC path the suite uses.
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection
                    .createStatement()
                    .executeQuery(query)
                    .use { rs ->
                        rs.next()
                        value = rs.getString(1)
                    }
            }
        return value!!
    }

    private fun currentWorkspaceName(): String =
        page.evaluate("() => document.getElementById('workspace-switcher').selectedOptions[0].text") as String
}
