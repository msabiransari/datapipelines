package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #349 — the workspace's composed LAYOUT, in a real browser (spec §4.1/§4.2/§4.4, A6/A13).
 *
 * This class replaces the 141 sidebar-resize suite: the owner's workspace ruling
 * (2026-09-30, spec §4.1 "No duplicate full description/settings sidebar") WITHDRAWS the
 * settings sidebar — its description and parameters moved to the Overview and Parameters
 * tabs — so the resize contract it pinned has no pane left to size. What must still be
 * true, asserted here so the withdrawal cannot silently regress:
 *
 *  - the workspace renders NO settings sidebar and NO second splitter handle; the graph
 *    owns the available width, and the dock (Node Details | Results | Errors | Events)
 *    is its bottom neighbour INSIDE the Flow pane;
 *  - the dock's drag contract survives the composition unchanged — the handle drags,
 *    the canvas follows, Cytoscape re-lays-out (the DockResize suite owns the full
 *    arithmetic; here the geometry is re-read once as the composition's control);
 *  - the tab strip is reachable while the dock is COLLAPSED (spec §4.2: "keep the tab
 *    strip accessible when contents collapse" is the workspace's rule; the dock strip
 *    stays on screen too);
 *  - at 390px the phone band renders and nothing scrolls the DOCUMENT sideways (110 §B).
 */
class PipelineWorkspaceLayoutBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("wslay-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("wslayws-" + generatedPassword("w").take(8).lowercase())
    }

    /** Four independent CALCULATOR nodes — the dock-resize fixture, self-contained. */
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

    private fun openWorkspaceFor(name: String) {
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a.tplx-detail-open").first().click()
        page.waitForURL(PipelineWorkspaceUrl.PATTERN)
        page.locator(".pe-card").first().waitFor()
    }

    private fun shot(name: String) = page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("349-$name.png")))

    private fun shotDir(): Path = Paths.get("build", "reports", "349-screenshots").also { it.toFile().mkdirs() }

    @Test
    fun `the workspace has no settings sidebar - the graph owns the width and the dock sits beside it in Flow`() {
        loginReadyUser()
        val name = "wslay/noside/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(1440, 900)
        openWorkspaceFor(name)

        page.evaluate("() => !!document.querySelector('.pe-sidebar')") shouldBe false
        page.evaluate("() => !!document.querySelector('[data-splitter=\\\"editor-sidebar\\\"]')") shouldBe false
        // The graph owns the width: the stage spans the workspace's content box.
        @Suppress("UNCHECKED_CAST")
        val widths = page.evaluate(
            """
            () => {
              const root = document.querySelector('.pe-root').getBoundingClientRect();
              const stage = document.querySelector('.pe-stage').getBoundingClientRect();
              const pane = document.querySelector('#pe-pane-flow').getBoundingClientRect();
              return { rootW: root.width, stageW: stage.width, paneW: pane.width };
            }
            """.trimIndent(),
        ) as Map<String, Any?>
        (widths["stageW"] as Number).toDouble() shouldBeGreaterThan (widths["paneW"] as Number).toDouble() * 0.95
        shot("flow-no-sidebar")
    }

    @Test
    fun `the dock drag survives the composition - the pane resizes inside the Flow pane and remembers`() {
        loginReadyUser()
        val name = "wslay/dock/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(1440, 900)
        openWorkspaceFor(name)

        val handleBefore = page.locator("[data-splitter='editor-dock']").boundingBox()
        val before = page.evaluate("() => document.querySelector('.pe-dock').getBoundingClientRect().height") as Number

        val x = handleBefore.x + handleBefore.width / 2
        val y = handleBefore.y + handleBefore.height / 2
        page.mouse().move(x, y)
        page.mouse().down()
        page.mouse().move(x, y - 120, Mouse.MoveOptions().setSteps(10))
        page.mouse().up()
        page.waitForLoadState(LoadState.NETWORKIDLE)

        val after = page.evaluate("() => document.querySelector('.pe-dock').getBoundingClientRect().height") as Number
        (after.toDouble() - before.toDouble()) shouldBeGreaterThan 80.0
        // Remembered: the splitter's localStorage key is the dock's own, unchanged.
        page.evaluate("() => window.localStorage.getItem('dp.pane.editor-dock') != null") shouldBe true
        shot("dock-in-flow-pane")
    }

    @Test
    fun `the tab strip stays reachable while the dock is collapsed, and the dock strip with it`() {
        loginReadyUser()
        val name = "wslay/collapse/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(1440, 900)
        openWorkspaceFor(name)

        // Collapse the dock...
        page.locator("button[aria-label='Collapse dock']").click()
        page.waitForTimeout(300.0)
        val dockH = (page.evaluate("() => document.querySelector('.pe-dock').getBoundingClientRect().height") as Number).toDouble()
        // ...the dock keeps its tab STRIP (no close, 065's rule)...
        dockH shouldBeGreaterThan 0.0
        page.evaluate("() => document.querySelector('.pe-dock-tabs').getBoundingClientRect().height > 0") shouldBe true
        // ...and the workspace's own tab strip is on screen and clickable.
        val tabs = page.locator("#pe-tab-overview")
        tabs.click()
        page.evaluate("() => document.querySelector('#pe-pane-overview').hidden") shouldBe false
        page.evaluate("() => document.querySelector('#pe-pane-flow').hidden") shouldBe true
    }

    @Test
    fun `at 390 the band renders and nothing scrolls the document sideways (110 kept by 349)`() {
        loginReadyUser()
        val name = "wslay/phone/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(390, 844)
        openWorkspaceFor(name)

        // The 110 band: the workspace is desktop-first BY DECISION, and the band says so
        // with the pipeline's name and the viewed-version chip — wording without "edit".
        page.locator(".app-wide-screen-note").first().waitFor()
        val band = page.locator(".app-wide-screen-note").first().innerText()
        band shouldContain name.split("/").last()
        band shouldContain "v1"
        band shouldNotContain "edit"

        @Suppress("UNCHECKED_CAST")
        val overflow = page.evaluate(
            """
            () => ({ doc: document.documentElement.scrollWidth - document.documentElement.clientWidth })
            """.trimIndent(),
        ) as Map<String, Any?>
        (overflow["doc"] as Number).toInt() shouldBe 0
        shot("phone-band")
    }
}
