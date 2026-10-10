package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI

/** Real charts and runtime ownership across prepared navigation, failed preparation and cached history. */
class PersistentChartNavigationBrowserTest : VisualizationBrowserSuite() {
    @Test
    @Suppress("LongMethod") // a single chart/history/zero-width journey keeps identity witnesses live
    fun `2d and 3d workspaces versions and cached history share one bundle and retain the rail`() {
        val root = ready("persistcharts")
        page.setViewportSize(1440, 1000)
        val (flat, _) = createVisualization("$root/charts/flat")
        val (surface, _) = createVisualization("$root/charts/surface", trace3d = true)
        openVisualizations(root)
        page.evaluate(
            "() => " +
                "{window.__rail=document.getElementById('app-rail');window.__rootRow=document.querySelector" +
                "('#nav-tree-visualizations [role=treeitem]')}",
        )
        val treeRequests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/api/v1/visualizations/tree")) treeRequests += it.url() }
        page.click("#nav-tree-visualizations a[href='/visualizations/$flat']")
        preview()
        page.evaluate("() => {window.__plotly=window.Plotly;window.__runtime=window.DatapipelinesDashboard}")
        sameShell()
        page.locator("#rail-resize").focus()
        page.keyboard().press("End")
        page.waitForFunction("() => document.getElementById('app-main').getBoundingClientRect().width < 1")
        page.keyboard().press("Home")
        page.waitForFunction(
            "() => document.querySelector('[data-viz-case-index=\"0\"] .js-plotly-plot')?.getBoundingClientRect().width > 100",
        )
        page.click(".dp-versions a[href='/visualizations/$flat?version=1&tab=preview']")
        page.waitForURL("**/visualizations/$flat?version=1&tab=preview")
        preview()
        sameShell()
        page.click("#nav-tree-visualizations a[href='/visualizations/$surface']")
        page.waitForURL("**/visualizations/$surface")
        preview()
        sameShell()
        page.evaluate("() => window.Plotly===window.__plotly && window.DatapipelinesDashboard===window.__runtime") shouldBe true
        page.locator("script[data-dp-plotly-bundle]").count() shouldBe 1
        page.locator("script[data-dp-plotly-bundle]").getAttribute("data-dp-plotly-bundle") shouldBe "3d"
        page.evaluate("() => performance.getEntriesByType('resource').filter(x=>x.name.includes('plotly-2d.min.js')).length") shouldBe 0
        page.click(".app-nav-link[data-nav-section='/visualizations']")
        page.waitForSelector("#viz-list-wrapper")
        page.goBack()
        page.waitForURL("**/visualizations/$surface")
        preview()
        sameShell()
        page.goBack()
        page.waitForURL("**/visualizations/$flat?version=1&tab=preview")
        preview()
        sameShell()
        page.goForward()
        page.waitForURL("**/visualizations/$surface")
        preview()
        sameShell()
        treeRequests.size shouldBe 0
        page.goBack()
        page.waitForURL("**/visualizations/$flat?version=1&tab=preview")
        preview()
        sameShell()
        page.evaluate("() => window.__dpVizPreviewSwap.listeners") shouldBe 1
        page.evaluate("() => window.WorkspaceHistory.stats().listeners") shouldBe 1
        // The restored preview remains interactive, and hidden cases are not mounted eagerly.
        page.selectOption("[data-viz-case-select]", "1")
        page.waitForSelector("[data-viz-case-index='1']:not([hidden])[data-dp-ready=true]")
    }

    @Test
    fun `all five real families retain loaded rows across leaves versions catalogs and history`() {
        val root = ready("allfamilies")
        seedParameterisedBoard(root)
        page.setViewportSize(1440, 1000)
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        page.evaluate("() => {window.__rail=document.getElementById('app-rail');window.__doc=document}")
        val requests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/tree")) requests += it.url() }
        listOf("pipelines", "templates", "dashboards", "visualizations", "parameter-sets").forEach { family ->
            val panel = "#nav-tree-$family"
            page.click("[data-nav-branch='$family'] [data-nav-tree-toggle]")
            page.waitForSelector("$panel [role=treeitem]")
            val href =
                page.evaluate(
                    """async family => {
                      const tree=window.DatapipelinesSidebarTrees.get(family);
                      let level=tree.state.levels.get(null);
                      while(true){
                        const leaf=Array.from(level.nodes.values()).find(node=>node.kind==='artifact');
                        if(leaf) return leaf.href;
                        const folder=Array.from(level.nodes.values()).find(node=>node.kind==='folder');
                        if(!folder) throw new Error('Fixture has no artifact');
                        await tree.state.expand(folder.key);level=tree.state.levels.get(folder.key);
                      }
                    }""",
                    family,
                ) as String
            page.evaluate(
                "family => window.__familyRow=document.querySelector('#nav-tree-'+family+' [role=treeitem]')",
                family,
            )
            val before = requests.size
            page.locator("$panel a[href='$href']").click()
            page.waitForURL("**$href")
            page.waitForFunction("() => !document.getElementById('app-main').classList.contains('htmx-settling')")
            if (family == "pipelines") {
                page.waitForFunction("() => !!window.__peInstance")
                page.locator("[data-pe-version-link]").first().click()
            } else {
                page.locator("#app-main a[href^='$href?version=1']").first().click()
                page.waitForURL("**$href?version=1*")
            }
            page.click("[data-nav-branch='$family'] .app-nav-link")
            page.waitForURL("**/$family")
            page.goBack()
            page.waitForURL("**$href*")
            page.goForward()
            page.waitForURL("**/$family")
            page.evaluate(
                "family => window.__doc===document && window.__rail===document.getElementById('app-rail') && " +
                    "window.__familyRow===document.querySelector('#nav-tree-'+family+' [role=treeitem]')",
                family,
            ) shouldBe true
            requests.size shouldBe before
        }
    }

    @Test
    fun `failed destination fetch retains a functional outgoing chart and unchanged rail`() {
        val root = ready("fetchfail")
        val (id, _) = createVisualization("$root/charts/flat")
        val (other, _) = createVisualization("$root/charts/other")
        openVisualizations(root)
        page.click("#nav-tree-visualizations a[href='/visualizations/$id']")
        preview()
        page.evaluate("() => window.__rail=document.getElementById('app-rail')")
        page.route("**/visualizations/$other") {
            it.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(503)
                    .setContentType("text/html")
                    .setBody("unavailable"),
            )
        }
        page.click("#nav-tree-visualizations a[href='/visualizations/$other']")
        page.waitForSelector("#dp-navigation-notice")
        page.url().substringAfterLast('/') shouldBe id
        page.locator("#nav-tree-visualizations a[href='/visualizations/$id']").getAttribute("aria-current") shouldBe "page"
        page.locator("#nav-tree-visualizations a[href='/visualizations/$other']").getAttribute("aria-current") shouldBe null
        page.evaluate("() => window.__rail===document.getElementById('app-rail')") shouldBe true
        page.selectOption("[data-viz-case-select]", "1")
        page.waitForSelector("[data-viz-case-index='1']:not([hidden])[data-dp-ready=true]")
        page.locator("#dp-navigation-notice").innerText() shouldContain "Could not open"
    }

    @Test
    fun `failed asset preparation preserves the outgoing pipeline observer before any swap`() {
        val root = ready("assetfail")
        val (id, _) = createVisualization("$root/charts/flat")
        val created = api("POST", "/api/v1/pipelines", calculator("$root/flow"))
        val pipeline = created.at("data", "id") as String
        page.navigate("$baseUrl/pipelines/$pipeline")
        page.waitForFunction("() => !!window.__peInstance")
        page.evaluate("() => {window.__observer=window.__peInstance;window.__rail=document.getElementById('app-rail')}")
        page.route("**/vendor/plotly/plotly-3d.min.js") {
            it.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(503)
                    .setContentType("text/plain")
                    .setBody("unavailable"),
            )
        }
        page.evaluate(
            "id => {const " +
                "a=document.createElement('a');a.href='/visualizations/'+id;a.textContent='Open " +
                "chart';a.id='chart-failure-link';document.getElementById('app-main').append(a);htmx.proces" +
                "s(a)}",
            id,
        )
        page.click("#chart-failure-link")
        page.waitForSelector("#dp-navigation-notice")
        page.url().substringAfterLast('/') shouldBe pipeline
        page.evaluate("() => window.__observer===window.__peInstance && window.__rail===document.getElementById('app-rail')") shouldBe true
        page.locator("#dp-navigation-notice").innerText() shouldContain "dependencies"
        page.locator(".pe-vchip").isVisible shouldBe true
    }

    @Test
    fun `dashboard disposal cancels its refresh and cached restoration mounts exactly once`() {
        val root = ready("boardpersist")
        val board = seedBoard(root)
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage?.ready===true")
        page.evaluate(
            "() => " +
                "{window.__oldBoard=window.__dpPage.instance;window.__rail=document.getElementById('app-rai" +
                "l');window.__disposals=0;const " +
                "old=window.__oldBoard.dispose.bind(window.__oldBoard);window.__oldBoard.dispose=function()" +
                "{window.__disposals++;return old()}}",
        )
        page.waitForFunction(
            "() => Object.keys(window.__oldBoard._refreshes).length > 0 && " +
                "Object.values(window.__oldBoard._refreshes).every(refresh => refresh.ended)",
        )
        page.evaluate("() => {window.__dpPage.instance.refresh({scope:'targets',targets:['slowchart']})}")
        page.waitForFunction("() => Object.values(window.__oldBoard._refreshes).some(refresh => !refresh.ended)")
        page.waitForRequest({ it.url().contains("/refreshes/") && it.url().endsWith("/abort") }) {
            page.click(".app-nav-link[data-nav-section='/templates']")
        }
        page.waitForSelector("#template-list-wrapper")
        page.evaluate("() => window.__disposals") shouldBe 1
        page.goBack()
        page.waitForFunction("() => window.__dpPage?.ready===true")
        page.evaluate(
            "() => window.__dpPage.instance!==window.__oldBoard && " +
                "window.__rail===document.getElementById('app-rail')",
        ) shouldBe true
        page.locator("#dp-board[data-datapipelines-dashboard]").count() shouldBe 1
        page.evaluate("() => window.__disposals") shouldBe 1
        page.locator("script[data-dp-plotly-bundle]").count() shouldBe 1
    }

    @Test
    fun `superseded final response cannot replace the newer catalog or dispose its predecessor early`() {
        val root = ready("supersede")
        val (id, _) = createVisualization("$root/charts/flat")
        page.navigate("$baseUrl/visualizations/$id")
        preview()
        page.evaluate("() => window.__rail=document.getElementById('app-rail')")
        var held: Route? = null
        page.route("**/templates") { route ->
            if (route.request().headers()["hx-request"] == "true") held = route else route.resume()
        }
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForFunction(
            "() => document.querySelector('.app-nav-link[data-nav-section=\"/templates\"]')?.classList.contains('htmx-request')",
        )
        page.selectOption("[data-viz-case-select]", "1")
        page.waitForSelector("[data-viz-case-index='1']:not([hidden])[data-dp-ready=true]")
        page.click(".app-nav-link[data-nav-section='/pipelines']")
        page.waitForSelector("#pipeline-list-wrapper")
        val route = checkNotNull(held)
        page.waitForResponse({ it.url().endsWith("/templates") && it.request().headers()["hx-request"] == "true" }) {
            route.fulfill(Route.FulfillOptions().setResponse(route.fetch()))
        }
        page.waitForFunction("() => !document.querySelector('.htmx-request')")
        page.locator("#pipeline-list-wrapper").count() shouldBe 1
        page.locator("#template-list-wrapper").count() shouldBe 0
        page.evaluate("() => window.__rail===document.getElementById('app-rail')") shouldBe true
    }

    private fun openVisualizations(root: String) {
        page.click("[data-nav-branch=visualizations] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-visualizations [data-tree-key='folder:$root']")
        for (path in listOf(root, "$root/charts")) {
            page.click("#nav-tree-visualizations [data-tree-key='folder:$path'] > .dp-tree-line button")
            page.waitForFunction(
                "key => window.DatapipelinesSidebarTrees.get('visualizations').state.levels.get(key)?.complete",
                "folder:$path",
            )
        }
    }

    @Test
    fun `a newer Home navigation also rejects a held artifact response`() {
        val root = ready("homewins")
        val (id, _) = createVisualization("$root/charts/flat")
        page.navigate("$baseUrl/visualizations/$id")
        preview()
        var held: Route? = null
        page.route("**/templates") { route ->
            if (route.request().headers()["hx-request"] == "true") held = route else route.resume()
        }
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForFunction(
            "() => document.querySelector('.app-nav-link[data-nav-section=\"/templates\"]')?.classList.contains('htmx-request')",
        )
        page.click(".app-nav-link[data-nav-section='/dashboard']")
        page.waitForURL("**/dashboard")
        page.evaluate("() => window.__homeHeading=document.querySelector('#app-main h1')")
        page.waitForResponse({ it.url().endsWith("/templates") }) { checkNotNull(held).resume() }
        page.waitForFunction(
            "() => !document.querySelector('.app-nav-link[data-nav-section=\"/templates\"]')?.classList.contains('htmx-request')",
        )
        page.url().endsWith("/dashboard") shouldBe true
        page.evaluate("() => window.__homeHeading===document.querySelector('#app-main h1')") shouldBe true
    }

    @Test
    fun `a held navigation preparation shows the shell progress bar and the destination renders twice`() {
        val root = ready("busyprep")
        val (id, _) = createVisualization("$root/charts/flat")
        openVisualizations(root)
        val renders = mutableListOf<String>()
        page.onRequest { if (URI(it.url()).path == "/visualizations/$id") renders += (it.headers()["hx-request"] ?: "preparation") }
        var held: Route? = null
        page.route("**/visualizations/$id") { route ->
            if (route.request().headers()["hx-request"] == null && held == null) held = route else route.resume()
        }
        val outgoing = page.url()
        page.click("#nav-tree-visualizations a[href='/visualizations/$id']")
        page.waitForCondition { held != null }
        // No htmx request is in flight yet — only the preparation GET — and the bar already says "working".
        page.evaluate("() => document.getElementById('app-progress').classList.contains('active')") shouldBe true
        page.url() shouldBe outgoing
        checkNotNull(held).resume()
        page.waitForURL("**/visualizations/$id")
        preview()
        page.waitForFunction("() => !document.getElementById('app-progress').classList.contains('active')")
        // Reported, not changed: the preparation GET plus the freshly authorized boosted GET (ui-screens §3.4).
        println("465-destination-renders $renders")
        renders shouldBe listOf("preparation", "true")
    }

    @Test
    @Suppress("LongMethod") // one document across editor, dashboard, template workspace and history: the counter must stay live
    fun `lifecycle dialogs execute once per document across editor dashboard editor template and history`() {
        val root = ready("onedialog")
        val board = seedBoard(root)
        val pipeline = api("POST", "/api/v1/pipelines", calculator("$root/flow")).at("data", "id") as String
        // Counts every evaluation: the script's last act is `window.lifecycleDialog = api`.
        page.addInitScript(
            """(() => { let value; window.__lifecycleDialogEvaluations = 0;
              Object.defineProperty(window, 'lifecycleDialog', { configurable: true, get() { return value; },
                set(next) { window.__lifecycleDialogEvaluations += 1; value = next; } }); })()""",
        )
        page.navigate("$baseUrl/pipelines/$pipeline")
        page.waitForFunction("() => !!window.__peInstance && window.__lifecycleDialogEvaluations > 0")
        inShell("/dashboards/$board")
        page.waitForFunction("() => window.__dpPage?.ready===true")
        inShell("/pipelines/$pipeline")
        page.waitForFunction("() => !!window.__peInstance && !document.querySelector('.pe-root[x-ignore]')")
        val template = "/templates/test/${root}_chart.sql"
        inShell(template)
        page.waitForFunction("() => !!document.getElementById('tx-dialog')")
        page.goBack()
        page.waitForURL("**/pipelines/$pipeline")
        page.waitForFunction("() => !!window.__peInstance && !document.querySelector('.pe-root[x-ignore]')")
        page.goForward()
        // The template workspace rewrites its query (tab) with replaceState, so the restored URL carries one.
        page.waitForURL("**$template*")
        page.waitForFunction("() => !!document.getElementById('tx-dialog')")
        page.evaluate("async () => { const assets = await import('/js/page-assets.mjs'); await assets.mountCharts(); }")
        page.evaluate("() => window.__lifecycleDialogEvaluations") shouldBe 1
    }

    @Test
    fun `a failed chart mount shows one notice across repeated settles of the refreshes poll`() {
        val root = ready("mountfail")
        val board = seedBoard(root)
        val attempts = mutableListOf<String>()
        page.route("**/js/datapipelines-dashboard-kpi.js") {
            attempts += it.request().url()
            it.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(503)
                    .setContentType("text/plain")
                    .setBody("unavailable"),
            )
        }
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForSelector("#app-main > p.ds-error")
        // Four settles driven by the board's own poll partial (its 15 s timer, triggered now).
        page.evaluate(
            """async () => {
              for (let i = 0; i < 4; i++) {
                const pane = document.querySelector('.dp-refreshes');
                const settled = new Promise(resolve => document.body.addEventListener('htmx:afterSettle', resolve, { once: true }));
                htmx.ajax('GET', pane.getAttribute('hx-get'), { source: pane, target: pane, swap: 'outerHTML' });
                await settled;
              }
              // The settle listener's mount attempt (if any) has finished once this resolves.
              const assets = await import('/js/page-assets.mjs'); await assets.mountCharts();
            }""",
        )
        println("465-failed-mount kpi-requests=${attempts.size}")
        page.locator("#app-main p.ds-error").count() shouldBe 1
        page.locator("#app-main p.ds-error").innerText() shouldContain "dependencies"
        attempts.size shouldBe 1
    }

    /** An in-shell navigation from a link inside main, as an artifact link on any page would make it. */
    private fun inShell(path: String) {
        page.evaluate(
            """path => { const a = document.createElement('a'); a.href = path; a.textContent = 'Go'; a.id = 'in-shell-link';
              document.getElementById('in-shell-link')?.remove(); document.getElementById('app-main').append(a); htmx.process(a); }""",
            path,
        )
        page.click("#in-shell-link")
        page.waitForURL("**$path")
        page.waitForFunction("() => !document.getElementById('app-main').classList.contains('htmx-settling')")
    }

    private fun preview() {
        page.waitForSelector("[data-viz-case-index='0'][data-dp-ready=true]")
    }

    private fun sameShell() {
        page.evaluate(
            "() => window.__rail===document.getElementById('app-rail') && " +
                "window.__rootRow===document.querySelector('#nav-tree-visualizations [role=treeitem]')",
        ) shouldBe true
    }

    private fun calculator(name: String): String =
        """{"name":"$name","nodes":[{"id":"n1","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"q", """ +
            """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""
}
