package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** #503 — the rail's maximum leaves a usable main track on real dashboard pages. */
class RailMaximumBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `rail maximum preserves main on a dashboard with parameters`() {
        startTrace()
        val root = ready("dprailparams")
        page.setViewportSize(1280, 800)
        exerciseMaximum(seedParameterisedBoard(root))
    }

    @Test
    fun `rail maximum preserves main on a dashboard without parameters`() {
        startTrace()
        val root = ready("dprailplain")
        page.setViewportSize(1280, 800)
        exerciseMaximum(seedHungBoard(root))
    }

    private fun exerciseMaximum(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        awaitBoard()
        resizeRail("End")
        assertGeometry(960)
        resizeRail("Home")
        assertGeometry(232)

        page.evaluate(
            """() => localStorage.setItem('dp-rail-width:' + document.documentElement.dataset.dpWorkspace, '2000')""",
        )
        page.reload()
        awaitBoard()
        assertGeometry(960)
        page.evaluate("() => document.documentElement.style.getPropertyValue('--app-rail-preferred')") shouldBe "2000px"
        page.evaluate(
            "() => localStorage.getItem('dp-rail-width:' + document.documentElement.dataset.dpWorkspace)",
        ) shouldBe "2000"

        // The live resize handler must use the new maximum without persisting the temporary cap.
        page.setViewportSize(1440, 800)
        page.waitForFunction("() => document.getElementById('rail-resize').getAttribute('aria-valuemax') === '1120'")
        assertGeometry(1120, maximum = 1120)
        page.evaluate("() => document.documentElement.style.getPropertyValue('--app-rail-preferred')") shouldBe "2000px"
        page.setViewportSize(1280, 800)
        page.waitForFunction("() => document.getElementById('rail-resize').getAttribute('aria-valuemax') === '960'")
        assertGeometry(960)
        resizeRail("Shift+ArrowRight")
        assertGeometry(960)
        resizeRail("Home")
        assertGeometry(232)

        val handle = checkNotNull(page.locator("#rail-resize").boundingBox())
        page.mouse().move(handle.x + handle.width / 2, handle.y + handle.height / 2)
        page.mouse().down()
        page.mouse().move(1270.0, handle.y + handle.height / 2)
        page.mouse().up()
        assertGeometry(960)
        resizeRail("Home")
        assertGeometry(232)
    }

    private fun awaitBoard() {
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        page.evaluate("() => window.__dpPage.code") shouldBe null
    }

    private fun resizeRail(key: String) {
        page.locator("#rail-resize").focus()
        page.keyboard().press(key)
    }

    private fun assertGeometry(
        expectedRail: Int,
        maximum: Int = 960,
    ) {
        val geometry =
            page.evaluate(
                """() => { const rail = document.getElementById('app-rail');
                  const main = document.getElementById('app-main');
                  const separator = document.getElementById('rail-resize');
                  return { rail: rail.getBoundingClientRect().width, main: main.clientWidth, scroll: main.scrollWidth,
                    now: separator.getAttribute('aria-valuenow'), max: separator.getAttribute('aria-valuemax') }; }""",
            ) as Map<*, *>
        withClue("rail/main geometry at $expectedRail px: $geometry") {
            // Check main first: the base's End must falsify the actual zero-width-track defect.
            ((geometry["main"] as Number).toInt() >= 320) shouldBe true
            geometry["scroll"] shouldBe geometry["main"]
            (abs((geometry["rail"] as Number).toDouble() - expectedRail) <= 1.0) shouldBe true
            geometry["now"] shouldBe expectedRail.toString()
            geometry["max"] shouldBe maximum.toString()
            documentOverflowsX() shouldBe false
        }
    }
}
