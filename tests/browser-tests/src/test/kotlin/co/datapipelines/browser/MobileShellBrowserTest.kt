package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID

/**
 * 110 §D — the phone shell in a real browser, at 390×844 unless a test says otherwise.
 *
 * The static audits and `node --test` pin the pieces (the state machine, the sprite, the
 * tokens); this pins what only a live page can show: that the rail is an off-canvas
 * drawer below 768px that opens, hands over focus, and closes by every path the brief
 * names (Escape, the scrim, a boosted navigation) and never survives a navigation; that
 * from 768 to 1099px the rail STARTS collapsed without any class on `<html>` (the CSS
 * default) and that a user who expands it wins over that default; that reduced motion
 * really removes the drawer's transition; and it produces the handback's screenshot set.
 *
 * The document-level overflow walk lives in [AppShellBrowserTest] — 110 §D extends that
 * test in place rather than forking it; this class owns only the drawer's behaviour.
 *
 * No timing sleeps: where a shot must wait out the drawer's transform, the wait is on
 * the GEOMETRY (left edge at 0), which is the fact, not on a duration, which is a guess
 * (the same discipline as ExplorerDetailBrowserTest's waitForDrawerClosed).
 */
class MobileShellBrowserTest : BrowserSuite() {
    private fun signedIn(slug: String): String {
        val user = seedLocalUser(uniqueEmail("$slug-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("$slug" + generatedPassword("w").take(8).lowercase())
        return user.email
    }

    /** The drawer's viewport box, read as the fact — boundingBox() is null for
     *  visibility:hidden elements, which the closed drawer deliberately is. */
    @Suppress("UNCHECKED_CAST")
    private fun railRect(): Map<String, Double> =
        page.evaluate(
            "() => { const r = document.querySelector('aside.app-rail').getBoundingClientRect();" +
                " return { left: r.left, right: r.right }; }",
        ) as Map<String, Double>

    /** Waits until the drawer is fully ON screen — the settled geometry, not a duration. */
    private fun waitForDrawerOpen() {
        page.waitForFunction(
            "() => { const r = document.querySelector('aside.app-rail').getBoundingClientRect(); return r.left === 0; }",
        )
    }

    private fun waitForDrawerClosed() {
        page.waitForFunction("() => !document.documentElement.classList.contains('rail-open')")
    }

    private fun htmlHas(cls: String): Boolean = page.evaluate("(c) => document.documentElement.classList.contains(c)", cls) as Boolean

    private fun activeElementIs(selector: String): Boolean =
        page.evaluate(
            "(sel) => !!document.activeElement && document.activeElement.matches(sel)",
            selector,
        ) as Boolean

    @Test
    fun `the drawer opens, hands over focus, and closes by every path`() {
        startTrace()
        signedIn("mob")
        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // On load: the opener is there, the drawer is off-screen (right <= 0).
        page.locator("#rail-open").isVisible().shouldBeTrue()
        railRect()["right"]!!.shouldBeLessThanOrEqual(0.0)

        // Open: the class, the aria state, the geometry, and focus on the first link.
        page.locator("#rail-open").click()
        page.locator("html.rail-open").waitFor()
        page.locator("#rail-open").getAttribute("aria-expanded") shouldBe "true"
        waitForDrawerOpen()
        railRect()["left"] shouldBe 0.0
        activeElementIs(".app-nav-link").shouldBeTrue()

        // Escape closes and hands focus back to the opener.
        page.keyboard().press("Escape")
        waitForDrawerClosed()
        activeElementIs("#rail-open").shouldBeTrue()

        // The scrim closes too — clicked OUTSIDE the drawer's 232px column.
        page.locator("#rail-open").click()
        page.locator("html.rail-open").waitFor()
        page.locator(".app-rail-backdrop").click(
            Locator
                .ClickOptions()
                .setPosition(350.0, 500.0),
        )
        waitForDrawerClosed()

        // A boosted navigation from a drawer link must not survive the swap: the
        // drawer closes when the new screen lands (htmx:afterSettle) and the URL
        // is /pipelines.
        page.locator("#rail-open").click()
        page.locator("html.rail-open").waitFor()
        page.locator("nav.app-nav a[href='/pipelines']").first().click()
        page.waitForURL("**/pipelines")
        waitForDrawerClosed()
        page.url() shouldContain "/pipelines"
    }

    @Test
    fun `at 1024 the opener is gone and the rail starts collapsed unless the user expanded it`() {
        startTrace()
        signedIn("tblt")
        page.setViewportSize(1024, 768)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // No drawer control at tablet width — hidden by CSS, so it cannot be clicked.
        page.locator("#rail-open").isVisible().shouldBeFalse()
        // The DEFAULT flip: no class on <html>, yet the labels the user would read
        // are hidden — assert on the LABEL's visibility, which is what is seen.
        htmlHas("rail-collapsed").shouldBeFalse()
        page
            .locator(".app-nav-link .app-rail-label")
            .first()
            .isVisible()
            .shouldBeFalse()

        // The user's explicit choice beats the default, and is remembered.
        page.locator("#rail-collapse").click()
        page
            .locator(".app-nav-link .app-rail-label")
            .first()
            .isVisible()
            .shouldBeTrue()
        page.evaluate("() => localStorage.getItem('dp-rail')") shouldBe "0"
        page.reload()
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page
            .locator(".app-nav-link .app-rail-label")
            .first()
            .isVisible()
            .shouldBeTrue()
    }

    @Test
    fun `reduced motion opens the drawer with no transition`() {
        startTrace()
        signedIn("rmot")
        page.setViewportSize(390, 844)
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        @Suppress("UNCHECKED_CAST")
        val durations =
            page.evaluate(
                "() => ({ rail: getComputedStyle(document.querySelector('aside.app-rail')).transitionDuration," +
                    " backdrop: getComputedStyle(document.querySelector('.app-rail-backdrop')).transitionDuration })",
            ) as Map<String, String>
        durations["rail"] shouldBe "0s"
        durations["backdrop"] shouldBe "0s"
    }

    /**
     * 110 §D.4 — the handback's screenshot set: dashboard, pipelines (drawer closed and
     * open), executions detail, the datasources register modal, and one editor with its
     * §C band — at 390 light and dark, plus the 768 dashboard. These come from THIS
     * suite against the booted app; the marketing `siteShots` run is untouched.
     *
     * The executions detail needs a real row: a CALCULATOR-only pipeline (no datasource)
     * is created through the same REST seam the explorers' fixtures use, and a completed
     * run is seeded for it (see [seedExecution] for why not the SSE execute endpoint).
     * Dark shots go through the app's OWN mode toggle — the theme is a server-side
     * stylesheet, so prefers-color-scheme emulation would change nothing.
     */
    @Test
    fun `the handback screenshots`() {
        val userEmail = signedIn("shots")
        val shots = Paths.get("build", "reports", "mobile-shots")
        shots.toFile().mkdirs()

        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/dashboard")
        // Unique per run: the template id is immutable, a second run of this test must
        // not 409 against its own previous fixture.
        val fixture = "mobile_shot_" + generatedPassword("f").take(8).lowercase()
        postJson(
            "/api/v1/templates",
            """{"id":"test/$fixture","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"$fixture","description":"110 screenshot fixture","body":"SELECT 1"}""",
        )
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/$fixture","display_name":"$fixture",""" +
                """"description":"110 screenshot fixture",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )
        val pipelineId = pipelineId("test/$fixture")

        // 390, light.
        shotDashboard(shots, "dashboard-390-light")
        shotPipelinesDrawer(shots, "pipelines-390-drawer")
        shotRegisterModal(shots, "datasources-register-modal-390-light")
        shotEditorBand(shots, pipelineId, "pipeline-editor-390-band-light")

        // The executions detail, from a seeded run and the history screen's own row click.
        seedExecution(pipelineId, userEmail)
        page.navigate("$baseUrl/executions")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.locator("table.ds-table tbody tr").first().waitFor()
        page.locator("table.ds-table tbody tr").first().click()
        page.waitForURL("**/executions/**")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        shot(shots, "executions-detail-390-light")

        // 390, dark: one toggle on the dashboard, then every dark shot. The theme is
        // the app's own stylesheet, so only the app's toggle changes it.
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK))
        page.locator("#mode-toggle").click()
        page.waitForFunction(
            "() => document.getElementById('theme-link').getAttribute('href').includes('/themes/dark.css')",
        )
        shot(shots, "dashboard-390-dark")
        shotPipelinesDrawer(shots, "pipelines-390-drawer-dark")
        shotRegisterModal(shots, "datasources-register-modal-390-dark")
        shotEditorBand(shots, pipelineId, "pipeline-editor-390-band-dark")

        // 768, light again (emulateMedia rides the browser context, so it is reset).
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
        page.setViewportSize(768, 1024)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.locator("#mode-toggle").click()
        page.waitForFunction(
            "() => document.getElementById('theme-link').getAttribute('href').includes('/themes/light.css')",
        )
        shot(shots, "dashboard-768-light")
    }

    private fun shotDashboard(
        shots: Path,
        name: String,
    ) {
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        shot(shots, name)
    }

    /** Pipelines with the drawer CLOSED and then OPEN, restoring the closed state. */
    private fun shotPipelinesDrawer(
        shots: Path,
        name: String,
    ) {
        page.navigate("$baseUrl/pipelines")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        shot(shots, "$name-closed")
        page.locator("#rail-open").click()
        page.locator("html.rail-open").waitFor()
        waitForDrawerOpen()
        shot(shots, "$name-open")
        page.keyboard().press("Escape")
        waitForDrawerClosed()
    }

    private fun shotRegisterModal(
        shots: Path,
        name: String,
    ) {
        page.navigate("$baseUrl/datasources")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.locator("button", Page.LocatorOptions().setHasText("Register Datasource")).first().click()
        page.locator("#register-modal .app-modal").waitFor()
        shot(shots, name)
    }

    private fun shotEditorBand(
        shots: Path,
        pipelineId: String,
        name: String,
    ) {
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.locator(".app-wide-screen-note").waitFor()
        shot(shots, name)
    }

    /** The one screenshot call, so every shot names its file the same way. */
    private fun shot(
        dir: Path,
        name: String,
    ) {
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")))
    }

    /** POSTs JSON through the page's own session (the 106 fixture pattern), asserting 201. */
    private fun postJson(
        url: String,
        body: String,
    ) {
        val status =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch(args.url, {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json',
                              'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''},
                    body: args.body,
                  });
                  return res.status;
                }""",
                mapOf("url" to url, "body" to body),
            )
        (status as Number).toInt() shouldBe 201
    }

    /**
     * Seeds ONE completed execution for the fixture pipeline straight into the metadata
     * DB — the [seedLocalUser] seam — rather than driving `POST /pipelines/{id}/execute`:
     * that endpoint answers an SSE stream (rest-api §6.1), so a 2xx says the run STARTED,
     * not that a row a listing will show exists yet, and the shot would race the executor.
     * The row is the fact the history screen renders; the node-stats array is shaped
     * exactly like the real one (partials/execution-node-stats reads node_id / rows_in /
     * rows_out / duration_ms / context_key → value).
     */
    private fun seedExecution(
        pipelineId: String,
        email: String,
    ) {
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO pipeline_executions (
                            execution_id, pipeline_id, pipeline_version, status, parameters_json,
                            triggered_by, triggered_via, root_execution_id,
                            started_at, completed_at, duration_ms, node_stats_json
                        ) VALUES (?, ?, 1, 'SUCCESS', '{}',
                                  (SELECT id FROM users WHERE email = ?), 'REST', ?,
                                  NOW(), NOW(), 42, CAST(? AS jsonb))
                        """.trimIndent(),
                    ).use { statement ->
                        val executionId = UUID.randomUUID()
                        statement.setObject(1, executionId)
                        statement.setObject(2, UUID.fromString(pipelineId))
                        statement.setString(3, email)
                        statement.setObject(4, executionId)
                        statement.setString(
                            5,
                            """[{"node_id":"fq","status":"SUCCESS","rows_in":1,"rows_out":1,""" +
                                """"duration_ms":42,"context_key":"run_fiscal_quarter","context_value":"2026-Q3"}]""",
                        )
                        statement.executeUpdate()
                    }
            }
    }

    /** The UUID of the named pipeline, read from the metadata DB (the seedLocalUser
     *  seam — the same container the app under test runs against). */
    private fun pipelineId(name: String): String =
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        "SELECT p.id FROM pipelines p WHERE p.name = ? " +
                            "ORDER BY p.created_at DESC LIMIT 1",
                    ).use { statement ->
                        statement.setString(1, name)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            UUID.fromString(rs.getString(1)).toString()
                        }
                    }
            }
}
