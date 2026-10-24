package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Computed geometry and interactions for the resource browser (#554, record §4): the menu has fixed
 * 202/64 widths, the browser's user width (240–480, clamped to leave main 320) owns its column, and
 * neither moves with tree content. At 1440 (the 1101–1599 band): 202 + 300 default + main 938.
 */
class PipelineSidebarTreeBrowserTest : RestTreeBrowserFixture() {
    @Test
    fun `the browser resizes within 240-480 on its boundary, main keeps its minimum, and depth moves nothing`() {
        readyTree()
        seedPipeline("acme/deep/one")
        openTree()
        expand("acme")
        menuWidth() shouldBe 202
        width() shouldBe 300
        mainWidth() shouldBe 1440 - 202 - 300
        page.locator("#browser-boundary").focus()
        page.keyboard().press("Shift+Tab")
        page.keyboard().press("Tab")
        page.evaluate("() => document.activeElement.id") shouldBe "browser-boundary"
        val boundaryShadow = page.locator("#browser-boundary").evaluate("el => getComputedStyle(el).boxShadow")
        val boundaryOutline = page.locator("#browser-boundary").evaluate("el => getComputedStyle(el).outlineStyle")
        val boundaryOutlineWidth = page.locator("#browser-boundary").evaluate("el => getComputedStyle(el).outlineWidth")
        println("644-measure|boundary|boxShadow|$boundaryShadow")
        println("644-measure|boundary|outline|" + page.locator("#browser-boundary").evaluate("el => getComputedStyle(el).outline"))
        page.keyboard().press("End")
        width() shouldBe 480
        mainWidth() shouldBe 1440 - 202 - 480
        page.locator("#browser-boundary").getAttribute("aria-valuenow") shouldBe "480"
        page.keyboard().press("Home")
        width() shouldBe 240
        mainWidth() shouldBe 1440 - 202 - 240
        page.keyboard().press("ArrowRight")
        width() shouldBe 256
        val box = checkNotNull(page.locator("#browser-boundary").boundingBox())
        page.mouse().move(box.x + box.width / 2, box.y + box.height / 2)
        page.mouse().down()
        page.mouse().move(202.0 + 400, 400.0)
        page.evaluate(
            "() => document.getElementById('browser-boundary')" +
                ".dispatchEvent(new PointerEvent('pointercancel', {pointerId:1,bubbles:true}))",
        )
        page.mouse().move(202.0 + 470, 400.0)
        page.mouse().up()
        width() shouldBe 400
        page.click("${folder("acme")} > .dp-tree-line button")
        expand("acme")
        expand("acme/deep")
        width() shouldBe 400
        menuWidth() shouldBe 202
        boundaryShadow shouldBe "none"
        boundaryOutline shouldBe "solid"
        boundaryOutlineWidth shouldBe "2px"
    }

    @Test
    fun `the menu collapse, a narrow window and a reload keep the browser preference, and reset returns the default`() {
        readyTree()
        seedPipeline("acme/one")
        openTree()
        val key = "dp-browser-width:" + page.evaluate("() => document.documentElement.dataset.dpWorkspace")
        page.locator("#browser-boundary").focus()
        repeat(5) { page.keyboard().press("ArrowRight") }
        width() shouldBe 380
        page.evaluate("k => localStorage.getItem(k)", key) shouldBe "380"
        // The menu's collapse and the browser's width are independent (record §3 rule 7).
        page.click("#rail-collapse")
        menuWidth() shouldBe 64
        width() shouldBe 380
        page.click("#rail-collapse")
        menuWidth() shouldBe 202
        // A narrow window clamps the EFFECTIVE width to leave main 320 and never rewrites the
        // preference: the explicit expansion holds in the 761–1100 band, so 800 - 202 - 320.
        page.setViewportSize(800, 900)
        settle()
        menuWidth() shouldBe 202
        width() shouldBe 278
        mainWidth() shouldBe 320
        page.locator("#browser-boundary").getAttribute("aria-valuemax") shouldBe "278"
        page.evaluate("k => localStorage.getItem(k)", key) shouldBe "380"
        page.setViewportSize(1440, 900)
        settle()
        width() shouldBe 380
        page.reload()
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        width() shouldBe 380
        page.click("#browser-width-reset")
        width() shouldBe 300
        page.evaluate("k => localStorage.getItem(k)", key) shouldBe null
    }

    @Test
    fun `a persistent leaf resolves CURRENT rather than newest release or draft`() {
        readyTree()
        val name = "acme/flows/versioned"
        val created = api("POST", "/api/v1/pipelines", pipelineBody(name, "n_v1"))
        val id = field(created, "id")
        val v1 = field(created, "body_hash")
        api("POST", "/api/v1/pipelines/$id/release", null, v1)
        val v2 = field(api("PUT", "/api/v1/pipelines/$id", pipelineBody(name, "n_v2"), v1), "body_hash")
        api("POST", "/api/v1/pipelines/$id/release", null, v2)
        api("PUT", "/api/v1/pipelines/$id", pipelineBody(name, "n_v3"), v2)
        api("POST", "/api/v1/pipelines/$id/current", """{"version":1}""")
        openTree()
        expand("acme")
        expand("acme/flows")
        page.evaluate(
            "() => {window.__railWitness=document.getElementById('app-rail');" +
                "window.__browserWitness=document.getElementById('app-browser')}",
        )
        page.locator(leaf(name)).getAttribute("href") shouldBe "/pipelines/$id"
        page.click(leaf(name))
        page.waitForURL("**/pipelines/$id")
        page.waitForFunction("() => !!window.__peInstance")
        page.evaluate("() => window.__railWitness===document.getElementById('app-rail')") shouldBe true
        page.evaluate("() => window.__browserWitness===document.getElementById('app-browser')") shouldBe true
        val json = page.locator("#pipeline-data").textContent()
        json shouldContain "n_v1"
        json shouldNotContain "n_v2"
        json shouldNotContain "n_v3"
        page.locator(".pe-vchip").innerText() shouldBe "v1 · released · current"
    }

    @Test
    fun `keyboard arrows move focus Home End reach rows and Enter activates a real anchor`() {
        readyTree()
        val id = seedPipeline("acme/keys/one")
        seedPipeline("acme/keys/two")
        openTree()
        val start = page.url()
        page.focus("$panel input")
        page.keyboard().press("ArrowDown")
        focused() shouldBe "folder:acme"
        page.keyboard().press("ArrowRight")
        page.waitForSelector(folder("acme/keys"))
        page.keyboard().press("ArrowDown")
        focused() shouldBe "folder:acme/keys"
        page.keyboard().press("ArrowRight")
        page.waitForSelector(leaf("acme/keys/two"))
        page.keyboard().press("Home")
        focused() shouldBe "folder:acme"
        page.keyboard().press("End")
        page.evaluate("() => document.activeElement.querySelector('a').title") shouldBe "acme/keys/two"
        page.keyboard().press("ArrowUp")
        page.evaluate("() => document.activeElement.querySelector('a').title") shouldBe "acme/keys/one"
        page.evaluate("s => [...document.querySelectorAll(s+' [role=treeitem]')].filter(e=>e.tabIndex===0).length", panel) shouldBe 1
        page.keyboard().press("ArrowLeft")
        focused() shouldBe "folder:acme/keys"
        page.keyboard().press("ArrowDown")
        page.url() shouldBe start
        page.keyboard().press("Enter")
        page.waitForURL("**/pipelines/$id")
    }

    @Test
    fun `the focus ring rides the focused row for keyboard only and never boxes a subtree`() {
        readyTree()
        seedPipeline("acme/keys/one")
        seedPipeline("acme/keys/two")
        openTree()
        // #516: a click lands on the item the renderer focused (render.mjs item.focus()), and
        // the selected-row tint is the only answer it may draw — the old outline on the item
        // boxed the whole expanded subtree the item element contains. #644 also checks
        // the vendored global box-shadow: the line's outline must be the ONLY ring.
        page.click("${folder("acme")} > .dp-tree-line button")
        expand("acme/keys")
        focused() shouldBe "folder:acme/keys"
        page.locator(folder("acme/keys")).evaluate("el => getComputedStyle(el).outlineWidth") shouldBe "0px"
        page.evaluate(
            "p => [...document.querySelector(p).querySelectorAll('[role=treeitem], .dp-tree-line')]" +
                ".filter(e => (parseFloat(getComputedStyle(e).outlineWidth) > 0 && " +
                "getComputedStyle(e).outlineStyle !== 'none') || getComputedStyle(e).boxShadow !== 'none').length",
            panel,
        ) shouldBe 0
        // Keyboard focus keeps the ring, on the focused row's line and inside its box: the
        // item itself still carries none, and no group or child line lights up with it.
        page.focus("$panel input")
        page.keyboard().press("ArrowDown")
        focused() shouldBe "folder:acme"
        page.evaluate("() => getComputedStyle(document.activeElement).outlineWidth") shouldBe "0px"
        val rootShadow = page.evaluate("() => getComputedStyle(document.activeElement).boxShadow")
        println("644-measure|root|boxShadow|$rootShadow")
        page.evaluate("() => getComputedStyle(document.activeElement.querySelector('.dp-tree-line')).outlineWidth") shouldBe "2px"
        page.keyboard().press("ArrowRight")
        focused() shouldBe "folder:acme/keys"
        val expandedShadow = page.evaluate("() => getComputedStyle(document.activeElement).boxShadow")
        println("644-measure|expanded|boxShadow|$expandedShadow")
        page.screenshot(
            Page.ScreenshotOptions().setPath(
                Paths.get("build", "browser-evidence", "644-expanded-folder.png").also { Files.createDirectories(it.parent) },
            ),
        )
        page.locator("${folder("acme/keys")} > .dp-tree-line").evaluate("el => getComputedStyle(el).outlineWidth") shouldBe "2px"
        // Chromium computes a 3px outline-width WITH style none on elements no rule outlines,
        // so "boxed" is the painted predicate the sweep below uses, not a width alone.
        page.evaluate(
            "() => getComputedStyle(document.activeElement.querySelector(':scope > [role=group]')).outlineStyle",
        ) shouldBe "none"
        page.evaluate(
            "() => [...document.activeElement.querySelector('[role=group]').querySelectorAll('[role=treeitem], .dp-tree-line')]" +
                ".every(e => (parseFloat(getComputedStyle(e).outlineWidth) === 0 || " +
                "getComputedStyle(e).outlineStyle === 'none') && getComputedStyle(e).boxShadow === 'none')",
        ) shouldBe true
        assertAll(
            { rootShadow shouldBe "none" },
            { expandedShadow shouldBe "none" },
        )
    }

    @Test
    fun `catalog q deep links and Browse folders shows the hidden browser at its root then a row preserves both columns`() {
        readyTree()
        val id = seedPipeline("acme/catalog/first")
        seedPipeline("trade/catalog/second")
        page.navigate("$baseUrl/pipelines?q=first")
        page.waitForSelector("#pipeline-list-wrapper a.tpl-result")
        page.locator("#pipeline-list-wrapper a.tpl-result").count() shouldBe 1
        page.locator("#pipeline-filter-q").inputValue() shouldBe "first"
        // #554: Browse folders SHOWS the browser — here after the reader hid it — and focuses its search.
        page.click("#browser-hide")
        page.locator("#app-browser").isVisible shouldBe false
        page.click("[data-nav-tree-reveal=pipelines]")
        page.locator("#app-browser").isVisible shouldBe true
        page.waitForSelector(folder("acme"))
        page.evaluate("() => document.activeElement===window.DatapipelinesSidebarTrees.get('pipelines').input") shouldBe true
        page.locator("$panel [aria-expanded=true]").count() shouldBe 0
        page.evaluate("() => window.__railWitness=document.getElementById('app-rail')")
        page.click("#pipeline-list-wrapper a.tpl-result")
        page.waitForURL("**/pipelines/$id")
        page.evaluate("() => window.__railWitness===document.getElementById('app-rail')") shouldBe true
        page.locator("#app-browser").isVisible shouldBe true
    }

    @Test
    fun `the phone browser drawer wraps a long tree label inside itself with no sideways scroll anywhere`() {
        readyTree()
        val name = "acme/deep/leaf_with_a_deliberately_long_name_for_horizontal_scrolling"
        seedPipeline(name)
        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/pipelines")
        openTree()
        expand("acme")
        expand("acme/deep")
        page.locator("#browser-boundary").isVisible shouldBe false
        width() shouldBe 360
        overflow() shouldBe false
        // Record §7: resource names wrap, unbroken ones included; the scroll region never widens.
        page.evaluate(
            "() => {const r=document.querySelector('#nav-tree-pipelines .dp-tree-scroll');return r.scrollWidth<=r.clientWidth}",
        ) shouldBe true
        val leafBox = checkNotNull(page.locator(leaf(name)).boundingBox())
        val drawer = checkNotNull(page.locator("#app-browser").boundingBox())
        (leafBox.x + leafBox.width <= drawer.x + drawer.width) shouldBe true
        // Wrapped, not clipped: the whole name is laid out inside the anchor.
        page.locator(leaf(name)).evaluate("a => a.scrollWidth <= a.clientWidth") shouldBe true
    }

    @Test
    fun `the tablet band starts with a 64 menu and a 270 browser, and the browser stays bounded once the menu expands`() {
        readyTree()
        seedPipeline("acme/one")
        page.setViewportSize(900, 900)
        settle()
        openTree()
        menuWidth() shouldBe 64
        width() shouldBe 270
        page.click("#rail-collapse")
        menuWidth() shouldBe 202
        width() shouldBe 270
        page.locator("#browser-boundary").focus()
        page.keyboard().press("End")
        width() shouldBe 900 - 202 - 320
        mainWidth() shouldBe 320
        overflow() shouldBe false
        page.keyboard().press("Home")
        width() shouldBe 240
    }

    @Test
    fun `search clear button is keyboard operable and retains search focus without navigation`() {
        readyTree()
        seedPipeline("acme/deep/one")
        openTree()
        search("one")
        val url = page.url()
        page.locator("$panel button[aria-label='Clear search']").focus()
        page.keyboard().press("Enter")
        closedRoot()
        page.url() shouldBe url
        page.evaluate("() => document.activeElement===window.DatapipelinesSidebarTrees.get('pipelines').input") shouldBe true
        page.locator("$panel button[aria-label='Clear search'] use").getAttribute("href") shouldContain "#x"
    }

    /**
     * #513 — the sidebar search box carries the shared length bound, rendered from the
     * server's one constant, and a too-long refusal names the limit instead of the panel's
     * generic failed state. One case, three legs: the bound on the page and the input, the
     * clamp under real typing (insertText goes through the browser's editing path, so
     * maxlength clamps it — `fill` would bypass it), and the server's real refusal shape
     * replayed at the route so the status row states the limit. `SearchNeedle.MAX_LENGTH`
     * is runtime-only for this module (`implementation` behind `:modules:app`), so the
     * constant is read reflectively — the test carries no second literal of the bound.
     */
    @Test
    fun `the search box carries the server's length bound and a refusal names the limit`() {
        readyTree()
        seedPipeline("acme/one")
        openTree()
        val input = "$panel input[type=search]"
        assertAll(
            {
                // (a) the bound arrives as the layout's data attribute and lands on the input.
                searchMaxLength shouldBe 200
                page.evaluate("() => document.documentElement.dataset.dpSearchMaxLength") shouldBe searchMaxLength.toString()
                (page.evaluate("() => document.querySelector('$panel input[type=search]').maxLength") as Number)
                    .toInt() shouldBe searchMaxLength
            },
            {
                // (b) real typing past the bound is clamped, so the request never exceeds it.
                page.focus(input)
                val searchRequest =
                    page.waitForRequest({ r -> r.url().contains("/api/v1/pipelines/tree/search") }) {
                        page.keyboard().insertText("a".repeat(searchMaxLength + 1))
                    }
                (page.evaluate("() => document.querySelector('$panel input[type=search]').value.length") as Number)
                    .toInt() shouldBe searchMaxLength
                searchRequest.url().substringAfter("q=").length shouldBe searchMaxLength
                page.waitForFunction(
                    "value => {const s=window.DatapipelinesSidebarTrees.get('pipelines').state;" +
                        " return s.query===value && s.search.complete}",
                    "a".repeat(searchMaxLength),
                )
                page.locator("$panel .dp-tree-status").textContent() shouldNotContain "Could not load"
            },
            {
                // (c) the server's own refusal, then the same shape replayed at the route.
                val real =
                    page.request().get("$baseUrl/api/v1/pipelines/tree/search?root=&q=" + "a".repeat(searchMaxLength + 1))
                real.status() shouldBe 400
                real.text() shouldContain "\"code\":\"request.query_too_long\""
                Files.createDirectories(envelopePath.parent)
                Files.writeString(envelopePath, real.text())
                page.route("**/api/v1/pipelines/tree/search**") { route ->
                    route.fulfill(
                        Route
                            .FulfillOptions()
                            .setStatus(400)
                            .setContentType("application/json")
                            .setBody(real.text()),
                    )
                }
                page.fill(input, "abc")
                page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('pipelines').state.search.status === 'error'")
                val status = page.locator("$panel .dp-tree-status").textContent()
                status shouldContain searchMaxLength.toString()
                status shouldNotContain "Could not load"
                page.locator("$panel .dp-tree-status button").textContent() shouldBe "Retry"
            },
        )
    }

    private val searchMaxLength: Int =
        Class.forName("co.datapipelines.pipeline.SearchNeedle").getDeclaredField("MAX_LENGTH").get(null) as Int

    private val envelopePath = Paths.get("build", "evidence", "513-query-too-long-envelope.json")

    private fun focused() = page.evaluate("() => document.activeElement.getAttribute('data-tree-key')")

    private fun overflow() =
        page.evaluate("() => document.documentElement.scrollWidth>innerWidth || document.body.scrollWidth>innerWidth") as Boolean
}
