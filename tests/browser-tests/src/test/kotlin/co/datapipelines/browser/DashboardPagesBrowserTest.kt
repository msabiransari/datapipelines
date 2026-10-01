package co.datapipelines.browser

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #10 L3b — the first-party PAGES in a real browser (ui-screens.md §4.21): the tree page, the
 * board page, the sidebar's lazy branch, the events pane and the promoter's lens, over the real
 * application with the real vendored runtime. The conformance page half lives in
 * [DashboardPageConformanceBrowserTest]; this suite owns what is PAGE-shaped — navigation,
 * rendering, roles, the pane, the disposal story, and the light/dark handback screens.
 *
 * ## The CSP measurement is POSITIVE here too
 * The pages load the vendored `plotly.css` from the layout head and never an inline script; the
 * collector's zero on both pages is asserted with the same empty-element-hash set-aside the
 * conformance host suite states (the one Playwright's own screenshot path produces, which the
 * computed-style checks cannot be fooled by).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardPagesBrowserTest : DashboardBrowserSuite() {
    private fun openTree() {
        page.navigate("$baseUrl/dashboards")
        page.waitForSelector("#dashboards-tree-pane .tpl-level")
    }

    /** Expands the folder whose FULL path is [path], waiting for its child level to land. */
    private fun expandFolder(path: String) {
        page.click(".tpl-summary:has(span[title='$path'])")
        page.waitForSelector(".tpl-folder:has(span[title='$path']) .tpl-level .tpl-tree")
    }

    @Test
    @Order(1)
    fun `the tree page lists folders, a folder's level lists its child, and a leaf navigates un-boosted`() {
        startTrace()
        val root = ready("dpageto")
        val board = seedBoard(root)
        openTree()

        // The root lists FOLDERS ONLY (a dashboard name needs a folder) — this workspace's
        // first segment, and no leaf.
        page.waitForSelector("#dash-tree-page .tpl-tree")
        page.locator("#dash-tree-page > .ds-empty").count() shouldBe 0
        page.locator("#dash-tree-page a.tpl-leaf").count() shouldBe 0
        page.locator("#dash-tree-page .tpl-summary").count() shouldBe 1

        // The sidebar's branch is INDEPENDENT state: closed until its own disclosure is
        // clicked, and its lazy level carries the NAV scope's ids.
        page.locator("#dash-tree-nav .tpl-tree").count() shouldBe 0

        expandFolder(root)
        expandFolder("$root/boards")
        page.waitForSelector("a.tpl-leaf:has(span[title='$root/boards/overview'])")

        // A leaf NAVIGATES — the URL moves to the board page and the document is a FULL
        // navigation (the landing is not replaced under it: the shell's brand is a fresh
        // document's, not a boosted swap's residue).
        page.click("a.tpl-leaf:has(span[title='$root/boards/overview'])")
        page.waitForURL("**/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
    }

    @Test
    @Order(2)
    fun `the sidebar's Dashboards branch expands lazily into the tree`() {
        startTrace()
        val root = ready("dpbranch")
        seedBoard(root)
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector(".app-nav-branch-tree")

        // Closed until its own disclosure is clicked; the page tree's id does not exist here.
        page.locator("#dash-tree-nav .tpl-tree").count() shouldBe 0
        page.click(".app-nav-branch-tree summary")
        page.waitForSelector("#dash-tree-nav .tpl-tree")
        page.waitForSelector("#dash-tree-nav .tpl-summary")
        // The NAV scope rides the lazy request: this level's folder placeholder carries the
        // nav-scope derived id, never the page's.
        val levelIds =
            page.evaluate(
                "() => Array.from(document.querySelectorAll('#dash-tree-nav .tpl-level')).map(function (el) { return el.id; })",
            ) as List<*>
        (levelIds.isNotEmpty()) shouldBe true
        levelIds.all { it is String && it.startsWith("dash-level-nav-") } shouldBe true
    }

    @Test
    @Order(3)
    fun `the board page boots, the events pane shows the completed refresh with its execution link, and no CSP violation`() {
        startTrace()
        val root = ready("dpboard")
        val board = seedBoard(root)
        page.navigate("$baseUrl/dashboards/$board")

        // The glue boots the vendored runtime on the real page; the initial action renders.
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.locator("#dp-board .dp-dashboard-kpi-number").first().waitFor()

        // The events pane is server-rendered at load — BEFORE the initial refresh finished —
        // so the completed row arrives with the pane's first bounded poll; the reload renders
        // the same read server-side and is what the assertion reads (no 15 s wait, no race).
        page.reload()
        page.waitForSelector(".dp-refreshes-row")
        val pane = page.locator(".dp-refreshes").first().innerText()
        pane shouldContain "COMPLETED"
        page.locator(".dp-refreshes-executions a[href*='/executions/']").first().waitFor()

        // Zero CSP refusals across the tree page, the board page and the reload — but the
        // empty-element hash Playwright's own screenshot path produces.
        val refusals = drainCspViolations()
        refusals.filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }.shouldBeEmpty()
    }

    @Test
    @Order(4)
    fun `disposal - away to the tree and back leaves exactly one instance, and forward again one more`() {
        startTrace()
        val root = ready("dpback")
        val board = seedBoard(root)
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")

        // AWAY: the nav's Dashboards link is a BOOSTED navigation (the tree page is an
        // ordinary page); the board document is left.
        page.click("a[data-nav-section='/dashboards']")
        page.waitForURL("**/dashboards")
        page.waitForSelector("#dashboards-tree-pane")

        // BACK: htmx restores its snapshot first (the glue disposed on the way out, so the
        // snapshot is the unmarked shell) and then fetches the page — the settled state is
        // the fetched page's mount, exactly one. (Measured on the suite's earlier runs: a
        // marker-carrying snapshot answered DashboardAlreadyMounted on the re-run init; the
        // bare snapshot swaps in with no script re-run — an empty region until the fetch.)
        page.goBack()
        page.waitForURL("**/dashboards/$board")
        // The settled mount, not a middle state: htmx restores the (unmarked) snapshot first
        // and fetches the page second — the count reaches 1 only when the fetched page's
        // runtime has mounted.
        page.waitForFunction("() => document.querySelectorAll('[data-datapipelines-dashboard]').length === 1")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        (page.evaluate("() => window.__dpPage.code") as String?) shouldBe null

        // FORWARD to the tree, BACK again: the same answer on the second traversal — the
        // fresh fetch re-mounts exactly once. Each hop is synchronised on the DOM the hop
        // lands (a boosted restore swaps; no load event fires), never on the URL alone.
        page.goForward()
        page.waitForSelector("#dashboards-tree-pane")
        page.waitForTimeout(250.0) // the boosted swap's own settle before the next history hop
        page.goBack()
        page.waitForURL("**/dashboards/$board")
        page.waitForFunction("() => document.querySelectorAll('[data-datapipelines-dashboard]').length === 1")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        (page.evaluate("() => window.__dpPage.code") as String?) shouldBe null
        (page.evaluate("() => document.getElementById('dp-board-refusal').hidden") as Boolean) shouldBe true
    }

    @Test
    @Order(5)
    fun `a viewer executes the board - a promoter without a promotion target sees no boards at all`() {
        startTrace()
        val root = ready("dproles")
        val board = seedBoard(root)

        // --- viewer: holds dashboard.read (D50: every reader an executor) and the board runs.
        val viewerEmail = uniqueEmail("dproles-v-" + generatedPassword("u").take(8))
        val viewer = seedLocalUser(viewerEmail, generatedPassword("pw"), mustChange = false, isAdmin = false, role = "viewer")
        seedMember(rootWorkspaceName(), viewerEmail, "viewer")
        val viewerSession = newSession()
        val vpage = viewerSession.page
        vpage.navigate("$baseUrl/login")
        vpage.fill("#login-email", viewer.email)
        vpage.fill("#login-password", viewer.oneTimePassword)
        vpage.click("form button[type=submit]")
        vpage.waitForURL("**/dashboard")
        switchWorkspace(vpage, rootWorkspaceName())
        vpage.navigate("$baseUrl/dashboards/$board")
        vpage.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        vpage.locator("#dp-board .plotly .main-svg").first().waitFor()

        // --- promoter: the lens fails closed without a promotion target — the tree is the
        // lens's own sentence, not a lie about emptiness, and the board is the family's 404.
        val promoterEmail = uniqueEmail("dproles-p-" + generatedPassword("u").take(8))
        val promoter = seedLocalUser(promoterEmail, generatedPassword("pw"), mustChange = false, isAdmin = false, role = "promoter")
        seedMember(rootWorkspaceName(), promoterEmail, "promoter")
        val promoterSession = newSession()
        val ppage = promoterSession.page
        ppage.navigate("$baseUrl/login")
        ppage.fill("#login-email", promoter.email)
        ppage.fill("#login-password", promoter.oneTimePassword)
        ppage.click("form button[type=submit]")
        ppage.waitForURL("**/dashboard")
        switchWorkspace(ppage, rootWorkspaceName())
        ppage.navigate("$baseUrl/dashboards")
        // The lens's own sentence (the fail-closed notice), and no leaf anywhere.
        ppage.waitForSelector("#app-main [data-lens-unavailable]")
        ppage.locator("#app-main a.tpl-leaf").count() shouldBe 0
        val hidden = ppage.navigate("$baseUrl/dashboards/$board")
        // The family's 404 for a hidden dashboard — the error page, never a board.
        hidden!!.status() shouldBe 404
        (ppage.evaluate("() => window.__dpPage === undefined || window.__dpPage === null") as Boolean) shouldBe true
    }

    @Test
    @Order(6)
    fun `two instances of one dashboard on one page - the page's glue and a test-only second container`() {
        startTrace()
        val root = ready("dptwo")
        val board = seedBoard(root)
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.evaluate(
            """
            () => {
              const second = document.createElement('div');
              second.id = 'dp-board-2';
              document.body.appendChild(second);
              window.__dpPage2 = { ready: false, instance: null };
              const instance = window.DatapipelinesDashboard.init({
                server: { baseUrl: '', credentials: 'session' },
                dashboard: { id: '$board', version: 'released' },
                container: second,
                adapter: window.DatapipelinesDashboard.adapters(second),
              });
              window.__dpPage2.instance = instance;
              instance.ready.then(function () { window.__dpPage2.ready = true; }, function () {});
            }
            """.trimIndent(),
        )
        page.waitForFunction("() => window.__dpPage2 && window.__dpPage2.ready === true")
        // Independent refreshes; disposing the page's instance leaves the second working.
        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['revenue'] })")
        page.evaluate("() => window.__dpPage2.instance.refresh({ scope: 'targets', targets: ['revenue'] })")
        page.waitForFunction("() => document.querySelectorAll('#dp-board .plotly, #dp-board-2 .plotly').length >= 2")
        page.evaluate("() => window.__dpPage.instance.dispose()")
        page.evaluate("() => window.__dpPage2.instance.refresh({ scope: 'all' })")
        page.waitForFunction("() => document.querySelectorAll('#dp-board-2 .plotly .main-svg').length > 0")
    }

    @Test
    @Order(7)
    fun `light and dark - the handback screenshots of both pages`() {
        val root = ready("dpshots")
        val board = seedBoardWithGrid(root)

        // The tree page, light then dark.
        openTree()
        expandFolder(root)
        expandFolder("$root/boards")
        ensureTheme("light")
        page.waitForTimeout(300.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-tree-light.png"),
                ),
        )
        ensureTheme("dark")
        page.waitForTimeout(300.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-tree-dark.png"),
                ),
        )

        // The board page, light then dark: the shutter waits for the draws to settle, and the
        // pane's completed row is in the shot (the page re-rendered AFTER the initial refresh
        // completed — the pane is server-rendered at load).
        ensureTheme("light")
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        page.reload()
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForSelector(".dp-refreshes-row")
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-board-light.png"),
                ),
        )
        ensureTheme("dark")
        page.reload()
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForTimeout(500.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "dashboards-board-dark.png"),
                ),
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** The workspace `ready(slug)` created — its name is the root the fixtures live under. */
    private fun rootWorkspaceName(): String = currentWorkspaceName()

    private fun currentWorkspaceName(): String =
        page.evaluate("() => document.getElementById('workspace-switcher').selectedOptions[0].text") as String

    /** One membership row, granted before the member's first login (AuthCache holds memberships). */
    private fun seedMember(
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
    private fun switchWorkspace(
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
}
