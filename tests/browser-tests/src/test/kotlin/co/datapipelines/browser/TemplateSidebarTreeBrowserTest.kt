package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
    fun `the phone drawer carries the tree, and both themes photograph it`() {
        loginReadyUser("tpl398phone")
        seedTemplate("test/drawer_probe.sql")
        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/templates")
        page.click("#rail-open")
        page.waitForSelector("nav.app-nav")
        page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-templates [role=tree]")
        settle()

        // The drawer keeps its full width with the tree open (#350: the phone breakpoint wins
        // over the desktop minimum) and the document does not grow sideways.
        railWidth() shouldBe 232.0
        val overflow =
            page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth") as Boolean
        overflow shouldBe false
        shot("phone-drawer-templates-tree")

        // Both themes, desktop: the tree in the widened rail.
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/dashboard")
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            openTree()
            expand("test")
            shot("templates-tree-$theme")
        }

        consoleErrors shouldBe emptyList()
    }
}
