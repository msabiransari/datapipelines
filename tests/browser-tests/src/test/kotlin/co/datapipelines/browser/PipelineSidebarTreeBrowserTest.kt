package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #350 (workspace spec §5, A11/A13) — the pipeline tree in the GLOBAL SIDEBAR, measured in a real
 * browser on the real application: geometry, scrolling, keyboard, the catalog page that replaced
 * the explorer, the phone drawer and the tablet band, both themes.
 *
 * Every geometry claim reads COMPUTED boxes (`getBoundingClientRect`, `scrollWidth`) after the
 * rail settles — never a class or a CSS property alone: "the tree region has overflow-x: auto"
 * proves nothing about whether a reader can reach the end of a long name, so the full label is
 * proven by actually scrolling (a horizontal wheel over the region) until its tail is inside the
 * region's visible box, from a start where it was not.
 *
 * The state half — restore, reveal, the admission guard, failure/retry, history and handler
 * counts — is [PipelineSidebarTreeStateBrowserTest].
 */
class PipelineSidebarTreeBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ fixtures

    private fun loginReadyUser(slug: String): String {
        val user =
            seedLocalUser(
                uniqueEmail(slug + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val workspace = "p350-" + generatedPassword("w").take(8).lowercase()
        createWorkspace(workspace)
        watchConsole()
        return workspace
    }

    /** One REST call in-page, with the session's CSRF pair (every non-GET carries it). */
    private fun api(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Pair<Int, String> {
        val answer =
            page.evaluate(
                """async ([method, path, body, ifMatch]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json' };
                  if (ifMatch) headers['If-Match'] = ifMatch;
                  if (method !== 'GET') headers['DP-CSRF-Token'] = csrf ? decodeURIComponent(csrf[1]) : '';
                  const res = await fetch(path, { method, credentials: 'same-origin', headers, body });
                  let text = '';
                  try { text = await res.text(); } catch (e) {}
                  return [res.status, text];
                }""",
                arrayOf(method, path, body, ifMatch),
            ) as List<*>
        return (answer[0] as Number).toInt() to (answer[1] as String)
    }

    private fun must(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): String {
        val (status, text) = api(method, path, body, ifMatch)
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(300)}" }
        return text
    }

    private fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    private fun idOf(text: String): String = Regex(""""id"\s*:\s*"([0-9a-f-]{36})"""").find(text)!!.groupValues[1]

    private fun calcBody(
        name: String,
        nodeId: String,
    ): String =
        """{"name":"$name","display_name":"${name.substringAfterLast('/')}","nodes":[{"id":"$nodeId","type":"CALCULATOR",""" +
            """"kind":"fiscal_quarter","context_key":"run_q_$nodeId",""" +
            """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""

    /** A released v1 pipeline at [name]; returns its id. */
    private fun seedPipeline(name: String): String {
        val created = must("POST", "/api/v1/pipelines", calcBody(name, "n1"))
        must("POST", "/api/v1/pipelines/${idOf(created)}/release", null, ifMatch = hashOf(created))
        return idOf(created)
    }

    /** v1 current release + v2 newer release + v3 draft, each with its OWN node id. */
    private fun seedThreeVersions(name: String): String {
        val created = must("POST", "/api/v1/pipelines", calcBody(name, "n_v1"))
        val id = idOf(created)
        val v1 = hashOf(created)
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v1)
        val v2 = hashOf(must("PUT", "/api/v1/pipelines/$id", calcBody(name, "n_v2"), ifMatch = v1))
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v2)
        must("PUT", "/api/v1/pipelines/$id", calcBody(name, "n_v3"), ifMatch = v2)
        must("POST", "/api/v1/pipelines/$id/current", """{"version": 1}""")
        return id
    }

    // ------------------------------------------------------------------ the rail

    private val panel = "#nav-tree-pipelines"
    private val region = "#nav-tree-pipelines [data-nav-tree-scroll]"

    private fun railWidth(): Double =
        (page.evaluate("() => document.querySelector('.app-rail').getBoundingClientRect().width") as Number).toDouble()

    /** Two animation frames: the fit is written in one, the grid lays out by the next. */
    private fun settle() {
        page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
    }

    private fun openTree() {
        page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
        page.waitForSelector("#pipeline-nav-root .tpl-tree, #pipeline-nav-root .ds-empty")
        settle()
    }

    private fun folder(path: String) = "$panel details.tpl-folder:has(> summary > span.tpl-label[title='$path'])"

    /** Expands the sidebar folder whose FULL path is [path], waiting for its level to land. */
    private fun expand(path: String) {
        page.click("${folder(path)} > summary")
        page.waitForSelector("${folder(path)} > .tpl-level:not(.tpl-level-pending)")
        settle()
    }

    private fun leaf(path: String) = "$panel a.tpl-leaf:has(span.tpl-label[title='$path'])"

    private fun shot(name: String) {
        val dir = Paths.get("build", "reports", "350-screenshots")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("350-$name.png")).setFullPage(false))
    }

    /** The measured geometry, printed into the XML's system-out — the handback's numbers. */
    private fun record(
        label: String,
        value: Any,
    ) = println("350-measure $label=$value")

    private val consoleErrors = mutableListOf<String>()

    /** Every console error the page logs from here on (the CSP collector is the suite's own). */
    private fun watchConsole() {
        page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
    }

    private fun documentOverflowsX(): Boolean =
        page.evaluate(
            "() => document.documentElement.scrollWidth > window.innerWidth || document.body.scrollWidth > window.innerWidth",
        ) as Boolean

    // A deep, long-named path: four levels with long segments, so its leaf row is wider than
    // the rail's maximum at 1440 (the measured case, not a guess — the test asserts it).
    // The first sub-folder is SHORT on purpose: folding it must bring the visible content back
    // under the minimum, so the shrink is measurable.
    private val deepFolders =
        listOf(
            "acme",
            "acme/alpha",
            "acme/alpha/beta_second_level_folder_with_long_name",
            "acme/alpha/beta_second_level_folder_with_long_name/gamma_third_level_folder_also_long",
        )
    private val deepLeaf = deepFolders.last() + "/leaf_pipeline_with_an_intentionally_very_long_name_xyz"

    // ------------------------------------------------------------------ A11 geometry

    @Test
    @Suppress("LongMethod") // one walk on purpose: every width is measured against the SAME tree it just grew
    fun `A11 - the tree opens in the rail, fits between the bounds, scrolls sideways past the max, and closing restores the baseline`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350geo")
        seedPipeline(deepLeaf)
        seedPipeline("acme/short/s1")
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("[data-nav-branch='pipelines']")
        drainCspViolations()

        // The ordinary rail is the closed-tree baseline (--app-rail-expanded).
        val baseline = railWidth()
        record("rail.baseline.1440", baseline)
        baseline shouldBe 232.0
        page.locator(panel).isVisible shouldBe false

        // Open: the root level arrives (folders only — §4.1) and the rail fits it, never below
        // the tree-open minimum.
        openTree()
        page.locator("$panel .tpl-leaf").count() shouldBe 0
        val opened = railWidth()
        record("rail.open.root.1440", opened)
        opened shouldBe 320.0 // `acme` alone is narrower than the minimum

        // Deep + long content: the rail grows to the MAXIMUM and stops there.
        deepFolders.forEach { expand(it) }
        page.waitForSelector(leaf(deepLeaf))
        settle()
        val widest = railWidth()
        record("rail.open.deep.1440", widest)
        widest shouldBe 400.0

        // Beyond the maximum the REGION scrolls sideways — and nothing else does.
        val overflow =
            page.evaluate(
                "s => { const r = document.querySelector(s); return [r.scrollWidth, r.clientWidth]; }",
                region,
            ) as List<*>
        record("region.scrollWidth/clientWidth", overflow)
        (overflow[0] as Number).toDouble() shouldBeGreaterThan (overflow[1] as Number).toDouble()

        // The full label: its text is the whole segment (no ellipsis), its tail starts OUTSIDE
        // the region's visible box, and a real horizontal wheel over the region brings the tail
        // inside it.
        val labelSelector = "${leaf(deepLeaf)} .tpl-label"
        page.locator(labelSelector).innerText() shouldBe deepLeaf.substringAfterLast('/')
        page.evaluate("s => getComputedStyle(document.querySelector(s)).textOverflow", labelSelector) shouldBe "clip"
        val tailHiddenBefore = tailOutside(labelSelector)
        tailHiddenBefore shouldBe true
        val docsBefore = docsLinkX()
        // The pointer rests on a VISIBLE row of the region (hovering the hidden label would let
        // Playwright scroll it into view itself) and the wheel does the scrolling.
        page.hover("${folder("acme")} > summary")
        repeat(6) { page.mouse().wheel(200.0, 0.0) }
        // The wheel is applied asynchronously: poll (bounded) for the region to stop moving,
        // then the TAIL is the assertion — a region that cannot scroll leaves it outside.
        var last = -1.0
        var polls = 0
        while (polls < SCROLL_POLLS) {
            polls += 1
            settle()
            val now = (page.evaluate("s => document.querySelector(s).scrollLeft", region) as Number).toDouble()
            if (now == last && now > 0) break
            last = now
        }
        record("region.scrollLeft.after-wheel", last)
        tailOutside(labelSelector) shouldBe false
        // The rest of the rail and the page did not move with it.
        page.evaluate("() => document.querySelector('.app-nav').scrollLeft") shouldBe 0
        docsLinkX() shouldBe docsBefore
        documentOverflowsX() shouldBe false
        shot("desktop-deep-scrolled-" + (page.evaluate("() => document.documentElement.getAttribute('data-theme')") ?: "theme"))

        // Folding the deep branch shrinks the fit back toward the content (still >= the min).
        page.click("${folder(deepFolders[1])} > summary")
        settle()
        val folded = railWidth()
        record("rail.open.folded.1440", folded)
        folded shouldBe 320.0

        // Closing the tree returns the ordinary rail.
        page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
        settle()
        railWidth() shouldBe baseline
        page.locator(panel).isVisible shouldBe false
        drainCspViolations().shouldBeEmpty()
        consoleErrors.shouldBeEmpty()
    }

    /** True while the label's right edge lies beyond the region's visible right edge. */
    private fun tailOutside(labelSelector: String): Boolean =
        page.evaluate(
            """([l, r]) => {
              const label = document.querySelector(l).getBoundingClientRect();
              const region = document.querySelector(r).getBoundingClientRect();
              return label.right > region.right + 1;
            }""",
            arrayOf(labelSelector, region),
        ) as Boolean

    private fun docsLinkX(): Double =
        (
            page.evaluate(
                "() => document.querySelector(\".app-nav-link[data-nav-section='/docs']\").getBoundingClientRect().x",
            ) as Number
        ).toDouble()

    @Test
    fun `A11 - an explicit icon collapse wins over a folder response that lands after it`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350col")
        seedPipeline(deepLeaf)
        page.navigate("$baseUrl/dashboard")
        openTree()
        railWidth() shouldBe 320.0

        // Hold the next folder level; collapse while it is in flight; then let it land.
        val held = mutableListOf<Pair<Route, com.microsoft.playwright.APIResponse>>()
        page.route("**/partials/pipelines?prefix=*") { route -> held.add(route to route.fetch()) }
        page.click("${folder(deepFolders[0])} > summary")
        val deadline = System.currentTimeMillis() + 10_000
        while (held.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "the folder request was never captured" }
            page.evaluate("() => 0")
        }
        page.click("#rail-collapse")
        settle()
        railWidth() shouldBe 60.0
        held.forEach { (route, response) -> route.fulfill(Route.FulfillOptions().setResponse(response)) }
        page.unroute("**/partials/pipelines?prefix=*")
        page.waitForSelector(
            "${folder(deepFolders[0])} > .tpl-level:not(.tpl-level-pending)",
            Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
        )
        settle()
        settle()
        // The late level measured and wrote its fit — and the collapse still wins.
        railWidth() shouldBe 60.0
        record("rail.collapsed.after-late-response", railWidth())

        // Expanding the rail again shows the still-open tree at its fitted width.
        page.click("#rail-collapse")
        settle()
        (railWidth() >= 320.0) shouldBe true
        page.locator(panel).isVisible shouldBe true
    }

    // ------------------------------------------------------------------ leaf → workspace (A1 kept)

    @Test
    fun `a leaf opens the canonical workspace at the CURRENT version - a full document with the current body`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350leaf")
        val id = seedThreeVersions("acme/flows/versioned")
        page.navigate("$baseUrl/dashboard")
        openTree()
        expand("acme")
        expand("acme/flows")

        val href = page.locator(leaf("acme/flows/versioned")).getAttribute("href")
        page.locator(leaf("acme/flows/versioned")).getAttribute("hx-boost") shouldBe "false"

        page.evaluate("() => { window.__p350Marker = 1; }")
        page.click(leaf("acme/flows/versioned"))
        page.waitForURL("**/pipelines/$id**")
        page.waitForSelector(".pe-root")
        // A FULL document: the marker on the old window is gone.
        page.evaluate("() => window.__p350Marker === undefined") shouldBe true
        // The CURRENT body (v1), not the newest release (v2) and not the draft (v3) — distinct
        // node ids per version, so a leaf aimed at any other version reads a different BODY.
        val json = page.evaluate("() => document.getElementById('pipeline-data').textContent").toString()
        json shouldNotContain "n_v2"
        json shouldNotContain "n_v3"
        json shouldContain "n_v1"
        page.locator(".pe-vchip").innerText() shouldBe "v1 · released · current"
        // …through the canonical, version-free URL (spec §3.1's current-first rule resolves it).
        href shouldBe "/pipelines/$id"

        // The sidebar came back open on the new document, the leaf marked as the current page.
        page.waitForSelector("${leaf("acme/flows/versioned")}[aria-current='page']")
        page.locator(panel).isVisible shouldBe true
        drainCspViolations().shouldBeEmpty()
        consoleErrors.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ keyboard

    @Test
    fun `keyboard - arrows move focus without navigating, ArrowRight opens a folder, Enter follows the leaf`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350kbd")
        val id = seedPipeline("acme/keys/one")
        seedPipeline("acme/keys/two")
        page.navigate("$baseUrl/dashboard")
        openTree()
        val start = page.url()

        // From the search box, ArrowDown enters the tree at its first row.
        page.focus("$panel [data-nav-tree-search]")
        page.keyboard().press("ArrowDown")
        focusedTitle() shouldBe "acme"
        page.keyboard().press("ArrowRight") // opens acme
        page.waitForSelector("${folder("acme")} > .tpl-level:not(.tpl-level-pending)")
        page.keyboard().press("ArrowDown")
        focusedTitle() shouldBe "acme/keys"
        page.keyboard().press("ArrowRight")
        page.waitForSelector("${folder("acme/keys")} > .tpl-level:not(.tpl-level-pending)")
        page.keyboard().press("ArrowDown")
        focusedTitle() shouldBe "acme/keys/one"
        page.keyboard().press("ArrowDown")
        focusedTitle() shouldBe "acme/keys/two"
        page.keyboard().press("ArrowUp")
        focusedTitle() shouldBe "acme/keys/one"
        // Moving onto leaves navigated nowhere — a sidebar row is a link, not a selection.
        page.url() shouldBe start
        // One tab stop in the tree: the focused row.
        page.evaluate("s => [...document.querySelectorAll(s + ' [role=treeitem]')].filter(e => e.tabIndex === 0).length", panel) shouldBe 1
        // ArrowLeft goes to the parent folder; Enter on a folder toggles it closed.
        page.keyboard().press("ArrowLeft")
        focusedTitle() shouldBe "acme/keys"
        page.keyboard().press("ArrowDown")
        page.keyboard().press("Enter")
        page.waitForURL("**/pipelines/$id")
        page.waitForSelector(".pe-root")
    }

    private fun focusedTitle(): String? =
        page.evaluate(
            "() => { const e = document.activeElement; const l = e && e.querySelector('.tpl-label');" +
                " return l ? l.getAttribute('title') : null; }",
        ) as String?

    // ------------------------------------------------------------------ the catalog page

    @Test
    fun `the catalog lists every pipeline flat, a row opens the workspace, q deep-links, and Browse folders opens the sidebar tree`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350cat")
        val id = seedPipeline("acme/catalog/first")
        seedPipeline("trade/catalog/second")

        page.navigate("$baseUrl/pipelines")
        page.waitForSelector("#pipeline-list-wrapper")
        // No tree and no detail pane on the page any more.
        page.locator("#app-main .tpl-folder").count() shouldBe 0
        page.locator("#pipeline-detail").count() shouldBe 0
        val rows = page.locator("#pipeline-list-wrapper a.tpl-result")
        rows.count() shouldBe 2

        // ?q= is still the deep link "find this pipeline" uses everywhere.
        page.navigate("$baseUrl/pipelines?q=first")
        page.waitForSelector("#pipeline-list-wrapper a.tpl-result")
        page.locator("#pipeline-list-wrapper a.tpl-result").count() shouldBe 1
        page.locator("#pipeline-filter-q").inputValue() shouldBe "first"

        // Browse folders opens the sidebar tree and puts focus in its search.
        page.click("[data-nav-tree-reveal='pipelines']")
        page.waitForSelector("#pipeline-nav-root .tpl-tree")
        page.evaluate("() => document.activeElement && document.activeElement.hasAttribute('data-nav-tree-search')") shouldBe true

        // A catalog row is a full-document link into the workspace.
        page.click("#pipeline-list-wrapper a.tpl-result")
        page.waitForURL("**/pipelines/$id")
        page.waitForSelector(".pe-root")
        drainCspViolations().shouldBeEmpty()
        consoleErrors.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ A13 widths + themes

    @Test
    fun `A13 - phone - the drawer keeps its width, the tree scrolls inside it, the page never overflows`() {
        page.setViewportSize(1440, 900) // the workspace switcher is in the closed drawer below 768
        loginReadyUser("p350ph")
        seedPipeline(deepLeaf)
        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/dashboard")
        page.click("#rail-open")
        page.waitForSelector("html.rail-open")
        openTree()
        deepFolders.forEach { expand(it) }
        page.waitForSelector(leaf(deepLeaf))
        settle()

        val drawer = railWidth()
        record("rail.drawer.390", drawer)
        drawer shouldBe 232.0 // the phone breakpoint wins over the desktop tree minimum
        val overflow = page.evaluate("s => { const r = document.querySelector(s); return r.scrollWidth > r.clientWidth; }", region)
        overflow shouldBe true
        documentOverflowsX() shouldBe false
        shot("phone-drawer-tree")
        drainCspViolations().shouldBeEmpty()
        consoleErrors.shouldBeEmpty()
    }

    @Test
    fun `A13 - tablet - the default icon rail hides the tree, an expanded rail widens within the bounds`() {
        page.setViewportSize(1024, 768)
        loginReadyUser("p350tab")
        seedPipeline(deepLeaf)
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("[data-nav-branch='pipelines']")
        railWidth() shouldBe 60.0
        page.locator("[data-nav-branch='pipelines'] [data-nav-tree-toggle]").isVisible shouldBe false

        page.click("#rail-collapse") // the reader expands the rail (stored "0")
        settle()
        railWidth() shouldBe 232.0
        openTree()
        railWidth() shouldBe 320.0
        deepFolders.forEach { expand(it) }
        settle()
        val widest = railWidth()
        record("rail.open.deep.1024", widest)
        widest shouldBe 400.0
        documentOverflowsX() shouldBe false
        shot("tablet-tree")
    }

    @Test
    fun `A13 - both themes render the open tree with the current leaf marked`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350thm")
        val id = seedPipeline("acme/themes/current_one")
        seedPipeline("acme/themes/other_one")
        for (mode in listOf("light", "dark")) {
            ensureTheme(mode)
            page.navigate("$baseUrl/pipelines/$id")
            page.waitForSelector(".pe-root")
            if (!page.locator(panel).isVisible) openTree()
            page.waitForSelector("${leaf("acme/themes/current_one")}[aria-current='page']")
            settle()
            page.evaluate("() => document.documentElement.getAttribute('data-theme')") shouldBe mode
            shot("workspace-tree-$mode")
        }
        drainCspViolations().shouldBeEmpty()
        consoleErrors.shouldBeEmpty()
    }

    private companion object {
        /** Frames the wheel's asynchronous scroll gets to settle — bounded, never a sleep. */
        const val SCROLL_POLLS = 20
    }
}
