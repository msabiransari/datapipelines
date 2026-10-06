package co.datapipelines.browser

import io.kotest.matchers.shouldBe

/** Shared real-application fixtures for the REST tree witnesses; each case creates its own workspace. */
abstract class RestTreeBrowserFixture : BrowserSuite() {
    protected val panel = "#nav-tree-pipelines"

    protected fun readyTree() {
        val user = seedLocalUser(uniqueEmail(generatedPassword("tree")), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("tree-" + generatedPassword("ws").lowercase())
        page.setViewportSize(1440, 900)
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
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

    protected fun openTree() {
        page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('pipelines')?.state.levels.get(null)?.complete")
    }

    protected fun folder(path: String) = "$panel [data-tree-key='folder:$path']"

    protected fun leaf(path: String) = "$panel a[title='$path']"

    protected fun expand(path: String) {
        page.click("${folder(path)} > .dp-tree-line button")
        page.waitForFunction(
            "key => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get(key)?.complete",
            "folder:$path",
        )
    }

    protected fun search(value: String) {
        page.fill("$panel input[type=search]", value)
        page.waitForFunction(
            "value => {const s=window.DatapipelinesSidebarTrees.get('pipelines').state; return s.query===value && s.search.complete}",
            value,
        )
    }

    protected fun closedRoot() {
        page.locator("$panel [aria-expanded=true]").count() shouldBe 0
        page.locator("$panel .dp-tree-rows > [role=treeitem]").count() shouldBe 1
    }

    protected fun width(): Int =
        (page.evaluate("() => document.getElementById('app-rail').getBoundingClientRect().width") as Number).toInt()

    protected fun settle() {
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
    }
}
