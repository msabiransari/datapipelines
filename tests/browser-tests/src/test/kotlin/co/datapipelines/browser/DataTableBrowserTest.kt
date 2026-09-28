package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 282 (#282) — the house data table in a real browser: `.dt-frame > .dt-viewport >
 * table.ds-table` with data-table.css and data-table.js, on the two pages the issue names.
 *
 *  - **The executions list** (a FIXED frame): after the viewport scrolls 200px the header row's
 *    top is still the viewport's top; `--dt-sbw` is the viewport's measured `offsetWidth −
 *    clientWidth` and the cap (the frame's ::after) is exactly that wide and the header's
 *    height tall; a header click reorders the rows and sets `aria-sort`; a drag on one column's
 *    handle changes that column's width and no other.
 *  - **The editor's result dock** (a frame FIXED by its pane, first column frozen): the header
 *    holds at `scrollTop = 200` and, at `scrollLeft = 300`, the first column's cells sit at the
 *    viewport's left edge.
 *  - **A page-flow table** (/admin/users): the header sticks to <main>, the page's own scroller,
 *    under the top bar.
 *
 * Real scrollbars. Playwright launches headless Chromium with `--hide-scrollbars`, which makes
 * every scrollbar zero-wide: the cap would be proven only at 0px, the one width that can hide a
 * wrong one. This suite runs its own Chromium WITHOUT that flag, so the scrollbar the owner saw
 * beside the header is really there and the cap has to cover it. Its own browser means its own
 * CSP collector (the suite's is wired to the shared browser): the same filter, asserted empty at
 * the end of every test. Screenshots (light and dark, 1440/1024/400) land in
 * build/reports/282-screenshots/ for the handback; the assertions are what fail the build.
 */
class DataTableBrowserTest : BrowserSuite() {
    private var ownContext: BrowserContext? = null

    /** The suite's `page`, re-pointed at a context of the real-scrollbar browser. */
    private fun useRealScrollbars() {
        val context = scrollbarBrowser().newContext(Browser.NewContextOptions().setViewportSize(1440, 900))
        context.onPage { p -> watchCsp(p) }
        ownContext = context
        page = context.newPage()
    }

    @AfterEach
    fun closeOwnContext() {
        ownContext?.close()
        ownContext = null
        withClue("Content-Security-Policy violations on the data-table pages") { drainOwnCsp().shouldBeEmpty() }
    }

    @Test
    fun `the executions list - the header holds, the cap covers the measured scrollbar, sort and resize act on one column`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dt-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        val pipelineId = seedPipelineWithRuns("test/dt-" + generatedPassword("p").take(8).lowercase(), admin.email, runs = 24)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/executions?pipeline_id=$pipelineId")
        val frame = "#execution-table .dt-frame"
        page.locator("$frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        page.locator("$frame table[data-dt-ready] tbody tr[data-href]").count() shouldBe 20

        // A dense table's frozen widths FILL its viewport and never pass it: whole pixels rounded
        // up used to overflow by a pixel or two, a horizontal scrollbar under every dense table.
        withClue("the locked table overflows its viewport sideways") { sideOverflow(frame) shouldBe 0 }

        // The header holds: scrolled 200px, the header row's top is still the viewport's top.
        val atRest = geometry(frame)
        withClue("the viewport must be scrollable by 200px for this to mean anything: $atRest") {
            (atRest["scrollable"] as Number).toDouble() shouldBeGreaterThan 200.0
        }
        scroll(frame, top = 200)
        val scrolled = geometry(frame)
        (scrolled["scrollTop"] as Number).toDouble() shouldBe (200.0 plusOrMinus 0.5)
        withClue("the header scrolled away with the rows: $scrolled") {
            (scrolled["headTop"] as Number).toDouble() shouldBe ((scrolled["viewportTop"] as Number).toDouble() plusOrMinus 1.0)
        }

        // The cap: --dt-sbw IS the measured scrollbar width, and the ::after is that wide and as
        // tall as the header. A real (non-overlay) scrollbar here, so the width is not zero.
        withClue("the cap's variables and box: $scrolled") {
            scrolled["sbw"] shouldBe "${scrolled["measured"]}px"
            (scrolled["measured"] as Number).toDouble() shouldBeGreaterThan 0.0
            scrolled["capContent"] shouldNotBe "none"
            scrolled["capWidth"] shouldBe "${scrolled["measured"]}px"
            scrolled["headH"] shouldBe "${Math.round((scrolled["headRowH"] as Number).toDouble())}px"
            scrolled["capHeight"] shouldBe scrolled["headH"]
        }
        shoot("executions-cap-1440-dark")

        sortFlipsTheDurationColumn(frame)

        // 288 #1, the htmx half: the pager replaces the whole frame, so page 2 arrives with
        // the server's order and no held sort — the state a fresh table has by construction.
        page.locator("#execution-table button:has-text('Next')").click()
        page.locator("$frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        settle()
        withClue("the htmx page swap cleared the client sort") {
            page.locator("$frame thead th:nth-child(5)").getAttribute("aria-sort") shouldBe "none"
            page.locator("$frame thead th:nth-child(5) .dt-sort").getAttribute("title") shouldBe "Sort this page by Duration"
        }

        aDragMovesOneColumn(frame)

        // Keyboard: a row is focusable and Enter opens it through the shell's own click handler.
        page.locator("$frame tbody tr[data-href]").first().focus()
        val target = page.locator("$frame tbody tr[data-href]").first().getAttribute("data-href")
        page.keyboard().press("Enter")
        page.waitForURL("**$target")
    }

    /**
     * 288 #4 — a FIXED viewport is a scroll area: the enhancer gives it `tabindex="0"` (the
     * markup renders -1, programmatically focusable only, so Tab could never reach it) and the
     * sheet a visible ring (`DataTableCssTokenTest` pins the rule; the browser proves the
     * behaviour — a focused viewport answers ArrowDown with scroll).
     */
    @Test
    fun `a fixed viewport is keyboard-scrollable - focusable by tab and scrolled by the arrows`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dtv-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        val pipelineId = seedPipelineWithRuns("test/dtv-" + generatedPassword("p").take(8).lowercase(), admin.email, runs = 24)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/executions?pipeline_id=$pipelineId")
        val frame = "#execution-table .dt-frame"
        page.locator("$frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        val viewport = "$frame > .dt-viewport"
        withClue("the enhancer owns the interactive state: tabindex 0") {
            page.evaluate("(sel) => document.querySelector(sel).getAttribute('tabindex')", viewport) shouldBe "0"
        }
        page.evaluate("(sel) => { const v = document.querySelector(sel); v.scrollTop = 0; }", viewport)
        page.locator(viewport).focus()
        page.keyboard().press("ArrowDown")
        settle()
        val scrollTop = page.evaluate("(sel) => document.querySelector(sel).scrollTop", viewport) as Number
        withClue("ArrowDown scrolled the focused viewport (scrollTop=$scrollTop)") {
            scrollTop.toDouble() shouldBeGreaterThan 0.0
        }
    }

    /**
     * 288 #1 — a paged table after a page change. Two producers, two truths, one rule: a page
     * whose ROWS ARE NEW (the htmx pagers replace the frame; any keyed-by-content re-render)
     * clears the client sort — the server's order shows and every `aria-sort` returns to none.
     * The DOCK's cursor paging re-renders its rows IN PLACE (Alpine, index-keyed: page 2
     * reuses page 1's `<tr>` elements), so the held sort persists by element identity — and
     * says so: the button's title names the held sort beside the `aria-sort`. Red on the base:
     * the title kept the inert wording while the sort silently held.
     */
    @Test
    fun `the dock's held sort across a page change is stated by its button, not silent`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dtc-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("dtc" + generatedPassword("w").take(8).lowercase())
        val datasource = "dt-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val pipelineId = createWideResultPipeline(datasource, rows = 2000)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card").first().waitFor()
        page.locator("[data-verb='pipeline-execute']").click()
        page.locator(".pe-status:has-text('Completed')").waitFor(Locator.WaitForOptions().setTimeout(RUN_TIMEOUT_MS))
        page.locator("#pe-dock-tab-results").click()
        val frame = "#pe-pane-results .dt-frame"
        page.locator("$frame table[data-dt-ready] tbody tr").nth(30).waitFor()
        page.locator("$frame thead th .dt-sort").first().waitFor()

        val th = page.locator("$frame thead th").first()
        val sortButton = th.locator(".dt-sort")
        th.getAttribute("aria-sort") shouldBe "none"
        sortButton.getAttribute("title") shouldBe "Sort this page by row_id"
        sortButton.click()
        th.getAttribute("aria-sort") shouldBe "ascending"

        page.locator(".pe-result-actions button:has-text('Next')").click()
        page.locator(".pe-result-page-info:has-text('Page 2 ')").waitFor()
        settle()
        withClue("the held sort across the dock's in-place page change is stated by the title") {
            th.getAttribute("aria-sort") shouldBe "ascending"
            sortButton.getAttribute("title") shouldBe "Sorting this page by row_id — click for highest first"
        }
    }

    /**
     * 288 #2 — the observer's discovery costs one subtree query per TOP-LEVEL root, not one
     * per added node: a boosted swap's whole subtree used to be queried once per element
     * (every child re-queried its own subtree). The test instruments the page's
     * querySelectorAll to count calls carrying the data-table selector — a call only the
     * component's own discovery makes — across two boosted navigations. The base's count for
     * the identical action is recorded beside this test's in the lane's evidence
     * (notes/evidence/287-ui-followups/observer-before.log / observer-after.log); the fix
     * lands in single digits. The ceiling is generous — htmx, shell and the page scripts'
     * own querySelectorAll calls carry other selectors and are not counted.
     */
    @Test
    fun `the observer discovers a swapped table with one query per top-level root`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dto-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        seedPipelineWithRuns("test/dto-" + generatedPassword("p").take(8).lowercase(), admin.email, runs = 24)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/executions")
        page.locator("#execution-table .dt-frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        settle()
        page.evaluate(
            """() => {
              window.__dtQueries = 0;
              window.__origQSA = Element.prototype.querySelectorAll;
              Element.prototype.querySelectorAll = function (sel) {
                if (typeof sel === 'string' && sel.indexOf('dt-frame') !== -1) window.__dtQueries++;
                return window.__origQSA.apply(this, arguments);
              };
            }""",
        )
        // Two boosted navigations between table-bearing pages: the dashboard's recent
        // executions and the executions list swap in both directions.
        page.locator("nav a[href='/dashboard']").click()
        page.waitForURL("**/dashboard")
        settle()
        page.locator("nav a[href='/executions']").click()
        page.waitForURL("**/executions**")
        page.locator("#execution-table .dt-frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        settle()
        val queries = (page.evaluate("() => window.__dtQueries") as Number).toInt()
        page.evaluate("() => { Element.prototype.querySelectorAll = window.__origQSA; }")
        withClue("data-table discovery queries across two boosted swaps: $queries") {
            queries shouldBeGreaterThan 0
            queries shouldBeLessThan 25
        }
    }

    /**
     * htmx caches the page (the history element is `document.body` — no `hx-history-elt` in
     * the layout) as MARKUP before a boosted swap and re-parses it on Back. The
     * enhancer's widths and measured variables are CSSOM writes; serialised into the snapshot
     * they would come back as `style` attributes, which `style-src 'self'` refuses — measured on
     * the lane instance with the cleanup removed: 9 violations on one Back, one per styled
     * element. The cleanup strips them before the save; the restored table is upgraded again
     * (once — its buttons are not doubled) and re-measured. The collector is asserted empty
     * after every test (closeOwnContext).
     */
    @Test
    fun `back to a data table restores it without an inline style and upgrades it once`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dth-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        val pipelineId = seedPipelineWithRuns("test/dth-" + generatedPassword("p").take(8).lowercase(), admin.email, runs = 24)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/executions?pipeline_id=$pipelineId")
        val frame = "#execution-table .dt-frame"
        page.locator("$frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        settle()
        withClue("the enhancer wrote its widths through the CSSOM") { styledElements(frame) shouldNotBe 0 }
        val wired = page.evaluate("() => window.__dpHistoryStyleCleanups.length")
        page.locator("nav a[href='/dashboard']").click()
        page.waitForURL("**/dashboard")
        page.goBack()
        page.waitForURL("**/executions**")
        page.locator("$frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
        settle()
        // htmx re-creates the cached body's scripts on Back: data-table.js must wire once (a second
        // registry entry here meant a second observer and a second instance per restored table).
        withClue("data-table.js wired once across Back") { page.evaluate("() => window.__dpHistoryStyleCleanups.length") shouldBe wired }
        page.locator("$frame thead .dt-sort").count() shouldBe 7
        withClue("the restored table was re-measured") { geometry(frame)["sbw"] shouldBe "${geometry(frame)["measured"]}px" }
        withClue("CSP after Back") { drainOwnCsp().shouldBeEmpty() }
    }

    @Test
    fun `the executions list - screenshots light and dark at 1440, 1024 and 400`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dts-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        val pipelineId = seedPipelineWithRuns("test/dts-" + generatedPassword("p").take(8).lowercase(), admin.email, runs = 24)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        val frame = "#execution-table .dt-frame"
        for (mode in listOf("light", "dark")) {
            page.setViewportSize(1440, 900)
            page.navigate("$baseUrl/executions?pipeline_id=$pipelineId")
            page.locator("$frame table[data-dt-ready] tbody tr[data-href]").first().waitFor()
            ensureTheme(mode)
            for (width in WIDTHS) {
                page.setViewportSize(width, 900)
                settleShell()
                scroll(frame, top = 200)
                val g = geometry(frame)
                withClue("$mode $width: the header scrolled away: $g") {
                    (g["headTop"] as Number).toDouble() shouldBe ((g["viewportTop"] as Number).toDouble() plusOrMinus 1.0)
                }
                withClue("$mode $width: the document scrolls sideways") { documentOverflow() shouldBe 0.0 }
                shoot("executions-$width-$mode")
            }
        }
    }

    @Test
    fun `the result dock - the header and the frozen first column hold while a wide result scrolls both ways`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dtd-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("dtdock" + generatedPassword("w").take(8).lowercase())
        val datasource = "dt-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val pipelineId = createWideResultPipeline(datasource)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card").first().waitFor()
        page.locator("[data-verb='pipeline-execute']").click()
        page.locator(".pe-status:has-text('Completed')").waitFor(Locator.WaitForOptions().setTimeout(RUN_TIMEOUT_MS))
        page.locator("#pe-dock-tab-results").click()
        val frame = "#pe-pane-results .dt-frame"
        page.locator("$frame table[data-dt-ready] tbody tr").nth(30).waitFor()
        page.locator("$frame thead th .dt-sort").first().waitFor()

        scroll(frame, top = 200)
        val down = geometry(frame)
        (down["scrollTop"] as Number).toDouble() shouldBe (200.0 plusOrMinus 0.5)
        withClue("the dock's header scrolled away: $down") {
            (down["headTop"] as Number).toDouble() shouldBe ((down["viewportTop"] as Number).toDouble() plusOrMinus 1.0)
        }
        withClue("the dock's cap: $down") { down["sbw"] shouldBe "${down["measured"]}px" }

        scroll(frame, left = 300)
        val across = frozenColumn(frame)
        withClue("the frozen column: $across") {
            (across["scrollLeft"] as Number).toDouble() shouldBe (300.0 plusOrMinus 0.5)
            across["scrolledClass"] shouldBe true
            @Suppress("UNCHECKED_CAST")
            (across["cellLefts"] as List<Number>).forEach { left ->
                left.toDouble() shouldBe ((across["viewportLeft"] as Number).toDouble() plusOrMinus 1.0)
            }
        }
        shoot("dock-frozen-1440-dark")

        for (mode in listOf("light", "dark")) {
            page.setViewportSize(1440, 900)
            ensureTheme(mode)
            for (width in WIDTHS) {
                page.setViewportSize(width, 900)
                settleShell()
                // Below 768 the editor shows its wide-screen note instead of the canvas and the
                // dock (ui-screens §3.6 / the editors' phone band), so the 400 shot records that.
                if (width >= 768) scroll(frame, top = 200, left = 300)
                shoot("dock-$width-$mode")
            }
        }
    }

    @Test
    fun `a page-flow table - its header sticks to main, the page's own scroller, under the top bar`() {
        useRealScrollbars()
        val admin = seedLocalUser(uniqueEmail("dtp-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        seedUsers("dtp-" + generatedPassword("s").take(6).lowercase(), count = 40)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        // A wide, short window: the table must FIT its frame (page-flow sticks to <main> only
        // then — the suite's emails are long), and <main> must scroll well past the table's top.
        page.setViewportSize(1920, 600)
        page.navigate("$baseUrl/admin/users")
        val frame = ".app-main .dt-frame"
        page.locator("$frame table[data-dt-ready] tbody tr").nth(19).waitFor()
        settle()
        @Suppress("UNCHECKED_CAST")
        val flow =
            page.evaluate(
                """(sel) => new Promise(done => {
                  const frame = document.querySelector(sel);
                  const main = document.querySelector('.app-main');
                  const offset = frame.getBoundingClientRect().top - main.getBoundingClientRect().top + main.scrollTop;
                  main.scrollTop = offset + 150;
                  requestAnimationFrame(() => requestAnimationFrame(() => {
                    const v = frame.querySelector(':scope > .dt-viewport');
                    done({
                      tableWidth: frame.querySelector('table').offsetWidth, frameWidth: frame.clientWidth,
                      fits: frame.classList.contains('dt-fits'),
                      overflow: getComputedStyle(v).overflowY,
                      wanted: offset + 150,
                      mainScrollTop: main.scrollTop,
                      mainTop: main.getBoundingClientRect().top,
                      frameTop: frame.getBoundingClientRect().top,
                      headTop: frame.querySelector('thead th').getBoundingClientRect().top,
                      barBottom: document.querySelector('.app-topbar').getBoundingClientRect().bottom,
                    });
                  }));
                })""",
                frame,
            ) as Map<String, Any?>
        withClue("page-flow at 1920x600: $flow") {
            flow["fits"] shouldBe true
            flow["overflow"] shouldBe "clip"
            // The scroll really carried the table's top ABOVE main's top — else "sticks" is vacuous.
            (flow["mainScrollTop"] as Number).toDouble() shouldBe ((flow["wanted"] as Number).toDouble() plusOrMinus 1.0)
            ((flow["mainTop"] as Number).toDouble() - (flow["frameTop"] as Number).toDouble()) shouldBeGreaterThan 100.0
            (flow["headTop"] as Number).toDouble() shouldBe ((flow["mainTop"] as Number).toDouble() plusOrMinus 1.0)
            (flow["headTop"] as Number).toDouble() shouldBe ((flow["barBottom"] as Number).toDouble() plusOrMinus 1.0)
        }
        shoot("admin-users-flow-1920x600")
    }

    // ------------------------------------------------------------------ the two interaction proofs

    /** Duration (the 5th column): ascending puts the shortest run first, again the longest. */
    private fun sortFlipsTheDurationColumn(frame: String) {
        val durations = columnTexts(frame, 5).map { parseMs(it) }
        val firstBefore = columnTexts(frame, 5).first()
        val header = page.locator("$frame thead th:nth-child(5)")
        header.getAttribute("aria-sort") shouldBe "none"
        header.locator(".dt-sort").click()
        header.getAttribute("aria-sort") shouldBe "ascending"
        parseMs(columnTexts(frame, 5).first()) shouldBe durations.filterNotNull().min()
        withClue("the first cell did not change: the sort moved nothing") { columnTexts(frame, 5).first() shouldNotBe firstBefore }
        header.locator(".dt-sort").click()
        header.getAttribute("aria-sort") shouldBe "descending"
        parseMs(columnTexts(frame, 5).first()) shouldBe durations.filterNotNull().max()
        withClue("the button says it sorts this page, and says the held sort (288 #1)") {
            header.locator(".dt-sort").getAttribute("title") shouldBe
                "Sorting this page by Duration — click to clear"
        }
    }

    /** A drag on the Status column's handle widens Status by the drag and nothing else. */
    private fun aDragMovesOneColumn(frame: String) {
        val before = headerWidths(frame)
        drag(page.locator("$frame thead th:nth-child(3) .dt-resizer"), dx = 80.0)
        val after = headerWidths(frame)
        withClue("widths before $before, after $after") {
            after[2] shouldBe (before[2] + 80.0 plusOrMinus 1.0)
            after.indices.filter { it != 2 }.forEach { i -> after[i] shouldBe (before[i] plusOrMinus 0.5) }
        }
    }

    // ------------------------------------------------------------------ measurements

    @Suppress("UNCHECKED_CAST")
    private fun geometry(frame: String): Map<String, Any?> =
        page.evaluate(
            """(sel) => {
              const frame = document.querySelector(sel);
              const v = frame.querySelector(':scope > .dt-viewport');
              const table = v.querySelector(':scope > table');
              // The sticky CELLS, not the row: Chromium moves the cells and leaves the <tr>'s box
              // where it was, so the row's rect reads "scrolled away" while the header is in place.
              // EVERY cell, reported as the one furthest from the viewport's top: a frozen first
              // cell is sticky for a second reason (dt-freeze) and alone could not show a header
              // that lost its own sticky rule (F1 left the dock green until this read them all).
              const viewportTop = v.getBoundingClientRect().top;
              const tops = Array.from(table.tHead.rows[0].cells).map(c => c.getBoundingClientRect().top);
              const cap = getComputedStyle(frame, '::after');
              return {
                viewportTop, headTop: tops.reduce((w, t) => Math.abs(t - viewportTop) > Math.abs(w - viewportTop) ? t : w),
                scrollTop: v.scrollTop, scrollable: v.scrollHeight - v.clientHeight,
                sbw: frame.style.getPropertyValue('--dt-sbw'), measured: v.offsetWidth - v.clientWidth,
                headH: frame.style.getPropertyValue('--dt-head-h'), headRowH: table.tHead.getBoundingClientRect().height,
                capContent: cap.content, capWidth: cap.width, capHeight: cap.height,
              };
            }""",
            frame,
        ) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun frozenColumn(frame: String): Map<String, Any?> =
        page.evaluate(
            """(sel) => {
              const frame = document.querySelector(sel);
              const v = frame.querySelector(':scope > .dt-viewport');
              const table = v.querySelector(':scope > table');
              const firsts = [table.tHead.rows[0].cells[0]].concat(
                Array.from(table.tBodies[0].rows).slice(0, 8).map(r => r.cells[0]));
              return {
                scrollLeft: v.scrollLeft, scrolledClass: frame.classList.contains('is-scrolled-x'),
                viewportLeft: v.getBoundingClientRect().left,
                cellLefts: firsts.map(c => c.getBoundingClientRect().left),
              };
            }""",
            frame,
        ) as Map<String, Any?>

    private fun scroll(
        frame: String,
        top: Int? = null,
        left: Int? = null,
    ) {
        page.evaluate(
            """(a) => {
              const v = document.querySelector(a.sel).querySelector(':scope > .dt-viewport');
              if (a.top !== null) v.scrollTop = a.top;
              if (a.left !== null) v.scrollLeft = a.left;
            }""",
            mapOf("sel" to frame, "top" to top, "left" to left),
        )
        settle()
    }

    /**
     * After a resize: the phone drawer has finished sliding off-canvas (a transform transition —
     * a shot taken mid-slide shows the rail over the page), the frames have re-measured, and the
     * theme switch's toasts are not sitting over the table.
     */
    private fun settleShell() {
        page.waitForFunction("() => innerWidth >= 768 || document.querySelector('.app-rail').getBoundingClientRect().right <= 0")
        page.evaluate("() => document.querySelectorAll('#toast .ds-toast').forEach(t => t.remove())")
        settle()
    }

    /** Two animation frames: the scroll listener and the ResizeObserver's deferred measure have run. */
    private fun settle() {
        page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
    }

    @Suppress("UNCHECKED_CAST")
    private fun columnTexts(
        frame: String,
        column: Int,
    ): List<String> = page.locator("$frame tbody tr[data-href] td:nth-child($column)").allInnerTexts().map { it.trim() }

    @Suppress("UNCHECKED_CAST")
    private fun headerWidths(frame: String): List<Double> =
        (
            page.evaluate(
                "(sel) => Array.from(document.querySelectorAll(sel + ' thead th')).map(th => th.getBoundingClientRect().width)",
                frame,
            ) as List<Number>
        ).map { it.toDouble() }

    private fun drag(
        handle: Locator,
        dx: Double,
    ) {
        val box = requireNotNull(handle.boundingBox()) { "the resize handle is not rendered" }
        val x = box.x + box.width / 2
        val y = box.y + box.height / 2
        page.mouse().move(x, y)
        page.mouse().down()
        page.mouse().move(x + dx, y, Mouse.MoveOptions().setSteps(8))
        page.mouse().up()
        settle()
    }

    private fun sideOverflow(frame: String): Int =
        (
            page.evaluate(
                "(sel) => { const v = document.querySelector(sel + ' > .dt-viewport'); return v.scrollWidth - v.clientWidth; }",
                frame,
            ) as Number
        ).toInt()

    /** Elements under the frame (the frame included) that carry a `style` attribute. */
    private fun styledElements(frame: String): Int =
        (
            page.evaluate(
                "(sel) => document.querySelectorAll(sel + ' [style]').length + (document.querySelector(sel).hasAttribute('style') ? 1 : 0)",
                frame,
            ) as Number
        ).toInt()

    private fun documentOverflow(): Double =
        (page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toDouble()

    private fun parseMs(text: String): Long? =
        Regex("""^(\d+) ms$""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toLong()

    private fun shoot(name: String) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("$name.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "282-screenshots").also { it.toFile().mkdirs() }

    // ------------------------------------------------------------------ fixtures

    /**
     * One pipeline in the `default` workspace the seeded admin administers, with [runs] (24)
     * finished executions an hour apart, every one a different duration (the newest is NOT the
     * shortest or the longest, so a sort visibly reorders). Returns the pipeline id the list
     * filters on.
     */
    private fun seedPipelineWithRuns(
        name: String,
        ownerEmail: String,
        runs: Int,
    ): UUID {
        val pipelineId = UUID.randomUUID()
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO pipelines (id, workspace_id, name, display_name, owner_id, current_version)
                    SELECT '$pipelineId', 'defa0000-0000-0000-0000-000000000001', '$name', '$name', id, 1
                      FROM users WHERE email = '$ownerEmail'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by, body_hash)
                    SELECT '$pipelineId', 1, '{}'::jsonb, id, 'seeded-fixture-hash' FROM users WHERE email = '$ownerEmail'
                    """.trimIndent(),
                )
                for (i in 0 until runs) {
                    val id = UUID.randomUUID()
                    // (5i + 35) mod 24 is a bijection on 0..23: every duration differs, and the
                    // newest run (i = 0) lands in the middle, never at either end.
                    val duration = 1_000 + ((5 * i + 35) % runs) * 250
                    statement.execute(
                        """
                        INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, executed_by,
                                                         triggered_via, started_at, completed_at, duration_ms,
                                                         result_row_count, root_execution_id)
                        SELECT '$id', '$pipelineId', 1, 'SUCCESS', id, 'UI',
                               NOW() - interval '${i + 1} hours', NOW() - interval '${i + 1} hours' + interval '$duration milliseconds',
                               $duration, ${i * 13}, '$id'
                          FROM users WHERE email = '$ownerEmail'
                        """.trimIndent(),
                    )
                }
            }
        }
        return pipelineId
    }

    /** [count] extra local users, so /admin/users is taller than the window and <main> scrolls. */
    private fun seedUsers(
        prefix: String,
        count: Int,
    ) {
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement ->
                for (i in 0 until count) {
                    val email = uniqueEmail("$prefix-$i")
                    statement.execute(
                        """
                        INSERT INTO users (email, display_name, provider, provider_subject, is_active, is_admin, must_change_password)
                        VALUES ('$email', 'Table Row $i', 'local', '$email', TRUE, FALSE, FALSE)
                        ON CONFLICT (email) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    /**
     * A caller result 17 columns wide and [rows] long, generated by the suite's own Postgres —
     * wide enough that the dock scrolls sideways past 300px at 1440 and long enough to scroll down.
     * [rows] past the result store's page size (1000, ResultConfig) makes the dock's Next
     * button live (a second cursor page).
     */
    private fun createWideResultPipeline(
        datasource: String,
        rows: Int = 200,
    ): String {
        val slug = "dtw" + generatedPassword("t").take(6).lowercase()
        val columns =
            (1..16).joinToString(", ") { c ->
                if (c % 3 == 0) "g * $c AS amount_$c" else "md5((g * $c)::text) AS text_column_$c"
            }
        EditorRunFixtures.createTemplate(page, "test/${slug}_wide.sql", "SELECT g AS row_id, $columns FROM generate_series(1, $rows) g")
        return EditorRunFixtures.postPipeline(
            page,
            "test/$slug",
            """[
              { "id": "wide", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_wide.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": [] }
            ]""",
        )
    }

    companion object {
        private val WIDTHS = listOf(1440, 1024, 400)
        private const val RUN_TIMEOUT_MS = 120_000.0

        /** SHA-256 of the empty inline style Playwright's own screenshot path adds — see BrowserSuite. */
        private const val EMPTY_INLINE_STYLE_HASH = "'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='"
        private val ownCsp = CopyOnWriteArrayList<String>()

        private fun watchCsp(page: Page) {
            page.onConsoleMessage { message ->
                val text = message.text()
                if (!text.contains("Content Security Policy")) return@onConsoleMessage
                if (text.startsWith("Applying inline style") && text.contains(EMPTY_INLINE_STYLE_HASH)) return@onConsoleMessage
                ownCsp += "${page.url()} — $text"
            }
        }

        private fun drainOwnCsp(): List<String> = ownCsp.toList().also { ownCsp.clear() }

        private var playwright: Playwright? = null
        private var browser: Browser? = null

        /** Headless Chromium with its scrollbars SHOWN (Playwright passes --hide-scrollbars by default). */
        private fun scrollbarBrowser(): Browser =
            browser ?: (playwright ?: Playwright.create().also { playwright = it })
                .chromium()
                .launch(BrowserType.LaunchOptions().setHeadless(true).setIgnoreDefaultArgs(listOf("--hide-scrollbars")))
                .also { browser = it }

        @JvmStatic
        @AfterAll
        fun closeScrollbarBrowser() {
            browser?.close()
            browser = null
            playwright?.close()
            playwright = null
        }
    }
}
