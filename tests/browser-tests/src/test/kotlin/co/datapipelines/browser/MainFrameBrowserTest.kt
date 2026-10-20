package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
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
 * #613 — the phone top bar pads the same band token (one left edge with <main>); above 760 it keeps its 24.
 * #610 — the phone bar's breadcrumb: at 760px and below the bar shows none of it (the frozen
 * prototype's rule) and every control keeps its ≥48px box on one line at the 320px floor width.
 *
 * Every reading is printed (`573-measure|case|label|value`, the handback's base/tip table) and
 * asserted softly, so a red names every number that moved.
 *
 * `LargeClass` is suppressed with the house reason (DashboardBrowserSuite): the phone-bar cases
 * share this class's readings map, `readBarFit`, `settle` and `shot` with the frame and pill
 * cases — a split would fork the measurement harness for no reader's benefit.
 */
@Suppress("LargeClass")
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

    /**
     * #614 — the status pill's painted left edge beside the content edge it should share: <main>'s left plus its
     * padding. The pill is raised by the shell's OWN `showStatusPill` (what a held navigation does after 150ms), so
     * the box is rendered, not a `hidden` element's computed style; [shotName] photographs it before it is hidden.
     */
    private fun readPill(
        prefix: String,
        shotName: String? = null,
    ) {
        page.evaluate("() => window.DpShell.showStatusPill(document)")
        settle()
        val left =
            page.evaluate(
                """() => { const p = document.getElementById('app-status-pill');
                  return p.getClientRects().length > 0 ? p.getBoundingClientRect().left : -1; }""",
            ) as Number
        if (shotName != null) shot(shotName)
        page.evaluate("() => window.DpShell.hideStatusPill(document)")
        readMain(prefix)
        readings["$prefix.pill"] = left.toDouble()
        readings["$prefix.contentEdge"] = reading("$prefix.left") + reading("$prefix.padLeft")
    }

    /**
     * The top bar's computed side padding, its box's left edge and its first visible child's left
     * edge (`#rail-open` at 390, the brand on the anonymous bar) — #613's readings.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readBar(prefix: String) {
        val values =
            page.evaluate(
                """() => {
                  const bar = document.querySelector('.app-topbar');
                  const cs = getComputedStyle(bar);
                  const r = bar.getBoundingClientRect();
                  const first = Array.from(bar.children).find(e => e.getClientRects().length > 0);
                  return { padLeft: parseFloat(cs.paddingLeft), padRight: parseFloat(cs.paddingRight),
                           left: r.left,
                           firstChildLeft: first ? first.getBoundingClientRect().left : -1 };
                }""",
            ) as Map<String, Number>
        values.forEach { (key, value) -> readings["$prefix.$key"] = value.toDouble() }
    }

    /**
     * #610 — the phone bar's fit, one reading set per page: how many children the bar displays,
     * whether the breadcrumb is in it at all (`crumbsDisplayed`), whether its leaf clips
     * (`crumbClipped`, the A.0 ellipsis read), whether the crumb group is displayed (the band
     * guard: leaf-only at 761, group back at 1101), whether everything sits on one line
     * (`oneLine`, the vertical-centre spread), the smallest control box (`minControlW/H` — the
     * record's ≥48px touch floor over the four controls), the gap between the bar's content
     * right edge and the avatar's right edge (`rightEdgeGap` — the right group must stay pinned
     * to the edge once the crumbs' `margin-right: auto` stops doing it), and the document's
     * horizontal overflow (the house readMain shape, `clientWidth`, not `innerWidth`, so a
     * vertical scrollbar's width can never masquerade as horizontal overflow).
     */
    @Suppress("UNCHECKED_CAST")
    private fun readBarFit(prefix: String) {
        val values =
            page.evaluate(
                """() => {
                  const bar = document.querySelector('.app-topbar');
                  const doc = document.documentElement;
                  const kids = Array.from(bar.children).filter(e => e.getClientRects().length > 0);
                  const centers = kids.map(e => { const r = e.getBoundingClientRect(); return (r.top + r.bottom) / 2; });
                  const controlBoxes = ['rail-open', 'mode-toggle', 'app-avatar', 'browser-open']
                    .map(id => document.getElementById(id))
                    .filter(e => e && e.getClientRects().length > 0)
                    .map(e => e.getBoundingClientRect());
                  const cs = getComputedStyle(bar);
                  const contentRight = bar.getBoundingClientRect().right - parseFloat(cs.paddingRight);
                  const avatar = document.getElementById('app-avatar');
                  const crumbs = document.getElementById('app-crumbs');
                  const group = crumbs ? crumbs.querySelector('.app-crumb-group') : null;
                  const leaf = crumbs ? crumbs.querySelector('.app-crumb-page') : null;
                  const crumbsDisplayed = crumbs !== null && getComputedStyle(crumbs).display !== 'none';
                  return { count: kids.length,
                           crumbsDisplayed: crumbsDisplayed ? 1 : 0,
                           crumbClipped: crumbsDisplayed && leaf ? (leaf.scrollWidth > leaf.clientWidth ? 1 : 0) : 0,
                           crumbGroupDisplayed: group && group.getClientRects().length > 0 ? 1 : 0,
                           oneLine: Math.max(...centers) - Math.min(...centers),
                           minControlW: Math.min(...controlBoxes.map(r => r.width)),
                           minControlH: Math.min(...controlBoxes.map(r => r.height)),
                           rightEdgeGap: avatar && avatar.getClientRects().length > 0
                             ? contentRight - avatar.getBoundingClientRect().right : -1,
                           docOverflowX: doc.scrollWidth - doc.clientWidth };
                }""",
            ) as Map<String, Number>
        values.forEach { (key, value) -> readings["$prefix.$key"] = value.toDouble() }
    }

    private fun printReadings(case: String) {
        readings.forEach { (label, value) -> println("573-measure|$case|$label|$value") }
    }

    /**
     * #610 — the anonymous bar's horizontal fit and the brand's one-line spread (the tile vs
     * the wordmark, NOT the logo SVG's own internals).
     */
    @Suppress("UNCHECKED_CAST")
    private fun readAnonBar(prefix: String) {
        val values =
            page.evaluate(
                """() => {
                  const bar = document.querySelector('.app-topbar-anon');
                  const doc = document.documentElement;
                  const centers = Array.from(bar.querySelectorAll('.app-brand > *'))
                    .filter(e => e.getClientRects().length > 0)
                    .map(e => { const r = e.getBoundingClientRect(); return (r.top + r.bottom) / 2; });
                  return { docOverflowX: doc.scrollWidth - doc.clientWidth,
                           brandOneLine: Math.max(...centers) - Math.min(...centers) };
                }""",
            ) as Map<String, Number>
        values.forEach { (key, value) -> readings["$prefix.$key"] = value.toDouble() }
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

    /** #613 (c): the phone bar pads the band token, so the bar and <main> share a content left edge. */
    @Test
    fun `at 390 the top bar pads 20 and shares main's left edge`() {
        ready("mftop")
        page.setViewportSize(PHONE_W, PHONE_H)
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".app-main .app-page-h h1")
        settle()
        readMain("main")
        readBar("bar")
        readings["main.contentLeft"] = reading("main.left") + reading("main.padLeft")
        readings["bar.contentLeft"] = reading("bar.left") + reading("bar.padLeft")
        readings["bar.contentLeft - main.contentLeft"] = readings["bar.contentLeft"]!! - readings["main.contentLeft"]!!
        printReadings("bar-390")
        shot("613-pipelines-390-bar")
        assertSoftly {
            near("bar.padLeft", 20.0)
            near("bar.padRight", 20.0)
            near("bar.contentLeft - main.contentLeft", 0.0)
            near("bar.firstChildLeft", readings["bar.contentLeft"]!!)
        }
    }

    /** #613 ruling 1's second half: the desktop bar is NOT banded — 24px at every width above 760. */
    @Test
    fun `above 760 the top bar keeps 24 while main follows its band`() {
        band("bar-1512", REFERENCE_W, REFERENCE_H, 32.0)
        readings.clear() // band() printed <main>'s readings; the top bar's print alone under this case
        readBar("bar")
        printReadings("bar-1512-topbar")
        near("bar.padLeft", 24.0)
        // The same live session (a second ready() would 302 /login past the form), one band up:
        // <main> moves to 40, the bar stays 24.
        page.setViewportSize(WIDE_BAND_W, BAND_H)
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".app-main .app-page-h h1")
        settle()
        readMain("main")
        readBar("bar")
        printReadings("bar-1600-topbar")
        assertSoftly {
            near("main.padLeft", 40.0)
            near("bar.padLeft", 24.0)
        }
    }

    /**
     * #613: the anonymous shell's bar shares `.app-topbar`, so it moves with the declaration. Its
     * pages are the error pages (the auth layout carries no top bar — 090 §C): the 614-signed-out
     * case's idiom, an unknown doc slug answered 404 in the anonymous shell. A fresh context that
     * never signs in IS anonymous.
     */
    @Test
    fun `the anonymous bar at 390 pads 20 too`() {
        page.setViewportSize(PHONE_W, PHONE_H)
        page.navigate("$baseUrl/docs/no-such-doc-613").status() shouldBe 404
        page.waitForSelector(".app-topbar-anon")
        settle()
        readBar("anon")
        printReadings("anon-390")
        near("anon.padLeft", 20.0)
    }

    /**
     * #610 — one phone-band walk over the record's three bar pages (a family page, the longest
     * leaf, the off-rail crumb with `#browser-open` hidden): each page's bar is read and then
     * asserted softly. Red at the base: the crumbs sit IN the phone bar and the leaf clips at
     * 320 (A.0: "Pipelines" 79px in 56, "Visualizations" 121px in 56). Green once the phone band
     * drops the breadcrumb — the fix's contract.
     */
    private fun phoneBarWalk(
        width: Int,
        height: Int,
        case: String,
    ) {
        ready("mf610")
        page.setViewportSize(width, height)
        for (screen in listOf("pipelines", "overview/visualizations", "settings")) {
            page.navigate("$baseUrl/$screen")
            // The element stays in the DOM below the band (#610) — shell.js still writes
            // into it — but the bar displays none of it, so ATTACHED, not the default
            // visible: visibility is the assertion's job, not the wait's.
            page.waitForSelector(
                "#app-crumbs .app-crumb-page",
                Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED),
            )
            settle()
            readBarFit("bar.$screen")
            printReadings("$case-$screen")
            shot("610-$screen-$width-bar")
            assertSoftly {
                withClue(screen) {
                    reading("bar.$screen.crumbsDisplayed") shouldBe 0.0
                    reading("bar.$screen.crumbClipped") shouldBe 0.0
                    reading("bar.$screen.oneLine").shouldBeLessThanOrEqual(1.0)
                    reading("bar.$screen.minControlW").shouldBeGreaterThanOrEqual(48.0)
                    reading("bar.$screen.minControlH").shouldBeGreaterThanOrEqual(48.0)
                    near("bar.$screen.rightEdgeGap", 0.0)
                    near("bar.$screen.docOverflowX", 0.0)
                }
            }
        }
    }

    /** #610 — at the record's floor width (320, the #607 shape) the bar shows no breadcrumb and every control keeps its box on one line. */
    @Test
    fun `at 320 the top bar holds every control on one line and shows no breadcrumb`() {
        phoneBarWalk(NARROW_W, NARROW_H, "610-320")
    }

    /** #610 — 390, the phone reference: the same contract one band past the floor width. */
    @Test
    fun `at 390 likewise the top bar holds every control on one line and shows no breadcrumb`() {
        phoneBarWalk(PHONE_W, PHONE_H, "610-390")
    }

    /**
     * #610's band guard — the hide must stay inside the phone band: at 761 the breadcrumb is
     * back, leaf-only (110's rule, the ≤1100 block untouched); at 1101 the group joins it.
     * Green at the base AND after the fix, in both directions of the gate log.
     */
    @Test
    fun `at 761 the leaf-only breadcrumb is back and at 1101 the group joins it`() {
        ready("mf610band")
        page.setViewportSize(CRUMB_LEAF_W, CRUMB_BAND_H)
        page.navigate("$baseUrl/pipelines")
        // ATTACHED, not visible: a hide that leaks out of the phone band (#610) must fail
        // the crumbsDisplayed ASSERTION, not time out the wait.
        page.waitForSelector(
            "#app-crumbs .app-crumb-page",
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED),
        )
        settle()
        readBarFit("bar.761")
        printReadings("610-761")
        shot("610-pipelines-761-bar")
        assertSoftly {
            reading("bar.761.crumbsDisplayed") shouldBe 1.0
            reading("bar.761.crumbGroupDisplayed") shouldBe 0.0
        }
        page.setViewportSize(CRUMB_GROUP_W, CRUMB_BAND_H)
        settle()
        readBarFit("bar.1101")
        printReadings("610-1101")
        shot("610-pipelines-1101-bar")
        assertSoftly {
            reading("bar.1101.crumbsDisplayed") shouldBe 1.0
            reading("bar.1101.crumbGroupDisplayed") shouldBe 1.0
        }
    }

    /**
     * #610 — the anonymous bar (the default layout signed out, `.app-topbar-anon`) at the floor
     * width: the brand's tile and wordmark on one line (their centres within 1px — the logo
     * SVG's own internals are geometry, not line boxes) and no sideways scroll. A fresh context
     * that never signs in IS anonymous; the auth layout carries no top bar at all (090 §C), so
     * the page is the unknown-doc 404 — the 613 case's shape, one width lower.
     */
    @Test
    fun `the anonymous bar at 320 keeps the brand on one line`() {
        page.setViewportSize(NARROW_W, NARROW_H)
        page.navigate("$baseUrl/docs/no-such-doc-610").status() shouldBe 404
        page.waitForSelector(".app-topbar-anon")
        settle()
        readAnonBar("anon")
        printReadings("610-anon-320")
        shot("610-login-320-bar")
        assertSoftly {
            near("anon.docOverflowX", 0.0)
            reading("anon.brandOneLine").shouldBeLessThanOrEqual(1.0)
        }
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
     * #614 — on a framed page the pill sits on the frame's content edge, not the track's: at 2200 the frame is
     * centred, so the old `menu + browser + pad` offset put the pill 249px (expanded) left of the content.
     */
    @Test
    fun `the status pill sits on the frame's content edge at 2200 - expanded and compact menu`() {
        ready("mfpill")
        page.setViewportSize(FRAME_W, FRAME_H)
        page.navigate("$baseUrl/settings")
        page.locator(".app-main .ds-headline").first().waitFor()
        settle()
        readPill("expanded", shotName = "614-settings-2200-pill")
        page.click("#rail-collapse")
        settle()
        readPill("compact")
        printReadings("pill-2200")
        assertSoftly {
            for ((state, rail) in listOf("expanded" to 202.0, "compact" to 64.0)) {
                near("$state.pill", reading("$state.contentEdge"), 1.0)
                // Non-vacuity: the frame binds here, so the content edge is past the track's.
                withClue("$state.contentEdge") { reading("$state.contentEdge") shouldBeGreaterThan rail + 40.0 + 1.0 }
            }
        }
    }

    /** #614 — a canvas keeps the whole track, so its pill stays on the track's content edge: menu + browser + pad. */
    @Test
    fun `on a canvas the status pill sits on the track's content edge at 2200`() {
        val root = ready("mfpillcanvas")
        page.setViewportSize(FRAME_W, FRAME_H)
        val pipeline = createDraftPipeline("$root/pill/probe")
        page.navigate("$baseUrl/pipelines/$pipeline")
        page.waitForSelector(".pe-root")
        settle()
        readPill("editor", shotName = "614-editor-2200-pill")
        val browserWidth = page.evaluate("() => document.getElementById('app-browser').getBoundingClientRect().width")
        readings["editor.browser"] = (browserWidth as Number).toDouble()
        printReadings("pill-canvas-2200")
        assertSoftly {
            near("editor.canvas", 1.0)
            withClue("editor.browser") { reading("editor.browser") shouldBeGreaterThan 0.0 }
            near("editor.pill", 202.0 + reading("editor.browser") + 40.0, 1.0)
            near("editor.pill", reading("editor.contentEdge"), 1.0)
        }
    }

    /**
     * #614 — the anonymous shell (signed out: no menu, no browser) frames <main> in the whole window; the pill follows.
     * Its pages are the error pages: the public docs render in the site's chrome, an unknown doc in this shell.
     */
    @Test
    fun `signed out the status pill sits on the frame's content edge at 2200`() {
        page.setViewportSize(FRAME_W, FRAME_H)
        page.navigate("$baseUrl/docs/no-such-doc-614").status() shouldBe 404
        page.locator(".app-shell-anon .app-main").first().waitFor()
        settle()
        readPill("anon", shotName = "614-docs-anon-2200-pill")
        printReadings("pill-anon-2200")
        assertSoftly {
            near("anon.rail", 0.0)
            near("anon.pill", reading("anon.contentEdge"), 1.0)
        }
    }

    /** #614 — where the frame does not bind (a track of 1500 or less) the pill does not move: 202 + 32 at 1512. */
    @Test
    fun `at 1512 the status pill keeps its offset`() {
        ready("mfpillref")
        page.setViewportSize(REFERENCE_W, REFERENCE_H)
        page.navigate("$baseUrl/settings")
        page.locator(".app-main .ds-headline").first().waitFor()
        settle()
        readPill("settings")
        printReadings("pill-1512")
        assertSoftly {
            near("settings.pill", 234.0, 1.0)
            near("settings.pill", reading("settings.contentEdge"), 1.0)
        }
    }

    /**
     * The canvases' height calcs subtract the band twice (`--app-main-pad`): each canvas root fills <main>'s inner
     * height, less at most the 1px the calcs keep for the header's border. The editor and the template workspace
     * therefore never scroll <main>. The board's flex chain shrinks its panes into that height too (#612),
     * keeping the activity dock visible without scrolling <main>.
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
            near("board.mainOverflowY", 0.0)
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
        const val NARROW_W = 320
        const val NARROW_H = 568
        const val CRUMB_LEAF_W = 761
        const val CRUMB_GROUP_W = 1101
        const val CRUMB_BAND_H = 900
        const val FRAME_W = 2200
        const val FRAME_H = 1000
        const val FRAME_MAX = 1500.0
    }
}
