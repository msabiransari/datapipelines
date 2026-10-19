package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 104 §B — the editor dock's height is the user's, in a real browser.
 *
 * The owner, 2026-09-08: *"In the pipeline bottom panel with details, results, errors etc, it
 * has a fixed height. You can min/max it but cannot change the height. I want it to be
 * flexible and the user should be able to drag the height and increase it so that contents
 * are easily visible."*
 *
 * `splitter.test.mjs` owns the arithmetic (clamp, steps, reset) and `graph-stage-resize.test.mjs`
 * owns which calls a resize makes in which state. Neither can say the thing that actually
 * failed for the owner, which is a PIXEL fact about three boxes that have to move together:
 * the dock grows by exactly what the stage loses, and Cytoscape's own viewport goes with it.
 * That last one is the defect this suite exists to refuse — a dock that grows OVER a canvas
 * which never re-lays-out leaves the bottom cards under the dock, and `cy.height()` still
 * reporting the old number is how you tell.
 */
class PipelineEditorDockResizeBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("dock-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("dockws-" + generatedPassword("w").take(8).lowercase())
    }

    /** Four independent CALCULATOR nodes — self-contained seeding, the [PipelineEditorFitBrowserTest] contract. */
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

    private lateinit var editorUrl: String

    private fun openEditorFor(name: String) {
        page.navigate("$baseUrl/pipelines?q=$name")
        // #350: the catalog row IS the link into the workspace (the detail pane and its Open are gone).
        page.locator("#pipeline-list-wrapper a.tpl-result").first().click()
        page.waitForURL(PipelineWorkspaceUrl.PATTERN)
        page.locator(".pe-card").first().waitFor()
        editorUrl = page.url()
    }

    /**
     * The four numbers, read in ONE evaluate so they describe the same frame: reading the dock
     * before a frame and the canvas after it would compare two layouts.
     */
    private val probe =
        """
        () => {
          const dock = document.querySelector('.pe-dock');
          const tabs = document.querySelector('.pe-dock-tabs');
          // #349: the execution identity strip is the dock's first row for a caller with
          // the execution read — the PANE is what the splitter sizes, so the strip comes
          // off the measure.
          const strip = document.querySelector('.pe-run-strip');
          const stripH = strip && getComputedStyle(strip).display !== 'none' ? strip.getBoundingClientRect().height : 0;
          const canvas = document.getElementById('cy-canvas');
          const handle = document.querySelector('[data-splitter="editor-dock"]');
          const cy = window.__peInstance && window.__peInstance.cy;
          return {
            paneH: dock.getBoundingClientRect().height - tabs.getBoundingClientRect().height - stripH,
            dockH: dock.getBoundingClientRect().height,
            canvasH: canvas.getBoundingClientRect().height,
            bodyH: document.querySelector('.pe-body').getBoundingClientRect().height,
            cyH: cy ? cy.height() : null,
            prop: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--pe-dock-pane-h')),
            reducedMotion: window.matchMedia('(prefers-reduced-motion: reduce)').matches,
            valueNow: handle ? Number(handle.getAttribute('aria-valuenow')) : null,
            valueMin: handle ? Number(handle.getAttribute('aria-valuemin')) : null,
            role: handle ? handle.getAttribute('role') : null,
            orientation: handle ? handle.getAttribute('aria-orientation') : null,
            label: handle ? handle.getAttribute('aria-label') : null,
            stored: window.localStorage.getItem('dp.pane.editor-dock'),
            transitionMs: (() => {
              const d = getComputedStyle(dock).transitionDuration;
              return d.endsWith('ms') ? parseFloat(d) : parseFloat(d) * 1000;
            })(),
            handleVisible: handle ? handle.getBoundingClientRect().height > 0 : false,
          };
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun read(): Map<String, Any?> = page.evaluate(probe) as Map<String, Any?>

    private fun Map<String, Any?>.d(k: String) = (this[k] as Number).toDouble()

    /** A real pointer drag on the handle: down, move in steps (so pointermove fires), up. */
    private fun dragHandle(byY: Double) {
        val box = page.locator("[data-splitter='editor-dock']").boundingBox()
        val x = box.x + box.width / 2
        val y = box.y + box.height / 2
        page.mouse().move(x, y)
        page.mouse().down()
        page.mouse().move(x, y + byY, Mouse.MoveOptions().setSteps(12))
        page.mouse().up()
        page.waitForTimeout(150.0)
    }

    private fun cardsOutsideCanvas(): List<String> {
        @Suppress("UNCHECKED_CAST")
        return page.evaluate(
            """() => {
              const c = document.getElementById('cy-canvas').getBoundingClientRect();
              return Array.from(document.querySelectorAll('.pe-card'))
                .map(el => ({ id: el.getAttribute('data-node-id'), r: el.getBoundingClientRect() }))
                .filter(({ r }) => r.top < c.top - 1 || r.bottom > c.bottom + 1)
                .map(({ id, r }) => id + ' [' + Math.round(r.top) + '..' + Math.round(r.bottom) + '] outside canvas ['
                      + Math.round(c.top) + '..' + Math.round(c.bottom) + ']');
            }""",
        ) as List<String>
    }

    private fun shot(name: String) = page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("104-$name.png")))

    private fun shotDir(): Path = Paths.get("build", "reports", "104-screenshots").also { it.toFile().mkdirs() }

    /**
     * The drag cases run at the 1512x982 reference, where the dock STARTS open (#615): at
     * 1440x900 the canvas with the dock open is under `--pe-dock-auto-collapse-below`, so the
     * dock now starts collapsed there — the short-window cases below pin that.
     */
    private fun ready(
        width: Int = REFERENCE_W,
        height: Int = REFERENCE_H,
        remembered: Int? = null,
    ): String {
        loginReadyUser()
        val name = "dock/resize/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        if (remembered != null) page.evaluate("v => localStorage.setItem('dp.pane.editor-dock', String(v))", remembered)
        page.setViewportSize(width, height)
        openEditorFor(name)
        return name
    }

    /** #615: the view controls' whole column is inside the canvas, and the Fit button is what a click there hits. */
    @Suppress("UNCHECKED_CAST")
    private fun controls(): Map<String, Any?> =
        page.evaluate(
            """() => {
              const c = document.getElementById('cy-canvas').getBoundingClientRect();
              const k = document.querySelector('.pe-graph-controls').getBoundingClientRect();
              const b = document.querySelector(".pe-graph-controls button[aria-label='Fit graph to view']").getBoundingClientRect();
              const hit = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
              return {
                inside: k.top >= c.top - 1 && k.bottom <= c.bottom + 1,
                fitHit: !!(hit && hit.closest('.pe-graph-controls')),
                collapsed: document.querySelector('.pe-dock').classList.contains('pe-dock-collapsed'),
                controls: [Math.round(k.top), Math.round(k.bottom)], canvas: [Math.round(c.top), Math.round(c.bottom)],
              };
            }""",
        ) as Map<String, Any?>

    @Test
    fun `on a short window the dock starts collapsed and every view control is inside the canvas`() {
        startTrace()
        ready(1280, 720)
        for ((w, h) in listOf(1280 to 720, 1440 to 900)) {
            page.setViewportSize(w, h)
            page.navigate(editorUrl)
            page.locator(".pe-card").first().waitFor()
            val k = controls()
            val r = read()
            println("615-start ${w}x$h $k $r")
            withClue("the dock did not start collapsed at ${w}x$h: $k") { k["collapsed"] shouldBe true }
            withClue("a view control left the canvas at ${w}x$h: $k") { k["inside"] shouldBe true }
            withClue("the Fit button is covered at ${w}x$h: $k") { k["fitHit"] shouldBe true }
            r.d("canvasH") shouldBeGreaterThanOrEqual CANVAS_FLOOR
        }
        // The click a person makes — the one that timed out under the heading before #615.
        page.locator(".pe-graph-controls button[aria-label='Fit graph to view']").click()
    }

    @Test
    fun `opening the dock on a short window leaves the canvas its floor`() {
        startTrace()
        ready(1280, 720)
        controls()["collapsed"] shouldBe true
        page.click("#pe-dock-tab-details")
        page.waitForFunction("() => !document.querySelector('.pe-dock').classList.contains('pe-dock-collapsed')")
        page.waitForTimeout(400.0) // the collapse transition (0.2s) settles
        val r = read()
        val k = controls()
        println("615-open-short $r $k")
        withClue("the canvas went under its floor when the dock opened: $r") { r.d("canvasH") shouldBeGreaterThanOrEqual CANVAS_FLOOR - 1 }
        withClue("the dock did not give way on a short window: $r") { r.d("paneH") shouldBeLessThanOrEqual DEFAULT_PANE - 1 }
        withClue("the pane is not usable: $r") { r.d("paneH") shouldBeGreaterThanOrEqual 100.0 }
        withClue("a view control left the canvas with the dock open: $k") { k["inside"] shouldBe true }
        withClue("the Fit button is covered with the dock open: $k") { k["fitHit"] shouldBe true }
        // A keyboard step starts from the row the person SEES: with the property still at 232
        // and the row capped, a step from the property would land above the row and move nothing.
        page.locator("[data-splitter='editor-dock']").focus()
        page.keyboard().press("ArrowDown")
        page.waitForTimeout(150.0)
        val stepped = read()
        withClue("ArrowDown did not shrink the visible pane by one step: $r -> $stepped") {
            (r.d("paneH") - stepped.d("paneH")) shouldBeGreaterThanOrEqual 15.0
            (r.d("paneH") - stepped.d("paneH")) shouldBeLessThanOrEqual 17.0
        }
        page.locator(".pe-graph-controls button[aria-label='Fit graph to view']").click()
    }

    @Test
    fun `at the 1512x982 reference the dock starts open at its default`() {
        startTrace()
        ready()
        val k = controls()
        val r = read()
        println("615-reference $k $r")
        k["collapsed"] shouldBe false
        r.d("paneH") shouldBeGreaterThanOrEqual DEFAULT_PANE - 1
        r.d("paneH") shouldBeLessThanOrEqual DEFAULT_PANE + 1
        k["inside"] shouldBe true
    }

    @Test
    fun `an untouched dock follows the window, and a gesture makes the state the person's`() {
        startTrace()
        ready(1280, 720)
        controls()["collapsed"] shouldBe true
        // Grown before anyone touched the dock: the default re-decides (#615's watch).
        page.setViewportSize(REFERENCE_W, REFERENCE_H)
        page.waitForFunction("() => !document.querySelector('.pe-dock').classList.contains('pe-dock-collapsed')")
        page.setViewportSize(1280, 720)
        page.waitForFunction("() => document.querySelector('.pe-dock').classList.contains('pe-dock-collapsed')")
        // A tab click is a gesture: from here the window no longer moves the dock.
        page.click("#pe-dock-tab-details")
        page.waitForFunction("() => !document.querySelector('.pe-dock').classList.contains('pe-dock-collapsed')")
        page.setViewportSize(1440, 900)
        page.setViewportSize(1280, 720)
        page.waitForTimeout(400.0) // two resize-observer turns and the 0.2s transition
        withClue("the window moved a dock the person had opened: ${controls()}") { controls()["collapsed"] shouldBe false }
        controls()["inside"] shouldBe true
    }

    @Test
    fun `a remembered pane height keeps the dock open on a short window`() {
        startTrace()
        ready(1280, 720, remembered = 200)
        val k = controls()
        val r = read()
        println("615-remembered $k $r")
        withClue("the person's own height did not keep the dock open: $k") { k["collapsed"] shouldBe false }
        withClue("the canvas went under its floor: $r") { r.d("canvasH") shouldBeGreaterThanOrEqual CANVAS_FLOOR - 1 }
        k["inside"] shouldBe true
        k["fitHit"] shouldBe true
    }

    @Test
    fun `dragging the handle 150px up grows the dock by 150 and shrinks the canvas by 150, and Cytoscape follows`() {
        startTrace()
        ready()

        val before = read()
        before["role"] shouldBe "separator"
        before["orientation"] shouldBe "horizontal"
        before["label"] shouldBe "Resize results panel"
        before.d("paneH") shouldBeGreaterThanOrEqual DEFAULT_PANE - 1
        before.d("paneH") shouldBeLessThanOrEqual DEFAULT_PANE + 1
        shot("dock-default")

        dragHandle(-150.0)
        val after = read()

        withClue("the dock did not grow by the drag: $before -> $after") {
            (after.d("paneH") - before.d("paneH")) shouldBeGreaterThanOrEqual 149.0
            (after.d("paneH") - before.d("paneH")) shouldBeLessThanOrEqual 151.0
        }
        withClue("the canvas did not give up what the dock took: $before -> $after") {
            (before.d("canvasH") - after.d("canvasH")) shouldBeGreaterThanOrEqual 149.0
            (before.d("canvasH") - after.d("canvasH")) shouldBeLessThanOrEqual 151.0
        }
        // THE defect: a dock that grows over a canvas Cytoscape never re-measured.
        withClue("cy.height() did not follow the container: $after") {
            (after.d("cyH") - after.d("canvasH")) shouldBeGreaterThanOrEqual -2.0
            (after.d("cyH") - after.d("canvasH")) shouldBeLessThanOrEqual 2.0
        }
        withClue("a card is under the dock after the resize") { cardsOutsideCanvas().shouldBeEmpty() }
        shot("dock-dragged-taller")
    }

    @Test
    fun `the floor and the ceiling hold, and the canvas keeps a readable strip`() {
        startTrace()
        ready()
        val before = read()

        dragHandle(5000.0)
        val atFloor = read()
        withClue("the dock went below its 120px floor: $atFloor") {
            atFloor.d("paneH") shouldBeGreaterThanOrEqual DOCK_MIN - 1
            atFloor.d("paneH") shouldBeLessThanOrEqual DOCK_MIN + 1
        }
        shot("dock-min")

        dragHandle(-5000.0)
        val atCeiling = read()
        // The ceiling is stated against the STAGE's own box, which is what `.pe-body` measures
        // and what the splitter's bounds read — not against `#cy-canvas`, which is a smaller
        // box inside it and would make the assertion looser than the rule.
        val total = before.d("bodyH") + before.d("paneH")
        withClue("the dock ate the canvas: $atCeiling (body+pane was $total)") {
            atCeiling.d("bodyH") shouldBeGreaterThanOrEqual CANVAS_FLOOR - 1.0
            atCeiling.d("paneH") shouldBeLessThanOrEqual total - CANVAS_FLOOR + 1.0
        }
        withClue("a card is under the dock at the ceiling") { cardsOutsideCanvas().shouldBeEmpty() }
        shot("dock-max")
    }

    @Test
    fun `the handle resizes from the keyboard and publishes its value`() {
        startTrace()
        ready()
        val handle = page.locator("[data-splitter='editor-dock']")
        handle.focus()
        val before = read()

        handle.press("ArrowUp")
        page.waitForTimeout(80.0)
        val stepped = read()
        withClue("ArrowUp did not step 16px: $before -> $stepped") {
            (stepped.d("paneH") - before.d("paneH")) shouldBeGreaterThanOrEqual 15.0
            (stepped.d("paneH") - before.d("paneH")) shouldBeLessThanOrEqual 17.0
        }
        handle.press("Shift+ArrowUp")
        page.waitForTimeout(80.0)
        val big = read()
        withClue("Shift+ArrowUp did not step 64px: $stepped -> $big") {
            (big.d("paneH") - stepped.d("paneH")) shouldBeGreaterThanOrEqual 63.0
            (big.d("paneH") - stepped.d("paneH")) shouldBeLessThanOrEqual 65.0
        }
        withClue("aria-valuenow does not describe the pane: $big") {
            (big.d("valueNow") - big.d("paneH")) shouldBeLessThanOrEqual 2.0
            (big.d("paneH") - big.d("valueNow")) shouldBeLessThanOrEqual 2.0
        }
        big.d("valueMin") shouldBe DOCK_MIN

        handle.press("Home")
        page.waitForTimeout(80.0)
        withClue("Home did not park on the floor") { read().d("paneH") shouldBeLessThanOrEqual DOCK_MIN + 1 }
    }

    @Test
    fun `a dragged height survives a reload, and a double-click gives the default back`() {
        startTrace()
        ready()
        dragHandle(-150.0)
        val dragged = read()
        // The stored number is the PROPERTY the splitter wrote; `paneH` is the rendered row,
        // which carries the dock's 1px `border-top` on top of it. Compare like with like.
        dragged["stored"] shouldBe dragged.d("prop").toInt().toString()

        page.navigate(editorUrl)
        page.locator(".pe-card").first().waitFor()
        val reloaded = read()
        withClue("the remembered height did not survive the reload: $dragged -> $reloaded") {
            (reloaded.d("paneH") - dragged.d("paneH")) shouldBeLessThanOrEqual 1.0
            (dragged.d("paneH") - reloaded.d("paneH")) shouldBeLessThanOrEqual 1.0
        }

        page.locator("[data-splitter='editor-dock']").dblclick()
        page.waitForTimeout(150.0)
        val reset = read()
        withClue("double-click did not restore the stylesheet default: $reset") {
            reset.d("paneH") shouldBeGreaterThanOrEqual DEFAULT_PANE - 1
            reset.d("paneH") shouldBeLessThanOrEqual DEFAULT_PANE + 1
        }
        reset["stored"] shouldBe null
    }

    @Test
    fun `collapse hides the handle and expand restores the dragged height`() {
        startTrace()
        ready()
        dragHandle(-120.0)
        val dragged = read()

        page.locator(".pe-dock-toggle[aria-label='Collapse dock']").click()
        page.waitForTimeout(300.0)
        val collapsed = read()
        withClue("a collapsed dock still shows a resize handle: $collapsed") { collapsed["handleVisible"] shouldBe false }
        withClue("the collapsed dock kept its pane row: $collapsed") { collapsed.d("paneH") shouldBeLessThanOrEqual 2.0 }

        page.locator(".pe-dock-toggle[aria-label='Restore dock']").click()
        page.waitForTimeout(400.0)
        val restored = read()
        withClue("expand did not restore the dragged height: $dragged -> $restored") {
            (restored.d("paneH") - dragged.d("paneH")) shouldBeLessThanOrEqual 1.5
            (dragged.d("paneH") - restored.d("paneH")) shouldBeLessThanOrEqual 1.5
        }
    }

    @Test
    fun `under prefers-reduced-motion the dock has no transition to lag behind the pointer`() {
        startTrace()
        ready()

        // Non-vacuity FIRST: with motion allowed the dock really does animate its height (the
        // 200ms `grid-template-rows` transition the collapse uses). Without this line the
        // assertion below would pass just as happily on a dock that never had a transition.
        withClue("the dock has no height transition at all, so the reduced-motion case is vacuous") {
            read().d("transitionMs") shouldBeGreaterThanOrEqual 150.0
        }

        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        val m = read()
        withClue("prefers-reduced-motion was not emulated, so the assertion below proves nothing: $m") {
            m["reducedMotion"] shouldBe true
        }
        // NOT `== "0s"`: the vendored design system zeroes motion with
        // `transition-duration: 0.01ms !important`, which computes to 1e-05s. The contract is
        // "no perceptible animation", and 0.01ms is how the design system states it — asserting
        // the literal 0s would have been asserting our own (unwinnable) rule instead of the
        // effect. Anything at or below a millisecond cannot lag a pointer.
        withClue("the dock still animates its height under reduced motion: $m") {
            m.d("transitionMs") shouldBeLessThanOrEqual 1.0
        }
    }

    private fun withClue(
        clue: String,
        block: () -> Unit,
    ) = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError(clue, e)
    }

    private companion object {
        /** `pipeline-editor.css`'s `--pe-dock-pane-h` default. */
        const val DEFAULT_PANE = 232.0

        /** `--pe-dock-min-h`, and the strip of canvas the dock may never eat (`--pe-dock-canvas-floor`). */
        const val DOCK_MIN = 120.0
        const val CANVAS_FLOOR = 180.0

        /** The 1512x982 reference viewport, where the dock starts open. */
        const val REFERENCE_W = 1512
        const val REFERENCE_H = 982
    }
}
