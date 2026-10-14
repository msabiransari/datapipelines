package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Computed geometry and interactions for the global rail; user width owns layout, independent of tree content. */
class PipelineSidebarTreeBrowserTest : RestTreeBrowserFixture() {
    @Test
    fun `the rail resizes past 400 while reserving the main minimum then recovers the content`() {
        readyTree()
        seedPipeline("acme/deep/one")
        openTree()
        expand("acme")
        page.locator("#rail-resize").focus()
        page.keyboard().press("End")
        width() shouldBe 1120
        (page.evaluate("() => document.getElementById('app-main').getBoundingClientRect().width") as Number).toInt() shouldBe 320
        page.keyboard().press("Home")
        width() shouldBe 232
        (page.evaluate("() => document.getElementById('app-main').getBoundingClientRect().width") as Number).toInt() shouldBe 1208
        val box = checkNotNull(page.locator("#rail-resize").boundingBox())
        page.mouse().move(box.x + box.width / 2, box.y + box.height / 2)
        page.mouse().down()
        page.mouse().move(900.0, 400.0)
        page.evaluate(
            "() => document.getElementById('rail-resize').dispatchEvent(new PointerEvent('pointercancel', {pointerId:1,bubbles:true}))",
        )
        page.mouse().move(1100.0, 400.0)
        page.mouse().up()
        width() shouldBe 900
        page.click("${folder("acme")} > .dp-tree-line button")
        expand("acme")
        width() shouldBe 900
    }

    @Test
    fun `collapse reset and temporary viewport clamps preserve and recover the preferred width`() {
        readyTree()
        seedPipeline("acme/one")
        openTree()
        page.locator("#rail-resize").focus()
        repeat(14) { page.keyboard().press("Shift+ArrowRight") }
        width() shouldBe 932
        page.click("#rail-boundary-collapse")
        width() shouldBe 60
        page.click("#rail-collapse")
        width() shouldBe 932
        page.setViewportSize(800, 900)
        settle()
        width() shouldBe 480
        page.setViewportSize(1440, 900)
        settle()
        width() shouldBe 932
        page.reload()
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        width() shouldBe 932
        page.click("#rail-width-reset")
        width() shouldBe 232
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
        page.evaluate("() => window.__railWitness=document.getElementById('app-rail')")
        page.locator(leaf(name)).getAttribute("href") shouldBe "/pipelines/$id"
        page.click(leaf(name))
        page.waitForURL("**/pipelines/$id")
        page.waitForFunction("() => !!window.__peInstance")
        page.evaluate("() => window.__railWitness===document.getElementById('app-rail')") shouldBe true
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
        // boxed the whole expanded subtree the item element contains.
        page.click("${folder("acme")} > .dp-tree-line button")
        expand("acme/keys")
        focused() shouldBe "folder:acme/keys"
        page.locator(folder("acme/keys")).evaluate("el => getComputedStyle(el).outlineWidth") shouldBe "0px"
        page.evaluate(
            "p => [...document.querySelector(p).querySelectorAll('[role=treeitem], .dp-tree-line')]" +
                ".filter(e => parseFloat(getComputedStyle(e).outlineWidth) > 0 && getComputedStyle(e).outlineStyle !== 'none').length",
            panel,
        ) shouldBe 0
        // Keyboard focus keeps the ring, on the focused row's line and inside its box: the
        // item itself still carries none, and no group or child line lights up with it.
        page.focus("$panel input")
        page.keyboard().press("ArrowDown")
        focused() shouldBe "folder:acme"
        page.evaluate("() => getComputedStyle(document.activeElement).outlineWidth") shouldBe "0px"
        page.evaluate("() => getComputedStyle(document.activeElement.querySelector('.dp-tree-line')).outlineWidth") shouldBe "2px"
        page.keyboard().press("ArrowRight")
        focused() shouldBe "folder:acme/keys"
        page.locator("${folder("acme/keys")} > .dp-tree-line").evaluate("el => getComputedStyle(el).outlineWidth") shouldBe "2px"
        // Chromium computes a 3px outline-width WITH style none on elements no rule outlines,
        // so "boxed" is the painted predicate the sweep below uses, not a width alone.
        page.evaluate(
            "() => getComputedStyle(document.activeElement.querySelector(':scope > [role=group]')).outlineStyle",
        ) shouldBe "none"
        page.evaluate(
            "() => [...document.activeElement.querySelector('[role=group]').querySelectorAll('.dp-tree-line')]" +
                ".every(e => parseFloat(getComputedStyle(e).outlineWidth) === 0 || getComputedStyle(e).outlineStyle === 'none')",
        ) shouldBe true
    }

    @Test
    fun `catalog q deep links and Browse folders opens only the root then a row preserves the rail`() {
        readyTree()
        val id = seedPipeline("acme/catalog/first")
        seedPipeline("trade/catalog/second")
        page.navigate("$baseUrl/pipelines?q=first")
        page.waitForSelector("#pipeline-list-wrapper a.tpl-result")
        page.locator("#pipeline-list-wrapper a.tpl-result").count() shouldBe 1
        page.locator("#pipeline-filter-q").inputValue() shouldBe "first"
        page.click("[data-nav-tree-reveal=pipelines]")
        page.waitForSelector(folder("acme"))
        page.evaluate("() => document.activeElement===window.DatapipelinesSidebarTrees.get('pipelines').input") shouldBe true
        page.locator("$panel [aria-expanded=true]").count() shouldBe 0
        page.evaluate("() => window.__railWitness=document.getElementById('app-rail')")
        page.click("#pipeline-list-wrapper a.tpl-result")
        page.waitForURL("**/pipelines/$id")
        page.evaluate("() => window.__railWitness===document.getElementById('app-rail')") shouldBe true
    }

    @Test
    fun `phone drawer keeps long tree labels inside its scroll region without document overflow`() {
        readyTree()
        seedPipeline("acme/deep/leaf_with_a_deliberately_long_name_for_horizontal_scrolling")
        page.setViewportSize(390, 844)
        page.reload()
        page.click("#rail-open")
        openTree()
        expand("acme")
        expand("acme/deep")
        page.locator("#rail-resize").isVisible shouldBe false
        overflow() shouldBe false
        page.evaluate("() => {const r=document.querySelector('#nav-tree-pipelines .dp-tree-scroll');r.scrollLeft=r.scrollWidth}")
        (page.evaluate("() => document.querySelector('#nav-tree-pipelines .dp-tree-scroll').scrollLeft") as Number).toInt().let {
            (it > 0) shouldBe
                true
        }
    }

    @Test
    fun `tablet starts collapsed and explicit expansion uses the bounded preference`() {
        readyTree()
        seedPipeline("acme/one")
        page.setViewportSize(900, 900)
        page.reload()
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        width() shouldBe 60
        page.click("#rail-collapse")
        openTree()
        width() shouldBe 232
        page.locator("#rail-resize").focus()
        page.keyboard().press("End")
        width() shouldBe 580
        overflow() shouldBe false
        page.keyboard().press("Home")
        width() shouldBe 232
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

    private fun focused() = page.evaluate("() => document.activeElement.getAttribute('data-tree-key')")

    private fun overflow() =
        page.evaluate("() => document.documentElement.scrollWidth>innerWidth || document.body.scrollWidth>innerWidth") as Boolean
}
