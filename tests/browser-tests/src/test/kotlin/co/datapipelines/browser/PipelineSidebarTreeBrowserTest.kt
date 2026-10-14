package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

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
