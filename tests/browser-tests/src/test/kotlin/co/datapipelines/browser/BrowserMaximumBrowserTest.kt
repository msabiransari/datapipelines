package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * #503, re-cut by #554 — the resource browser's maximum leaves a usable main track on real dashboard
 * pages. The width the reader can give the browser is `min(--app-browser-max, viewport - menu - main
 * minimum)` and the stylesheet's clamp lifts its `--app-browser-min` floor over the band default;
 * at 900 px the compact menu (64) allows the full ceiling, the expanded menu (202) only
 * `900 - 202 - --app-main-min-width`, and the clamp moves the rendered width without ever rewriting
 * the stored preference (record §4 "Browser resizing"). Before #554 this pinned the rail's 960/1120
 * maximum over a 232 px floor. #788: every expected width is derived from the range the page itself
 * reports ([browserRange]) — app.css is the range's one source.
 */
class BrowserMaximumBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `browser maximum preserves main on a dashboard with parameters`() {
        startTrace()
        val root = ready("dpbrowserparams")
        page.setViewportSize(BAND_VIEWPORT, 800)
        exerciseMaximum(seedParameterisedBoard(root))
    }

    @Test
    fun `browser maximum preserves main on a dashboard without parameters`() {
        startTrace()
        val root = ready("dpbrowserplain")
        page.setViewportSize(BAND_VIEWPORT, 800)
        exerciseMaximum(seedHungBoard(root))
    }

    private fun exerciseMaximum(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        awaitBoard()
        val range = browserRange(page)
        // In the 761–1100 band the menu is compact (64) and the band default sits under the
        // range's floor, so the clamp lifts the effective width to the floor itself.
        val bandMaximum = range.maximumAt(BAND_VIEWPORT, 64)
        assertGeometry(range.effectiveDefault, maximum = bandMaximum, range = range)
        resizeBrowser("End")
        assertGeometry(bandMaximum, maximum = bandMaximum, range = range)
        preference() shouldBe "${bandMaximum}px"

        // The expanded menu takes 138 px more: the clamp narrows the column and main keeps its minimum.
        page.click("#rail-collapse")
        val expandedMaximum = range.maximumAt(BAND_VIEWPORT, 202)
        assertGeometry(expandedMaximum, maximum = expandedMaximum, range = range)
        preference() shouldBe "${bandMaximum}px"
        resizeBrowser("ArrowRight")
        assertGeometry(expandedMaximum, maximum = expandedMaximum, range = range)
        preference() shouldBe "${bandMaximum}px"
        stored() shouldBe "$bandMaximum"
        page.click("#rail-collapse")
        assertGeometry(bandMaximum, maximum = bandMaximum, range = range)
        resizeBrowser("Home")
        assertGeometry(range.min, maximum = bandMaximum, range = range)

        // A value outside the range (#784's raised floor: 256 was a preference under the old
        // 240 floor and must now be removed) is not a preference: removed before the first
        // paint, the band default shows. Both one-past-the-bounds values derive from the read
        // range; the retired 256 stays as the named history case.
        listOf("2000", "${range.max + 1}", "256", "${range.min - 1}").forEach { seed ->
            page.evaluate("value => localStorage.setItem('dp-browser-width:' + document.documentElement.dataset.dpWorkspace, value)", seed)
            page.reload()
            awaitBoard()
            assertGeometry(range.effectiveDefault, maximum = bandMaximum, range = range)
            preference() shouldBe ""
            stored() shouldBe null
        }

        // A wider window moves the range, never the preference; a drag past the maximum stops there.
        page.setViewportSize(1440, 800)
        val wide = browserRange(page)
        page.waitForFunction(
            "expected => document.getElementById('browser-boundary').getAttribute('aria-valuenow') === expected",
            "${wide.effectiveDefault}",
        )
        assertGeometry(wide.effectiveDefault, maximum = wide.maximumAt(1440, 202), range = wide)
        val handle = checkNotNull(page.locator("#browser-boundary").boundingBox())
        page.mouse().move(handle.x + handle.width / 2, handle.y + handle.height / 2)
        page.mouse().down()
        page.mouse().move(1430.0, handle.y + handle.height / 2)
        page.mouse().up()
        assertGeometry(wide.maximumAt(1440, 202), maximum = wide.maximumAt(1440, 202), range = wide)
        resizeBrowser("Home")
        assertGeometry(wide.min, maximum = wide.maximumAt(1440, 202), range = wide)
    }

    private fun awaitBoard() {
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        page.evaluate("() => window.__dpPage.code") shouldBe null
    }

    private fun resizeBrowser(key: String) {
        page.locator("#browser-boundary").focus()
        page.keyboard().press(key)
    }

    private fun preference() = page.evaluate("() => document.documentElement.style.getPropertyValue('--app-browser-preferred')")

    private fun stored() = page.evaluate("() => localStorage.getItem('dp-browser-width:' + document.documentElement.dataset.dpWorkspace)")

    private fun assertGeometry(
        expectedBrowser: Int,
        maximum: Int,
        range: BrowserRange,
    ) {
        val geometry =
            page.evaluate(
                """() => { const browser = document.getElementById('app-browser');
                  const main = document.getElementById('app-main');
                  const separator = document.getElementById('browser-boundary');
                  return { browser: browser.getBoundingClientRect().width, main: main.clientWidth, scroll: main.scrollWidth,
                    now: separator.getAttribute('aria-valuenow'), max: separator.getAttribute('aria-valuemax'),
                    min: separator.getAttribute('aria-valuemin') }; }""",
            ) as Map<*, *>
        withClue("browser/main geometry at $expectedBrowser px: $geometry") {
            ((geometry["main"] as Number).toInt() >= range.mainMin) shouldBe true
            geometry["scroll"] shouldBe geometry["main"]
            (abs((geometry["browser"] as Number).toDouble() - expectedBrowser) <= 1.0) shouldBe true
            geometry["now"] shouldBe expectedBrowser.toString()
            geometry["max"] shouldBe maximum.toString()
            // The separator's live ARIA floor is the stylesheet's, read through the same page.
            geometry["min"] shouldBe range.min.toString()
            documentOverflowsX() shouldBe false
        }
    }

    private companion object {
        /** In the 761–1100 band the menu is compact (64) and the band default sits under `--app-browser-min`. */
        const val BAND_VIEWPORT = 900
    }
}
