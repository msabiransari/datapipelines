package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * #371 — the dashboard grid has a ROW UNIT, so a Plotly figure has a height on every page that
 * mounts the first-party composite adapter; #386 — a figure in a 2-row slot keeps a PLOT AREA
 * (compact margins with `automargin`, not Plotly's ≈100/80 px defaults, which ate the whole 176 px
 * slot); #412 — below `layout.breakpoint_px` (640 when absent) every item spans the full width in
 * grid order, the row unit unchanged (the implementation spec §3.2, dashboards.md §2.2).
 *
 * Every assertion is a MEASUREMENT in a real browser, never a status code or a class name: for a
 * slot of `h` rows the figure must stand between `h × unit − (h − 1) × gap` and the slot's own
 * box, where the unit is read from the page's own `--dashboard-row-unit` token (resolved to pixels
 * through a probe element, so a rem value counts) and the gap from the board's computed `row-gap`.
 * The unit and the gap being positive lengths are asserted FIRST: with neither every bound below is
 * vacuous. A slot's COLUMN count is derived from its width against the board's track geometry, so
 * "full width" is a number (12), not an inline style read back. The plot area is Plotly's own
 * `_fullLayout._size.h` (the `nsewdrag` rect beside it in the record).
 *
 * The 640 px breakpoint is measured on the board: the 735 px board at 767 px viewport holds its
 * 6/6/3/9 grid, while the 694 px board at 1280 px collapses under an explicit 700 px threshold.
 * A live host-width crossing proves the slots re-place AND the figure re-measures to its new width.
 */
class DashboardGridRowUnitBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `the board page holds the stored grid at 767, 768 and 1280 px, in both themes`() {
        startTrace()
        val root = ready("dprow")
        val board = seedBoardWithGrid(root)
        for (width in listOf(DESKTOP_WIDTH_PX, TABLET_WIDTH_PX, INVERTED_VIEWPORT_PX)) {
            page.setViewportSize(width, VIEWPORT_HEIGHT_PX)
            for (theme in THEMES) {
                openBoard(board, theme)
                val measured = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "board/$width/$theme")
                assertRowUnit(measured)
                assertBoardRows(measured, BOARD_ROWS)
                assertColumns(measured, BOARD_STORED_COLUMNS)
                assertFiguresFillTheirSlots(measured, BOARD_SLOTS)
                assertHalfRatio(measured, BOARD_SLOTS)
                assertPlotAreaFloor(measured, SMALL_SLOT)
            }
        }
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `below 640 board px every board item spans full width in grid order, in both themes`() {
        startTrace()
        val root = ready("dprowm")
        val board = seedBoardWithGrid(root)
        page.setViewportSize(NARROW_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        for (theme in THEMES) {
            openBoard(board, theme)
            val measured = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "board/$NARROW_WIDTH_PX/$theme")
            assertRowUnit(measured)
            assertColumns(measured, BOARD_SLOTS_ALL.keys.associateWith { GRID_COLUMNS })
            assertStackedInGridOrder(measured, BOARD_GRID_ORDER)
            assertBoardRows(measured, BOARD_SLOTS_ALL.values.sum())
            assertFiguresFillTheirSlots(measured, BOARD_SLOTS)
            assertFiguresSpanTheirSlots(measured, BOARD_SLOTS.keys)
            assertPlotAreaFloor(measured, SMALL_SLOT)
        }
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `a board width crossing the breakpoint re-places the slots and resizes the figures, both ways`() {
        startTrace()
        val root = ready("dprowx")
        val board = seedBoardWithGrid(root)
        page.setViewportSize(DESKTOP_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        val theme = THEMES.first()
        openBoard(board, theme)
        val wide = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "crossing/$DESKTOP_WIDTH_PX-before/$theme")
        assertColumns(wide, BOARD_STORED_COLUMNS)

        page.setViewportSize(NARROW_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        page.waitForFunction(SETTLED_JS, mapOf("scope" to BOARD_SCOPE, "slots" to BOARD_SLOTS.keys.toList(), "full" to true))
        val narrow = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "crossing/$NARROW_WIDTH_PX/$theme")
        assertColumns(narrow, BOARD_SLOTS_ALL.keys.associateWith { GRID_COLUMNS })
        assertStackedInGridOrder(narrow, BOARD_GRID_ORDER)
        assertFiguresSpanTheirSlots(narrow, BOARD_SLOTS.keys)
        assertPlotAreaFloor(narrow, SMALL_SLOT)

        page.setViewportSize(DESKTOP_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        page.waitForFunction(SETTLED_JS, mapOf("scope" to BOARD_SCOPE, "slots" to BOARD_SLOTS.keys.toList(), "full" to false))
        val restored = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "crossing/$DESKTOP_WIDTH_PX-after/$theme")
        assertColumns(restored, BOARD_STORED_COLUMNS)
        assertBoardRows(restored, BOARD_ROWS)
        assertFiguresSpanTheirSlots(restored, BOARD_SLOTS.keys)
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `a configured breakpoint collapses a 694 px board at a 1280 px viewport`() {
        startTrace()
        val root = ready("dprow700")
        val board = seedBoardWithGrid(root, CONFIGURED_BREAKPOINT_PX)
        page.setViewportSize(DESKTOP_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        openBoard(board, THEMES.first())
        val measured = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "configured/$DESKTOP_WIDTH_PX")
        record("configured viewport=$DESKTOP_WIDTH_PX tree=closed boardWidth=${measured.boardWidth} columns=full")
        (measured.boardWidth < CONFIGURED_BREAKPOINT_PX) shouldBe true
        assertColumns(measured, BOARD_SLOTS_ALL.keys.associateWith { GRID_COLUMNS })
        assertStackedInGridOrder(measured, BOARD_GRID_ORDER)
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `widening the navigation rail stacks a narrow board and resetting it restores the stored grid`() {
        startTrace()
        val root = ready("dprowtree")
        val board = seedBoardWithGrid(root)
        page.setViewportSize(DESKTOP_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        openBoard(board, THEMES.first())

        // The reader's rail control is the board host's narrowing cause: the rail is user-resizable
        // (#460's separator; owner ruling 2026-10-09 — the Pipelines tree no longer reflows the page,
        // it opens inside the rail). Two shifted presses take the rail from 232 to 332 px, the board
        // from ~694 to ~594 px — one crossing below the 640 default. Home resets the rail to 232.
        val separator = page.locator("#rail-resize")
        separator.focus()
        page.keyboard().press("Shift+ArrowRight")
        page.keyboard().press("Shift+ArrowRight")
        page.waitForFunction(
            "() => { var board = document.querySelector('#dp-board .dp-dashboard'); " +
                "return !!board && board.getBoundingClientRect().width > 0 && board.getBoundingClientRect().width < 640; }",
        )
        page.waitForFunction(SETTLED_JS, mapOf("scope" to BOARD_SCOPE, "slots" to BOARD_SLOTS.keys.toList(), "full" to true))
        val narrow = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "rail/wide/$DESKTOP_WIDTH_PX")
        record("rail=wide viewport=$DESKTOP_WIDTH_PX boardWidth=${narrow.boardWidth} columns=full")
        (narrow.boardWidth < BREAKPOINT_PX) shouldBe true
        assertColumns(narrow, BOARD_SLOTS_ALL.keys.associateWith { GRID_COLUMNS })
        assertStackedInGridOrder(narrow, BOARD_GRID_ORDER)

        separator.focus()
        page.keyboard().press("Home")
        page.waitForFunction(SETTLED_JS, mapOf("scope" to BOARD_SCOPE, "slots" to BOARD_SLOTS.keys.toList(), "full" to false))
        val restored = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "rail/home/$DESKTOP_WIDTH_PX")
        record("rail=home viewport=$DESKTOP_WIDTH_PX boardWidth=${restored.boardWidth} columns=stored")
        (restored.boardWidth >= BREAKPOINT_PX) shouldBe true
        assertColumns(restored, BOARD_STORED_COLUMNS)
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `a 767 px viewport keeps the 735 px board grid`() {
        startTrace()
        val root = ready("dpro767")
        val board = seedBoardWithGrid(root)
        page.setViewportSize(INVERTED_VIEWPORT_PX, VIEWPORT_HEIGHT_PX)
        openBoard(board, THEMES.first())
        val measured = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "inverted/$INVERTED_VIEWPORT_PX")
        record("inverted viewport=$INVERTED_VIEWPORT_PX tree=closed boardWidth=${measured.boardWidth} columns=stored")
        (measured.boardWidth >= BREAKPOINT_PX) shouldBe true
        assertColumns(measured, BOARD_STORED_COLUMNS)
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `a board booted in the hidden versions pane keeps its stored grid until reveal`() {
        startTrace()
        val root = ready("dprowhidden")
        val board = seedBoardWithGrid(root)
        page.setViewportSize(DESKTOP_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        page.navigate("$baseUrl/dashboards/$board?tab=versions")
        ensureTheme(THEMES.first())
        page.reload()
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        val hiddenColumns =
            page.evaluate(
                """() => Object.fromEntries(Array.from(document.querySelectorAll("#dp-board [data-dp-slot]"),
                    slot => [slot.getAttribute("data-dp-slot"), slot.style.gridColumnEnd]))""",
            ) as Map<*, *>
        record("hidden viewport=$DESKTOP_WIDTH_PX boardWidth=0 columns=$hiddenColumns")
        hiddenColumns shouldBe mapOf("revenue" to "span 6", "cells" to "span 6", "total" to "span 3", "slowchart" to "span 9")

        page.locator("[data-dp-tab='board']").click()
        page.waitForFunction(
            """() => {
                var board = document.querySelector('#dp-board .dp-dashboard');
                return !!board && board.getBoundingClientRect().width > 0;
            }""",
        )
        val revealed = measure(page, BOARD_SCOPE, BOARD_SLOTS_ALL.keys, "hidden/revealed/$DESKTOP_WIDTH_PX")
        record("revealed viewport=$DESKTOP_WIDTH_PX boardWidth=${revealed.boardWidth} columns=stored")
        assertColumns(revealed, BOARD_STORED_COLUMNS)
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `the visualization preview page gives its figure the same height in both themes, and its one item follows the 640 px board rule`() {
        startTrace()
        val root = ready("dprowp")
        val previewUrl = startPreviewSession("$root/charts/rowunit")
        val agent = newBrowserContext()
        try {
            val agentPage = agent.newPage()
            for (theme in THEMES) {
                agentPage.navigate("$baseUrl$previewUrl&theme=$theme")
                agentPage.waitForSelector("$PREVIEW_SCOPE[data-dp-ready='true']")
                val measured = measure(agentPage, PREVIEW_SCOPE, PREVIEW_SLOTS.keys, "preview/desktop/$theme")
                assertRowUnit(measured)
                assertBoardRows(measured, PREVIEW_ROWS)
                assertColumns(measured, PREVIEW_SLOTS.keys.associateWith { GRID_COLUMNS })
                assertFiguresFillTheirSlots(measured, PREVIEW_SLOTS)
            }
            agentPage.setViewportSize(NARROW_WIDTH_PX, VIEWPORT_HEIGHT_PX)
            agentPage.navigate("$baseUrl$previewUrl&theme=light")
            agentPage.waitForSelector("$PREVIEW_SCOPE[data-dp-ready='true']")
            val narrow = measure(agentPage, PREVIEW_SCOPE, PREVIEW_SLOTS.keys, "preview/$NARROW_WIDTH_PX/light")
            assertRowUnit(narrow)
            assertBoardRows(narrow, PREVIEW_ROWS)
            assertColumns(narrow, PREVIEW_SLOTS.keys.associateWith { GRID_COLUMNS })
            assertFiguresFillTheirSlots(narrow, PREVIEW_SLOTS)
            assertFiguresSpanTheirSlots(narrow, PREVIEW_SLOTS.keys)
        } finally {
            agent.close()
        }
        drainCspViolations().shouldBeEmpty()
    }

    // ------------------------------------------------------------------------------ the measurement

    /** One measured page: the unit, the gaps, the board box and each slot's and figure's geometry. */
    private class Measured(
        val label: String,
        val raw: Map<*, *>,
    ) {
        val unit = (raw["unit"] as Number).toDouble()
        val rowGap = (raw["rowGap"] as Number).toDouble()
        val columnGap = (raw["columnGap"] as Number).toDouble()
        val boardWidth = (raw["boardWidth"] as Number).toDouble()
        val boardHeight = (raw["boardHeight"] as Number).toDouble()
        val slots = raw["slots"] as Map<*, *>

        fun slot(
            name: String,
            key: String,
        ): Double = (((slots[name] as Map<*, *>)[key]) as Number).toDouble()

        override fun toString() =
            "$label token='${raw["token"]}' unit=${unit}px rowGap=${rowGap}px columnGap=${columnGap}px " +
                "board=${boardWidth}x$boardHeight slots=$slots"
    }

    private fun openBoard(
        board: String,
        theme: String,
    ) {
        page.navigate("$baseUrl/dashboards/$board")
        ensureTheme(theme)
        // The theme toggle swaps the stylesheet in place; a fresh load renders every figure under it.
        page.reload()
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
    }

    /** Waits for Plotly's own render of every figure, measures, records the line and shoots the page. */
    private fun measure(
        target: Page,
        scope: String,
        slots: Collection<String>,
        label: String,
    ): Measured {
        val figures = slots.filter { it in PLOTLY_SLOTS }
        target.waitForFunction(RENDERED_JS, mapOf("scope" to scope, "slots" to figures))
        val measured = Measured(label, target.evaluate(MEASURE_JS, mapOf("scope" to scope, "slots" to slots.toList())) as Map<*, *>)
        record(measured.toString())
        shoot(target, scope, label, figures)
        return measured
    }

    private fun assertRowUnit(m: Measured) {
        withClue("the page declares a positive --dashboard-row-unit; with none every bound below is vacuous: $m") {
            (m.unit > 0.0) shouldBe true
        }
        withClue("the board's gap resolves to a positive length (the --space-4 token); with none the stacking bounds are vacuous: $m") {
            (m.rowGap > 0.0 && m.columnGap > 0.0) shouldBe true
        }
    }

    private fun assertBoardRows(
        m: Measured,
        rows: Int,
    ) {
        val expected = rows * m.unit + (rows - 1) * m.rowGap
        withClue("the board is $rows rows tall ($expected), no trailing row for the empty default slot: $m") {
            (Math.abs(m.boardHeight - expected) <= TOLERANCE_PX) shouldBe true
        }
    }

    /** Each slot's column count from its width: `round((w + gap) / (track + gap))` over the board's 12 tracks. */
    private fun assertColumns(
        m: Measured,
        expected: Map<String, Int>,
    ) {
        val track = (m.boardWidth - (GRID_COLUMNS - 1) * m.columnGap) / GRID_COLUMNS
        val counted = expected.keys.associateWith { Math.round((m.slot(it, "width") + m.columnGap) / (track + m.columnGap)).toInt() }
        record("${m.label} columns=$counted")
        withClue("the slots span $expected columns of $GRID_COLUMNS (counted $counted): $m") {
            counted shouldBe expected
        }
    }

    /** Grid order: each slot starts one gap below the previous one's foot, left edges flush with the board's. */
    private fun assertStackedInGridOrder(
        m: Measured,
        order: List<String>,
    ) {
        for (i in 1 until order.size) {
            val above = order[i - 1]
            val below = order[i]
            val foot = m.slot(above, "top") + m.slot(above, "height")
            withClue("'$below' starts one gap below '$above' (${foot + m.rowGap}): $m") {
                (Math.abs(m.slot(below, "top") - (foot + m.rowGap)) <= TOLERANCE_PX) shouldBe true
            }
        }
        for (name in order) {
            withClue("'$name' starts at the board's left edge: $m") {
                (Math.abs(m.slot(name, "left")) <= TOLERANCE_PX) shouldBe true
            }
        }
    }

    private fun assertFiguresFillTheirSlots(
        m: Measured,
        slots: Map<String, Int>,
    ) {
        for ((name, rows) in slots) {
            val slotHeight = m.slot(name, "height")
            val figureHeight = m.slot(name, "figure")
            val expectedSlot = rows * m.unit + (rows - 1) * m.rowGap
            val lowerBound = rows * m.unit - (rows - 1) * m.rowGap - TOLERANCE_PX
            withClue("the '$name' slot spans $rows rows of the unit plus its gaps ($expectedSlot): $m") {
                (Math.abs(slotHeight - expectedSlot) <= TOLERANCE_PX) shouldBe true
            }
            withClue("the '$name' figure is at least $lowerBound px (rows × unit − gaps): $m") {
                (figureHeight >= lowerBound) shouldBe true
            }
            withClue("the '$name' figure is no taller than its slot ($slotHeight): $m") {
                (figureHeight <= slotHeight + TOLERANCE_PX) shouldBe true
            }
        }
    }

    /** The figure follows its slot's WIDTH too: after a collapse (or a crossing) Plotly re-measured. */
    private fun assertFiguresSpanTheirSlots(
        m: Measured,
        names: Collection<String>,
    ) {
        for (name in names) {
            withClue("the '$name' figure is as wide as its slot (${m.slot(name, "width")}): $m") {
                (Math.abs(m.slot(name, "figureWidth") - m.slot(name, "width")) <= WIDTH_TOLERANCE_PX) shouldBe true
            }
        }
    }

    private fun assertHalfRatio(
        m: Measured,
        slots: Map<String, Int>,
    ) {
        val tall = slots.entries.maxBy { it.value }.key
        val short = slots.entries.minBy { it.value }.key
        val ratio = m.slot(short, "figure") / m.slot(tall, "figure")
        withClue("the 2-row figure is about half the 4-row one (ratio $ratio): $m") {
            (ratio in HALF_LOW..HALF_HIGH) shouldBe true
        }
    }

    /**
     * #386's floor: the 2-row figure's PLOT AREA is at least [PLOT_AREA_FLOOR] of its slot. Half,
     * because what a compact frame leaves outside the plot — one line of x tick labels below (an axis
     * title adds a second), a title line above when the figure has one — fits in the other half of a
     * 176 px slot at the default font; Plotly's own margins (≈100 top + 80 bottom) left a sliver.
     */
    private fun assertPlotAreaFloor(
        m: Measured,
        name: String,
    ) {
        val slotHeight = m.slot(name, "height")
        val plot = m.slot(name, "plot")
        record("${m.label} plotArea[$name]=${plot}px of slot ${slotHeight}px (drag ${m.slot(name, "drag")}px)")
        withClue("the '$name' plot area ($plot px) is at least ${PLOT_AREA_FLOOR * slotHeight} px ($PLOT_AREA_FLOOR of the slot): $m") {
            (plot >= PLOT_AREA_FLOOR * slotHeight) shouldBe true
        }
    }

    /**
     * The page as a person sees it, named by page, viewport and theme, beside the measurements — the
     * board element whole, and each figure's slot on its own. The app's main pane scrolls inside the
     * viewport, so neither the full-page shot nor the board's shows what lies below the pane's fold of a
     * stacked (collapsed) board; a slot shot scrolls its slot into view first, so every figure is seen.
     */
    private fun shoot(
        target: Page,
        scope: String,
        label: String,
        slots: Collection<String>,
    ) {
        Files.createDirectories(REPORT.parent)
        val name = label.replace('/', '-')
        target.screenshot(Page.ScreenshotOptions().setFullPage(true).setPath(REPORT.parent.resolve("$name.png")))
        target.locator("$scope .dp-dashboard").screenshot(Locator.ScreenshotOptions().setPath(REPORT.parent.resolve("$name-board.png")))
        for (slot in slots) {
            target
                .locator("$scope [data-dp-slot='$slot']")
                .screenshot(Locator.ScreenshotOptions().setPath(REPORT.parent.resolve("$name-$slot.png")))
        }
    }

    /** One line per measurement into the build's reports, for the handback's record of the geometry. */
    private fun record(line: String) {
        Files.createDirectories(REPORT.parent)
        Files.writeString(REPORT, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /**
     * An author's visualization with ONE test case, and its test session started over REST from
     * the signed-in page (cookie plus the CSRF double-submit); the session's preview URL back.
     */
    private fun startPreviewSession(name: String): String {
        val created = postJson("/api/v1/visualizations", visualization(name))
        val id = (created["data"] as Map<*, *>)["id"] as String
        val started = postJson("/api/v1/visualizations/$id/tests/sessions", "")
        return (started["data"] as Map<*, *>)["preview_url"] as String
    }

    private fun postJson(
        path: String,
        body: String,
    ): Map<*, *> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const response = await fetch(args.path, { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: args.body });
                  const text = await response.text();
                  let json = null;
                  try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }
                  return { status: response.status, text: text, json: json };
                }""",
                mapOf("path" to path, "body" to body),
            ) as Map<*, *>
        val status = (result["status"] as Number).toInt()
        withClue("POST $path -> $status ${(result["text"] as String).take(EXCERPT)}") { (status in HTTP_OK_RANGE) shouldBe true }
        return result["json"] as Map<*, *>
    }

    private fun visualization(name: String): String =
        """
        {"name": "$name", "display_name": "Row unit", "description": "",
         "renderer": {"kind": "plotly", "version": "4"},
         "inputs": {"sales": {"columns": [{"name": "month", "type": "STRING", "nullable": false},
                                          {"name": "units", "type": "INTEGER", "nullable": false}]}},
         "config": {"data": [{"type": "bar", "x": [], "y": []}], "layout": {"title": {"text": "Units"}}},
         "bindings": {"data[0].x": "month", "data[0].y": "units"},
         "presentation": {"title": "Units"},
         "tests": {"cases": [
            {"name": "three months", "fixtures": {"sales": [{"month": "Jan", "units": 3},
                                                           {"month": "Feb", "units": 5},
                                                           {"month": "Mar", "units": 2}]},
             "assertions": [{"kind": "rendered"}]}]}}
        """.trimIndent()

    private companion object {
        val THEMES = listOf("light", "dark")
        const val GRID_COLUMNS = 12

        /** The board page's container; the seeded grid pins revenue (a chart) at 4 rows and slowchart at 2. */
        const val BOARD_SCOPE = "#dp-board"
        val BOARD_SLOTS = mapOf("revenue" to 4, "slowchart" to 2)

        /** The seeded grid (DashboardBrowserSuite.seedBoardWithGrid): every item's row span, in GRID ORDER (row, then column). */
        val BOARD_SLOTS_ALL = linkedMapOf("revenue" to 4, "cells" to 4, "total" to 2, "slowchart" to 2)
        val BOARD_GRID_ORDER = BOARD_SLOTS_ALL.keys.toList()

        /** The seeded grid's stored widths (`w`): revenue 6, cells 6, total 3, slowchart 9. */
        val BOARD_STORED_COLUMNS = mapOf("revenue" to 6, "cells" to 6, "total" to 3, "slowchart" to 9)

        /** The seeded grid's last row: total and slowchart sit at y 4 with h 2. */
        const val BOARD_ROWS = 6

        /** The 2-row Plotly slot #386 is about. */
        const val SMALL_SLOT = "slowchart"

        /** The slots whose content is a Plotly figure (cells is a table, total a KPI). */
        val PLOTLY_SLOTS = setOf("revenue", "slowchart", "preview")

        /** The preview's first case: ONE occurrence named `preview`, 12 wide, 4 rows. */
        const val PREVIEW_SCOPE = "section[data-dp-case-index='0']"
        val PREVIEW_SLOTS = mapOf("preview" to 4)
        const val PREVIEW_ROWS = 4

        /** The default is measured on the host; 640 itself holds and 639 collapses. */
        const val BREAKPOINT_PX = 640
        const val NARROW_WIDTH_PX = BREAKPOINT_PX - 1
        const val TABLET_WIDTH_PX = 768
        const val INVERTED_VIEWPORT_PX = 767
        const val CONFIGURED_BREAKPOINT_PX = 700
        const val DESKTOP_WIDTH_PX = 1280
        const val VIEWPORT_HEIGHT_PX = 900

        const val TOLERANCE_PX = 3.0
        const val WIDTH_TOLERANCE_PX = 3.0
        const val HALF_LOW = 0.35
        const val HALF_HIGH = 0.65
        const val PLOT_AREA_FLOOR = 0.5
        const val EXCERPT = 600
        val HTTP_OK_RANGE = 200..299
        val REPORT: Path = Paths.get("build", "reports", "dashboard-grid-row-unit", "measurements.txt")

        /** Plotly's own completion: every figure's graph div carries its resolved layout. */
        const val RENDERED_JS =
            """(args) => args.slots.every(function (name) {
              var g = document.querySelector(args.scope + " [data-dp-slot='" + name + "'] .js-plotly-plot");
              return !!(g && g._fullLayout && g.querySelector('.main-svg'));
            })"""

        /**
         * After a board-width crossing: every figure's slot is (full) or is not (stored) the board's width,
         * AND Plotly's svg has caught up with its slot's width — the re-measure, not just the re-place.
         */
        const val SETTLED_JS =
            """(args) => {
              var board = document.querySelector(args.scope + ' .dp-dashboard');
              if (!board) return false;
              var width = board.getBoundingClientRect().width;
              return args.slots.every(function (name) {
                var slot = board.querySelector("[data-dp-slot='" + name + "']");
                var svg = slot && slot.querySelector('.plotly .main-svg');
                if (!svg) return false;
                var slotWidth = slot.getBoundingClientRect().width;
                var full = Math.abs(slotWidth - width) <= 1;
                return full === args.full && Math.abs(svg.getBoundingClientRect().width - slotWidth) <= 3;
              });
            }"""

        /** The token resolved to pixels through a probe, the board's gaps and box, and each slot's and figure's geometry. */
        const val MEASURE_JS =
            """(args) => {
              var root = document.querySelector(args.scope);
              var board = root.querySelector('.dp-dashboard');
              var probe = document.createElement('div');
              probe.style.position = 'absolute';
              probe.style.visibility = 'hidden';
              probe.style.height = 'var(--dashboard-row-unit)';
              document.body.appendChild(probe);
              var unit = probe.getBoundingClientRect().height;
              probe.remove();
              var boardBox = board.getBoundingClientRect();
              var style = getComputedStyle(board);
              var out = {
                token: getComputedStyle(document.documentElement).getPropertyValue('--dashboard-row-unit').trim(),
                unit: unit,
                boardWidth: boardBox.width,
                boardHeight: boardBox.height,
                rowGap: parseFloat(style.rowGap) || 0,
                columnGap: parseFloat(style.columnGap) || 0,
                slots: {}
              };
              args.slots.forEach(function (name) {
                var slot = board.querySelector("[data-dp-slot='" + name + "']");
                var box = slot.getBoundingClientRect();
                var entry = { top: box.top - boardBox.top, left: box.left - boardBox.left, width: box.width, height: box.height };
                var svg = slot.querySelector('.plotly .main-svg');
                if (svg) {
                  var graph = slot.querySelector('.js-plotly-plot');
                  var drag = slot.querySelector('.nsewdrag');
                  entry.figure = svg.getBoundingClientRect().height;
                  entry.figureWidth = svg.getBoundingClientRect().width;
                  entry.plot = graph && graph._fullLayout && graph._fullLayout._size ? graph._fullLayout._size.h : 0;
                  entry.drag = drag ? drag.getBoundingClientRect().height : 0;
                  entry.margin = graph && graph._fullLayout && graph._fullLayout._size ? graph._fullLayout._size : null;
                }
                out.slots[name] = entry;
              });
              return out;
            }"""
    }
}
