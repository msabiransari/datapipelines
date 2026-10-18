package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #573 — <main>'s padding by the viewport's band and its 1500px frame, MEASURED in Chromium (the
 * application-shell record §4, ui-screens §3.1/§3.6). The bands: 40px from 1600, 32 from 1101 to
 * 1599, 24 from 761 to 1100, 20 at 760 and below, on all four sides, through `--app-main-pad`. The
 * 1512 reference: 202 menu + 300 browser leave a 1010px frame and 946px inside its padding. The
 * frame: at most 1500px outer width, centred in the content track — except on a page whose root
 * carries `app-canvas` (the editors, the explorer, the board, the execution result), which keeps
 * the whole track. And no full-height canvas overflows <main>: their height calcs subtract the
 * same token, which is what a band change would otherwise break by twice the difference.
 *
 * Every reading is printed (`573-measure|case|label|value`, the handback's base/tip table) and
 * asserted softly, so a red names every number that moved.
 */
class MainFrameBrowserTest : DashboardBrowserSuite() {
    private val readings = linkedMapOf<String, Double?>()

    /** <main>'s computed padding and box, the page's horizontal fit and <main>'s vertical overflow. */
    @Suppress("UNCHECKED_CAST")
    private fun readMain(prefix: String) {
        val values =
            page.evaluate(
                """() => {
                  const m = document.getElementById('app-main');
                  const cs = getComputedStyle(m);
                  const r = m.getBoundingClientRect();
                  const visible = id => { const e = document.getElementById(id);
                    return e && e.getClientRects().length > 0 && getComputedStyle(e).position !== 'fixed'
                      ? e.getBoundingClientRect().width : 0; };
                  const doc = document.documentElement;
                  return { padTop: parseFloat(cs.paddingTop), padRight: parseFloat(cs.paddingRight),
                           padBottom: parseFloat(cs.paddingBottom), padLeft: parseFloat(cs.paddingLeft),
                           left: r.left, right: r.right, width: r.width, clientWidth: m.clientWidth,
                           track: window.innerWidth - visible('app-rail') - visible('app-browser'),
                           rail: visible('app-rail'), docOverflowX: doc.scrollWidth - doc.clientWidth,
                           mainOverflowY: m.scrollHeight - m.clientHeight,
                           innerHeight: m.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom),
                           canvasHeight: document.querySelector('.app-main > .app-canvas')?.getBoundingClientRect().height ?? 0,
                           canvas: document.querySelector('.app-main > .app-canvas') ? 1 : 0 };
                }""",
            ) as Map<String, Number>
        values.forEach { (key, value) -> readings["$prefix.$key"] = value.toDouble() }
    }

    private fun printReadings(case: String) {
        readings.forEach { (label, value) -> println("573-measure|$case|$label|$value") }
    }

    private fun near(
        label: String,
        expected: Double,
        tolerance: Double = 0.5,
    ) = withClue(label) { readings[label].shouldNotBeNull().shouldBeBetween(expected - tolerance, expected + tolerance, 0.0) }

    private fun reading(label: String): Double = withClue(label) { readings[label].shouldNotBeNull() }

    private fun settle() {
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("573-$name.png")).setFullPage(false))
    }

    /** The in-page REST call (CompactScaleBrowserTest's shape): a draft pipeline needs no datasource. */
    @Suppress("UNCHECKED_CAST")
    private fun createDraftPipeline(name: String): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const r = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: args.body });
                  return { status: r.status, body: (await r.text()).slice(0, 2000) };
                }""",
                mapOf(
                    "body" to
                        """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"573 frame",""" +
                        """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                        """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
                ),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        val id = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(result["body"] as String)
        return requireNotNull(id) { "no id in the create response: ${result["body"]}" }.groupValues[1]
    }

    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.locator("#dp-board .dp-dashboard-card").first().waitFor()
    }

    /** The newest execution's detail page, through the history screen's own row click. */
    private fun openExecutionDetail() {
        page.navigate("$baseUrl/executions")
        page.locator(".dt-frame .ds-table tbody tr").first().waitFor()
        page.locator(".dt-frame .ds-table tbody tr").first().click()
        page.waitForURL("**/executions/**")
        page.locator(".app-main .ds-headline").first().waitFor()
    }

    /** One band: all four sides of <main> at [expected], and no page-level horizontal scroll. */
    private fun band(
        case: String,
        width: Int,
        height: Int,
        expected: Double,
    ) {
        ready("mf$width")
        page.setViewportSize(width, height)
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".app-main .app-page-h h1")
        settle()
        readMain("main")
        printReadings(case)
        assertSoftly {
            listOf("padTop", "padRight", "padBottom", "padLeft").forEach { near("main.$it", expected) }
            near("main.docOverflowX", 0.0)
        }
    }

    @Test
    fun `from 1600 main pads 40 on all four sides`() {
        band("band-1600", WIDE_BAND_W, BAND_H, 40.0)
    }

    @Test
    fun `from 1101 to 1599 main pads 32 on all four sides`() {
        band("band-1512", REFERENCE_W, REFERENCE_H, 32.0)
    }

    @Test
    fun `from 761 to 1100 main pads 24 on all four sides`() {
        band("band-900", TABLET_W, TABLET_H, 24.0)
    }

    @Test
    fun `at 760 and below main pads 20 on all four sides and the page never scrolls sideways`() {
        band("band-390", PHONE_W, PHONE_H, 20.0)
    }

    /** ui-screens §3.4's old Known note measured these six screens overflowing a 390px document; none may. */
    @Test
    fun `at 390 none of the shell's screens scrolls the page sideways`() {
        ready("mfphone")
        page.setViewportSize(PHONE_W, PHONE_H)
        for (screen in listOf("dashboard", "executions", "pipelines", "templates", "datasources", "api-console")) {
            page.navigate("$baseUrl/$screen")
            page.locator(".app-main .ds-headline").first().waitFor()
            settle()
            readMain(screen)
        }
        printReadings("phone-390")
        assertSoftly {
            for (screen in listOf("dashboard", "executions", "pipelines", "templates", "datasources", "api-console")) {
                near("$screen.docOverflowX", 0.0)
            }
        }
    }

    @Test
    fun `the 1512 reference - a 1010 frame and 946 inside its padding`() {
        ready("mfref")
        page.setViewportSize(REFERENCE_W, REFERENCE_H)
        openBrowserTree("pipelines")
        settle()
        readMain("main")
        val browserWidth = page.evaluate("() => document.getElementById('app-browser').getBoundingClientRect().width")
        readings["browser.width"] = (browserWidth as Number).toDouble()
        val content = reading("main.clientWidth") - reading("main.padLeft") - reading("main.padRight")
        readings["main.content"] = content
        printReadings("reference-1512")
        shot("pipelines-1512")
        assertSoftly {
            near("main.rail", 202.0)
            near("browser.width", 300.0)
            near("main.width", 1010.0)
            near("main.content", 946.0)
        }
    }

    @Test
    fun `the frame binds at 1500 and centres in its track - expanded and compact menu`() {
        ready("mfbind")
        page.setViewportSize(FRAME_W, FRAME_H)
        page.navigate("$baseUrl/settings")
        page.locator(".app-main .ds-headline").first().waitFor()
        settle()
        readMain("expanded")
        shot("settings-2200-framed")
        page.click("#rail-collapse")
        settle()
        readMain("compact")
        printReadings("frame-2200")
        assertSoftly {
            for ((state, rail) in listOf("expanded" to 202.0, "compact" to 64.0)) {
                near("$state.rail", rail)
                near("$state.width", FRAME_MAX)
                near("$state.left", rail + (FRAME_W - rail - FRAME_MAX) / 2, 1.0)
                withClue("$state.right") { reading("$state.right") shouldBeLessThanOrEqual FRAME_W.toDouble() }
                near("$state.canvas", 0.0)
            }
        }
    }

    @Test
    @Suppress("LongMethod") // one walk over the five canvases: each opt-out is read on its own page
    fun `a canvas page opts out of the frame and keeps the whole track`() {
        startTrace()
        val root = ready("mfcanvas")
        page.setViewportSize(FRAME_W, FRAME_H)
        val board = seedBoardWithGrid(root)
        val pipeline = createDraftPipeline("$root/frame/probe")
        createTemplate("test/${root}_frame.sql", "SELECT 1 AS one")

        openBoard(board)
        settle()
        readMain("board")
        page.navigate("$baseUrl/pipelines/$pipeline")
        page.waitForSelector(".pe-root")
        settle()
        readMain("editor")
        shot("editor-2200-canvas")
        page.navigate("$baseUrl/templates/test/${root}_frame.sql")
        page.waitForSelector(".tw-root")
        settle()
        readMain("template")
        page.navigate("$baseUrl/schedules")
        page.waitForSelector(".tplx-page")
        settle()
        readMain("schedules")
        // The board's refresh above is what gives the history a row.
        openExecutionDetail()
        settle()
        readMain("execution")
        printReadings("canvas-2200")
        assertSoftly {
            for (canvas in listOf("board", "editor", "template", "schedules", "execution")) {
                near("$canvas.canvas", 1.0)
                near("$canvas.width", reading("$canvas.track"), 1.0)
                // Non-vacuity: the track is wider than the frame, so a framed page would read 1500.
                withClue("$canvas.track") { reading("$canvas.track") shouldBeGreaterThan FRAME_MAX }
            }
        }
    }

    /**
     * The canvases' height calcs subtract the band twice (`--app-main-pad`): each canvas root fills <main>'s inner
     * height, less at most the 1px the calcs keep for the header's border. The editor and the template workspace
     * therefore never scroll <main>. The board's root fills it too, but its content does not yet shrink into
     * it (`.dp-ws-root` keeps its natural height: 31px past the fold at the base, before #573 — a follow-up), so its
     * overflow is printed, not asserted.
     */
    @Test
    fun `every full-height canvas fills main's inner height at the 1512 band`() {
        startTrace()
        val root = ready("mffit")
        page.setViewportSize(REFERENCE_W, REFERENCE_H)
        val board = seedBoardWithGrid(root)
        val pipeline = createDraftPipeline("$root/fit/probe")
        createTemplate("test/${root}_fit.sql", "SELECT 1 AS one")

        page.navigate("$baseUrl/pipelines/$pipeline")
        page.waitForSelector(".pe-root")
        settle()
        readMain("editor")
        page.navigate("$baseUrl/templates/test/${root}_fit.sql")
        page.waitForSelector(".tw-root")
        settle()
        readMain("template")
        openBoard(board)
        settle()
        readMain("board")
        printReadings("fit-1512")
        assertSoftly {
            for (canvas in listOf("editor", "template", "board")) {
                near("$canvas.padTop", 32.0)
                readings["$canvas.fill"] = reading("$canvas.innerHeight") - reading("$canvas.canvasHeight")
                near("$canvas.fill", 0.5, 1.0)
            }
            near("editor.mainOverflowY", 0.0)
            near("template.mainOverflowY", 0.0)
        }
    }

    private companion object {
        const val WIDE_BAND_W = 1600
        const val BAND_H = 900
        const val REFERENCE_W = 1512
        const val REFERENCE_H = 982
        const val TABLET_W = 900
        const val TABLET_H = 800
        const val PHONE_W = 390
        const val PHONE_H = 844
        const val FRAME_W = 2200
        const val FRAME_H = 1000
        const val FRAME_MAX = 1500.0
    }
}
