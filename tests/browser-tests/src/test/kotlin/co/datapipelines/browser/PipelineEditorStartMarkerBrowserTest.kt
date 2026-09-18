package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 151 addendum in the browser (issue #144): the Start disc RUNS the pipeline.
 *
 *  1. A member who may execute sees the disc as a real button (`role="button"`, named
 *     "Start execution"); clicking it starts the run through the same `executePipeline()`
 *     the toolbar uses — the toolbar button goes disabled, the disc reads Running… and is
 *     `aria-disabled`; the run completes and End reads Finished with the elapsed time.
 *  2. A VIEWER — the product's rule is D-R3, "viewers execute": every workspace member may
 *     run what they can read — gets the same button, and activates it from the KEYBOARD
 *     (focus, Enter). The brief's premise that a viewer sees no button was checked against
 *     `RoleModel.roles` and is false in a browser session; the `canExecute=false` render is
 *     the template contract `RoleVisibilityRenderTest` pins, and graph-markers.test.mjs pins
 *     the marker it draws.
 *  3. Light and dark, wide and narrow: screenshots for the evidence record.
 *
 * Screenshots land in `build/reports/151-screenshots/`; the assertions fail the build.
 */
class PipelineEditorStartMarkerBrowserTest : BrowserSuite() {
    @Test
    fun `the Start disc is a button that runs the pipeline - End takes the outcome and the elapsed time`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("sm-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val pipelineId = seedPipeline(page, "sm")
        // The shots are named by theme, so the theme is SET, not assumed (a seeded user's
        // default is whatever the profile says).
        ensureTheme("light")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='stg']").waitFor()

        val disc = page.locator(".pe-card-boundary-start .pe-marker")
        disc.getAttribute("role") shouldBe "button"
        disc.getAttribute("aria-label") shouldBe "Start execution"
        disc.getAttribute("tabindex") shouldBe "0"
        disc.getAttribute("aria-disabled") shouldBe null
        page.locator("[data-verb='pipeline-execute']").count() shouldBe 1
        page.locator(".pe-card-boundary-end .pe-marker").getAttribute("role") shouldBe "img"
        shoot("start-idle-light-wide")
        shootClose("start-idle-close", "__execution_start__")

        // Click the disc — not the toolbar. One DOM read per poll: the word, the disc's
        // aria-disabled and the toolbar button's disabled must hold AT THE SAME INSTANT —
        // the run is short, and a read after it ended would compare two different moments.
        disc.click()
        waitUntil("the disc reads Running…, is aria-disabled, and the toolbar button is disabled with it") {
            page.evaluate(
                "() => { const word = document.querySelector('.pe-card-boundary-start .pe-marker-word');" +
                    " const d = document.querySelector('.pe-card-boundary-start .pe-marker');" +
                    " const btn = document.querySelector(\"[data-verb='pipeline-execute']\");" +
                    " return !!(word && word.textContent.trim() === 'Running…' && d && d.getAttribute('aria-disabled') === 'true' && btn && btn.disabled); }",
            ) == true
        }
        shoot("start-running-light-wide")

        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        page.locator(".pe-card-boundary-end .pe-marker-word").innerText().trim() shouldBe "Finished"
        page.locator(".pe-card-boundary-end .pe-marker-elapsed").innerText().trim() shouldMatch ELAPSED
        page.locator(".pe-card-boundary-end .pe-marker").getAttribute("aria-label")!! shouldStartWith "Execution end — finished in "
        page.locator(".pe-card-boundary-start .pe-marker-word").innerText().trim() shouldBe "Start"
        page.locator(".pe-card-boundary-start .pe-marker").getAttribute("aria-disabled") shouldBe null
        shoot("end-finished-light-wide")
        shootClose("end-finished-close", "__execution_end__")

        // The same page in dark, and at a narrow desktop width.
        ensureTheme("dark")
        page.locator(".pe-card-boundary-end .pe-marker-word").waitFor()
        shoot("end-finished-dark-wide")
        shootClose("end-finished-dark-close", "__execution_end__")
        shootClose("start-idle-dark-close", "__execution_start__")
        page.setViewportSize(1024, 720)
        // Fit through the graph's own API: at this width the rail overlaps the stage's
        // bottom-right controls, so a pointer click on Fit lands on the rail.
        page.evaluate("() => window.__peInstance && window.__peInstance.graph && window.__peInstance.graph.fitToView()")
        page.waitForTimeout(400.0)
        shoot("end-finished-dark-narrow")
        ensureTheme("light")
        page.locator(".pe-card-boundary-end .pe-marker-word").waitFor()
        shoot("end-finished-light-narrow")
    }

    @Test
    fun `a viewer gets the same Start button and runs the pipeline from the keyboard`() {
        startTrace()
        // The pipeline is seeded by an admin in the shared `default` workspace…
        val admin = seedLocalUser(uniqueEmail("sma-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        val pipelineId = seedPipeline(page, "smv")

        // …and opened by a VIEWER of that workspace (no author, promoter or admin flag).
        val viewer =
            seedLocalUser(
                uniqueEmail("smv-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                author = false,
                promoter = false,
                admin = false,
            )
        val session = newSession()
        try {
            val vp = session.page
            vp.navigate("$baseUrl/login")
            vp.fill("#login-email", viewer.email)
            vp.fill("#login-password", viewer.oneTimePassword)
            vp.click("form button[type=submit]")
            vp.waitForURL("**/dashboard")
            vp.navigate("$baseUrl/pipelines/$pipelineId/editor")
            vp.locator(".pe-card[data-node-id='stg']").waitFor()
            vp.locator("[data-role-note='read-only']").count() shouldBe 1

            val disc = vp.locator(".pe-card-boundary-start .pe-marker")
            disc.getAttribute("role") shouldBe "button"
            disc.getAttribute("aria-label") shouldBe "Start execution"
            vp.locator("[data-verb='pipeline-execute']").count() shouldBe 1

            // Keyboard: focus the disc and press Enter.
            disc.focus()
            vp.evaluate("() => document.activeElement && document.activeElement.classList.contains('pe-marker-run')") shouldBe true
            vp.keyboard().press("Enter")
            waitUntil(vp, "the viewer's run started from the keyboard") {
                vp.locator(".pe-card-boundary-start .pe-marker-word").innerText().trim() == "Running…"
            }
            vp.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
            vp.locator(".pe-card-boundary-end .pe-marker-word").innerText().trim() shouldBe "Finished"
            vp.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("151-viewer-keyboard-finished.png")).setFullPage(true))
        } finally {
            session.close()
        }
    }

    /** A modest fan-out run (60k rows: a few seconds, long enough to be seen Running…) — the graph shape 151 documents. */
    private fun seedPipeline(
        page: Page,
        slug: String,
    ): String {
        val datasource = "$slug-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val name = "test/${slug}_" + generatedPassword("p").take(8).lowercase()
        return EditorRunFixtures.createFanOutPipeline(page, name, datasource, rows = 60_000)
    }

    private fun waitUntil(
        what: String,
        done: () -> Boolean,
    ) = waitUntil(page, what, done)

    private fun waitUntil(
        on: Page,
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + EXECUTION_TIMEOUT_MS.toLong()
        while (System.currentTimeMillis() < deadline) {
            val ok =
                try {
                    done()
                } catch (_: Exception) {
                    false
                }
            if (ok) return
            Thread.sleep(POLL_MS)
        }
        error("never saw: $what (on ${on.url()})")
    }

    private fun shoot(state: String) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("151-$state.png")).setFullPage(true))
    }

    /** The stage at zoom 1 centred on [nodeId] (the cards and shapes at their real size), then back to the fit. */
    private fun shootClose(
        state: String,
        nodeId: String,
    ) {
        page.evaluate(
            "(id) => { const cy = document.getElementById('cy-canvas')._cyreg.cy; cy.zoom(1); cy.center(cy.getElementById(id)); }",
            nodeId,
        )
        page.locator(".pe-stage").screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("151-$state.png")))
        page.evaluate("() => window.__peInstance && window.__peInstance.graph && window.__peInstance.graph.fitToView()")
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "151-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        const val EXECUTION_TIMEOUT_MS = 180_000.0
        const val POLL_MS = 40L
        val ELAPSED = Regex("\\d+ ms|\\d+\\.\\d s|\\d+m \\d+s")
    }
}
