package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe

/** Shared real-application fixtures for the REST tree witnesses; each case creates its own workspace. */
abstract class RestTreeBrowserFixture : BrowserSuite() {
    protected val panel = "#nav-tree-pipelines"

    protected fun readyTree() {
        val user = seedLocalUser(uniqueEmail(generatedPassword("tree")), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        landOnDashboard()
        createWorkspace("tree-" + generatedPassword("ws").lowercase())
        page.setViewportSize(1440, 900)
        named(page, "the resource browser's tree registry (window.DatapipelinesSidebarTrees)", ::shellInitFacts) {
            page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        }
    }

    protected fun api(
        method: String,
        path: String,
        body: String?,
        hash: String? = null,
    ): String =
        page.evaluate(
            """async ([method,path,body,hash]) => {
              const csrf=document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const headers={'Content-Type':'application/json','DP-CSRF-Token':csrf?decodeURIComponent(csrf[1]):''};
              if(hash)headers['If-Match']=hash;
              const response=await fetch(path,{method,headers,body,credentials:'same-origin'});
              if(!response.ok)throw new Error('Fixture refused: '+response.status+' '+await response.text());
              return response.text();
            }""",
            arrayOf(method, path, body, hash),
        ) as String

    protected fun field(
        json: String,
        key: String,
    ): String = page.evaluate("([text,key]) => JSON.parse(text).data[key]", arrayOf(json, key)) as String

    protected fun pipelineBody(
        name: String,
        node: String = "n1",
    ): String =
        """{"name":"$name","display_name":"${name.substringAfterLast('/')}","nodes":[{"id":"$node","type":"CALCULATOR",""" +
            """"kind":"fiscal_quarter","context_key":"run_q_$node",""" +
            """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""

    protected fun seedPipeline(name: String): String {
        val created = api("POST", "/api/v1/pipelines", pipelineBody(name))
        val id = field(created, "id")
        api("POST", "/api/v1/pipelines/$id/release", null, field(created, "body_hash"))
        return id
    }

    /** #554: the Pipelines panel of the resource browser, on screen with its root level loaded. */
    protected fun openTree() = openBrowserTree("pipelines")

    protected fun folder(path: String) = "$panel [data-tree-key='folder:$path']"

    protected fun leaf(path: String) = "$panel a[title='$path']"

    protected fun expand(path: String) {
        named(page, "the pipelines tree's expand button for folder $path (${folder(path)} > .dp-tree-line button)") {
            page.click("${folder(path)} > .dp-tree-line button")
        }
        named(page, "the pipelines tree's level for folder $path (folder:$path)", { levelFacts(it, "folder:$path") }) {
            page.waitForFunction(
                "key => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get(key)?.complete",
                "folder:$path",
            )
        }
    }

    protected fun search(value: String) {
        named(page, "the pipelines tree's search field ($panel input[type=search])") {
            page.fill("$panel input[type=search]", value)
        }
        named(page, "the pipelines tree's search results (query '$value')", { searchFacts(it) }) {
            page.waitForFunction(
                "value => {const s=window.DatapipelinesSidebarTrees.get('pipelines').state; return s.query===value && s.search.complete}",
                value,
            )
        }
    }

    /** #831: one tree level as its wait left it — status, completion, row count, error sentence; no row content. */
    private fun levelFacts(
        on: Page,
        key: String,
    ): String =
        treeProbe(
            on,
            """
            key => {
              const level = window.DatapipelinesSidebarTrees?.get('pipelines')?.state.levels.get(key);
              if (!level) return 'level=absent';
              return 'level=' + level.status + ' complete=' + level.complete + ' rows=' + level.nodes.size +
                (level.error ? ' error=' + level.error.message : '');
            }
            """.trimIndent(),
            key,
        )

    /** #831: the search as its wait left it — the query the tree holds and the search level's state. */
    private fun searchFacts(on: Page): String =
        treeProbe(
            on,
            """
            () => {
              const state = window.DatapipelinesSidebarTrees?.get('pipelines')?.state;
              if (!state) return 'tree=absent';
              const s = state.search;
              return 'query=' + JSON.stringify(state.query) + ' search=' + s.status + ' complete=' + s.complete + ' rows=' + s.nodes.size;
            }
            """.trimIndent(),
            null,
        )

    private fun treeProbe(
        on: Page,
        script: String,
        arg: Any?,
    ): String = runCatching { on.evaluate(script, arg) as String }.getOrElse { "the tree probe itself failed: $it" }

    protected fun closedRoot() {
        page.locator("$panel [aria-expanded=true]").count() shouldBe 0
        page.locator("$panel .dp-tree-rows > [role=treeitem]").count() shouldBe 1
    }

    /** The resource browser's rendered width (#554: the column the separator resizes). */
    protected fun width(): Int = boxWidth("app-browser")

    /** The primary menu's rendered width: 202 expanded, 64 compact (#554). */
    protected fun menuWidth(): Int = boxWidth("app-rail")

    protected fun mainWidth(): Int = boxWidth("app-main")

    protected fun boxWidth(id: String): Int =
        (page.evaluate("id => Math.round(document.getElementById(id).getBoundingClientRect().width)", id) as Number).toInt()

    protected fun settle() {
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
    }
}
