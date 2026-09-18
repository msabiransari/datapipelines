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
 * 151 addendum in the browser (issue #144): the Start disc RUNS the pipeline; 159 (#148):
 * a HUMAN press runs it, and while it runs the disc is the Cancel control.
 *
 *  1. A member who may execute sees the disc as a real button (`role="button"`, named
 *     "Start execution"); clicking it starts the run through the same `executePipeline()`
 *     the toolbar uses — the toolbar button goes disabled and the disc becomes CANCEL
 *     (159: named "Cancel execution", the word Cancel, never `aria-disabled`) beside the
 *     toolbar's own Cancel; the run completes and End reads Finished with the elapsed time.
 *  2. THE HELD PRESS (159/#148, the owner's "Start does nothing"): Playwright's `click()`
 *     releases the button ~2 ms after pressing it; a hand holds it 50–150 ms. In that window
 *     Cytoscape's mousedown `activate()`d the marker node, the html-label re-rendered the
 *     disc, and the mouseup landed on a NEW element — no click was ever dispatched. So this
 *     suite presses the way a hand does (`mouse().down()`, a hold, `mouse().up()`): the run
 *     starts, and the same press on the Cancel disc stops it — asserted on the PERSISTED
 *     execution (ABORTED from the executions API), with the disc and the toolbar agreeing
 *     at every step and focus staying on the disc across its re-render. Red on the tree
 *     before the press guard (graph.js wireMarkerActivation), by construction.
 *     (No boosted-arrival arm: the editor is a full document load by decision — a boosted
 *     swap initialises its Alpine root before the editor scripts define `pipelineEditor`
 *     and the page has no canvas at all, pipeline-detail.html; measured again 2026-09-17.)
 *  3. A VIEWER — the product's rule is D-R3, "viewers execute": every workspace member may
 *     run what they can read — gets the same button, and activates it from the KEYBOARD
 *     (focus, Enter); the focus is still on the disc once the run is live (159's keeper).
 *     The brief's premise that a viewer sees no button was checked against
 *     `RoleModel.roles` and is false in a browser session; the `canExecute=false` render is
 *     the template contract `RoleVisibilityRenderTest` pins, and graph-markers.test.mjs pins
 *     the marker it draws.
 *  4. Light and dark, wide and narrow: screenshots for the evidence record.
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
        // name and the toolbar's state must hold AT THE SAME INSTANT — the run is short,
        // and a read after it ended would compare two different moments.
        disc.click()
        waitUntil("the disc is the Cancel control and the toolbar reads Running… with its Cancel beside it") { runningAgrees(page) }
        shoot("start-running-light-wide")

        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        page.locator(".pe-card-boundary-end .pe-marker-word").innerText().trim() shouldBe "Finished"
        page.locator(".pe-card-boundary-end .pe-marker-elapsed").innerText().trim() shouldMatch ELAPSED
        page.locator(".pe-card-boundary-end .pe-marker").getAttribute("aria-label")!! shouldStartWith "Execution end — finished in "
        idleAgrees(page) shouldBe true
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
            waitUntil(vp, "the viewer's run started from the keyboard") { runningAgrees(vp) }
            // 159: the html-label re-rendered the disc as Cancel; the keeper handed focus to
            // the replacement, so the next Enter would cancel — the keyboard never lost its place.
            vp.evaluate("() => document.activeElement && document.activeElement.classList.contains('pe-marker-cancel')") shouldBe true
            vp.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
            vp.locator(".pe-card-boundary-end .pe-marker-word").innerText().trim() shouldBe "Finished"
            vp.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("151-viewer-keyboard-finished.png")).setFullPage(true))
        } finally {
            session.close()
        }
    }

    @Test
    fun `a held press on the disc starts the run and a held press on Cancel stops it - recorded ABORTED, disc and toolbar agreeing`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("smh-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val pipelineId = seedPipeline(page, "smh", rows = SLOW_ROWS)
        ensureTheme("light")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='stg']").waitFor()
        idleAgrees(page) shouldBe true
        shoot("held-idle")

        heldPress(page)
        waitUntil("a held press started the run: the disc is Cancel, the toolbar Running… + Cancel") { runningAgrees(page) }
        page.evaluate("() => document.activeElement === document.querySelector('.pe-card-boundary-start .pe-marker')") shouldBe true
        val execId = executionIdOf(page)
        check(execId != null) { "the run started but the page holds no execution id" }
        shoot("held-running-cancel")
        shootClose("held-running-cancel-close", "__execution_start__")

        // The same hand, the same disc: now it cancels. The DELETE is the toolbar's own path
        // (cancelExecution → sseHandler.cancel); the record is the executions API, not the DOM.
        heldPress(page)
        val server = awaitTerminalStatus(page, execId)
        server shouldBe "ABORTED"
        // The page's own settle needs the live `execution_aborted` frame, which #143 shows
        // is not delivered under multi-execution load (persisted and replayable, never
        // streamed; the client's 5 s fallback aborts its reader, and an AbortError ends
        // the read without the recovery poll — sse.js cancel/readStream). When it arrives
        // the disc and the toolbar settle together and focus is still on the disc; when it
        // does not, the diagnostic says so and the agreement is read off a reload — the
        // record above, not the DOM, is this arm's proof of the cancel.
        val settledLive = awaitIdle(page)
        if (settledLive) {
            page.locator(".pe-card-boundary-end .pe-marker-word").innerText().trim() shouldBe "Stopped"
            page.evaluate("() => document.activeElement === document.querySelector('.pe-card-boundary-start .pe-marker')") shouldBe true
        } else {
            println("159-cancel-delivery=missed execution=$execId replayAbort=${replayCarriesAbort(page, execId)} (#143)")
            page.reload()
            page.locator(".pe-card[data-node-id='stg']").waitFor()
        }
        idleAgrees(page) shouldBe true
        shoot(if (settledLive) "held-stopped" else "held-stopped-after-reload")
    }

    /** A fan-out run — 60k rows is a few seconds; [SLOW_ROWS] is long enough to cancel by hand — the graph shape 151 documents. */
    private fun seedPipeline(
        page: Page,
        slug: String,
        rows: Int = 60_000,
    ): String {
        val datasource = "$slug-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val name = "test/${slug}_" + generatedPassword("p").take(8).lowercase()
        return EditorRunFixtures.createFanOutPipeline(page, name, datasource, rows = rows)
    }

    /**
     * A press the way a hand makes it: down, a hold, up — [HOLD_MS] between them. Playwright's
     * `click()` releases within ~2 ms and never saw #148 (the disc was re-rendered under a
     * held button); this is the input that did.
     */
    private fun heldPress(on: Page) {
        val box = on.locator(".pe-card-boundary-start .pe-marker").boundingBox()
        val cx = box.x + box.width / 2
        val cy = box.y + box.height / 2
        on.mouse().move(cx, cy)
        on.waitForTimeout(60.0)
        on.mouse().down()
        on.waitForTimeout(HOLD_MS)
        on.mouse().up()
    }

    /** One read, one instant: the disc is Cancel exactly while the toolbar is Running… with its Cancel shown. */
    private fun runningAgrees(on: Page): Boolean =
        on.evaluate(
            "() => { const word = document.querySelector('.pe-card-boundary-start .pe-marker-word');" +
                " const d = document.querySelector('.pe-card-boundary-start .pe-marker');" +
                " const btn = document.querySelector(\"[data-verb='pipeline-execute']\");" +
                " const cancel = document.querySelector(\"[data-verb='execution-cancel']\");" +
                " return !!(word && word.textContent.trim() === 'Cancel'" +
                " && d && d.classList.contains('pe-marker-cancel') && d.getAttribute('aria-label') === 'Cancel execution'" +
                " && d.getAttribute('role') === 'button' && d.getAttribute('aria-disabled') === null" +
                " && btn && btn.disabled && cancel && getComputedStyle(cancel).display !== 'none'); }",
        ) == true

    /** The idle agreement: Start on the disc, Execute enabled on the toolbar, no Cancel anywhere. */
    private fun idleAgrees(on: Page): Boolean =
        on.evaluate(
            "() => { const word = document.querySelector('.pe-card-boundary-start .pe-marker-word');" +
                " const d = document.querySelector('.pe-card-boundary-start .pe-marker');" +
                " const btn = document.querySelector(\"[data-verb='pipeline-execute']\");" +
                " const cancel = document.querySelector(\"[data-verb='execution-cancel']\");" +
                " return !!(word && word.textContent.trim() === 'Start'" +
                " && d && !d.classList.contains('pe-marker-cancel') && d.getAttribute('aria-label') === 'Start execution'" +
                " && d.getAttribute('aria-disabled') === null" +
                " && btn && !btn.disabled && (!cancel || getComputedStyle(cancel).display === 'none')); }",
        ) == true

    /** The page's own return to idle (Execute enabled again) within the cancel bound, without failing the arm. */
    private fun awaitIdle(on: Page): Boolean {
        val deadline = System.currentTimeMillis() + CANCEL_SETTLE_MS.toLong()
        while (System.currentTimeMillis() < deadline) {
            if (idleAgrees(on)) return true
            Thread.sleep(POLL_MS * 5)
        }
        return false
    }

    /** Did the SERVER emit the abort at all? The §10.3 replay log is the record; the live stream is the delivery. */
    private fun replayCarriesAbort(
        on: Page,
        execId: String,
    ): String =
        on.evaluate(
            """async (id) => {
              try {
                const res = await fetch('/api/v1/executions/' + id + '/events', { credentials: 'same-origin' });
                if (!res.ok) return 'http-' + res.status;
                return (await res.text()).includes('execution_aborted') ? 'yes' : 'no';
              } catch (e) { return 'err'; }
            }""",
            execId,
        ) as String

    private fun executionIdOf(on: Page): String? =
        on.evaluate(
            "() => window.__peInstance && window.__peInstance.sseHandler ? window.__peInstance.sseHandler.executionId : null",
        ) as String?

    /** The persisted status, polled until terminal: the executions API is the record, the stream only the delivery. */
    private fun awaitTerminalStatus(
        on: Page,
        execId: String,
    ): String {
        val deadline = System.currentTimeMillis() + CANCEL_SETTLE_MS.toLong()
        var status = "unknown"
        while (System.currentTimeMillis() < deadline) {
            status =
                on.evaluate(
                    """async (id) => {
                      const res = await fetch('/api/v1/executions/' + id, { credentials: 'same-origin' });
                      if (!res.ok) return 'http-' + res.status;
                      const data = await res.json();
                      return (data.data || data).status;
                    }""",
                    execId,
                ) as String
            if (status == "ABORTED" || status == "SUCCESS" || status == "FAILED") return status
            Thread.sleep(POLL_MS * 5)
        }
        return status
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

        /** A hand's hold between press and release — inside the window the re-render used to open. */
        const val HOLD_MS = 150.0

        /** Enough rows to stage for well over the cancel latency (the 150 branching fixture's slow branch). */
        const val SLOW_ROWS = 2_000_000

        /** The cancel contract's latency bound (§8.3.1, one poll tick plus slack), as the boundaries suite waits. */
        const val CANCEL_SETTLE_MS = 45_000.0
    }
}
