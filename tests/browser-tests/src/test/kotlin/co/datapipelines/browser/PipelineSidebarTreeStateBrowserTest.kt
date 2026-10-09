package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** REST state guards replace the retired fragment/stamp/auto-reveal contract with shallow, persistent state. */
class PipelineSidebarTreeStateBrowserTest : RestTreeBrowserFixture() {
    @Test
    fun `fresh initialization ignores old persisted expansions and does not reveal the current deep leaf`() {
        readyTree()
        val id = seedPipeline("acme/a/b/target")
        page.evaluate(
            "() => " +
                "localStorage.setItem('dp-nav:pipelines:'+document.documentElement.dataset.dpWorkspace, " +
                "JSON.stringify({open:true,paths:['acme','acme/a','acme/a/b']}))",
        )
        page.navigate("$baseUrl/pipelines/$id")
        val requests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/api/v1/pipelines/tree")) requests += it.url() }
        openTree()
        closedRoot()
        requests.size shouldBe 1
        page.locator(leaf("acme/a/b/target")).count() shouldBe 0
        expand("acme")
        expand("acme/a")
        expand("acme/a/b")
        page.waitForSelector("${leaf("acme/a/b/target")}[aria-current=page]")
        requests.size shouldBe 4
    }

    @Test
    fun `one expansion delivers the entire level and reopening a complete level makes no request`() {
        readyTree()
        repeat(27) { seedPipeline("acme/p$it") }
        val requests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/api/v1/pipelines/tree")) requests += it.url() }
        openTree()
        expand("acme")
        page.locator("${folder("acme")} > .dp-tree-group > [role=treeitem]").count() shouldBe 27
        page.click("${folder("acme")} > .dp-tree-line button")
        expand("acme")
        requests.size shouldBe 2
    }

    @Test
    fun `failed child loading keeps its parent and sibling stable and Retry delivers the real level`() {
        readyTree()
        seedPipeline("acme/fails/one")
        openTree()
        page.evaluate("() => window.__parentRow=document.querySelector('[data-tree-key=\"folder:acme\"]')")
        val pattern = "**/api/v1/pipelines/tree?root=&parent=acme"
        page.route(pattern) {
            it.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(503)
                    .setContentType("application/json")
                    .setBody("{}"),
            )
        }
        page.click("${folder("acme")} > .dp-tree-line button")
        page.waitForSelector("${folder("acme")} .dp-tree-status button")
        page.locator("${folder("acme")} .dp-tree-status").innerText() shouldContain "Could not load"
        page.unroute(pattern)
        page.click("${folder("acme")} .dp-tree-status button")
        page.waitForSelector(folder("acme/fails"))
        page.evaluate("() => window.__parentRow===document.querySelector('[data-tree-key=\"folder:acme\"]')") shouldBe true
        expand("acme/fails")
        page.waitForSelector(leaf("acme/fails/one"))
    }

    @Test
    fun `new name query wins and clear and deleting query both return to closed roots`() {
        readyTree()
        seedPipeline("acme/deep/alpha")
        seedPipeline("acme/deep/omega")
        openTree()
        expand("acme")
        expand("acme/deep")
        search("alpha")
        page.locator("$panel [aria-expanded=true]").count() shouldBe 2
        search("omega")
        page.locator(leaf("acme/deep/alpha")).count() shouldBe 0
        page.waitForSelector(leaf("acme/deep/omega"))
        page.click("$panel button[aria-label='Clear search']")
        closedRoot()
        search("alpha")
        page.fill("$panel input", "")
        closedRoot()
    }

    @Test
    fun `foreign workspace JSON never joins rows and exposes a recovery button`() {
        readyTree()
        seedPipeline("acme/hidden/leaf")
        openTree()
        val pattern = "**/api/v1/pipelines/tree?root=&parent=acme"
        page.route(pattern) { route ->
            val response = route.fetch()
            val body = response.text().replace(Regex("\"workspace_id\":\"[^\"]+\""), "\"workspace_id\":\"foreign\"")
            route.fulfill(Route.FulfillOptions().setResponse(response).setBody(body))
        }
        page.click("${folder("acme")} > .dp-tree-line button")
        page.waitForSelector("$panel .dp-tree-rows > .dp-tree-status button")
        page.locator("$panel [data-tree-key]").count() shouldBe 0
        page.unroute(pattern)
        page.click("$panel .dp-tree-status button")
        page.waitForSelector(folder("acme"))
        closedRoot()
    }

    @Test
    fun `workspace switches create a fresh tree and leave prior workspace rows behind`() {
        readyTree()
        seedPipeline("acme/private/leaf")
        openTree()
        expand("acme")
        createWorkspace("second-" + generatedPassword("ws").lowercase())
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        openTree()
        page.locator("$panel [data-tree-key]").count() shouldBe 0
        page.locator("$panel .dp-tree-status").innerText() shouldContain "No items"
    }

    @Test
    fun `changed authorization view drops every cached level before the new root is admitted`() {
        readyTree()
        seedPipeline("acme/view/leaf")
        openTree()
        val pattern = "**/api/v1/pipelines/tree*"
        page.route(pattern) { route ->
            val response = route.fetch()
            val body = response.text().replace(Regex("\"view_token\":\"[^\"]+\""), "\"view_token\":\"new-view\"")
            route.fulfill(Route.FulfillOptions().setResponse(response).setBody(body))
        }
        page.click("${folder("acme")} > .dp-tree-line button")
        page.waitForSelector("$panel .dp-tree-rows > .dp-tree-status button")
        page.locator("$panel [data-tree-key]").count() shouldBe 0
        page.click("$panel .dp-tree-status button")
        page.waitForSelector(folder("acme"))
        closedRoot()
        expand("acme")
        page.waitForSelector(folder("acme/view"))
    }

    @Test
    fun `catalog and cached history preserve the live instance rows and loaded selection`() {
        readyTree()
        val id = seedPipeline("acme/history/leaf")
        openTree()
        expand("acme")
        expand("acme/history")
        page.evaluate(
            "() => " +
                "{window.__treeInstance=window.DatapipelinesSidebarTrees.get('pipelines');window.__row=docu" +
                "ment.querySelector('[data-tree-key=\"folder:acme\"]')}",
        )
        page.click(leaf("acme/history/leaf"))
        page.waitForURL("**/pipelines/$id")
        page.waitForSelector("${leaf("acme/history/leaf")}[aria-current=page]")
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForSelector("#template-list-wrapper")
        // The rail drops the mark in markCurrent on htmx:afterSettle, a settle delay AFTER the swap
        // that adds #template-list-wrapper: wait for the settled rail, never read it at the swap.
        page.waitForFunction("() => !document.querySelector('$panel [aria-current=page]')")
        page.goBack()
        page.waitForURL("**/pipelines/$id")
        page.waitForFunction("() => !!window.__peInstance")
        page.evaluate(
            "() => window.__treeInstance===window.DatapipelinesSidebarTrees.get('pipelines') && " +
                "window.__row===document.querySelector('[data-tree-key=\"folder:acme\"]')",
        ) shouldBe
            true
        page.goForward()
        page.waitForSelector("#template-list-wrapper")
        page.locator("$panel [aria-current=page]").count() shouldBe 0
    }

    @Test
    fun `redundant empty input leaves the pending folder generation intact and the held reply lands`() {
        readyTree()
        seedPipeline("acme/fx/one")
        openTree()
        search("missing")
        page.click("$panel button[aria-label='Clear search']")
        expand("acme")
        var held: Route? = null
        val pattern = "**/api/v1/pipelines/tree?root=&parent=acme%2Ffx"
        page.route(pattern) { held = it }
        page.click("${folder("acme/fx")} > .dp-tree-line button")
        page.waitForFunction(
            "() => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get('folder:acme/fx').status==='loading'",
        )
        val generation =
            page.evaluate(
                "() => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get('folder:acme/fx').generation",
            )
        page.evaluate("() => document.querySelector('#nav-tree-pipelines input').dispatchEvent(new Event('input',{bubbles:true}))")
        page.evaluate("() => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get('folder:acme/fx').generation") shouldBe
            generation
        val route = checkNotNull(held)
        route.fulfill(Route.FulfillOptions().setResponse(route.fetch()))
        page.waitForSelector(leaf("acme/fx/one"))
        page.fill("$panel input", "one")
        page.evaluate("() => document.querySelector('#nav-tree-pipelines input').dispatchEvent(new Event('input',{bubbles:true}))")
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('pipelines').state.search.complete")
        page.waitForSelector(leaf("acme/fx/one"))
    }
}
