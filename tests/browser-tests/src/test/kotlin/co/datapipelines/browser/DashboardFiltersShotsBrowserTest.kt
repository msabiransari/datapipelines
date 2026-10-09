package co.datapipelines.browser

import com.microsoft.playwright.Page
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #493 C — the board's filters as pictures, in light and dark, on the acceptance board (a long
 * checkbox list, a dependent pair and a constrained INTEGER input): the panel at the desktop width,
 * the drawer at a phone viewport, and the VALIDATION state — `limit` typed past its maximum, the
 * row's `.dp-dashboard-parameter-error` alert rendered from the evaluate response's
 * `state.errors` (datapipelines-dashboard.js, the parameters pane). The geometry and the wire are
 * ASSERTED by `DashboardFiltersBrowserTest`; this class waits on the validation marker before its
 * shot, so a pane that stops rendering the alert is red here too.
 *
 * Files land in `build/reports/dashboard-filters-screenshots/` as `filters-<state>-<mode>.png`.
 */
class DashboardFiltersShotsBrowserTest : DashboardBrowserSuite() {
    private fun shotDir(): Path = Paths.get("build", "reports", "dashboard-filters-screenshots").also { it.toFile().mkdirs() }

    private fun shot(name: String) {
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("filters-$name.png")))
    }

    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        val code = page.evaluate("() => window.__dpPage.code") as String?
        check(code == null) { "the board page failed to boot: $code" }
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
    }

    private val limit = "#dp-board-filters [data-dp-parameter=\"limit\"]"

    @Test
    fun `the handback screenshots - the long list in the panel and the drawer, and the validation state, light and dark`() {
        startTrace()
        val root = ready("dpfshot")
        val board = seedAcceptanceBoard(root)
        for (mode in listOf("light", "dark")) {
            page.setViewportSize(DESKTOP_WIDTH, DESKTOP_HEIGHT)
            openBoard(board)
            ensureTheme(mode)
            openBoard(board)
            shot("panel-$mode")

            // The validation state: past the declared maximum (100), the row carries the alert.
            page.fill("$limit input", "500")
            page.keyboard().press("Tab")
            page.waitForSelector("$limit .dp-dashboard-parameter-error[role=alert]")
            println(
                "493-shots $mode validation=" + page.textContent("$limit .dp-dashboard-parameter-error") +
                    " shown-value=" + page.inputValue("$limit input"),
            )
            page.locator("$limit input").scrollIntoViewIfNeeded()
            shot("validation-$mode")

            page.setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
            openBoard(board)
            page.click("#dp-board-filters-trigger")
            page.waitForFunction(
                """() => { const panel = document.getElementById('dp-board-filters'); const style = getComputedStyle(panel);
                  return panel.classList.contains('is-open') && style.visibility === 'visible' && style.transform === 'none'; }""",
            )
            shot("drawer-$mode")
            page.evaluate("() => { const p = document.getElementById('dp-board-filters'); p.scrollTop = p.scrollHeight; }")
            shot("drawer-end-$mode")
        }
    }

    private companion object {
        const val DESKTOP_WIDTH = 1440
        const val DESKTOP_HEIGHT = 900
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844
    }
}
