package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

/** Template branch geometry, canonical links, keyboard, phone drawer and themes on the real REST tree. */
class TemplateSidebarTreeBrowserTest : BrowserSuite() {
    private fun loginReadyUser(slug: String): String {
        val user =
            seedLocalUser(
                uniqueEmail(slug + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val workspace = "tplsb-" + generatedPassword("w").take(8).lowercase()
        createWorkspace(workspace)
        watchConsole()
        return workspace
    }

    /** One REST call in-page, with the session's CSRF pair. */
    private fun api(
        method: String,
        path: String,
        body: String?,
    ): Pair<Int, String> {
        val answer =
            page.evaluate(
                """async ([method, path, body]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json' };
                  if (method !== 'GET') headers['DP-CSRF-Token'] = csrf ? decodeURIComponent(csrf[1]) : '';
                  const res = await fetch(path, { method, credentials: 'same-origin', headers, body });
                  let text = '';
                  try { text = await res.text(); } catch (e) {}
                  return [res.status, text];
                }""",
                arrayOf(method, path, body),
            ) as List<*>
        return (answer[0] as Number).toInt() to (answer[1] as String)
    }

    private fun must(
        method: String,
        path: String,
        body: String?,
    ): String {
        val (status, text) = api(method, path, body)
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(300)}" }
        return text
    }

    /** A live template at [name]; its body is irrelevant to the tree. */
    private fun seedTemplate(name: String) {
        must(
            "POST",
            "/api/v1/templates",
            """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"${name.substringAfterLast('/')}",""" +
                """"description":"398 sidebar fixture","body":"SELECT 1"}""",
        )
    }

    // ------------------------------------------------------------------ the rail

    private val panel = "#nav-tree-templates"

    private fun railWidth(): Double =
        (page.evaluate("() => document.querySelector('.app-rail').getBoundingClientRect().width") as Number).toDouble()

    /** Two animation frames: the fit is written in one, the grid lays out by the next. */
    private fun settle() {
        page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
    }

    /**
     * Ensures the Templates panel is OPEN and its root level has landed. The toggle is a
     * TOGGLE: after a navigation that restored the remembered open state, the panel is
     * already open and clicking it would CLOSE it — so the click is conditional on the
     * panel's own state, and the wait is scoped to the OPEN panel.
     */
    private fun openTree() {
        val alreadyOpen =
            page.evaluate("() => { const p = document.getElementById('nav-tree-templates'); return !!p && !p.hidden; }") as Boolean
        if (!alreadyOpen) {
            page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        }
        page.waitForSelector("#nav-tree-templates:not([hidden]) [role=tree]")
        settle()
    }

    private fun folder(path: String) = "$panel [data-tree-key='folder:$path']"

    private fun expand(path: String) {
        if (page.locator(folder(path)).getAttribute("aria-expanded") != "true") {
            page.click("${folder(path)} > .dp-tree-line button")
        }
        page.waitForFunction(
            "selector => document.querySelector(selector)?.getAttribute('aria-busy') === 'false'",
            "${folder(path)} > .dp-tree-group",
        )
        settle()
    }

    private fun leaf(path: String) = "$panel a[href='/templates/$path']"

    private fun shot(name: String) {
        val dir = Paths.get("build", "reports", "398-screenshots")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("398-$name.png")).setFullPage(false))
    }

    private val consoleErrors = mutableListOf<String>()

    private fun watchConsole() {
        page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
    }

    /** The live host, with deterministic REST responses and a request witness. */
    private fun answerPipelines(route: Route) {
        val search = route.request().url().contains("/tree/search")
        val query = if (search) "item" else null
        val parent = if (URI(route.request().url()).query.contains("parent=root")) "root" else ""
        val nodes =
            if (search || parent == "root") {
                (if (search) listOf(treeNode("root", null, folder = true)) else emptyList()) +
                    List(80) { treeNode("root/item%02d".format(it), "folder:root", folder = false) }
            } else {
                listOf(treeNode("root", null, folder = true))
            }
        val data =
            mapOf(
                "family" to "pipelines",
                "mode" to if (search) "search" else "browse",
                "root" to "",
                "parent" to parent,
                "query" to query,
                "next_cursor" to null,
                "workspace_id" to page.locator("html").getAttribute("data-dp-workspace-id"),
                "view_token" to "accordion-fixture",
                "nodes" to nodes,
            )
        val encoded =
            page.evaluate(
                """value => {
                  value.data.query ??= null; value.data.next_cursor = null;
                  value.data.nodes.forEach(node => { node.parent_key ??= null; node.href = null; });
                  return JSON.stringify(value);
                }""",
                mapOf("schema_version" to 1, "data" to data),
            ) as String
        route.fulfill(Route.FulfillOptions().setContentType("application/json").setBody(encoded))
    }

    private fun treeNode(
        path: String,
        parent: String?,
        folder: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "key" to if (folder) "folder:$path" else "artifact:$path",
            "parent_key" to parent,
            "kind" to if (folder) "folder" else "artifact",
            "name" to path.substringAfterLast('/'),
            "path" to path,
            "has_children" to folder,
            "match" to !folder,
            "href" to null,
        )

    private fun assertOpen(family: String?) {
        page.locator(".app-nav-tree:not([hidden])").count() shouldBe if (family == null) 0 else 1
        page.locator("[data-nav-tree-toggle][aria-expanded=true]").count() shouldBe if (family == null) 0 else 1
        page.locator(".app-nav-branch.is-tree-open").count() shouldBe if (family == null) 0 else 1
        if (family != null) page.locator("#nav-tree-$family").isVisible shouldBe true
    }

    @Test
    @Suppress("LongMethod") // one fixture carries the query, folder, scroll and request witnesses through A → B → A
    fun `the accordion switches exclusively retains context and excludes hidden controls from tab order`() {
        startTrace()
        page.setViewportSize(1440, 900)
        loginReadyUser("accordion478")
        val requests = mutableListOf<String>()
        page.route(java.util.function.Predicate { it.contains("/api/v1/pipelines/tree") }, { route ->
            requests += route.request().url()
            answerPipelines(route)
        })
        page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='folder:root']")
        assertOpen("pipelines")
        page.locator("#nav-tree-pipelines input").getAttribute("placeholder") shouldBe "Search pipelines…"
        page.fill("#nav-tree-pipelines input", "item")
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='artifact:root/item79']")
        // Search opens the ancestor; explicitly close and expand it again before taking the witness.
        val folder = "#nav-tree-pipelines [data-tree-key='folder:root']"
        page.click("$folder > .dp-tree-line button")
        page.click("$folder > .dp-tree-line button")
        page.evaluate(
            """() => {
              const tree = window.DatapipelinesSidebarTrees.get('pipelines');
              tree.scroll.scrollTop = 600;
              window.__accordion = {tree, folder: document.querySelector('#nav-tree-pipelines [data-tree-key="folder:root"]'), top: tree.scroll.scrollTop};
            }""",
        )
        page.evaluate("() => window.__accordion.top") shouldBe 600
        val before = requests.size
        page.click("[data-nav-branch=templates] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-templates [role=tree]")
        assertOpen("templates")
        page.locator("$panel input").getAttribute("placeholder") shouldBe "Search templates…"
        // Native Enter closes, and Space opens: both use the same click path as the pointer.
        page.press("[data-nav-branch=templates] [data-nav-tree-toggle]", "Enter")
        assertOpen(null)
        page.evaluate("() => document.activeElement.checkVisibility()") shouldBe true
        page.press("[data-nav-branch=templates] [data-nav-tree-toggle]", "Space")
        assertOpen("templates")
        page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
        assertOpen("pipelines")
        page.inputValue("#nav-tree-pipelines input") shouldBe "item"
        page.locator(folder).getAttribute("aria-expanded") shouldBe "true"
        page.evaluate(
            """() => {
              const saved = window.__accordion;
              return saved.tree === window.DatapipelinesSidebarTrees.get('pipelines') &&
                saved.folder === document.querySelector('#nav-tree-pipelines [data-tree-key="folder:root"]') &&
                saved.tree.scroll.scrollTop === saved.top;
            }""",
        ) shouldBe true
        requests.size shouldBe before
        page.focus("#nav-tree-pipelines input")
        repeat(16) {
            page.keyboard().press("Tab")
            page.evaluate("() => !!document.activeElement && !document.activeElement.closest('[hidden]')") shouldBe true
        }
        page.click("#rail-collapse")
        page.waitForFunction("() => document.documentElement.classList.contains('rail-collapsed')")
        page.locator("#nav-tree-pipelines input").isVisible shouldBe false
        page.click("#rail-collapse")
        page.waitForFunction("() => !document.documentElement.classList.contains('rail-collapsed')")
        assertOpen("pipelines")
        page.inputValue("#nav-tree-pipelines input") shouldBe "item"
        page.evaluate("() => window.__accordion.tree.scroll.scrollTop === window.__accordion.top") shouldBe true
        requests.size shouldBe before
        // The catalog link keeps navigation separate from disclosure; Browse folders then switches and focuses.
        page.click("[data-nav-branch=templates] .app-nav-link")
        page.waitForSelector("#template-list-wrapper")
        assertOpen("pipelines")
        page.focus("#nav-tree-pipelines input")
        page.evaluate("() => document.querySelector('[data-nav-tree-reveal=templates]').click()")
        assertOpen("templates")
        page.evaluate("() => document.activeElement.matches('#nav-tree-templates input')") shouldBe true
        // A programmatic collapse cannot leave focus stranded in its hidden search.
        page.evaluate("() => document.querySelector('[data-nav-branch=templates] [data-nav-tree-toggle]').click()")
        assertOpen(null)
        page.evaluate("() => document.activeElement.matches('[data-nav-branch=templates] [data-nav-tree-toggle]')") shouldBe true
        page.evaluate("() => document.activeElement.checkVisibility()") shouldBe true
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `a late pipelines reply stays hidden and keeps focus on the open templates search`() {
        startTrace()
        loginReadyUser("late478")
        val held = mutableListOf<Route>()
        page.route(java.util.function.Predicate { it.contains("/api/v1/pipelines/tree") }, { held += it })
        page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
        page.waitForCondition { held.size == 1 }
        page.click("[data-nav-branch=templates] [data-nav-tree-toggle]")
        page.focus("#nav-tree-templates input")
        assertOpen("templates")
        answerPipelines(held.removeAt(0))
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get(null)?.status !== 'loading'")
        page.evaluate("() => window.DatapipelinesSidebarTrees.get('pipelines').state.levels.get(null)?.status") shouldBe "complete"
        assertOpen("templates")
        page.evaluate("() => document.activeElement.matches('#nav-tree-templates input')") shouldBe true
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `legacy restoration chooses the current page then menu order and removes old keys`() {
        startTrace()
        val workspace = loginReadyUser("restore478")
        for ((path, family) in listOf("/pipelines" to "pipelines", "/templates" to "templates", "/dashboard" to "pipelines")) {
            page.evaluate(
                """ws => {
                  localStorage.removeItem('dp-tree-open:' + ws);
                  localStorage.setItem('dp-tree-panel:' + ws + ':pipelines', '1');
                  localStorage.setItem('dp-tree-panel:' + ws + ':templates', '1');
                }""",
                workspace,
            )
            page.navigate("$baseUrl$path")
            page.waitForSelector("#nav-tree-$family [role=tree]")
            assertOpen(family)
            page.evaluate(
                """ws => ['pipelines','templates','dashboards','visualizations','parameter-sets']
                  .every(f => localStorage.getItem('dp-tree-panel:' + ws + ':' + f) === null)""",
                workspace,
            ) shouldBe true
            page.evaluate("ws => localStorage.getItem('dp-tree-open:' + ws)", workspace) shouldBe family
        }
        page.evaluate("ws => localStorage.setItem('dp-tree-open:' + ws, 'parameter-sets')", workspace)
        page.reload()
        page.waitForSelector("#nav-tree-parameter-sets input")
        assertOpen("parameter-sets")
        page.locator("#nav-tree-parameter-sets input").getAttribute("placeholder") shouldBe "Search parameter sets…"
        page.locator("#nav-tree-parameter-sets input").getAttribute("aria-label") shouldBe "Search Parameter Sets"
        page.evaluate("ws => localStorage.setItem('dp-tree-open:' + ws, '<unknown>')", workspace)
        page.reload()
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
        assertOpen(null)
        page.evaluate("ws => localStorage.getItem('dp-tree-open:' + ws)", workspace) shouldBe null
        consoleErrors shouldBe emptyList()
    }

    // A deep, long-named path: four levels with long segments, so its leaf row is wider than
    // the rail's maximum at 1440 (the measured case, not a guess).
    private val deepFolders =
        listOf(
            "acme",
            "acme/alpha",
            "acme/alpha/beta_second_level_folder_with_long_name",
            "acme/alpha/beta_second_level_folder_with_long_name/gamma_third_level_folder_also_long",
        )
    private val deepLeaf = deepFolders.last() + "/leaf_template_with_an_intentionally_very_long_name_xyz.sql"

    // ------------------------------------------------------------------ A11 geometry

    @Test
    fun `the templates tree leaves user width unchanged while deep levels load and close`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("tpl398geo")
        seedTemplate(deepLeaf)
        seedTemplate("acme/short/s1.sql")
        page.navigate("$baseUrl/dashboard")
        openTree()
        page.locator("$panel a").count() shouldBe 0
        railWidth() shouldBe 232.0
        page.locator("#rail-resize").focus()
        repeat(12) { page.keyboard().press("Shift+ArrowRight") }
        railWidth() shouldBe 832.0
        deepFolders.forEach { expand(it) }
        page.waitForSelector(leaf(deepLeaf))
        page.locator("${leaf(deepLeaf)} span").first().innerText() shouldBe deepLeaf.substringAfterLast('/')
        railWidth() shouldBe 832.0
        page.click("${folder(deepFolders[1])} > .dp-tree-line button")
        railWidth() shouldBe 832.0
        page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        railWidth() shouldBe 832.0
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `a leaf preserves its loaded row into the workspace and marks current without restoration fetch`() {
        loginReadyUser("tpl398leaf")
        seedTemplate("test/nav_probe.sql")
        page.navigate("$baseUrl/dashboard")
        openTree()
        expand("test")
        page.waitForSelector(leaf("test/nav_probe.sql"))

        page.evaluate(
            "() => " +
                "{window.__templateRail=document.getElementById('app-rail');window.__templateRow=document.q" +
                "uerySelector('#nav-tree-templates [role=treeitem]')}",
        )
        page.locator(leaf("test/nav_probe.sql")).getAttribute("href") shouldBe "/templates/test/nav_probe.sql"

        page.locator(leaf("test/nav_probe.sql")).click()
        page.waitForURL("**/templates/test/nav_probe.sql")
        page.waitForSelector(".tw-root")
        page.evaluate(
            "() => window.__templateRail===document.getElementById('app-rail') && " +
                "window.__templateRow===document.querySelector('#nav-tree-templates [role=treeitem]')",
        ) shouldBe
            true
        // A current marker updates the loaded leaf without fetching its ancestors.
        openTree()
        expand("test")
        page.waitForFunction(
            "(sel) => document.querySelector(sel)?.getAttribute('aria-current') === 'page'",
            leaf("test/nav_probe.sql"),
        )
        // A full navigation back to the workspace re-marks it the same way.
        page.navigate("$baseUrl/templates/test/nav_probe.sql")
        page.waitForSelector(".tw-root")
        openTree()
        expand("test")
        page.waitForFunction(
            "(sel) => document.querySelector(sel)?.getAttribute('aria-current') === 'page'",
            leaf("test/nav_probe.sql"),
        )

        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `a template loaded only by search marks current without browsing its ancestors`() {
        loginReadyUser("tpl460searchcurrent")
        seedTemplate("search/deep/current_probe.sql")
        page.navigate("$baseUrl/dashboard")
        val requests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/api/v1/templates/tree")) requests += it.url() }
        openTree()
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('templates').state.levels.get(null)?.complete")
        page.fill("$panel input[type=search]", "current_probe.sql")
        page.waitForSelector(leaf("search/deep/current_probe.sql"))
        page.waitForFunction("() => window.DatapipelinesSidebarTrees.get('templates').state.search.complete")
        page.evaluate(
            "() => {window.__searchRail=document.getElementById('app-rail');" +
                "window.__searchLeaf=document.querySelector('#nav-tree-templates a[href=\"/templates/search/deep/current_probe.sql\"]')}",
        )
        page.click(leaf("search/deep/current_probe.sql"))
        page.waitForURL("**/templates/search/deep/current_probe.sql")
        page.waitForSelector(".tw-root")
        page.waitForFunction("() => !document.querySelector('.htmx-settling') && !document.querySelector('.htmx-request')")
        page.locator(leaf("search/deep/current_probe.sql")).getAttribute("aria-current") shouldBe "page"
        page.evaluate(
            "() => window.__searchRail===document.getElementById('app-rail') && " +
                "window.__searchLeaf===document.querySelector('#nav-tree-templates a[href=\"/templates/search/deep/current_probe.sql\"]')",
        ) shouldBe true
        page.inputValue("$panel input[type=search]") shouldBe "current_probe.sql"
        requests.size shouldBe 2
        requests.count { it.contains("/tree/search") } shouldBe 1
        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `the catalog has no tree and its Browse folders opens the sidebar - the keyboard reaches the rows`() {
        loginReadyUser("tpl398cat")
        seedTemplate("test/cat_probe.sql")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/templates")
        page.waitForSelector("#template-list-wrapper")

        // The page pane is gone: no explorer markup of any kind.
        page.locator("[data-explorer-pane]").count() shouldBe 0
        page.locator(".tplx-splitter").count() shouldBe 0
        page.locator("[data-explorer-drawer-open]").count() shouldBe 0

        // The catalog row is a link to the workspace with the full path on title (§9.4).
        val row = page.locator("#template-list-wrapper a.tpl-result").first()
        row.getAttribute("href") shouldBe "/templates/test/cat_probe.sql"
        row.locator(".tpl-path").getAttribute("title") shouldBe "test/cat_probe.sql"

        // Browse folders is the sidebar tree's other door: the branch opens, focus lands in
        // its search (the host adapter's reveal).
        page.click("[data-nav-tree-reveal='templates']")
        page.waitForSelector("#nav-tree-templates [role=tree]")
        page.waitForSelector("#nav-tree-templates [aria-expanded]")
        page.evaluate("() => document.activeElement?.matches('#nav-tree-templates input[type=search]')") shouldBe true

        // The keyboard: ArrowDown moves focus among the rows (the nav context moves FOCUS only).
        // The root level holds FOLDERS only (077) — the first row the keydown reaches is the
        // folder summary; the invariant is that focus moved into the tree's rows at all.
        page.press("#nav-tree-templates input[type=search]", "ArrowDown")
        val focused =
            page.evaluate(
                "() => document.activeElement?.matches('#nav-tree-templates [role=treeitem]')",
            ) as Boolean
        focused shouldBe true

        consoleErrors shouldBe emptyList()
    }

    @Test
    @Suppress("LongMethod") // the six theme/rail combinations photograph four real, synchronized tree states each
    fun `the phone drawer carries the tree, and both themes photograph it`() {
        startTrace()
        loginReadyUser("tpl398phone")
        seedTemplate(deepLeaf)
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        val held = mutableListOf<Route>()
        var hold = false
        var fail = false
        page.route(java.util.function.Predicate { it.contains("/api/v1/templates/tree/search") }, { route ->
            when {
                fail -> {
                    route.fulfill(
                        Route
                            .FulfillOptions()
                            .setStatus(503)
                            .setContentType("application/json")
                            .setBody("{}"),
                    )
                }

                hold -> {
                    held += route
                }

                else -> {
                    route.resume()
                }
            }
        })
        val dir = Paths.get("build", "reports", "478-screenshots")
        Files.createDirectories(dir)
        listOf("light", "dark").forEach { theme ->
            page.setViewportSize(1440, 900)
            page.navigate("$baseUrl/pipelines")
            ensureTheme(theme)
            for (width in listOf("wide", "narrow", "phone")) {
                page.setViewportSize(if (width == "phone") 390 else 1440, if (width == "phone") 844 else 900)
                page.navigate("$baseUrl/pipelines")
                if (width == "phone") {
                    page.click("#rail-open")
                } else {
                    page.focus("#rail-resize")
                    page.keyboard().press("Home")
                    page.waitForFunction("() => document.querySelector('.app-rail').getBoundingClientRect().width === 232")
                    if (width == "wide") {
                        repeat(4) { index ->
                            page.keyboard().press("Shift+ArrowRight")
                            page.waitForFunction(
                                "expected => document.querySelector('.app-rail').getBoundingClientRect().width === expected",
                                232 + (index + 1) * 50,
                            )
                        }
                    }
                }
                openTree()
                railWidth() shouldBe if (width == "wide") 432.0 else 232.0
                assertOpen("templates")
                page.locator("[data-nav-branch=pipelines] .app-nav-link.active").count() shouldBe 1
                hold = true
                page.fill("$panel input", "leaf_template")
                page.waitForCondition { held.size == 1 }
                page.waitForSelector("$panel .dp-tree-status[data-status^='Loading']")
                page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$theme-$width-loading.png")))
                hold = false
                held.removeAt(0).resume()
                page.waitForSelector(leaf(deepLeaf))
                settle()
                page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$theme-$width-long-name.png")))
                page.fill("$panel input", "no_match_478")
                page.waitForSelector("$panel .dp-tree-status[data-status='No matches']")
                page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$theme-$width-empty.png")))
                fail = true
                page.fill("$panel input", "fault478")
                page.waitForSelector("$panel .dp-tree-status[data-status='Could not load. ']")
                page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$theme-$width-error.png")))
                fail = false
                page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth") shouldBe false
            }
        }
        // The deliberate 503 fixture is the only expected console error; the CSP collector still applies.
        consoleErrors.filterNot { it.contains("503") } shouldBe emptyList()
    }
}
