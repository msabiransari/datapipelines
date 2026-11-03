package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs

/**
 * #783 — the dashboard board's activity dock is sized by the SHARED splitter (`splitter.js`),
 * the third pane after the editor's dock and the explorers' tree, in a real browser.
 *
 * Before #783 the board carried its own page-local drag: a 6px button whose hit area was its
 * top pixel row, a 96–480 clamp no other pane used, and a teardown that stripped the dragged
 * height on every in-shell leave, so a height was lost on Back and on reload. `splitter.test.mjs`
 * owns the arithmetic (`boardDockBounds`, the clamp, the steps); this suite owns the PIXEL facts
 * that arithmetic cannot state: the pane the person reads moves by what the board gives up, the
 * board keeps its two-row floor (`--dp-board-floor`), and the remembered height is the height of
 * the first frame after a reload, a leave-and-Back and a tab switch.
 *
 * The exemplar is [PipelineEditorDockResizeBrowserTest]; the cases mirror its floor/ceiling,
 * keyboard, reload/reset and collapse cases on the board.
 */
class DashboardDockResizeBrowserTest : DashboardBrowserSuite() {
    private lateinit var boardUrl: String

    /**
     * A fresh admin, [seedBoardWithGrid]'s board (no parameter, so the filters state is `none`
     * and the toolbar is not a row), the 1512x982 reference, and the board open on its Board tab.
     * [remembered] is written to `dp.pane.board-dock` before the board's first load.
     */
    private fun openBoard(remembered: Int? = null) {
        val root = ready("dp783r")
        val board = seedBoardWithGrid(root)
        if (remembered != null) page.evaluate("v => localStorage.setItem('$STORAGE_KEY', String(v))", remembered)
        page.setViewportSize(REFERENCE_W, REFERENCE_H)
        boardUrl = "$baseUrl/dashboards/$board"
        page.navigate(boardUrl)
        boardReady()
    }

    private fun boardReady() {
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.waitForSelector("#dp-pane-board:not([hidden])")
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
    }

    /**
     * Every number one case reads, in ONE evaluate so they describe the same frame. `paneH` is
     * the dock BODY the splitter sizes (`.dp-board-dock-body`, border-box: its height is the
     * property); `layoutH` is the board row the pane shares its space with.
     */
    private val probe =
        """
        () => {
          const dock = document.getElementById('dp-board-dock');
          const body = dock.querySelector('.dp-board-dock-body');
          const layout = document.querySelector('.dp-board-layout');
          const handle = document.querySelector('[data-splitter="board-dock"]');
          const main = document.getElementById('app-main');
          const hr = handle ? handle.getBoundingClientRect() : null;
          const hit = hr && hr.height > 0 ? document.elementFromPoint(hr.x + hr.width / 2, hr.y + hr.height / 2) : null;
          return {
            paneH: body.getBoundingClientRect().height,
            layoutH: layout.getBoundingClientRect().height,
            prop: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--dp-dock-pane-h')),
            valueNow: handle ? Number(handle.getAttribute('aria-valuenow')) : null,
            valueMin: handle ? Number(handle.getAttribute('aria-valuemin')) : null,
            valueMax: handle ? Number(handle.getAttribute('aria-valuemax')) : null,
            role: handle ? handle.getAttribute('role') : null,
            orientation: handle ? handle.getAttribute('aria-orientation') : null,
            label: handle ? handle.getAttribute('aria-label') : null,
            tabindex: handle ? handle.getAttribute('tabindex') : null,
            stored: window.localStorage.getItem('$STORAGE_KEY'),
            handleH: hr ? hr.height : 0,
            centreHit: !!(hit && hit.closest('[data-splitter="board-dock"]')),
            collapsed: dock.hasAttribute('data-dp-dock-collapsed'),
            overflow: main.scrollHeight - main.clientHeight,
            dockBottom: dock.getBoundingClientRect().bottom,
            mainBottom: main.getBoundingClientRect().bottom,
          };
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun read(): Map<String, Any?> = page.evaluate(probe) as Map<String, Any?>

    private fun Map<String, Any?>.d(k: String) = (this[k] as Number).toDouble()

    /** The handle's CENTRE, down, steps, up — the base handle answered only on its top row (A.0). */
    private fun dragHandle(byY: Double) {
        val box = page.locator("[data-splitter='board-dock']").boundingBox()
        val x = box.x + box.width / 2
        val y = box.y + box.height / 2
        page.mouse().move(x, y)
        page.mouse().down()
        page.mouse().move(x, y + byY, Mouse.MoveOptions().setSteps(12))
        page.mouse().up()
        page.waitForTimeout(150.0)
    }

    private fun near(
        actual: Double,
        expected: Double,
        tolerance: Double = 1.0,
    ) = abs(actual - expected) <= tolerance

    private fun shot(name: String) = page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("783-$name.png")))

    private fun shotDir(): Path = Paths.get("build", "browser-evidence").also { Files.createDirectories(it) }

    @Test
    fun `the grip is a labelled separator a pointer hits at its centre, over a pane at its 200px default`() {
        startTrace()
        openBoard()
        val r = read()
        println("783-default $r")
        r["role"] shouldBe "separator"
        r["orientation"] shouldBe "horizontal"
        r["label"] shouldBe "Resize the activity dock"
        r["tabindex"] shouldBe "0"
        withClue("the pane did not open at the stylesheet default: $r") { near(r.d("paneH"), DEFAULT_PANE) shouldBe true }
        withClue("the grip is not a strip a pointer can find: $r") {
            r.d("handleH") shouldBeGreaterThanOrEqual GRIP_STRIP_MIN
            r["centreHit"] shouldBe true
        }
        r.d("valueMin") shouldBe DOCK_MIN
        withClue("aria-valuemax is not the board-floor ceiling: $r") {
            near(r.d("valueMax"), r.d("layoutH") + r.d("paneH") - BOARD_FLOOR) shouldBe true
        }
        withClue("aria-valuenow does not describe the pane: $r") { near(r.d("valueNow"), r.d("paneH")) shouldBe true }
        shot("dock-default")
    }

    @Test
    fun `dragging the grip 150px up grows the pane by what the board gives up, and remembers it`() {
        startTrace()
        openBoard()
        val before = read()
        dragHandle(-150.0)
        val after = read()
        println("783-drag $before -> $after")
        withClue("the pane did not grow by the drag: $before -> $after") {
            near(after.d("paneH") - before.d("paneH"), 150.0) shouldBe true
        }
        withClue("the board did not give up what the pane took: $before -> $after") {
            near(before.d("layoutH") - after.d("layoutH"), 150.0) shouldBe true
        }
        withClue("the drag pushed main past its box: $after") {
            after.d("overflow") shouldBe 0.0
            after.d("dockBottom") shouldBeLessThanOrEqual after.d("mainBottom")
        }
        after["stored"] shouldBe after.d("prop").toInt().toString()
        shot("dock-dragged")
    }

    @Test
    fun `the floor and the ceiling hold - 120px of pane, two grid rows of board, and nothing at 480`() {
        startTrace()
        openBoard()
        val before = read()
        val total = before.d("layoutH") + before.d("paneH")
        // Non-vacuity: the reference must leave a ceiling above the retired page-local 480, or
        // the "nothing at 480" assertion below would pass on a dock that still had that cap.
        withClue("the reference viewport leaves no room above 480, so the case proves nothing: $before") {
            (total - BOARD_FLOOR) shouldBeGreaterThan RETIRED_MAX + 8.0
        }

        dragHandle(5000.0)
        val atFloor = read()
        withClue("the pane went below (or did not reach) its 120px floor: $atFloor") {
            near(atFloor.d("paneH"), DOCK_MIN) shouldBe true
        }
        shot("dock-min")

        dragHandle(-5000.0)
        val atCeiling = read()
        println("783-ceiling total=$total $atCeiling")
        withClue("the pane ate the board's two-row floor: $atCeiling (layout+pane was $total)") {
            atCeiling.d("layoutH") shouldBeGreaterThanOrEqual BOARD_FLOOR - 1.0
            near(atCeiling.d("paneH"), total - BOARD_FLOOR) shouldBe true
        }
        // The CSS row floor (`minmax(--dp-board-floor, 1fr)`) clamps the RENDERED pane on its own, so the
        // pane's height alone cannot see the splitter's ceiling: a JS bound above the board floor writes,
        // stores and announces a size the person never sees (prop 670 over a 510 pane: steps move
        // nothing). The written property and aria-valuenow are the splitter's own statement.
        withClue("the splitter wrote a size past the ceiling the page shows: $atCeiling (layout+pane was $total)") {
            near(atCeiling.d("prop"), total - BOARD_FLOOR) shouldBe true
            near(atCeiling.d("valueNow"), atCeiling.d("paneH")) shouldBe true
            near(atCeiling.d("valueMax"), total - BOARD_FLOOR) shouldBe true
        }
        withClue("a cap at the retired 480 still holds the pane: $atCeiling") {
            atCeiling.d("paneH") shouldBeGreaterThan RETIRED_MAX + 1.0
        }
        withClue("the ceiling pushed the dock out of main: $atCeiling") {
            atCeiling.d("overflow") shouldBe 0.0
            atCeiling.d("dockBottom") shouldBeLessThanOrEqual atCeiling.d("mainBottom")
        }
        shot("dock-max")
    }

    @Test
    fun `the grip resizes from the keyboard - ArrowUp 16, Shift 64, Home the floor, End the ceiling`() {
        startTrace()
        openBoard()
        val handle = page.locator("[data-splitter='board-dock']")
        handle.focus()
        val before = read()
        val total = before.d("layoutH") + before.d("paneH")

        handle.press("ArrowUp")
        page.waitForTimeout(80.0)
        val stepped = read()
        withClue("ArrowUp did not step 16px: $before -> $stepped") { near(stepped.d("paneH") - before.d("paneH"), 16.0) shouldBe true }
        handle.press("Shift+ArrowUp")
        page.waitForTimeout(80.0)
        val big = read()
        withClue("Shift+ArrowUp did not step 64px: $stepped -> $big") { near(big.d("paneH") - stepped.d("paneH"), 64.0) shouldBe true }
        withClue("aria-valuenow does not follow the pane: $big") { near(big.d("valueNow"), big.d("paneH"), 2.0) shouldBe true }

        handle.press("Home")
        page.waitForTimeout(80.0)
        withClue("Home did not park on the floor: ${read()}") { near(read().d("paneH"), DOCK_MIN) shouldBe true }
        handle.press("End")
        page.waitForTimeout(80.0)
        val end = read()
        withClue("End did not park on the ceiling: $end (layout+pane was $total)") {
            near(end.d("paneH"), total - BOARD_FLOOR) shouldBe true
        }
    }

    @Test
    fun `a dragged height survives a tab switch, Back across the tabs, and a leave to the catalog and Back`() {
        startTrace()
        openBoard()
        dragHandle(-100.0)
        val dragged = read()

        page.click("#dp-tab-versions")
        page.waitForSelector("#dp-pane-versions:not([hidden]) table")
        page.click("#dp-tab-board")
        boardReady()
        val switched = read()
        withClue("the tab switch lost the height: $dragged -> $switched") { near(switched.d("paneH"), dragged.d("paneH")) shouldBe true }

        page.goBack()
        page.waitForSelector("#dp-pane-versions:not([hidden])")
        page.goBack()
        boardReady()
        val back = read()
        withClue("Back across the tabs lost the height: $dragged -> $back") { near(back.d("paneH"), dragged.d("paneH")) shouldBe true }

        // The loss A.0 measured on base: the page's teardown stripped the property on the leave,
        // and the htmx history restore brought back a dock at the default.
        page.click("a.dp-back")
        page.waitForURL("**/dashboards")
        page.goBack()
        boardReady()
        val restored = read()
        println("783-history $dragged -> $switched -> $back -> $restored")
        withClue("a leave to the catalog and Back lost the height: $dragged -> $restored") {
            near(restored.d("paneH"), dragged.d("paneH")) shouldBe true
        }
        withClue("the restored grip is not wired: $restored") { near(restored.d("valueNow"), restored.d("paneH"), 2.0) shouldBe true }
    }

    @Test
    fun `a reload restores the height from dp-pane-board-dock, and a double-click gives the default back`() {
        startTrace()
        openBoard()
        dragHandle(-120.0)
        val dragged = read()
        dragged["stored"] shouldBe dragged.d("prop").toInt().toString()

        page.reload()
        boardReady()
        val reloaded = read()
        withClue("the remembered height did not survive the reload: $dragged -> $reloaded") {
            near(reloaded.d("paneH"), dragged.d("paneH")) shouldBe true
        }

        page.locator("[data-splitter='board-dock']").dblclick()
        page.waitForTimeout(150.0)
        val reset = read()
        withClue("double-click did not restore the stylesheet default: $reset") { near(reset.d("paneH"), DEFAULT_PANE) shouldBe true }
        reset["stored"] shouldBe null
    }

    /**
     * A remembered height is the height of the FIRST frame the dock is in, not a shift onto it:
     * `splitter.js` is a parser-blocking tag above the dock's markup and restores the property
     * at module load. The frame loop records the body's height on the first animation frame it
     * exists in; the layout-shift budget is the explorer suite's ([ExplorerPaneGeometryBrowserTest]).
     */
    @Test
    fun `a full load with a remembered height paints the dock at that height on its first frame`() {
        startTrace()
        page.addInitScript(
            """
            window.__dockFirstFrame = null;
            (function tick() {
              const b = document.querySelector('.dp-board-dock-body');
              if (b) { window.__dockFirstFrame = b.getBoundingClientRect().height; return; }
              requestAnimationFrame(tick);
            })();
            window.__cls = 0;
            new PerformanceObserver((l) => { for (const e of l.getEntries())
              if (!e.hadRecentInput) window.__cls += e.value; }).observe({type: 'layout-shift', buffered: true});
            """.trimIndent(),
        )
        openBoard(remembered = REMEMBERED)
        val r = read()
        val first = page.evaluate("() => window.__dockFirstFrame") as Number?
        val cls = (page.evaluate("() => window.__cls") as Number).toDouble()
        println("783-first-frame first=$first cls=$cls $r")
        withClue("the pane did not open at the remembered height: $r") { near(r.d("paneH"), REMEMBERED.toDouble()) shouldBe true }
        withClue("the first frame with the dock in it was not at the remembered height: first=$first") {
            near(requireNotNull(first) { "the frame loop never saw the dock body" }.toDouble(), REMEMBERED.toDouble()) shouldBe true
        }
        withClue("cumulative layout shift $cls") { cls shouldBeLessThan CLS_BUDGET }
    }

    @Test
    fun `collapse hides the grip and its pane, and expand restores the dragged height`() {
        startTrace()
        openBoard()
        dragHandle(-90.0)
        val dragged = read()

        page.click("[data-dp-dock-collapse]")
        page.waitForSelector("#dp-board-dock[data-dp-dock-collapsed]")
        val collapsed = read()
        withClue("a collapsed dock still shows its grip: $collapsed") { collapsed.d("handleH") shouldBe 0.0 }
        withClue("a collapsed dock kept its pane: $collapsed") { collapsed.d("paneH") shouldBe 0.0 }

        page.click("[data-dp-dock-collapse]")
        page.waitForSelector("#dp-board-dock:not([data-dp-dock-collapsed])")
        page.waitForTimeout(150.0)
        val expanded = read()
        withClue("expand did not restore the dragged height: $dragged -> $expanded") {
            near(expanded.d("paneH"), dragged.d("paneH")) shouldBe true
        }
        withClue("the grip did not come back: $expanded") { expanded["centreHit"] shouldBe true }
    }

    @Test
    fun `the separator follows geometry changes without rewriting the saved height`() {
        startTrace()
        openBoard(remembered = REMEMBERED)
        for ((width, height) in listOf(1280 to 720, REFERENCE_W to REFERENCE_H)) {
            page.setViewportSize(width, height)
            boardReady()
            val geometry = read()
            withClue("separator values after resizing to ${width}x$height: $geometry") {
                near(geometry.d("valueNow"), geometry.d("paneH")) shouldBe true
                near(geometry.d("valueMax"), geometry.d("layoutH") + geometry.d("paneH") - BOARD_FLOOR) shouldBe true
                geometry.d("prop") shouldBe REMEMBERED.toDouble()
                geometry["stored"] shouldBe REMEMBERED.toString()
                if (height == 720) {
                    geometry.d("paneH") shouldBeLessThan REMEMBERED.toDouble()
                } else {
                    near(geometry.d("paneH"), REMEMBERED.toDouble()) shouldBe true
                }
            }
        }
    }

    private companion object {
        const val STORAGE_KEY = "dp.pane.board-dock"

        /** dashboards.css's `:root` `--dp-dock-pane-h`. */
        const val DEFAULT_PANE = 200.0

        /** splitter.js's DOCK_MIN (shared with the editor) and BOARD_FLOOR (`--dp-board-floor`, 2 x 80px). */
        const val DOCK_MIN = 120.0
        const val BOARD_FLOOR = 160.0

        /** The page-local drag's ceiling before #783. */
        const val RETIRED_MAX = 480.0

        /** `--app-splitter-strip` is 9px; anything under 6 is the base's 1px-hit button again. */
        const val GRIP_STRIP_MIN = 6.0

        const val REMEMBERED = 360

        const val CLS_BUDGET = 0.05

        const val REFERENCE_W = 1512
        const val REFERENCE_H = 982
    }
}
