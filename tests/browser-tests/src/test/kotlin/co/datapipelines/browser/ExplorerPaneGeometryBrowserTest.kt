package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.options.LoadState
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * 090 §A/§B — the explorers' two panes, measured on the shapes the owner actually uses.
 *
 * ## What was wrong, and what the numbers said
 *
 * The report was "detail section … very close with the side menu", diagnosed upstream as the
 * tree pane collapsing to badge width because something beat its 260px floor. **The floor was
 * never beaten.** Measured on a SETTLED page at 1440/1920/2560 with the rail expanded and
 * collapsed, before any change in this round: the pane was 432px (1440) or 480px (1920, 2560)
 * every time, `min-width` computed 260px, and the gap to the detail pane was 24px — six
 * measurements, six correct layouts. No persisted width existed to restore, and no flex
 * `min-width` override was in the cascade; the pane is a grid item in `minmax(260px, auto)`.
 *
 * What DID reproduce is a frame, not a state. `templates/list.html` and `pipelines/list.html`
 * each carried their own `<link rel="stylesheet" href="/css/template-tree.css">` INSIDE the
 * `content` fragment — inside `#app-main`, the boosted-swap target. htmx replaces that
 * region's markup and the browser only then discovers the link; an inserted stylesheet does
 * not block the already-painted document, so the explorer painted with no tree rules at all:
 *
 *     width  rail       first frame after the swap        settled
 *     1920   expanded   tree 1640px, detail's left edge   tree 480px, detail at
 *                       EQUAL to the tree's (stacked)     tree.right + 24
 *     2560   expanded   tree 2280px, stacked              tree 480px, gapped
 *     2560   collapsed  tree 2452px, stacked              tree 480px, gapped
 *
 * A full-width tree with the detail pane collapsed underneath it IS "the detail section is
 * very close to the side menu", and it is the same event as the round's other report, "css is
 * applied after data load". The sheet moved to `layouts/default`'s <head>; after that the
 * first frame measures identically to the settled page at all six combinations.
 *
 * ## Why the first frame is the assertion and CLS is not
 *
 * `PerformanceObserver('layout-shift')` scored the broken navigation at **0.0000**: the panes
 * were inserted in the wrong geometry rather than moved from a right one, and an insertion is
 * not a shift. A CLS budget alone would have passed the defect. The budget is still asserted
 * below — it is the right instrument for the FONT half of §B — but the geometry at the first
 * animation frame after `htmx:afterSwap` is what pins this one.
 */
class ExplorerPaneGeometryBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("geo-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("geows-" + generatedPassword("w").take(8).lowercase())
    }

    /**
     * A row in BOTH trees. The assertion "the first leaf's text is visible" is the owner's
     * own symptom — the pane was badge-width and the names were gone — so an explorer with
     * an empty tree would satisfy every geometric assertion here while proving nothing. The
     * REST seeding is the [ExplorerStressBrowserTest] pattern: cookie session + the dp_csrf
     * double-submit pair, in-page.
     */
    private fun seedBothTrees() {
        page.navigate("$baseUrl/dashboard")
        postJson(
            "/api/v1/templates",
            """{"id":"test/geometry_probe","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"geometry_probe","description":"090 pane geometry fixture","body":"SELECT 1"}""",
        )
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/geometry_probe","display_name":"geometry_probe","nodes":[{"id":"fq",""" +
                """"type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )
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

    /**
     * The root level shows FOLDERS, and a folder must be OPEN before its leaf exists (§9.1 —
     * one request per level). Opening it is also what puts a real name in the pane.
     */
    private fun openFirstFolder() {
        page.waitForSelector("summary.tpl-summary")
        if (page.locator("details.tpl-folder[open]").count() == 0) {
            page.waitForResponse({ it.url().contains("prefix=test") }) {
                page.locator("summary.tpl-summary").first().click()
            }
        }
        page.waitForSelector("button.tpl-leaf")
    }

    /**
     * The three facts, read in ONE evaluate so they describe the same frame. `--gap-lg` is
     * read from the document rather than written as 24 here: the rule is "the shell's gap",
     * and a token change must move the assertion with it, not break it.
     */
    private val geometryProbe =
        """
        () => {
          const t = document.querySelector('.tplx-tree');
          const d = document.querySelector('.tplx-detail');
          if (!t || !d) return null;
          const label = document.querySelector('.tplx-tree .tpl-label');
          const tb = t.getBoundingClientRect(), db = d.getBoundingClientRect();
          const lb = label ? label.getBoundingClientRect() : null;
          return {
            treeWidth: tb.width,
            viewport: window.innerWidth,
            detailPadding: parseFloat(getComputedStyle(document.querySelector('.tplx-detail')).paddingLeft),
            detailLeft: db.left,
            treeRight: tb.right,
            gap: db.left - tb.right,
            gapToken: (() => {
              // --gap-lg is `1.5rem`; parseFloat would give 1.5 and make the gap assertion
              // vacuous (measured: gap 24 >= 1.5 passes on any layout at all). Resolve the
              // token to PIXELS the way the layout does — by laying an element out with it.
              const probe = document.createElement('div');
              probe.style.cssText = 'position:absolute;visibility:hidden;width:var(--gap-lg)';
              document.body.appendChild(probe);
              const px = probe.getBoundingClientRect().width;
              probe.remove();
              return px;
            })(),
            labelWidth: lb ? lb.width : null,
            labelRight: lb ? lb.right : null,
            labelClipped: label ? label.scrollWidth > label.clientWidth + 1 : null,
            // 104 §C — the divider handle that replaced the pane's native `resize` corner.
            handleRole: (() => { const h = document.querySelector('[data-splitter="explorer-tree"]');
                                 return h ? h.getAttribute('role') : null; })(),
            handleNow: (() => { const h = document.querySelector('[data-splitter="explorer-tree"]');
                                return h ? Number(h.getAttribute('aria-valuenow')) : null; })(),
            nativeResize: getComputedStyle(t).resize,
            stored: window.localStorage.getItem('dp.pane.explorer-tree')
          };
        }
        """.trimIndent()

    /** Arms a one-shot capture of the FIRST animation frame after the next boosted swap. */
    private val armFirstFrame =
        """
        (probe) => { window.__ff = null;
          document.body.addEventListener('htmx:afterSwap', () => {
            requestAnimationFrame(() => { window.__ff = eval('(' + probe + ')')(); });
          }, {once: true}); }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun probe(): Map<String, Any?> = page.evaluate(geometryProbe) as Map<String, Any?>

    private fun Map<String, Any?>.d(k: String) = (this[k] as Number).toDouble()

    /** A real pointer drag on the divider handle: down, move in steps, up. */
    private fun dragTreeBy(dx: Double) {
        val box = page.locator("[data-splitter='explorer-tree']").boundingBox()
        val y = box.y + 200
        page.mouse().move(box.x + box.width / 2, y)
        page.mouse().down()
        page.mouse().move(box.x + box.width / 2 + dx, y, Mouse.MoveOptions().setSteps(12))
        page.mouse().up()
        page.waitForTimeout(150.0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertPanes(
        label: String,
        raw: Any?,
    ) {
        val m = raw as? Map<String, Any?> ?: error("$label produced no geometry — the explorer did not render")
        val treeWidth = (m["treeWidth"] as Number).toDouble()
        val gap = (m["gap"] as Number).toDouble()
        val gapToken = (m["gapToken"] as Number).toDouble()
        val labelWidth = (m["labelWidth"] as Number?)?.toDouble()

        withClue(label, "tree pane below its 260px floor: $m") { treeWidth shouldBeGreaterThanOrEqual TREE_FLOOR }
        // The pane is a SHARE of the viewport, not a capped pixel width: on the owner's
        // 3491px window the former 480px ceiling left the tree 14% of the canvas. The floor
        // wins below 1000px; above it the pane tracks 26vw.
        val viewport = (m["viewport"] as Number).toDouble()
        val expected = minOf(viewport * TREE_SHARE, TREE_CEILING)
        if (expected > TREE_FLOOR) {
            withClue(label, "tree pane is not min(${TREE_SHARE}vw, ${TREE_CEILING}px): $m") {
                treeWidth shouldBeGreaterThanOrEqual expected - 1.0
                treeWidth shouldBeLessThanOrEqual expected + 1.0
            }
        }
        // The detail's content stands off the divider (2026-09-08): the pane's left padding.
        withClue(label, "detail content sits on the divider: $m") {
            ((m["detailPadding"] as Number?)?.toDouble() ?: 0.0) shouldBeGreaterThanOrEqual DETAIL_INSET
        }
        // The detail pane starts at least one shell gap right of the tree's edge. `>=` and not
        // `==`: the rule is that the panes never overlap or crowd, and a future layout may
        // legitimately open the gap further.
        withClue(label, "detail pane crowds or overlaps the tree: $m") { gap shouldBeGreaterThanOrEqual gapToken }
        withClue(label, "--gap-lg did not resolve: $m") { gapToken shouldBeGreaterThanOrEqual 1.0 }
        // The first row's TEXT is visible, which is the thing the owner could not see.
        withClue(label, "the first row's label has no width: $m") {
            (labelWidth ?: 0.0) shouldBeGreaterThanOrEqual MIN_LABEL_WIDTH
        }
        withClue(label, "the first row's label is truncated inside the pane: $m") { m["labelClipped"] shouldBe false }
        // 104 §C: the handle is the resizer now, and the native corner one is gone. Asserted on
        // the DEFAULT path so a revert of either half is caught by the suite that already runs.
        withClue(label, "no divider handle on the explorer: $m") { m["handleRole"] shouldBe "separator" }
        withClue(label, "the native resize corner came back: $m") { m["nativeResize"] shouldBe "none" }
    }

    private fun withClue(
        label: String,
        clue: String,
        block: () -> Unit,
    ) = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$label — $clue", e)
    }

    /**
     * D55 in the browser: the pipeline this suite creates over REST is a brand-new one, so the
     * explorer must render it as **v1 with the pending-release badge** — the state every pipeline
     * now starts in. It is asserted here rather than in a new suite because the fixture already
     * exists: `seedBothTrees` posts a pipeline and `openFirstFolder` puts its leaf on screen.
     *
     * What it would catch: the badge read `p.currentVersion` alone, which is null before the
     * first release — the row rendered the literal `vnull` in a page no unit test looks at.
     */
    @Test
    fun `a freshly created pipeline renders as v1 with the pending-release badge`() {
        startTrace()
        ready()
        seedBothTrees()

        page.setViewportSize(WIDTHS.first(), 900)
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".tplx-tree")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        openFirstFolder()

        val leaf = page.locator("button.tpl-leaf").first()
        withClue("the version badge", "a never-released pipeline names its DRAFT's version, not 'vnull'") {
            leaf
                .locator(".ds-badge-default")
                .first()
                .textContent()
                .trim() shouldBe "v1"
        }
        withClue("the draft badge", "unreleased work stays visible from first paint (versioning §7)") {
            leaf.locator(".ds-badge-warning").count() shouldBe 1
            leaf
                .locator(".ds-badge-warning")
                .first()
                .getAttribute("title")
                .shouldContain("pending release")
        }
    }

    @Test
    fun `both explorers keep the pane contract at three widths and both rail states`() {
        startTrace()
        ready()
        seedBothTrees()

        for (width in WIDTHS) {
            for (collapsed in listOf(false, true)) {
                page.setViewportSize(width, 900)
                for (route in listOf("/templates", "/pipelines")) {
                    page.navigate("$baseUrl$route")
                    page.evaluate(
                        "(c) => { document.documentElement.classList.toggle('rail-collapsed', c);" +
                            " window.localStorage.setItem('dp-rail', c ? '1' : '0'); }",
                        collapsed,
                    )
                    page.waitForSelector(".tplx-tree")
                    page.waitForLoadState(LoadState.NETWORKIDLE)
                    openFirstFolder()
                    assertPanes("full load $route at ${width}px rail-collapsed=$collapsed", page.evaluate(geometryProbe))
                }
            }
        }
    }

    /**
     * The regression test proper. Reverting the `<link>` in either list template — putting the
     * explorer sheet back inside `#app-main` — turns this red at the first width, while the
     * settled-page test above stays green: that is exactly the gap that let the defect ship.
     */
    @Test
    fun `the pane contract already holds on the first frame after a boosted navigation`() {
        startTrace()
        ready()
        seedBothTrees()

        for (width in WIDTHS) {
            for (collapsed in listOf(false, true)) {
                page.setViewportSize(width, 900)
                for (section in listOf("/templates", "/pipelines")) {
                    // Always start from a screen that is NOT an explorer, so the swap really
                    // introduces the explorer's markup rather than replacing like with like.
                    page.navigate("$baseUrl/dashboard")
                    page.evaluate(
                        "(c) => { document.documentElement.classList.toggle('rail-collapsed', c);" +
                            " window.localStorage.setItem('dp-rail', c ? '1' : '0'); }",
                        collapsed,
                    )
                    page.waitForLoadState(LoadState.NETWORKIDLE)
                    page.evaluate(armFirstFrame, geometryProbe)
                    page.click("a[data-nav-section='$section']")
                    page.waitForSelector(".tplx-tree")
                    page.waitForFunction("() => window.__ff !== null")
                    assertPanes(
                        "boosted first frame $section at ${width}px rail-collapsed=$collapsed",
                        page.evaluate("() => window.__ff"),
                    )
                }
            }
        }
    }

    /**
     * 104 §C — the tree pane's width is the USER's.
     *
     * Three rounds were spent guessing it in the stylesheet (`clamp(260px, 30vw, 480px)` left
     * the tree 14% of the owner's 3491px window; 26vw uncapped gave it 908px for a column of
     * short names), and all three were spent because the pane's only resizer was the browser's
     * native `resize: horizontal` handle — at the pane's BOTTOM-RIGHT corner, where nobody
     * looks for a divider. This is the handle ON the divider, measured: drag it 200px and the
     * tree is 200px wider AND the detail's left edge moved with it (a tree that grew while the
     * detail stayed put would be an overlap, not a resize); reload and the width survives;
     * double-click and the stylesheet's own default comes back.
     */
    @Test
    fun `the divider handle resizes the tree, the width survives a reload, and a double-click resets it`() {
        startTrace()
        ready()
        seedBothTrees()
        page.setViewportSize(1920, 900)
        page.navigate("$baseUrl/templates")
        page.waitForSelector(".tplx-tree")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        openFirstFolder()

        val before = probe()
        assertPanes("templates at 1920 before the drag", before)

        dragTreeBy(200.0)
        val dragged = probe()
        withClue("the drag", "the tree is not 200px wider: $before -> $dragged") {
            (dragged.d("treeWidth") - before.d("treeWidth")) shouldBeGreaterThanOrEqual 198.0
            (dragged.d("treeWidth") - before.d("treeWidth")) shouldBeLessThanOrEqual 202.0
        }
        withClue("the drag", "the detail pane did not move with the divider: $before -> $dragged") {
            (dragged.d("detailLeft") - before.d("detailLeft")) shouldBeGreaterThanOrEqual 198.0
            (dragged.d("detailLeft") - before.d("detailLeft")) shouldBeLessThanOrEqual 202.0
        }
        withClue("the drag", "the panes crowd after the resize: $dragged") {
            dragged.d("gap") shouldBeGreaterThanOrEqual dragged.d("gapToken")
        }
        withClue("the drag", "aria-valuenow does not describe the pane: $dragged") {
            (dragged.d("handleNow") - dragged.d("treeWidth")) shouldBeLessThanOrEqual 2.0
            (dragged.d("treeWidth") - dragged.d("handleNow")) shouldBeLessThanOrEqual 2.0
        }
        dragged["stored"] shouldBe dragged.d("treeWidth").toInt().toString()

        page.reload()
        page.waitForSelector(".tplx-tree")
        val reloaded = probe()
        withClue("the reload", "the remembered width did not survive: $dragged -> $reloaded") {
            (reloaded.d("treeWidth") - dragged.d("treeWidth")) shouldBeLessThanOrEqual 1.0
            (dragged.d("treeWidth") - reloaded.d("treeWidth")) shouldBeLessThanOrEqual 1.0
        }

        page.locator("[data-splitter='explorer-tree']").dblclick()
        page.waitForTimeout(150.0)
        val reset = probe()
        withClue("the reset", "double-click did not restore the stylesheet default: $reset") {
            (reset.d("treeWidth") - before.d("treeWidth")) shouldBeLessThanOrEqual 1.0
            (before.d("treeWidth") - reset.d("treeWidth")) shouldBeLessThanOrEqual 1.0
        }
        reset["stored"] shouldBe null
        assertPanes("templates at 1920 after the reset", reset)
    }

    /**
     * 104 §C — ONE key for both explorers (`dp.pane.explorer-tree`), so a user who widens the
     * tree in Templates finds it wide in Pipelines. The panes are the same component; two keys
     * would make them two components that merely look alike.
     */
    @Test
    fun `the width the user set in one explorer is the width the other opens at`() {
        startTrace()
        ready()
        seedBothTrees()
        page.setViewportSize(1920, 900)
        page.navigate("$baseUrl/templates")
        page.waitForSelector(".tplx-tree")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        openFirstFolder()

        dragTreeBy(200.0)
        val templates = probe()

        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".tplx-tree")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val pipelines = probe()

        withClue("the shared key", "the width did not follow the user to the other explorer: $pipelines") {
            (pipelines.d("treeWidth") - templates.d("treeWidth")) shouldBeLessThanOrEqual 1.0
            (templates.d("treeWidth") - pipelines.d("treeWidth")) shouldBeLessThanOrEqual 1.0
        }
    }

    /**
     * 104 §C — a remembered width is the width of the FIRST frame, not a shift onto it.
     *
     * This is the whole reason `splitter.js` is a parser-blocking script ABOVE the markup
     * instead of a deferred one: a size applied after the pane paints moves the detail pane,
     * and moving a painted element is precisely what CLS scores. (The 090 stylesheet defect
     * scored 0.0000 because it INSERTED the panes wrong rather than moving them — this one
     * would really shift, so the budget is the right instrument here.)
     */
    @Test
    fun `a full load with a remembered width paints at that width inside the layout-shift budget`() {
        startTrace()
        ready()
        seedBothTrees()
        page.setViewportSize(1920, 900)
        page.addInitScript(
            """
            window.localStorage.setItem('dp.pane.explorer-tree', '600');
            window.__cls = 0;
            new PerformanceObserver((l) => { for (const e of l.getEntries())
              if (!e.hadRecentInput) window.__cls += e.value; }).observe({type: 'layout-shift', buffered: true});
            """.trimIndent(),
        )
        page.navigate("$baseUrl/templates")
        page.waitForSelector(".tplx-tree")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        val m = probe()
        withClue("the remembered width", "the pane did not open at the remembered 600px: $m") {
            (m["treeWidth"] as Number).toDouble() shouldBeGreaterThanOrEqual 599.0
            (m["treeWidth"] as Number).toDouble() shouldBeLessThanOrEqual 601.0
        }
        val cls = (page.evaluate("() => window.__cls") as Number).toDouble()
        withClue("first paint at a remembered width", "cumulative layout shift $cls") { cls shouldBeLessThan CLS_BUDGET }
    }

    /**
     * §B's budget. Kept even though it did NOT catch the stylesheet defect (see the class
     * KDoc) because it is the instrument that covers the font half: `font-display: optional`
     * replaced `swap` in this round precisely so a face arriving late can no longer reflow a
     * painted page, and this is what would notice if it came back.
     */
    @Test
    fun `a boosted navigation across three routes stays inside the layout-shift budget`() {
        startTrace()
        ready()
        page.setViewportSize(1920, 1080)
        page.addInitScript(
            """
            window.__cls = 0;
            new PerformanceObserver((l) => { for (const e of l.getEntries())
              if (!e.hadRecentInput) window.__cls += e.value; }).observe({type: 'layout-shift', buffered: true});
            """.trimIndent(),
        )
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.evaluate("() => { window.__cls = 0; }")

        for (section in listOf("/templates", "/pipelines", "/executions")) {
            page.click("a[data-nav-section='$section']")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            val cls = (page.evaluate("() => window.__cls") as Number).toDouble()
            withClue("boosted navigation to $section", "cumulative layout shift $cls") { cls shouldBeLessThan CLS_BUDGET }
            page.evaluate("() => { window.__cls = 0; }")
        }
    }

    private companion object {
        /** `template-tree.css`'s declared floor for the pane. */
        const val TREE_FLOOR = 260.0

        /** …and its declared share of the viewport (`22vw`), capped at 40rem (640px at 16px). */
        const val TREE_SHARE = 0.22
        const val TREE_CEILING = 640.0

        /** `--gap-lg` at the default scale: the detail's inset from the divider. */
        const val DETAIL_INSET = 24.0

        /** The three review widths plus the owner's monitor, where the 480px ceiling showed. */
        val WIDTHS = listOf(1440, 1920, 2560, 3491)

        /** A row label narrower than this is the badge-only pane the owner reported. */
        const val MIN_LABEL_WIDTH = 40.0

        /** The round's brief: < 0.05, a quarter of the "good" Core Web Vitals threshold. */
        const val CLS_BUDGET = 0.05
    }
}
