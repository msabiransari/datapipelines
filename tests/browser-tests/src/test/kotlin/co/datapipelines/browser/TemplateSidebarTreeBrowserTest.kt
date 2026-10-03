package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #398 (workspace spec §5 read onto templates, A11/A13) — the TEMPLATE tree in the GLOBAL
 * SIDEBAR, measured in a real browser on the real application: geometry (the rail fits the
 * visible rows between the same tokens every tree shares), the catalog that replaced the
 * page explorer, a leaf's FULL-DOCUMENT navigation into the template workspace, the keyboard,
 * the phone drawer and both themes.
 *
 * Every geometry claim reads COMPUTED boxes after the rail settles — never a class or a CSS
 * property alone. The state half (restore, reveal, the admission guard, failure/retry,
 * history and handler counts) is the ONE engine's (`nav-tree.js`), already pinned by
 * [PipelineSidebarTreeStateBrowserTest]; what is templates-specific here is the branch's
 * markup, the root URL and the leaf's destination.
 */
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
        page.waitForSelector("#nav-tree-templates:not([hidden]) .tpl-tree, #nav-tree-templates:not([hidden]) .ds-empty")
        settle()
    }

    private fun folder(path: String) = "$panel details.tpl-folder:has(> summary > span.tpl-label[title='$path'])"

    /** Expands the sidebar folder whose FULL path is [path], waiting for its level to land.
     * Idempotent: a folder the tree's restore already opened has its level in place — the
     * summary click would CLOSE it (the toggle), so it is skipped. */
    private fun expand(path: String) {
        val open =
            page.evaluate(
                """(sel) => {
                  const d = document.querySelector(sel);
                  return !!d && d.open && !!d.querySelector(':scope > .tpl-level:not(.tpl-level-pending)');
                }""",
                folder(path),
            ) as Boolean
        if (!open) {
            page.click("${folder(path)} > summary")
            page.waitForSelector("${folder(path)} > .tpl-level:not(.tpl-level-pending)")
        }
        settle()
    }

    private fun leaf(path: String) = "$panel a.tpl-leaf:has(span.tpl-label[title='$path'])"

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
    fun `A11 - the templates tree opens in the rail, fits between the shared bounds, and closing restores the baseline`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("tpl398geo")
        seedTemplate(deepLeaf)
        seedTemplate("acme/short/s1.sql")
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("[data-nav-branch='templates']")
        drainCspViolations()

        // The ordinary rail is the closed-tree baseline (--app-rail-expanded).
        val baseline = railWidth()
        baseline shouldBe 232.0
        page.locator(panel).isVisible shouldBe false

        // Open: the root level arrives (folders only — §4.1) and the rail fits it, never below
        // the tree-open minimum.
        openTree()
        page.locator("$panel .tpl-leaf").count() shouldBe 0
        railWidth() shouldBe 320.0 // `acme` alone is narrower than the minimum

        // Deep + long content: the rail grows to the MAXIMUM and stops there (the tokens are
        // the sidebar's shared ones — one tree engine, one geometry).
        deepFolders.forEach { expand(it) }
        page.waitForSelector(leaf(deepLeaf))
        settle()
        railWidth() shouldBe 400.0

        // The full label: its text is the whole segment (no ellipsis) — the region scrolls.
        page.locator("${leaf(deepLeaf)} .tpl-label").innerText() shouldBe deepLeaf.substringAfterLast('/')

        // Folding the SHORT mid-level hides the whole deep subtree (its own labels were the
        // width); the visible rows are back under the minimum and the rail follows.
        page.click("${folder(deepFolders[1])} > summary")
        settle()
        railWidth() shouldBe 320.0

        // Closing the tree restores the baseline.
        page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        settle()
        railWidth() shouldBe 232.0

        consoleErrors shouldBe emptyList()
    }

    @Test
    fun `a leaf is a full-document link into its workspace, and the page re-marks the current leaf`() {
        loginReadyUser("tpl398leaf")
        seedTemplate("test/nav_probe.sql")
        page.navigate("$baseUrl/dashboard")
        openTree()
        expand("test")
        page.waitForSelector(leaf("test/nav_probe.sql"))

        // The leaf carries the workspace URL as its href, and hx-boost=false is the engine's
        // full-navigation contract (the markup pins it; the walk proves the destination).
        page.locator(leaf("test/nav_probe.sql")).getAttribute("href") shouldBe "/templates/test/nav_probe.sql"

        page.locator(leaf("test/nav_probe.sql")).click()
        page.waitForURL("**/templates/test/nav_probe.sql")
        page.waitForSelector(".tw-root")
        // The workspace names itself to the rail (the [data-nav-current] hook) — a boosted
        // arrival or a fresh document re-marks the leaf either way.
        page.waitForFunction(
            "() => document.querySelector('[data-nav-current]').getAttribute('data-nav-current-path') === 'test/nav_probe.sql'",
        )
        page.navigate("$baseUrl/dashboard")
        openTree()
        expand("test")
        page.waitForSelector(leaf("test/nav_probe.sql"))
        page.locator(leaf("test/nav_probe.sql")).getAttribute("aria-current") shouldBe "page"

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
        // its search (nav-tree.js's reveal).
        page.click("[data-nav-tree-reveal='templates']")
        page.waitForSelector("#template-nav-root .tpl-tree")
        page.waitForSelector("#template-nav-root .tpl-folder")
        page.evaluate("() => document.activeElement?.matches('#nav-tree-templates [data-nav-tree-search]')") shouldBe true

        // The keyboard: ArrowDown moves focus among the rows (the nav context moves FOCUS only).
        // The root level holds FOLDERS only (077) — the first row the keydown reaches is the
        // folder summary; the invariant is that focus moved into the tree's rows at all.
        page.press("#nav-tree-templates [data-nav-tree-search]", "ArrowDown")
        val focused =
            page.evaluate(
                "() => document.activeElement?.matches('#nav-tree-templates .tpl-summary, #nav-tree-templates .tpl-leaf')",
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
        page.waitForSelector("#template-nav-root .tpl-tree")
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
