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

    /** The root level shows FOLDERS; the `test` folder must be open before its leaf exists. */
    private fun openTestFolder() {
        page.waitForSelector("summary.tpl-summary")
        if (page.locator("details.tpl-folder[open]").count() == 0) {
            page.waitForResponse({ it.url().contains("prefix=test") }) {
                page.locator("summary.tpl-summary").first().click()
            }
        }
        page.waitForSelector("button.tpl-leaf")
    }

    private fun selectLeaf(leafSegment: String) {
        openTestFolder()
        val leaf = page.locator("button.tpl-leaf", Page.LocatorOptions().setHasText(leafSegment)).first()
        page.waitForResponse({ it.url().contains("/detail") || it.url().contains("/versions") }) { leaf.click() }
        page.waitForSelector(".tplx-detail-header")
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
    fun `a leaf indents one step under its folder, level with a sibling folder, in both explorers`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        seedTemplate("test/indent_leaf")
        seedTemplate("test/sub/indent_nested")
        seedPipeline("test/indent_leaf")
        seedPipeline("test/sub/indent_nested")

        for (screen in listOf("pipelines", "templates")) {
            page.setViewportSize(1280, 900)
            page.navigate("$baseUrl/$screen")
            page.waitForSelector(".tplx-tree")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            openTestFolder()
            shot("$screen-tree-1280")

            val m =
                measure(
                    """() => {
                      const parent = document.querySelector('details.tpl-folder[open] > summary.tpl-summary');
                      const level = parent.nextElementSibling;
                      const leaf = level.querySelector(':scope > .tpl-tree > .tpl-node > button.tpl-leaf');
                      const sibling = level.querySelector(':scope > .tpl-tree > .tpl-node > details > summary.tpl-summary');
                      const left = (el, sel) => el.querySelector(sel).getBoundingClientRect().left;
                      return {
                        indent: parseFloat(getComputedStyle(level).paddingLeft),
                        parentLabel: left(parent, '.tpl-label'),
                        leafLabel: left(leaf, '.tpl-label'),
                        siblingLabel: left(sibling, '.tpl-label'),
                        leafIcon: left(leaf, '.tpl-row-icon'),
                        siblingIcon: left(sibling, '.tpl-icon-folder'),
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
    fun `the version row's overflow menu is fully visible when the list is one row tall, in both explorers`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        seedTemplate("test/menu_probe")
        seedPipeline("test/menu_probe")

        for ((screen, panel) in listOf("pipelines" to "#pipeline-tab-versions", "templates" to "#template-tab-versions")) {
            // 700 tall, so the versions card sits BELOW the fold and the click on its ⋯ must
            // scroll it into view first — the exact shape that closed the menu before the fix.
            page.setViewportSize(1280, 700)
            page.navigate("$baseUrl/$screen")
            page.waitForSelector(".tplx-tree")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            selectLeaf("menu_probe")

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

            // A scroll while the menu is open FOLLOWS the ⋯, it does not close the menu: the
            // browser delivers `scroll` asynchronously, so the scroll-into-view that precedes
            // a click on a ⋯ below the fold lands AFTER the click opened the menu — closing on
            // scroll shut every such menu before its first item could be clicked
            // (LifecycleDialogBrowserTest, 2026-09-12). The shell's scroller is <main>.
            val after =
                measure(
                    """async () => {
                      const list = document.querySelector('$panel .tplx-vmenu-list');
                      const summary = list.closest('details').querySelector('summary');
                      const before = { list: list.getBoundingClientRect().top, anchor: summary.getBoundingClientRect().top };
                      const scroller = document.querySelector('main');
                      scroller.scrollTop = 0;
                      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                      before.list = list.getBoundingClientRect().top; before.anchor = summary.getBoundingClientRect().top;
                      scroller.scrollTop = 40;
                      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                      const moved = summary.getBoundingClientRect().top - before.anchor;
                      return {
                        scrolled: scroller.scrollTop,
                        anchorMoved: moved,
                        listMoved: list.getBoundingClientRect().top - before.list,
                        open: list.closest('details').open,
                        shown: list.matches(':popover-open'),
                      };
                    }""",
                )
            withClue("$screen: the scroller must actually have moved for this to test anything: $after") {
                after.px("scrolled") shouldBeGreaterThanOrEqual 1.0
                abs(after.px("anchorMoved")) shouldBeGreaterThanOrEqual 1.0
            }
            withClue("$screen: after a scroll the menu is still open, still shown, and moved WITH its ⋯: $after") {
                after["open"] shouldBe true
                after["shown"] shouldBe true
                after.px("listMoved").shouldBeWithinOnePxOf(after.px("anchorMoved"), "$screen: menu follows the anchor")
            }
        }
    }
}
