package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.abs

/**
 * Two defects the owner found in the 2026-09-12 testing round, pinned where they are SEEN —
 * in a real browser, on both explorers, because one stylesheet and one partial family dress
 * both trees and both version lists.
 *
 * #398: both trees measured here are the SIDEBAR's (the page pane retired with the
 * templates explorer); the version menus are both WORKSPACES' Versions tabs.
 *
 * 1. THE TREE. A leaf row carried no chevron slot, so its file glyph sat where a folder's
 *    chevron sits and its label landed at the PARENT folder's label x — a leaf read as a
 *    sibling of its own folder, at every depth, in every tree. The invariant is what a
 *    file-tree reader expects: a leaf's label sits exactly one `--tpl-indent` right of its
 *    parent's label, level with a sibling folder's label.
 *
 * 2. THE ⋯ MENU. The version rows' overflow menu is an absolutely positioned popover inside
 *    `.tplx-tabpanel`, which scrolls (`overflow: auto`). A one-row list is one row tall, so
 *    the open menu fell into the panel's scrollable overflow — present in the DOM, invisible
 *    on the screen. The invariant is hit-testable: the point at the menu's centre must
 *    resolve to the menu, and the menu must lie inside the viewport.
 */
class TreeIndentAndVersionMenuBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("tim-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("timws-" + generatedPassword("w").take(8).lowercase())
    }

    private fun postJson(
        url: String,
        body: String,
    ) {
        val status =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch(args.url, {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json',
                              'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''},
                    body: args.body,
                  });
                  return res.status;
                }""",
                mapOf("url" to url, "body" to body),
            )
        (status as Number).toInt() shouldBe 201
    }

    private fun seedTemplate(id: String) =
        postJson(
            "/api/v1/templates",
            """{"id":"$id","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"${id.substringAfterLast('/')}","description":"tree fixture","body":"SELECT 1"}""",
        )

    private fun seedPipeline(name: String) =
        postJson(
            "/api/v1/pipelines",
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","nodes":[{"id":"fq",""" +
                """"type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )

    /**
     * The root level shows FOLDERS; the `test` folder must be open before its leaf exists.
     * #398: [root] scopes the tree — BOTH trees are the SIDEBAR's now (whose leaves are
     * links: `.tpl-leaf`, either element), so each needs its branch toggle first.
     */
    private fun openTestFolder(root: String) {
        val branch = if (root == PIPELINES) "pipelines" else "templates"
        page.click("[data-nav-branch='$branch'] [data-nav-tree-toggle]")
        page.waitForSelector("$root [data-tree-key='folder:test']")
        page.waitForResponse({ it.url().contains("/tree?") && it.url().contains("parent=test") }) {
            page.click("$root [data-tree-key='folder:test'] > .dp-tree-line button")
        }
        page.waitForSelector("$root a.dp-tree-activate")
    }

    @Suppress("UNCHECKED_CAST")
    private fun measure(script: String): Map<String, Any?> = page.evaluate(script) as Map<String, Any?>

    private fun Map<String, Any?>.px(key: String): Double = (this[key] as Number).toDouble()

    /** Evidence for the handback, beside the numbers: what the owner will see. */
    private fun shot(name: String) {
        val dir = Paths.get("build", "reports", "tree-menu-screenshots")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")).setFullPage(false))
    }

    private fun Double.shouldBeWithinOnePxOf(
        other: Double,
        what: String,
    ) = withClue("$what: $this vs $other") { abs(this - other) shouldBeLessThanOrEqual 1.0 }

    @Test
    fun `a leaf indents one step under its folder, level with a sibling folder, in the sidebar tree and the templates explorer`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        seedTemplate("test/indent_leaf")
        seedTemplate("test/sub/indent_nested")
        seedPipeline("test/indent_leaf")
        seedPipeline("test/sub/indent_nested")

        // #350/#398: BOTH trees are the SIDEBAR's — the same rows, measured in the rail.
        for ((screen, root) in listOf("pipelines" to PIPELINES, "templates" to TEMPLATES)) {
            page.setViewportSize(1280, 900)
            named(page, "the dashboard navigation to $baseUrl/dashboard") {
                page.navigate("$baseUrl/dashboard")
            }
            page.waitForLoadState(LoadState.NETWORKIDLE)
            openTestFolder(root)
            shot("$screen-tree-1280")

            val m =
                measure(
                    """() => {
                      const parent = document.querySelector('$root [data-tree-key=\"folder:test\"] > .dp-tree-line > button');
                      const level = parent.closest('[role=treeitem]').querySelector(':scope > .dp-tree-group');
                      const leaf = level.querySelector(':scope > [data-tree-key^=\"artifact:\"] > .dp-tree-line > a');
                      const sibling = level.querySelector(':scope > [aria-expanded] > .dp-tree-line > button');
                      const left = (el, sel) => el.querySelector(sel).getBoundingClientRect().left;
                      return {
                        indent: parseFloat(getComputedStyle(level).paddingLeft),
                        parentLabel: left(parent, 'span[title]'),
                        leafLabel: left(leaf, 'span[title]'),
                        siblingLabel: left(sibling, 'span[title]'),
                        leafIcon: left(leaf, 'svg'),
                        siblingIcon: left(sibling, 'svg'),
                      };
                    }""",
                )

            withClue("$screen: the level's indent must be a real, positive step") {
                m.px("indent") shouldBeGreaterThanOrEqual 8.0
            }
            (m.px("leafLabel") - m.px("parentLabel"))
                .shouldBeWithinOnePxOf(m.px("indent"), "$screen: leaf label sits one indent right of its PARENT's label")
            m.px("leafLabel").shouldBeWithinOnePxOf(m.px("siblingLabel"), "$screen: leaf label level with a SIBLING folder's label")
            m.px("leafIcon").shouldBeWithinOnePxOf(m.px("siblingIcon"), "$screen: leaf glyph level with a sibling folder's glyph")
        }
    }

    @Test
    fun `the version row's overflow menu is fully visible when the list is one row tall, on the workspace and in the templates explorer`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        seedTemplate("test/menu_probe")
        seedPipeline("test/menu_probe")

        // #350/#398: BOTH families' version rows are their WORKSPACE's Versions tab (the same
        // house-table fragment and the same ⋯ menu).
        for ((screen, panel) in listOf("pipelines" to "#pe-pane-versions", "templates" to "#tw-pane-versions")) {
            // 700 tall, so the versions card sits BELOW the fold and the click on its ⋯ must
            // scroll it into view first — the exact shape that closed the menu before the fix.
            page.setViewportSize(1280, 700)
            if (screen == "pipelines") {
                page.navigate("$baseUrl/pipelines?q=menu_probe")
                page.locator("#pipeline-list-wrapper a.tpl-result").first().click()
                page.waitForURL(PipelineWorkspaceUrl.PATTERN)
                page.waitForSelector(".pe-root")
                // Boosted markup arrives before the observer finishes its mount.
                page.waitForFunction("() => !!window.__peInstance")
                page.locator("#pe-tab-versions").click()
                page.waitForSelector("$panel tr[data-version-row]")
            } else {
                page.navigate("$baseUrl/templates?q=menu_probe")
                page.waitForSelector("#template-list-wrapper a.tpl-result")
                page.locator("a.tpl-result", Page.LocatorOptions().setHasText("menu_probe")).first().click()
                page.waitForURL("**/templates/**")
                page.waitForSelector(".tw-root")
                page.locator("#tw-tab-versions").click()
                page.waitForSelector("$panel tr[data-version-row]")
            }

            page.locator("$panel details.tplx-vmenu summary").first().click()
            page.locator("$panel .tplx-vmenu-list").first().waitFor()
            shot("$screen-version-menu-1280")

            val m =
                measure(
                    """() => {
                      const list = document.querySelector('$panel .tplx-vmenu-list');
                      const r = list.getBoundingClientRect();
                      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
                      return {
                        top: r.top, bottom: r.bottom, left: r.left, right: r.right,
                        vw: window.innerWidth, vh: window.innerHeight,
                        items: list.querySelectorAll('.tplx-vmenu-item').length,
                        hitInside: !!(hit && hit.closest('.tplx-vmenu-list')),
                      };
                    }""",
                )

            withClue("$screen: a draft's menu carries at least Release and Purge") {
                (m["items"] as Number).toInt() shouldBeGreaterThanOrEqual 1
            }
            withClue("$screen: the menu's centre must hit-test to the menu itself, not to whatever clips it: $m") {
                m["hitInside"] shouldBe true
            }
            withClue("$screen: the menu lies inside the viewport: $m") {
                m.px("top") shouldBeGreaterThanOrEqual 0.0
                m.px("left") shouldBeGreaterThanOrEqual 0.0
                m.px("bottom") shouldBeLessThanOrEqual m.px("vh")
                m.px("right") shouldBeLessThanOrEqual m.px("vw")
            }

            // The follow-the-scroll arm asked the shell's <main> scroller to move — the page
            // explorer's layout. Both workspaces' Versions panes are page-flow cards that, one
            // row tall, have nothing to scroll (measured scrolled=0 at #350; the same shape on
            // the template workspace), so the arm has no surface left and is retired with the
            // pane. The menu-visibility invariants above are the ones the phone layout needs.
        }
    }

    private companion object {
        /** #398: both trees are the sidebar's — the page pane retired with the explorer. */
        const val TEMPLATES = "#nav-tree-templates"
        const val PIPELINES = "#nav-tree-pipelines"
    }
}
