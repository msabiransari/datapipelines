package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * #371 — the dashboard grid has a ROW UNIT, so a Plotly figure has a height on every page that
 * mounts the first-party composite adapter. The adapter builds its grid inline with columns and
 * a gap but no row size; a slot's `h` is a row SPAN, so without a row unit a slot whose only
 * content is a responsive Plotly host has no height to span and the figure collapses.
 *
 * The assertion is a MEASUREMENT in a real browser, never a status code or a class name: for a
 * slot of `h` rows the figure must stand between `h × unit − (h − 1) × gap` (the lower bound the
 * brief states) and the slot's own box, where the unit is read from the page's own
 * `--dashboard-row-unit` token (resolved to pixels through a probe element, so a rem value
 * counts) and the gap from the board's computed `row-gap`. The unit being a positive length is
 * asserted FIRST: with no token the bound would be negative and every height would pass.
 *
 * Three cases, so a regression names the page it broke: the BOARD page in both themes, the board
 * page at 767 px (below the 768 px breakpoint the dashboards document names), and the
 * visualization test PREVIEW page (one 12-wide, 4-row slot — the page #353 scoped its own copy
 * of the rule to).
 */
class DashboardGridRowUnitBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `the board page gives a figure its slot's height in both themes`() {
        startTrace()
        val root = ready("dprow")
        val board = seedBoardWithGrid(root)
        for (theme in THEMES) {
            page.navigate("$baseUrl/dashboards/$board")
            ensureTheme(theme)
            page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
            assertFiguresFillTheirSlots(page, BOARD_SCOPE, BOARD_SLOTS, BOARD_ROWS, "board/desktop/$theme")
        }
    }

    @Test
    fun `the board page keeps the row unit below the 768 px breakpoint`() {
        startTrace()
        val root = ready("dprowm")
        val board = seedBoardWithGrid(root)
        page.setViewportSize(NARROW_WIDTH_PX, NARROW_HEIGHT_PX)
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        val theme = page.evaluate("() => document.documentElement.getAttribute('data-theme')") as String
        assertFiguresFillTheirSlots(page, BOARD_SCOPE, BOARD_SLOTS, BOARD_ROWS, "board/$NARROW_WIDTH_PX/$theme")
    }

    @Test
    fun `the visualization preview page gives its figure the same height in both themes`() {
        startTrace()
        val root = ready("dprowp")
        val previewUrl = startPreviewSession("$root/charts/rowunit")
        val agent = newBrowserContext()
        try {
            val agentPage = agent.newPage()
            for (theme in THEMES) {
                agentPage.navigate("$baseUrl$previewUrl&theme=$theme")
                agentPage.waitForSelector("$PREVIEW_SCOPE[data-dp-ready='true']")
                assertFiguresFillTheirSlots(agentPage, PREVIEW_SCOPE, PREVIEW_SLOTS, PREVIEW_ROWS, "preview/desktop/$theme")
            }
            agentPage.setViewportSize(NARROW_WIDTH_PX, NARROW_HEIGHT_PX)
            agentPage.navigate("$baseUrl$previewUrl&theme=light")
            agentPage.waitForSelector("$PREVIEW_SCOPE[data-dp-ready='true']")
            assertFiguresFillTheirSlots(agentPage, PREVIEW_SCOPE, PREVIEW_SLOTS, PREVIEW_ROWS, "preview/$NARROW_WIDTH_PX/light")
        } finally {
            agent.close()
        }
    }

    /** Waits for Plotly's own render of every slot's figure, measures, and asserts the geometry. */
    private fun assertFiguresFillTheirSlots(
        target: Page,
        scope: String,
        slots: Map<String, Int>,
        gridRows: Int,
        label: String,
    ) {
        val args = mapOf("scope" to scope, "slots" to slots.keys.toList())
        target.waitForFunction(RENDERED_JS, args)
        val measured = target.evaluate(MEASURE_JS, args) as Map<*, *>
        val unit = (measured["unit"] as Number).toDouble()
        val gap = (measured["gap"] as Number).toDouble()
        val report = "$label token='${measured["token"]}' unit=${unit}px gap=${gap}px slots=${measured["slots"]}"
        record(report)
        shoot(target, label)

        withClue("the page declares a positive --dashboard-row-unit; with none every bound below is vacuous: $report") {
            (unit > 0.0) shouldBe true
        }
        val boardHeight = (measured["board"] as Number).toDouble()
        val expectedBoard = gridRows * unit + (gridRows - 1) * gap
        withClue("the board is $gridRows rows tall ($expectedBoard) - no trailing row for the empty default slot: $report board=$boardHeight") {
            (Math.abs(boardHeight - expectedBoard) <= TOLERANCE_PX) shouldBe true
        }
        val figures = measured["slots"] as Map<*, *>
        for ((name, rows) in slots) {
            val figure = figures[name] as Map<*, *>
            val slotHeight = (figure["slot"] as Number).toDouble()
            val figureHeight = (figure["figure"] as Number).toDouble()
            val expectedSlot = rows * unit + (rows - 1) * gap
            val lowerBound = rows * unit - (rows - 1) * gap - TOLERANCE_PX
            withClue("the '$name' slot spans $rows rows of the unit plus its gaps ($expectedSlot): $report") {
                (Math.abs(slotHeight - expectedSlot) <= TOLERANCE_PX) shouldBe true
            }
            withClue("the '$name' figure is at least $lowerBound px (rows × unit − gaps): $report") {
                (figureHeight >= lowerBound) shouldBe true
            }
            withClue("the '$name' figure is no taller than its slot ($slotHeight): $report") {
                (figureHeight <= slotHeight + TOLERANCE_PX) shouldBe true
            }
        }
        if (slots.size > 1) {
            val tall = slots.entries.maxBy { it.value }.key
            val short = slots.entries.minBy { it.value }.key
            val ratio = (figures.heightOf(short) / figures.heightOf(tall))
            withClue("the 2-row figure is about half the 4-row one (ratio $ratio): $report") {
                (ratio in HALF_LOW..HALF_HIGH) shouldBe true
            }
        }
    }

    private fun Map<*, *>.heightOf(name: String): Double = ((this[name] as Map<*, *>)["figure"] as Number).toDouble()

    /** The page as a person sees it, named by page, viewport and theme, beside the measurements. */
    private fun shoot(
        target: Page,
        label: String,
    ) {
        Files.createDirectories(REPORT.parent)
        target.screenshot(Page.ScreenshotOptions().setFullPage(true).setPath(REPORT.parent.resolve(label.replace('/', '-') + ".png")))
    }

    /** One line per measurement into the build's reports, for the handback's record of the heights. */
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

        /** The board page's container; the seeded grid pins revenue (a chart) at 4 rows and slowchart at 2. */
        const val BOARD_SCOPE = "#dp-board"
        val BOARD_SLOTS = mapOf("revenue" to 4, "slowchart" to 2)

        /** The seeded grid's last row: total and slowchart sit at y 4 with h 2. */
        const val BOARD_ROWS = 6

        /** The preview's first case: ONE occurrence named `preview`, 12 wide, 4 rows. */
        const val PREVIEW_SCOPE = "section[data-dp-case-index='0']"
        val PREVIEW_SLOTS = mapOf("preview" to 4)
        const val PREVIEW_ROWS = 4

        const val NARROW_WIDTH_PX = 767
        const val NARROW_HEIGHT_PX = 900
        const val TOLERANCE_PX = 3.0
        const val HALF_LOW = 0.35
        const val HALF_HIGH = 0.65
        const val EXCERPT = 600
        val HTTP_OK_RANGE = 200..299
        val REPORT: Path = Paths.get("build", "reports", "dashboard-grid-row-unit", "measurements.txt")

        /** Plotly's own completion: every figure's graph div carries its resolved layout. */
        const val RENDERED_JS =
            """(args) => args.slots.every(function (name) {
              var g = document.querySelector(args.scope + " [data-dp-slot='" + name + "'] .js-plotly-plot");
              return !!(g && g._fullLayout && g.querySelector('.main-svg'));
            })"""

        /** The token resolved to pixels through a probe, the board's row gap, and each slot's and figure's box. */
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
              var out = {
                token: getComputedStyle(document.documentElement).getPropertyValue('--dashboard-row-unit').trim(),
                unit: unit,
                board: board.getBoundingClientRect().height,
                gap: parseFloat(getComputedStyle(board).rowGap) || 0,
                slots: {}
              };
              args.slots.forEach(function (name) {
                var slot = board.querySelector("[data-dp-slot='" + name + "']");
                var svg = slot.querySelector('.plotly .main-svg');
                out.slots[name] = { slot: slot.getBoundingClientRect().height, figure: svg.getBoundingClientRect().height };
              });
              return out;
            }"""
    }
}
