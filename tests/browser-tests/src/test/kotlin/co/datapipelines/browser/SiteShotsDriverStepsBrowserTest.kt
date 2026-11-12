package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.TimeoutError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * #545, #546, #549 — three steps of the `siteShots` driver ([SiteShotsMain]) executed against
 * the REAL pages the driver drives, in this suite's own app (the driver itself needs a demo
 * deployment, which no lane starts). Every case calls the driver's own step through
 * [SiteShotsMain.Shots]; a copied selector would only test the copy.
 *
 *  - #549: the `home-agent-builds` framing ([SiteShotsMain.Shots.frameAgentBuilds] /
 *    [SiteShotsMain.Shots.unframeAgentBuilds]) collapses the dock whichever way the workspace
 *    started it (#615: at 1440x900 the dock STARTS collapsed), and puts the editor back as it
 *    found it, a frame that failed half-way included — with no step on the withdrawn sidebar
 *    splitter (#349).
 *  - #546: a stored dark theme is healed at run start ([SiteShotsMain.Shots.ensureLightTheme]),
 *    and [SiteShotsMain.Shots.withTheme] restores light on every exit without ever hiding
 *    either failure.
 *  - #545: [SiteShotsMain.Shots.probeAndReloadDatasources] probes every row by its own verb,
 *    fails a row that never answers BY NAME within the bound it is given, and leaves the table
 *    showing the outcomes it produced.
 *
 * The driver's `baseUrl` is set by reflection, as [SiteShotsNavBrowserTest] does, and put back.
 */
class SiteShotsDriverStepsBrowserTest : BrowserSuite() {
    // ---------------------------------------------------------------- #549 the agent-builds framing

    @Test
    fun `frames the canvas when the dock starts collapsed`() {
        val name = readyPipeline()
        page.setViewportSize(SHORT_W, SHORT_H)
        openWorkspaceAsFramed(name)
        count(".pe-dock.pe-dock-collapsed") shouldBe 1
        expandRail()

        val editorUrl =
            withDriver { shots ->
                shots.frameAgentBuilds(name)
                assertFramed()
                page.url().also { shots.unframeAgentBuilds() }
            }

        assertEditorRestored(editorUrl, dockOpen = false)
    }

    @Test
    fun `frames the canvas when the dock starts open`() {
        val name = readyPipeline()
        page.setViewportSize(TALL_W, TALL_H)
        openWorkspaceAsFramed(name)
        count(".pe-dock:not(.pe-dock-collapsed)") shouldBe 1
        expandRail()

        val editorUrl =
            withDriver { shots ->
                shots.frameAgentBuilds(name)
                assertFramed()
                page.url().also { shots.unframeAgentBuilds() }
            }

        assertEditorRestored(editorUrl, dockOpen = true)
    }

    @Test
    fun `a frame that fails after collapsing is still undone`() {
        val name = readyPipeline()
        page.setViewportSize(TALL_W, TALL_H)
        openWorkspaceAsFramed(name)
        count(".pe-dock:not(.pe-dock-collapsed)") shouldBe 1
        expandRail()

        val editorUrl =
            withDriver { shots ->
                val failure = shouldThrow<IllegalStateException> { shots.frameAgentBuilds(name) { error("boom") } }
                failure.message shouldBe "boom"
                // The seam ran with the dock collapsed and the rail collapsed: the failure is mid-frame.
                count(".pe-dock.pe-dock-collapsed") shouldBe 1
                count("html.rail-collapsed") shouldBe 1
                page.url().also { shots.unframeAgentBuilds() }
            }

        assertEditorRestored(editorUrl, dockOpen = true)
    }

    // ---------------------------------------------------------------- #546 the theme

    @Test
    fun `a stored dark theme is healed at run start`() {
        loginReadyUser()
        ensureTheme("dark")
        page.navigate("$baseUrl/dashboard")
        themeHref() shouldContain "/themes/dark.css"

        withDriver { shots ->
            shots.ensureLightTheme()
            page.navigate("$baseUrl/dashboard")
            themeHref() shouldContain "/themes/light.css"

            val writes = mutableListOf<String>()
            page.onRequest { request -> if (request.url().contains(THEME_PARTIAL)) writes += "${request.method()} ${request.url()}" }
            shots.ensureLightTheme()
            writes.shouldBeEmpty()
        }
    }

    @Test
    fun `withTheme restores on every exit`() {
        loginReadyUser()
        withDriver { shots ->
            val failure = shouldThrow<IllegalStateException> { shots.withTheme("dark") { error("boom") } }
            failure.message shouldBe "boom"
            failure.suppressed.toList().shouldBeEmpty()
        }
        page.navigate("$baseUrl/dashboard")
        themeHref() shouldContain "/themes/light.css"
    }

    @Test
    fun `withTheme returns the block's value`() {
        loginReadyUser()
        val value =
            withDriver { shots ->
                shots.withTheme("dark") {
                    themeHref() shouldContain "/themes/dark.css"
                    ANSWER
                }
            }
        value shouldBe ANSWER
        page.navigate("$baseUrl/dashboard")
        themeHref() shouldContain "/themes/light.css"
    }

    @Test
    fun `a failed restore after a successful block surfaces`() {
        loginReadyUser()
        withDriver { shots ->
            val failure =
                shouldThrowAny {
                    shots.withTheme("dark") {
                        page.route(THEME_ROUTE) { it.abort() }
                    }
                }
            // The restore's own failure (its stylesheet swap never arrives), not a swallowed one.
            failure.shouldBeInstanceOf<TimeoutError>()
            page.unroute(THEME_ROUTE)
            shots.ensureLightTheme()
        }
    }

    @Test
    fun `the block's exception stays primary`() {
        loginReadyUser()
        withDriver { shots ->
            val failure =
                shouldThrow<IllegalStateException> {
                    shots.withTheme("dark") {
                        page.route(THEME_ROUTE) { it.abort() }
                        error("boom")
                    }
                }
            failure.message shouldBe "boom"
            failure.suppressed.toList() shouldHaveSize 1
            failure.suppressed.single().shouldBeInstanceOf<TimeoutError>()
            page.unroute(THEME_ROUTE)
            shots.ensureLightTheme()
        }
    }

    // ---------------------------------------------------------------- #545 the datasource probe

    @Test
    fun `probing every row`() {
        loginReadyUser()
        val suffix = generatedPassword("d").take(8).lowercase()
        val first = "h2-ss545a-$suffix"
        val second = "h2-ss545b-$suffix"
        registerH2(first)
        registerH2(second)
        page.navigate("$baseUrl/datasources")
        page.locator(".ds-table").waitFor()
        lastTestCell(first) shouldBe "never tested"
        lastTestCell(second) shouldBe "never tested"

        val probed = withDriver { shots -> shots.probeAndReloadDatasources(PROBE_TIMEOUT_MS) }

        probed shouldContainExactlyInAnyOrder listOf(first, second)
        lastTestCell(first) shouldStartWithBadge "ok"
        lastTestCell(second) shouldStartWithBadge "ok"
    }

    @Test
    fun `a Test that never answers fails by name`() {
        loginReadyUser()
        val name = "h2-ss545c-" + generatedPassword("d").take(8).lowercase()
        registerH2(name)
        page.navigate("$baseUrl/datasources")
        page.locator(".ds-table").waitFor()
        page.route("**/partials/datasources/*/test") { it.abort() }

        val started = System.nanoTime()
        val failure =
            withDriver { shots ->
                shouldThrow<IllegalStateException> { shots.probeAndReloadDatasources(UNANSWERED_TIMEOUT_MS) }
            }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

        failure.message.orEmpty() shouldContain "'$name'"
        failure.message.orEmpty() shouldContain "no response"
        // The bound passed is the bound used: it waited for it, and not for the driver's 180 s default.
        elapsedMs shouldBeGreaterThanOrEqual UNANSWERED_TIMEOUT_MS.toLong()
        elapsedMs shouldBeLessThan UNANSWERED_CEILING_MS
    }

    // ---------------------------------------------------------------- fixtures

    /** Runs [block] against the driver's own steps with its `baseUrl` aimed at this suite's app. */
    private fun <T> withDriver(block: (SiteShotsMain.Shots) -> T): T {
        val driverUrl = SiteShotsMain::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        val priorUrl = driverUrl.get(null)
        driverUrl.set(null, baseUrl)
        try {
            return block(SiteShotsMain.Shots(page))
        } finally {
            driverUrl.set(null, priorUrl)
        }
    }

    private fun loginReadyUser() {
        page.setDefaultTimeout(STEP_TIMEOUT_MS)
        val user =
            seedLocalUser(
                uniqueEmail("ss545-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("ss545ws-" + generatedPassword("w").take(8).lowercase())
    }

    /** A fresh user, a fresh workspace and one seeded pipeline; returns the pipeline's name. */
    private fun readyPipeline(): String {
        loginReadyUser()
        val name = "ss545/frame/" + generatedPassword("p").take(8).lowercase().replace('-', '_')
        seedPipeline(name) shouldBe CREATED
        return name
    }

    /** Four independent CALCULATOR nodes — PipelineWorkspaceLayoutBrowserTest's fixture. */
    private fun seedPipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const calc = (id) => ({
                id,
                type: 'CALCULATOR',
                kind: 'fiscal_quarter',
                context_key: 'q_' + id,
                inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
              });
              const res = await fetch('/api/v1/pipelines', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                body: JSON.stringify({ name, display_name: name, nodes: ['a', 'b', 'c', 'd'].map(calc) }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    /**
     * The precondition's workspace, opened with the rail COLLAPSED — the state the frame opens
     * the editor in. The rail decides the dock's start (#615 measures `.pe-panes`): at 1440x900
     * this fixture's panes are 611.6 px with the rail collapsed, so the dock starts OPEN there,
     * and collapsed from 1440x880 down (measured 2026-11-12, stable 2 s after the cards).
     */
    private fun openWorkspaceAsFramed(name: String) {
        page.navigate("$baseUrl/dashboard")
        page.locator("#rail-collapse").click()
        page.locator("html.rail-collapsed").waitFor()
        openWorkspaceFor(name)
    }

    /** Puts the rail back, so the frame's own rail step is the one under test. */
    private fun expandRail() {
        page.locator("#rail-collapse").click()
        page.locator("html:not(.rail-collapsed)").waitFor()
    }

    private fun openWorkspaceFor(name: String) {
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("#pipeline-list-wrapper a.tpl-result").first().click()
        page.waitForURL(PipelineWorkspaceUrl.PATTERN)
        page.locator(".pe-card").first().waitFor()
    }

    /** ReleaseChecksBrowserTest's in-page REST seeding, POST only. */
    @Suppress("UNCHECKED_CAST")
    private fun post(
        url: String,
        body: String,
    ): Pair<Int, String?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch(args.url, {
                    method: 'POST', credentials: 'same-origin', body: args.body,
                    headers: {'Content-Type': 'application/json',
                              'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''},
                  });
                  const text = await res.text();
                  return {status: res.status, body: text.length < 8192 ? text : null};
               }""",
                mapOf("url" to url, "body" to body),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    /** An H2 datasource bound to the active workspace — the bundled driver, no external db. */
    private fun registerH2(name: String) {
        val (status, body) =
            post(
                "/api/v1/datasources",
                """{"name":"$name","display_name":"Site shots H2","dialect":"H2",""" +
                    """"jdbc_url":"jdbc:h2:mem:${name.replace("-", "_")};DB_CLOSE_DELAY=-1",""" +
                    """"username":"sa","password":"sa"}""",
            )
        if (status != CREATED) throw AssertionError("datasource POST $status: ${body?.take(400)}")
    }

    private fun count(selector: String): Int = page.locator(selector).count()

    private fun themeHref(): String = page.locator("#theme-link").getAttribute("href") ?: ""

    /** The Last-test cell (the sixth column) of [name]'s row, on the page as it is now. */
    private fun lastTestCell(name: String): String =
        page
            .locator(".ds-table tbody tr")
            .all()
            .single { rowName(it) == name }
            .locator("td:nth-child(6)")
            .innerText()
            .trim()

    /** A datasource row's name: the first span of its first cell. */
    private fun rowName(row: Locator): String =
        row
            .locator("td:first-child > span")
            .first()
            .innerText()
            .trim()

    /** A probed cell reads its badge, then the probe's timestamp. */
    private infix fun String.shouldStartWithBadge(badge: String) {
        substringBefore(' ').substringBefore('\n') shouldBe badge
    }

    private fun assertFramed() {
        count("html.rail-collapsed") shouldBe 1
        count(".pe-dock-collapsed") shouldBe 1
    }

    private fun assertEditorRestored(
        editorUrl: String,
        dockOpen: Boolean,
    ) {
        PipelineWorkspaceUrl.matches(editorUrl) shouldBe true
        page.url() shouldBe editorUrl
        count(".pe-dock") shouldBe 1
        if (dockOpen) {
            count(".pe-dock:not(.pe-dock-collapsed)") shouldBe 1
        } else {
            count(".pe-dock.pe-dock-collapsed") shouldBe 1
        }
        count("html.rail-collapsed") shouldBe 0
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 5_000.0
        const val PROBE_TIMEOUT_MS = 5_000.0
        const val UNANSWERED_TIMEOUT_MS = 3_000.0
        const val UNANSWERED_CEILING_MS = 20_000L
        const val NANOS_PER_MS = 1_000_000L

        /**
         * A window short enough that the dock STARTS collapsed with the rail collapsed (#615).
         * Not the driver's 1440x900: there the collapsed rail leaves this fixture's canvas
         * 334.6 px if the dock opened, above the 320 px floor, so it starts open.
         */
        const val SHORT_W = 1440
        const val SHORT_H = 860

        /** A window tall enough that the dock starts open. */
        const val TALL_W = 1440
        const val TALL_H = 1400

        const val CREATED = 201
        const val ANSWER = 42
        const val THEME_PARTIAL = "/partials/profile/theme"
        const val THEME_ROUTE = "**/partials/profile/theme"
    }
}
