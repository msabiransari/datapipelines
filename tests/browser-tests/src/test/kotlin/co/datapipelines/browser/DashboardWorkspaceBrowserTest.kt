package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #400 — the dashboard WORKSPACE in a real browser (ui-screens.md §4.21): the tab strip over
 * today's board pane (the board re-fits when its hidden pane is revealed), the choose-a-version
 * state (#409) for a draft-only dashboard, the promoter's lens (no verb, no POST, the draft the
 * family's 404), the Versions tab's dialogs opening into `#dp-dialog`, and the catalog's
 * draft-only row state. Every page the suite opens is proven ZERO-CSP by the after-each.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardWorkspaceBrowserTest : DashboardBrowserSuite() {
    @Test
    @Order(1)
    fun `the tab strip swaps panes in page, re-fits the hidden board, and carries the tab in the URL`() {
        startTrace()
        val root = ready("dpws")
        val board = seedBoardWithGrid(root)
        seedDraftVersion(board)

        // A Versions deep link at a NAMED version (the lifecycle redirects' shape): the Board
        // pane is HIDDEN at load, its lazy rows load, and the strip marks Versions.
        page.navigate("$baseUrl/dashboards/$board?version=2&tab=versions")
        page.waitForSelector("#dp-pane-versions:not([hidden]) .ds-table")
        page.locator("#dp-pane-board").getAttribute("hidden") shouldBe "hidden"
        page.locator("#dp-tab-versions").getAttribute("aria-selected") shouldBe "true"
        // The lazy pane shows the history: the released v1 serving, the draft v2 beside it.
        page.locator("#dp-pane-versions [data-version-row='2']").count() shouldBe 1

        // IN PAGE to Board: no navigation (the document is the same), the pane reveals, and
        // the runtime's OWN resize re-fits the chart that booted into the hidden pane.
        page.click("#dp-tab-board")
        page.waitForSelector("#dp-pane-board:not([hidden])")
        page.waitForFunction(
            "() => { const s = document.querySelector('#dp-board .plotly .main-svg'); return s && s.getBoundingClientRect().width > 100; }",
        )
        page.locator("#dp-tab-board").getAttribute("aria-selected") shouldBe "true"
        page.url() shouldContain "tab=board"
        page.url() shouldContain "version=2"

        // The URL IS the state (replaceState, the pipeline editor's #349 rule): a reload of
        // the deep link re-selects Board, and the Versions deep link re-selects Versions —
        // Back after a replaceState'd switch leaves the document, as #350 left it.
        page.reload()
        page.waitForSelector("#dp-pane-board:not([hidden])")
        page.navigate("$baseUrl/dashboards/$board?version=2&tab=versions")
        page.waitForSelector("#dp-pane-versions:not([hidden]) .ds-table")
    }

    @Test
    @Order(2)
    fun `#409 - a draft-only dashboard opens into the choose-a-version state, and the draft link boots the board`() {
        startTrace()
        val root = ready("dp409")
        val board = seedDraftOnlyBoard(root)

        // The CATALOG row says so first (the list page's promise, kept as a row state).
        page.navigate("$baseUrl/dashboards")
        page.waitForSelector("#dash-list-wrapper a.tpl-result")
        page.locator("#dash-list-wrapper .ds-badge-warning").first().innerText() shouldContain "draft v1 pending release"

        // The workspace NAMES the dashboard, offers the draft, and says there is no release —
        // never the old empty h1 and the "not found" for a dashboard the tree just linked.
        page.navigate("$baseUrl/dashboards/$board")
        // The choose-a-version state has no tab strip at all.
        page.locator(".dp-ws-root").count() shouldBe 0
        page.locator("h1.dp-crumb-name").innerText() shouldContain "$root/boards/only_draft"
        page.locator(".ds-empty-title").first().innerText() shouldBe "Choose a version"
        page.locator(".ds-empty-description").first().innerText() shouldContain "has no release yet"
        page.locator("#dp-board-refusal").count() shouldBe 0

        // The draft link is a FULL navigation onto Board at v1 — and the draft BOOTS (its pins
        // are released; the everything-lens serves the draft, R2).
        page.click(".ds-empty-actions a")
        page.waitForURL("**/dashboards/$board?version=1&tab=board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        (page.evaluate("() => document.getElementById('dp-board').getAttribute('data-dp-dashboard-version')") as String?) shouldBe "1"
    }

    @Test
    @Order(3)
    fun `a promoter's lens refuses the workspace, the catalog says why, and no POST ever fires`() {
        startTrace()
        val root = ready("dpromo")
        val board = seedBoard(root)
        seedDraftVersion(board)

        val promoterEmail = uniqueEmail("dpromo-p-" + generatedPassword("u").take(8))
        val promoter = seedLocalUser(promoterEmail, generatedPassword("pw"), mustChange = false, isAdmin = false, role = "promoter")
        seedMemberWorkspace(rootWorkspaceName(), promoterEmail, "promoter")
        val session = newSession()
        val ppage = session.page
        ppage.navigate("$baseUrl/login")
        ppage.fill("#login-email", promoter.email)
        ppage.fill("#login-password", promoter.oneTimePassword)
        ppage.click("form button[type=submit]")
        ppage.waitForURL("**/dashboard")
        switchTo(ppage, rootWorkspaceName())

        // The collector watches EVERY request the promoter's page makes.
        val posts = mutableListOf<String>()
        ppage.onRequest { request ->
            if (request.method() == "POST") posts += request.method() + " " + request.url()
        }

        // This deployment has no promotion target: the lens FAILS CLOSED — the workspace is
        // the family's 404 (named versions and the default alike), the hidden draft among them.
        ppage.navigate("$baseUrl/dashboards/$board").status() shouldBe 404
        ppage.navigate("$baseUrl/dashboards/$board?version=2").status() shouldBe 404

        // The catalog is the lens's own sentence (the fail-closed notice) and no row anywhere.
        ppage.navigate("$baseUrl/dashboards")
        ppage.waitForSelector("#app-main [data-lens-unavailable]")
        ppage.locator("#app-main a.tpl-result").count() shouldBe 0
        ppage.locator("#app-main [data-verb]").count() shouldBe 0

        // The wire proof (A5): no POST left the promoter's page — filtered by METHOD and path.
        posts.shouldBeEmpty()
        session.close()
    }

    @Test
    @Order(4)
    fun `the release dialog opens into the dialog container - the D61 rows name the pins`() {
        startTrace()
        val root = ready("dpdlg")
        val board = seedBoardWithGrid(root)
        seedDraftVersion(board)

        page.navigate("$baseUrl/dashboards/$board?tab=versions")
        page.waitForSelector("#dp-pane-versions:not([hidden]) [data-verb='dashboard-release']")
        page.click("[data-verb='dashboard-release']")
        page.waitForSelector("#dp-dialog [data-lifecycle-dialog='dashboard-release']")
        val dialog = page.locator("#dp-dialog .app-modal")
        dialog.innerText() shouldContain "Release v2?"
        // The released pins render as locked rows; no draft pin, so no cascade consent group.
        dialog.innerText() shouldContain "Dependencies this release locks"
        // Close is emptying the container: the dialog leaves without a navigation.
        page.click("#dp-dialog [data-lifecycle-close]")
        page.waitForTimeout(250.0)
        page.locator("#dp-dialog .app-modal").count() shouldBe 0
    }

    @Test
    @Order(5)
    fun `every tab is CSP-clean and the workspace screens both themes`() {
        startTrace()
        val root = ready("dpshot")
        val board = seedBoardWithGrid(root)
        seedDraftVersion(board)

        ensureTheme("light")
        listOf("board", "overview", "refreshes", "versions").forEach { tab ->
            page.navigate("$baseUrl/dashboards/$board?tab=$tab")
            page.waitForSelector("#dp-pane-" + tab + ":not([hidden])")
            page.waitForTimeout(300.0)
            drainCspViolations()
                .filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }
                .shouldBeEmpty()
        }

        assertVersionsAtPhoneAndDesktop(board)

        // The choose-a-version state (a draft-only dashboard), both themes.
        val draftOnly = seedDraftOnlyBoard(root + "x")
        page.navigate("$baseUrl/dashboards/$draftOnly")
        page.waitForSelector(".ds-empty-title")
        ensureTheme("dark")
        page.waitForTimeout(300.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-workspace-choose-version-dark.png"),
                ),
        )
        ensureTheme("light")

        // The workspace's Board tab, dark then light: the handback screens.
        page.navigate("$baseUrl/dashboards/$board?tab=versions")
        page.waitForSelector("#dp-pane-versions:not([hidden]) .ds-table")
        ensureTheme("dark")
        page.waitForTimeout(300.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-workspace-versions-dark.png"),
                ),
        )
        page.click("#dp-tab-board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForFunction(
            "() => { const s = document.querySelector('#dp-board .plotly .main-svg'); return s && s.getBoundingClientRect().width > 100; }",
        )
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-workspace-board-dark.png"),
                ),
        )
        ensureTheme("light")
    }

    @Test
    @Order(6)
    fun `#402 - Back and Forward re-select in-page tab switches, and a boosted leave and Back wires once`() {
        startTrace()
        val root = ready("dphist")
        val board = seedBoardWithGrid(root)
        seedDraftVersion(board)

        // A blocked inline style or script on any walk below fails the class's after-each (zero CSP):
        // the in-page walk runs light, the leave-and-restore dark.
        ensureTheme("light")
        page.navigate("$baseUrl/dashboards/$board?version=2")
        page.waitForSelector("#dp-pane-board:not([hidden])")
        val length = historyLength()

        // Board → Overview → Versions: two entries of the workspace's own (tab only).
        page.click("#dp-tab-overview")
        expectTab("overview")
        page.click("#dp-tab-versions")
        expectTab("versions")
        historyLength() shouldBe length + 2
        page.url() shouldContain "version=2"

        // Back ×2, Forward ×2 — IN PAGE: the same document, no entry minted.
        val marker = page.evaluate("() => (window.__p402Doc = Math.random().toString(36).slice(2))")
        for ((back, tab) in listOf(true to "overview", true to "board", false to "overview", false to "versions")) {
            if (back) page.goBack() else page.goForward()
            expectTab(tab)
            historyLength() shouldBe length + 2
            page.evaluate("() => window.__p402Doc") shouldBe marker
        }
        historyStat("replayed.dashboards") shouldBe 4
        historyStat("listeners") shouldBe 1

        ensureTheme("dark")
        page.navigate("$baseUrl/dashboards/$board?version=2&tab=versions")
        page.waitForSelector("#dp-pane-versions:not([hidden])")
        // A boosted leave and Back: htmx restores the page; it is wired ONCE (one root marker,
        // one window listener) and its strip still switches and replays.
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForSelector("[data-explorer-pane] .tpl-tree, [data-explorer-pane] .ds-empty")
        page.goBack()
        page.waitForSelector("#dp-pane-versions:not([hidden])")
        page.waitForFunction("() => document.querySelectorAll('.dp-ws-root[data-dp-ws-wired=\"1\"]').length === 1")
        historyStat("listeners") shouldBe 1
        page.click("#dp-tab-refreshes")
        expectTab("refreshes")
        page.goBack()
        expectTab("versions")
        page.evaluate("() => document.querySelectorAll('.dp-ws-root').length") shouldBe 1
    }

    private fun historyLength(): Int = (page.evaluate("() => history.length") as Number).toInt()

    private fun historyStat(path: String): Int =
        (
            page.evaluate(
                "p => p.split('.').reduce((o, k) => (o == null ? o : o[k]), window.WorkspaceHistory.stats()) || 0",
                path,
            ) as Number
        ).toInt()

    /**
     * One tab, asserted whole: the strip, the ONE visible pane, and the URL's `tab` — the
     * arrival entry keeps its own URL (no `tab=` when the page was entered on Board).
     */
    private fun expectTab(tab: String) {
        page.waitForSelector("#dp-pane-$tab:not([hidden])")
        page.locator("#dp-tab-$tab").getAttribute("aria-selected") shouldBe "true"
        page.evaluate("() => document.querySelectorAll('.dp-ws-tab[aria-selected=\"true\"]').length") shouldBe 1
        page.evaluate("() => document.querySelectorAll('[data-dp-pane]:not([hidden])').length") shouldBe 1
        if (tab == "board") page.url() shouldNotContain "tab=" else page.url() shouldContain "tab=$tab"
    }

    // ------------------------------------------------------------------ fixtures

    /** The ACTIVE workspace's name — `ready()`'s root is an artifact-NAME prefix, never the workspace's. */
    private fun rootWorkspaceName(): String =
        page.evaluate("() => document.getElementById('workspace-switcher').selectedOptions[0].text") as String

    /** One membership row, granted before the member's first login (the pages' suite's shape). */
    private fun seedMemberWorkspace(
        workspaceName: String,
        email: String,
        role: String,
    ) {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        SELECT w.id, u.id, '$role'
                          FROM workspaces w, users u
                         WHERE w.name = '$workspaceName' AND u.email = '$email'
                        """.trimIndent(),
                    )
                }
            }
    }

    /** The chrome's switcher, synchronised on the badge's workspace NAME (the 273 pattern). */
    private fun switchTo(
        target: com.microsoft.playwright.Page,
        workspaceName: String,
    ) {
        target.waitForSelector("#workspace-switcher")
        target.selectOption(
            "#workspace-switcher",
            arrayOf(workspaceName),
            com.microsoft.playwright.Page
                .SelectOptionOptions()
                .setForce(true),
        )
        target.waitForFunction("() => document.querySelector('.app-ws b')?.textContent?.trim() === '$workspaceName'")
    }

    /**
     * A dashboard whose ONLY version is a DRAFT (the {D} shape), pinning the standard seed's
     * RELEASED source and chart — the #409 choose-a-version state's fixture, and the draft
     * that BOOTS from its own link (R2: the everything lens serves the draft).
     */
    private fun seedDraftOnlyBoard(root: String): String {
        seedBoard(root)
        val workspaceSql = "(SELECT id FROM workspaces WHERE name = '${rootWorkspaceName()}')"
        val creatorSql =
            "(SELECT created_by FROM dashboards WHERE name = '$root/boards/overview' AND workspace_id = $workspaceSql)"
        val boardBody =
            """{"display_name":"only_draft",""" +
                """"sources":[{"name":"s1","pipeline":{"name":"$root/pipelines/chart","version":1},"parameters":{}}],""" +
                """"visualizations":[{"name":"v1","type":"visualization",""" +
                """"visualization":{"name":"$root/visualizations/chart","version":1},""" +
                """"inputs":{"main":{"source":"s1"}}}],"layout":{"columns":12,"grid":[]},""" +
                """"actions":[{"name":"refresh_all","type":"refresh","scope":"all","initial":true}]}"""
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES (gen_random_uuid(), $workspaceSql, '$root/boards/only_draft', '$root/boards/only_draft', '', NULL, $creatorSql)",
        )
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, created_by) " +
                "SELECT id, 1, '${boardBody.replace("'", "''")}'::jsonb, 'DRAFT', 'seeded-only-draft-' || id, $creatorSql " +
                "FROM dashboards WHERE name = '$root/boards/only_draft' AND workspace_id = $workspaceSql",
        )
        return sqlToValue(
            "SELECT id::text AS i FROM dashboards WHERE name = '$root/boards/only_draft' AND workspace_id = $workspaceSql",
        )
    }

    /**
     * #422 — the four tabs at the phone width too, Versions included: no sideways scroll, and the Versions
     * cells read relative with the absolute UTC stamp on `title`, never a raw ISO instant. Restores the viewport.
     */
    private fun assertVersionsAtPhoneAndDesktop(board: String) {
        val desktop = page.viewportSize()
        page.setViewportSize(PHONE_W, PHONE_H)
        listOf("board", "overview", "refreshes", "versions").forEach { tab ->
            page.navigate("$baseUrl/dashboards/$board?tab=$tab")
            page.waitForSelector("#dp-pane-" + tab + ":not([hidden])")
            if (tab == "versions") page.waitForSelector("#dp-pane-versions .ds-table")
            page.waitForTimeout(300.0)
            withClue("$tab at ${PHONE_W}x$PHONE_H scrolls sideways") { documentOverflowsX() shouldBe false }
        }
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-workspace-versions-390.png"),
                ),
        )
        page.setViewportSize(desktop.width, desktop.height)
        page.navigate("$baseUrl/dashboards/$board?tab=versions")
        page.waitForSelector("#dp-pane-versions .ds-table")
        val cells = page.locator("#dp-pane-versions td").allInnerTexts()
        cells.shouldNotBeEmpty()
        cells.filter { ISO_INSTANT.containsMatchIn(it) }.shouldBeEmpty()
        page.locator("#dp-pane-versions td span[title$='UTC']").count() shouldBeGreaterThan 0
    }

    /** One DRAFT version copied off the board's RELEASED v1 — same body, new number, no release stamps. */
    private fun seedDraftVersion(boardId: String) {
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, created_by) " +
                "SELECT dashboard_id, 2, body_json, 'DRAFT', 'seeded-draft-2-' || dashboard_id, created_by " +
                "FROM dashboard_versions WHERE dashboard_id = '$boardId' AND version = 1",
        )
    }

    private fun sqlToValue(query: String): String {
        var value: String? = null
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

    private companion object {
        const val PHONE_W = 390
        const val PHONE_H = 844

        /** A raw ISO-8601 instant (`2026-10-01T09:00:00Z`) — the text #422 retired from the Versions cells. */
        val ISO_INSTANT = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d""")
    }
}
