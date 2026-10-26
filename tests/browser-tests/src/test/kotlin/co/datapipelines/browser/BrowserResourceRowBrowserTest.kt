package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID

/**
 * #701 — the resource row re-cut to ONE line (owner ruling 2026-10-24; the record §5/§7
 * as amended in 4cf4f91d), MEASURED in the browser (ui-screens §3.4): the browser surface
 * is the overlay token, a row is 12px vertical / 8px horizontal padding and an 8px radius and ONE text line — the
 * 12px title truncates with an ellipsis (the control's `title` carries the full canonical
 * name) and yields width to the badges, which share the line after it and never leave the
 * rail (#541); the glyph is centred on the line, a folder's following its expansion
 * (`folder` closed / `folder-open` open) and a leaf's carrying its family glyph, and neither ever
 * rotates. Rows sit 4px apart, hover tints the row, and selection is primary ink over the
 * selected surface (never the link blue).
 *
 * Every number is a computed style or a box read off the real page at 1440×900,
 * collected first and printed (`701-measure|…` in the XML's system-out, the handback's
 * base/tip table), then asserted softly, so a red names EVERY number that moved rather
 * than the first. Token comparisons resolve the named token through an ATTACHED probe
 * on the same page (a detached one computes to ""), never a hex literal.
 */
class BrowserResourceRowBrowserTest : RestTreeBrowserFixture() {
    private val readings = linkedMapOf<String, Any?>()

    /** One row's full reading: computed styles, boxes and the tokens they must resolve to. */
    @Suppress("UNCHECKED_CAST")
    private fun rowMetrics(path: String): Map<String, Any?>? =
        page.evaluate(
            """(args) => {
          const control = document.querySelector(args.panel + " .dp-tree-activate[title='" + args.path + "']");
          if (!control) return null;
          const line = control.parentElement;
          const item = line.parentElement;
          const title = control.querySelector('.dp-tree-title');
          const meta = control.querySelector('.dp-tree-meta');
          const icon = control.querySelector('svg');
          if (!title || !meta || !icon) return null;
          const cs = getComputedStyle(control), lcs = getComputedStyle(line);
          const tcs = getComputedStyle(title), mcs = getComputedStyle(meta);
          const cr = control.getBoundingClientRect(), tr = title.getBoundingClientRect();
          const mr = meta.getBoundingClientRect(), icr = icon.getBoundingClientRect();
          const itr = item.getBoundingClientRect();
          const probe = document.createElement('span');
          probe.style.display = 'none'; document.body.appendChild(probe);
          const tok = (name) => { probe.style.color = 'var(' + name + ')'; return getComputedStyle(probe).color; };
          const use = icon.querySelector('use');
          const out = {
            surfaceOverlay: tok('--surface-overlay'), surfaceHover: tok('--surface-hover'),
            surfaceSelected: tok('--surface-selected'), textPrimary: tok('--text-primary'),
            textSecondary: tok('--text-secondary'),
            controlBg: cs.backgroundColor, lineBg: lcs.backgroundColor, lineColor: lcs.color,
            metaColor: mcs.color,
            padTop: parseFloat(cs.paddingTop), padRight: parseFloat(cs.paddingRight),
            padBottom: parseFloat(cs.paddingBottom), padLeft: parseFloat(cs.paddingLeft),
            radius: parseFloat(cs.borderTopLeftRadius),
            titleFontSize: parseFloat(tcs.fontSize), titleLineHeight: parseFloat(tcs.lineHeight),
            titleTextOverflow: tcs.textOverflow,
            titleHeight: tr.height,
            titleScrollWidth: title.scrollWidth, titleClientWidth: title.clientWidth,
            iconHref: use ? use.getAttribute('href') : null, iconTransform: getComputedStyle(icon).transform,
            iconTop: icr.top, titleTop: tr.top, titleBottom: tr.bottom, titleLeft: tr.left,
            titleRight: tr.right, titleCenterY: tr.top + tr.height / 2, iconCenterY: icr.top + icr.height / 2,
            metaTop: mr.top, metaLeft: mr.left, metaBottom: mr.bottom, metaRight: mr.right,
            controlHeight: cr.height, controlWidth: cr.width,
            controlRight: cr.right, controlBottom: cr.bottom,
            controlTitle: control.getAttribute('title'), labelTitle: title.getAttribute('title'),
            itemTop: itr.top, itemBottom: itr.bottom,
          };
          probe.remove();
          return out;
        }""",
            mapOf("panel" to panel, "path" to path),
        ) as Map<String, Any?>?

    /** The browser surface's own background, with the token it must equal. */
    @Suppress("UNCHECKED_CAST")
    private fun surfaceMetrics(): Map<String, Any?> =
        page.evaluate(
            """() => {
          const browser = document.getElementById('app-browser');
          const cs = getComputedStyle(browser);
          const probe = document.createElement('span');
          probe.style.display = 'none'; document.body.appendChild(probe);
          probe.style.color = 'var(--surface-overlay)';
          const token = getComputedStyle(probe).color;
          probe.remove();
          return { browserBg: cs.backgroundColor, surfaceOverlay: token };
        }""",
        ) as Map<String, Any?>

    private fun record(
        label: String,
        metrics: Map<String, Any?>?,
    ) {
        metrics.shouldNotBeNull()
        for ((key, value) in metrics) readings["$label.$key"] = value
    }

    private fun printReadings(case: String) {
        readings.forEach { (label, value) -> println("701-measure|$case|$label|$value") }
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("701-$name.png")).setFullPage(false))
    }

    private fun num(label: String): Double = (readings[label] as Number).toDouble()

    private fun near(
        label: String,
        expected: Double,
        tolerance: Double = 1.0,
    ) {
        withClue(label) { num(label).shouldBeBetween(expected - tolerance, expected + tolerance, 0.0) }
    }

    private fun atLeast(
        label: String,
        floor: Double,
    ) {
        withClue(label) { num(label).shouldBeGreaterThanOrEqual(floor) }
    }

    private fun matchesToken(
        label: String,
        token: String,
    ) {
        withClue(label) { readings[label] shouldBe readings[token] }
    }

    private fun isTransparent(label: String) {
        withClue(label) { (readings[label] as String) shouldBe "rgba(0, 0, 0, 0)" }
    }

    /** #541: visible badges and both horizontal scroll boundaries, without scrolling. */
    @Test
    fun `long depth two and three leaves keep every badge inside the default browser in both themes`() {
        startTrace()
        readyTree()
        val paths = listOf("row/" + "x".repeat(64), "row/deep/" + "x".repeat(64))
        seedPipeline(paths.first())
        api("POST", "/api/v1/pipelines", pipelineBody(paths.last()))
        openTree()
        expand("row")
        expand("row/deep")
        paths.forEach { page.waitForSelector(leaf(it) + " .dp-tree-version:not([hidden])") }
        page.waitForSelector(leaf(paths.last()) + " .dp-tree-draft:not([hidden])")
        val back = page.locator("html").getAttribute("data-theme") ?: "dark"
        for (theme in listOf("light", "dark")) {
            ensureTheme(theme)
            for (path in paths) {
                val metrics =
                    page.evaluate(
                        """([panel, path]) => {
                          const browser = document.querySelector('#app-browser'), tree = document.querySelector(panel);
                          const rect = el => { const r = el.getBoundingClientRect();
                            return {left:r.left, right:r.right, top:r.top, bottom:r.bottom}; };
                          const p = rect(browser), scroll = tree.querySelector('.dp-tree-scroll');
                          const badges = [...tree.querySelectorAll('a[title="' + path + '"] .ds-badge:not([hidden])')]
                            .map(el => ({text:el.textContent, ...rect(el)}));
                          const title = tree.querySelector('a[title="' + path + '"] .dp-tree-title');
                          return {panel:p, badges, panelScroll:browser.scrollWidth, panelClient:browser.clientWidth,
                            treeScroll:scroll.scrollWidth, treeClient:scroll.clientWidth,
                            titleScrollWidth:title.scrollWidth, titleClientWidth:title.clientWidth,
                            inside:badges.length > 0 && badges.every(r => r.left >= p.left - 0.5 && r.right <= p.right + 0.5
                              && r.top >= p.top - 0.5 && r.bottom <= p.bottom + 0.5),
                            noOverflow:browser.scrollWidth <= browser.clientWidth && scroll.scrollWidth <= scroll.clientWidth};
                        }""",
                        listOf(panel, path),
                    ) as Map<*, *>
                println("541-measure|$theme|$path|$metrics")
                assertSoftly {
                    withClue("$theme $path badge bounds: $metrics") { metrics["inside"] shouldBe true }
                    withClue("$theme $path scrollWidth <= clientWidth: $metrics") { metrics["noOverflow"] shouldBe true }
                    // #701: the long name truncates instead of wrapping — the title's box is one line
                    withClue("$theme $path title truncates: $metrics") {
                        (metrics["titleScrollWidth"] as Number).toDouble() shouldBeGreaterThan
                            (metrics["titleClientWidth"] as Number).toDouble()
                    }
                }
            }
            Files.createDirectories(Paths.get("build/browser-evidence"))
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/browser-evidence/541-badges-$theme.png")))
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/browser-evidence/701-badges-$theme.png")))
        }
        ensureTheme(back)
    }

    /** The resting state (#701): one line per row — a truncating title, its badges beside
     *  it, the glyph centred, 12px vertical / 8px horizontal padding, an 8px radius, the 4px rhythm, the overlay
     *  surface, hover on a sibling, the folder glyph following its expansion. */
    @Suppress("LongMethod") // one document carries the ruling's geometry, truncation, glyph and badge measurements
    @Test
    fun `a row is one line - a truncating title, its badges beside it, a centred glyph, the 4px rhythm on the overlay surface`() {
        startTrace()
        readyTree()
        seedPipeline("row/alpha")
        seedPipeline("row/beta")
        seedPipeline("row/" + "x".repeat(64))
        openTree()
        page.waitForSelector(folder("row"))

        // the collapsed folder shows the CLOSED folder glyph before anything opens
        record("row.folder.collapsed", rowMetrics("row"))
        withClue("row.folder.collapsed.iconHref") {
            (readings["row.folder.collapsed.iconHref"] as String).endsWith("#folder") shouldBe true
        }

        expand("row")
        page.waitForSelector(leaf("row/alpha") + " .dp-tree-version:not([hidden])")

        record("surface", surfaceMetrics())
        record("row.alpha", rowMetrics("row/alpha"))
        record("row.beta", rowMetrics("row/beta"))
        record("row.long", rowMetrics("row/" + "x".repeat(64)))
        record("row.folder", rowMetrics("row"))
        shot("rows-1440")

        // hover tints the hovered row's control, and nothing else
        page.hover(leaf("row/beta"))
        record("row.beta.hover", rowMetrics("row/beta"))
        page.mouse().move(5.0, 500.0)

        printReadings("rest")
        val longPath = "row/" + "x".repeat(64)
        assertSoftly {
            matchesToken("surface.browserBg", "surface.surfaceOverlay")
            isTransparent("row.alpha.controlBg")
            isTransparent("row.alpha.lineBg")
            near("row.alpha.padTop", 12.0, 0.05)
            near("row.alpha.padRight", 8.0, 0.05)
            near("row.alpha.padBottom", 12.0, 0.05)
            near("row.alpha.padLeft", 8.0, 0.05)
            near("row.alpha.radius", 8.0, 0.05)
            near("row.alpha.titleFontSize", 12.0, 0.05)
            near("row.alpha.titleLineHeight", 18.6, 0.1)
            // ONE line: every row's title is a single 18.6px line, folders and leaves
            // alike, and the control is exactly padding + that line + padding
            for (row in listOf("row.alpha", "row.beta", "row.long", "row.folder")) {
                near("$row.titleHeight", num("$row.titleLineHeight"), 0.5)
                near("$row.controlHeight", num("$row.padTop") + num("$row.titleLineHeight") + num("$row.padBottom"), 1.0)
            }
            // the glyph is vertically centred on the text line and never rotated
            for (row in listOf("row.alpha", "row.long", "row.folder")) {
                withClue("$row glyph centred on the title line") {
                    num("$row.iconCenterY").shouldBeBetween(num("$row.titleCenterY") - 1.0, num("$row.titleCenterY") + 1.0, 0.0)
                }
                withClue("$row icon transform") { readings["$row.iconTransform"] shouldBe "none" }
            }
            // the badges share the title's line and stay inside the control's padding box
            for (row in listOf("row.alpha", "row.beta")) {
                atLeast("$row.metaTop", num("$row.titleTop") - 1.0)
                withClue("$row.metaBottom") {
                    num("$row.metaBottom").shouldBeLessThanOrEqual(num("$row.controlBottom") - num("$row.padBottom") + 1.0)
                }
                withClue("$row.metaLeft") { num("$row.metaLeft").shouldBeGreaterThanOrEqual(num("$row.titleRight") - 0.5) }
                withClue("$row.metaRight") {
                    num("$row.metaRight").shouldBeLessThanOrEqual(num("$row.controlRight") - num("$row.padRight") + 0.5)
                }
            }
            // the long name truncates with an ellipsis; the tooltip carries the full path
            withClue("row.long.titleScrollWidth > titleClientWidth") {
                num("row.long.titleScrollWidth").shouldBeGreaterThan(num("row.long.titleClientWidth"))
            }
            withClue("row.long.titleTextOverflow") { readings["row.long.titleTextOverflow"] shouldBe "ellipsis" }
            withClue("row.long.controlTitle") { readings["row.long.controlTitle"] shouldBe longPath }
            withClue("row.long.labelTitle") { readings["row.long.labelTitle"] shouldBe longPath }
            // glyphs: the expanded folder is folder-open, a pipeline leaf carries workflow
            withClue("row.folder.iconHref") { (readings["row.folder.iconHref"] as String).endsWith("#folder-open") shouldBe true }
            withClue("row.alpha.iconHref") { (readings["row.alpha.iconHref"] as String).endsWith("#workflow") shouldBe true }
            near("row.beta.itemTop", num("row.alpha.itemBottom") + 4.0, 0.5)
            matchesToken("row.beta.hover.controlBg", "row.beta.surfaceHover")
        }
    }

    /** Selection: primary ink on the line, the selected surface on the control, muted meta. */
    @Test
    fun `selection is primary ink over the selected surface on the row's control, with the metadata muted`() {
        startTrace()
        readyTree()
        seedPipeline("row/alpha")
        seedPipeline("row/beta")
        openTree()
        page.waitForSelector(folder("row"))
        expand("row")
        page.waitForSelector(leaf("row/alpha"))

        page.click(leaf("row/alpha"))
        page.waitForFunction(
            "path => document.querySelector('#nav-tree-pipelines a[title=\"' + path + '\"]')" +
                ".closest('[role=treeitem]').getAttribute('aria-selected') === 'true'",
            "row/alpha",
        )
        record("row.alpha", rowMetrics("row/alpha"))
        shot("row-selected-1440")

        printReadings("selected")
        assertSoftly {
            matchesToken("row.alpha.lineColor", "row.alpha.textPrimary")
            matchesToken("row.alpha.controlBg", "row.alpha.surfaceSelected")
            matchesToken("row.alpha.metaColor", "row.alpha.textSecondary")
            isTransparent("row.alpha.lineBg")
        }
    }

    /** #516's keyboard ring stays on the LINE at the card's radius; #644 resets the item's global shadow. */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the keyboard ring rides the row line rounded to the card's radius, never the control`() {
        startTrace()
        readyTree()
        seedPipeline("row/alpha")
        openTree()
        page.waitForSelector(folder("row"))
        page.focus("$panel input")
        page.keyboard().press("ArrowDown")
        page.waitForFunction("() => document.activeElement?.getAttribute('role') === 'treeitem'")
        val ring =
            page.evaluate(
                """() => {
              const line = document.activeElement.querySelector(':scope > .dp-tree-line');
              const control = line.querySelector('.dp-tree-activate');
              const lcs = getComputedStyle(line), ccs = getComputedStyle(control);
              return { lineOutlineWidth: lcs.outlineWidth, lineOutlineStyle: lcs.outlineStyle,
                lineRadius: parseFloat(lcs.borderTopLeftRadius), controlRadius: parseFloat(ccs.borderTopLeftRadius),
                controlOutlineStyle: ccs.outlineStyle, itemBoxShadow: getComputedStyle(document.activeElement).boxShadow };
            }""",
            ) as Map<String, Any?>
        record("ring", ring)
        shot("row-focus-ring-1440")

        printReadings("ring")
        assertSoftly {
            withClue("ring.lineOutlineWidth") { readings["ring.lineOutlineWidth"] shouldBe "2px" }
            withClue("ring.lineOutlineStyle") { readings["ring.lineOutlineStyle"] shouldBe "solid" }
            near("ring.lineRadius", num("ring.controlRadius"), 0.05)
            near("ring.controlRadius", 8.0, 0.05)
            withClue("ring.controlOutlineStyle") { readings["ring.controlOutlineStyle"] shouldBe "none" }
            withClue("ring.itemBoxShadow") { readings["ring.itemBoxShadow"] shouldBe "none" }
        }
    }

    /** #739: guide geometry comes from the real row and its pseudo-elements, including an expanded last child. */
    @Suppress("LongMethod") // one fixture compares connected guides in both themes, then search and clear
    @Test
    fun `guides join parent to last child centre across gaps and ignore the last child's expanded subtree`() {
        startTrace()
        readyTree()
        listOf("guide/a/leaf", "guide/b/leaf", "guide/c/leaf").forEach { seedPipeline(it) }
        openTree()
        expand("guide")
        expand("guide/a")
        expand("guide/c")
        for (theme in listOf("light", "dark")) {
            ensureTheme(theme)
            record("guide.parent", guideMetrics("guide"))
            for (path in listOf("guide/a", "guide/b", "guide/c", "guide/a/leaf", "guide/c/leaf")) {
                record(path, guideMetrics(path))
            }
            printReadings("guides-$theme")
            page.screenshot(Page.ScreenshotOptions().setPath(evidence("739-guides-$theme.png")))
            assertSoftly {
                withClue("root has no vertical guide") { readings["guide.parent.beforeContent"] shouldBe "none" }
                withClue("root has no tick") { readings["guide.parent.afterContent"] shouldBe "none" }
                for (path in listOf("guide/a", "guide/b", "guide/c", "guide/a/leaf", "guide/c/leaf")) {
                    withClue("$path guide exists") { readings["$path.beforeContent"] shouldBe "\"\"" }
                    near("$path.indent", 8.0)
                    near("$path.guideX", num("$path.parentIconCenterX"))
                    near("$path.tickY", num("$path.rowCenterY"))
                    near("$path.tickEndX", num("$path.iconLeft"))
                    near("$path.tickLength", 2.0, 0.1)
                    near("$path.rowHeight", 42.6, 0.1)
                    near("$path.guideWidth", 1.0, 0.1)
                    near("$path.tickHeight", 1.0, 0.1)
                    matchesToken("$path.guideColor", "$path.borderSubtle")
                    matchesToken("$path.tickColor", "$path.borderSubtle")
                }
                withClue("the fixture has three direct children") { (readings["guide/a.siblingItems"] as Number).toInt() shouldBe 3 }
                withClue("the first child is not last") { readings["guide/a.lastItem"] shouldBe false }
                withClue("the middle child is not last") { readings["guide/b.lastItem"] shouldBe false }
                withClue("the expanded third child is last") { readings["guide/c.lastItem"] shouldBe true }
                near("guide/a.guideTop", num("guide.parent.rowCenterY"))
                near("guide/a.guideBottom", num("guide/b.itemTop"))
                near("guide/b.guideBottom", num("guide/c.itemTop"))
                near("guide/c.guideBottom", num("guide/c.rowCenterY"))
                withClue("last child's expanded subtree extends below its guide") {
                    num("guide/c.itemBottom").shouldBeGreaterThan(num("guide/c.guideBottom") + num("guide/c.rowHeight"))
                }
                // A single child's guide reaches UP into its parent and stops at its own row centre.
                near("guide/c/leaf.guideTop", num("guide/c.rowCenterY"))
                near("guide/c/leaf.guideBottom", num("guide/c/leaf.rowCenterY"))
            }
            // A trailing status/Load more row must not make the last RESOURCE's guide span its subtree.
            page.evaluate(
                """panel => {
                  const group = document.querySelector(panel + ' [data-tree-key="folder:guide"] > .dp-tree-group');
                  const notice = document.createElement('div'); notice.className = 'dp-tree-status';
                  notice.textContent = 'More results'; group.append(notice);
                }""",
                panel,
            )
            record("notice.last", guideMetrics("guide/c"))
            near("notice.last.guideBottom", num("guide/c.rowCenterY"))
            page.evaluate("panel => document.querySelector(panel + ' .dp-tree-status').remove()", panel)
        }
        search("leaf")
        // Search contains server-provided ancestor folders too: every level must be guide-free.
        val searchGuides =
            page.evaluate(
                """panel => [...document.querySelectorAll(panel + ' .dp-tree-item')].every(item =>
                  getComputedStyle(item, '::before').content === 'none' && getComputedStyle(item, '::after').content === 'none')""",
                panel,
            )
        searchGuides shouldBe true
        page.click("$panel button[aria-label='Clear search']")
        expand("guide")
        record("cleared.first", guideMetrics("guide/a"))
        withClue("clearing search restores browse guides") { readings["cleared.first.beforeContent"] shouldBe "\"\"" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun guideMetrics(path: String): Map<String, Any?>? =
        page.evaluate(
            """([panel, path]) => {
              const control = document.querySelector(panel + ' .dp-tree-activate[title="' + path + '"]');
              if (!control) return null;
              const item = control.closest('.dp-tree-item'), group = item.parentElement;
              const parent = group.closest('.dp-tree-item');
              const ir = item.getBoundingClientRect(), cr = control.getBoundingClientRect();
              const icon = control.querySelector('svg').getBoundingClientRect();
              const parentIcon = parent?.querySelector(':scope > .dp-tree-line svg').getBoundingClientRect();
              const before = getComputedStyle(item, '::before'), after = getComputedStyle(item, '::after');
              const probe = document.createElement('span'); document.body.append(probe);
              probe.style.color = 'var(--border-subtle)';
              const out = {
                beforeContent: before.content, afterContent: after.content,
                siblingItems: group.querySelectorAll(':scope > .dp-tree-item').length,
                lastItem: item.matches(':not(:has(~ .dp-tree-item))'),
                itemTop: ir.top, itemBottom: ir.bottom, rowCenterY: cr.top + cr.height / 2, rowHeight: cr.height,
                parentIconCenterX: parentIcon ? parentIcon.left + parentIcon.width / 2 : null,
                iconLeft: icon.left, guideX: ir.left + parseFloat(before.left),
                guideTop: ir.top + parseFloat(before.top),
                guideBottom: ir.top + parseFloat(before.top) + parseFloat(before.height),
                guideWidth: parseFloat(before.width), guideColor: before.backgroundColor,
                tickY: ir.top + parseFloat(after.top), tickLength: parseFloat(after.width),
                tickEndX: ir.left + parseFloat(after.left) + parseFloat(after.width),
                tickHeight: parseFloat(after.height), tickColor: after.backgroundColor,
                indent: parseFloat(getComputedStyle(group).paddingInlineStart), borderSubtle: getComputedStyle(probe).color,
              };
              probe.remove(); return out;
            }""",
            listOf(panel, path),
        ) as Map<String, Any?>?

    // ---------------------------------------------------------------- #701 round two (owner ruling 2026-10-25)

    /** F1: the supported minimum — the compact row keeps every badge inside the rail at depth 3 and 10. */
    @Test
    fun `at the 240px minimum every badge stays inside the rail at depth three and ten in both themes`() {
        narrowRows(MIN_WIDTH)
    }

    /** F1, widened: the default — a depth-10 leaf overflowed by ~62px before the compact mode. */
    @Test
    fun `at the 300px default every badge stays inside the rail and every name keeps width at depth three and ten`() {
        narrowRows(DEFAULT_WIDTH)
    }

    /**
     * One width's matrix (brief 701b A.2), set the way the product sets it — the preference on
     * `html` that browser-resize.mjs writes — and proven taken before anything is read: the
     * pipelines tree's depth-3 and depth-10 leaves (v13 and draft) and the templates tree's
     * (v13, draft and #533's needs review), light and dark.
     */
    private fun narrowRows(width: Int) {
        startTrace()
        readyTree()
        PIPELINE_ROWS.forEach { releaseTimes(it, RELEASES) }
        val workspace = UUID.fromString(field(api("GET", "/api/v1/pipelines/tree", null), "workspace_id"))
        val scripts = scriptRows(width)
        seedReviewedScripts(workspace, scripts)
        val rows = mutableListOf<Map<String, Any?>>()
        for ((family, paths) in listOf("pipelines" to PIPELINE_ROWS, "templates" to scripts)) {
            openBrowserTree(family)
            setBrowserWidth(width)
            paths.forEach { openFolders(family, it) }
            for (theme in listOf("light", "dark")) {
                ensureTheme(theme)
                paths.forEach { rows += requireNotNull(narrowMetrics(family, it)).plus(listOf("theme" to theme, "family" to family)) }
                page.screenshot(Page.ScreenshotOptions().setPath(evidence("701b-$width-$family-$theme.png")))
            }
        }
        // the other end, from the default case: at the 480px maximum the rows are still compact (no
        // threshold, owner ruling 2026-10-25 amended), and widening never takes width from a name
        val widest = if (width == DEFAULT_WIDTH) widestSide(scripts) else emptyList()
        (rows + widest).forEach { row ->
            println("701b-measure|${row["width"] ?: width}|${row["depth"]}|${row["theme"]}|${row["family"]}|$row")
        }
        assertSoftly {
            rows.forEach { assertNarrowRow(width, it) }
            widest.forEach { row ->
                assertNarrowRow(MAX_WIDTH, row)
                val narrower = rows.single { it["path"] == row["path"] && it["theme"] == row["theme"] }
                withClue("$MAX_WIDTH the name only grows: $row vs $narrower") {
                    (row["titleClientWidth"] as Number).toDouble().shouldBeGreaterThan((narrower["titleClientWidth"] as Number).toDouble())
                }
            }
        }
    }

    /** The templates rows and PNG evidence in both themes at the browser's 480px maximum. */
    private fun widestSide(scripts: List<String>): List<Map<String, Any?>> {
        setBrowserWidth(MAX_WIDTH)
        return listOf("light", "dark").flatMap { theme ->
            ensureTheme(theme)
            val metadata = listOf("theme" to theme, "family" to "templates", "width" to MAX_WIDTH)
            val rows = scripts.map { requireNotNull(narrowMetrics("templates", it)).plus(metadata) }
            page.screenshot(Page.ScreenshotOptions().setPath(evidence("739-$MAX_WIDTH-templates-$theme.png")))
            rows
        }
    }

    private fun assertNarrowRow(
        width: Int,
        row: Map<String, Any?>,
    ) {
        val label = "$width ${row["theme"]} ${row["path"]}: $row"

        fun n(key: String) = (row[key] as Number).toDouble()
        withClue("$label — the width took") { n("browserWidth").shouldBeBetween(width - 0.5, width + 0.5, 0.0) }
        withClue("$label — every badge, the version two-digit") {
            row["badgeTexts"] shouldBe if (row["family"] == "templates") listOf("v13", "draft", "needs review") else listOf("v13", "draft")
        }
        withClue("$label — badges inside the browser's content edge") { n("badgeRight").shouldBeLessThanOrEqual(n("contentRight") + 0.5) }
        withClue("$label — no sideways scroll (#541)") { n("treeScrollWidth").shouldBeLessThanOrEqual(n("treeClientWidth")) }
        if (width == MIN_WIDTH && n("depth") > SHALLOW_DEPTH && row["family"] == "templates") {
            // #739 spacing amendment: a deep three-badge leaf at the minimum may use the trailing
            // row/scroll inset — inside the rail, never clipped
            withClue("$label — the accepted residue stays inside the rail") {
                n("metaRight").shouldBeLessThanOrEqual(n("contentRight") + 0.5)
            }
        } else {
            withClue("$label — meta inside the control") { n("metaRight").shouldBeLessThanOrEqual(n("controlInnerRight") + 0.5) }
        }
        withClue("$label — tooltip") { listOf(row["controlTitle"], row["labelTitle"]) shouldBe listOf(row["path"], row["path"]) }
        withClue("$label — ellipsis") { row["titleTextOverflow"] shouldBe "ellipsis" }
        if (width >= DEFAULT_WIDTH || n("depth") <= SHALLOW_DEPTH) {
            withClue("$label — the name keeps width") { n("titleClientWidth").shouldBeGreaterThan(0.0) }
        } else {
            withClue("$label — the ruling accepts a 0px name here") { n("titleClientWidth").shouldBeGreaterThanOrEqual(0.0) }
        }
        // the compact rows (every width): the indent and row insets are --space-2, row gaps --space-1, badge gaps/sides --space-0-5
        withClue("$label — compact indent") { n("indent") shouldBe n("space2") }
        withClue("$label — family leaf glyph") {
            (row["iconHref"] as String).endsWith(if (row["family"] == "templates") "#code" else "#workflow") shouldBe true
        }
        withClue("$label — horizontal row inset") { n("rowInset") shouldBe n("space2") }
        withClue("$label — horizontal row gap") { n("rowGap") shouldBe n("space1") }
        withClue("$label — badge gap") { n("badgeGap") shouldBe n("spaceHalf") }
        withClue("$label — compact badge side padding") { n("badgePadLeft") shouldBe n("spaceHalf") }
    }

    /** The preference browser-resize.mjs writes; the box must report it before any reading counts. */
    private fun setBrowserWidth(width: Int) {
        page.evaluate("w => document.documentElement.style.setProperty('--app-browser-preferred', w + 'px')", width)
        settle()
        withClue("#app-browser box width") { width() shouldBe width }
    }

    private fun openFolders(
        family: String,
        path: String,
    ) {
        val segments = path.split('/').dropLast(1)
        for (depth in 1..segments.size) {
            val key = "folder:" + segments.take(depth).joinToString("/")
            val folder = "#nav-tree-$family [data-tree-key='$key']"
            if (page.locator(folder).getAttribute("aria-expanded") != "true") page.click("$folder > .dp-tree-line button")
            page.waitForFunction(
                "([family, key]) => window.DatapipelinesSidebarTrees.get(family).state.levels.get(key)?.complete",
                listOf(family, key),
            )
        }
        page.waitForSelector("#nav-tree-$family .dp-tree-activate[title='$path'] .dp-tree-draft:not([hidden])")
    }

    @Suppress("UNCHECKED_CAST")
    private fun narrowMetrics(
        family: String,
        path: String,
    ): Map<String, Any?>? =
        page.evaluate(
            """([family, path]) => {
          const browser = document.getElementById('app-browser'), tree = document.getElementById('nav-tree-' + family);
          const control = tree.querySelector(".dp-tree-activate[title='" + path + "']");
          if (!control) return null;
          const item = control.closest('[role=treeitem]'), group = item.parentElement.closest('.dp-tree-group');
          const title = control.querySelector('.dp-tree-title'), meta = control.querySelector('.dp-tree-meta');
          const scroll = tree.querySelector('.dp-tree-scroll'), br = browser.getBoundingClientRect();
          const cs = getComputedStyle(control), cr = control.getBoundingClientRect();
          const badges = [...control.querySelectorAll('.ds-badge:not([hidden])')];
          const probe = document.createElement('span');
          probe.style.display = 'none'; document.body.appendChild(probe);
          const tok = (name) => { probe.style.paddingLeft = 'var(' + name + ')'; return parseFloat(getComputedStyle(probe).paddingLeft); };
          const out = { path, depth: Number(item.getAttribute('aria-level')),
            browserWidth: br.width, browserClientWidth: browser.clientWidth, contentRight: br.left + browser.clientLeft + browser.clientWidth,
            treeScrollWidth: scroll.scrollWidth, treeClientWidth: scroll.clientWidth,
            badgeTexts: badges.map(b => b.textContent), badgeRight: Math.max(...badges.map(b => b.getBoundingClientRect().right)),
            badgePadLeft: parseFloat(getComputedStyle(badges[0]).paddingLeft),
            metaRight: meta.getBoundingClientRect().right, controlInnerRight: cr.right - parseFloat(cs.paddingRight),
            titleClientWidth: title.clientWidth, titleScrollWidth: title.scrollWidth, titleTextOverflow: getComputedStyle(title).textOverflow,
            controlTitle: control.getAttribute('title'), labelTitle: title.getAttribute('title'),
            indent: group ? parseFloat(getComputedStyle(group).paddingInlineStart) : null,
            iconHref: control.querySelector('use').getAttribute('href'),
            rowInset: parseFloat(cs.paddingLeft), rowGap: parseFloat(cs.columnGap),
            badgeGap: parseFloat(getComputedStyle(meta).columnGap),
            spaceHalf: tok('--space-0-5'), space1: tok('--space-1'), space2: tok('--space-2') };
          probe.remove();
          return out;
        }""",
            listOf(family, path),
        ) as Map<String, Any?>?

    /** Creates the pipeline [name], releases it [times] times (a changed body each time after the first), then edits it once more. */
    private fun releaseTimes(
        name: String,
        times: Int,
    ) {
        val created = api("POST", "/api/v1/pipelines", pipelineBody(name))
        val id = field(created, "id")
        var hash = field(created, "body_hash")
        for (round in 1..times) {
            if (round > 1) hash = field(api("PUT", "/api/v1/pipelines/$id", pipelineBody(name, "n$round"), hash), "body_hash")
            api("POST", "/api/v1/pipelines/$id/release", null, hash)
        }
        api("PUT", "/api/v1/pipelines/$id", pipelineBody(name, "n_draft"), hash)
    }

    /**
     * The templates tree's three badges on the real page: a jsonata script citing a workspace rule,
     * released [RELEASES] times and edited once more, then the rule superseded (the #533 marker's
     * path, [NeedsReviewFixtures]). Every write precedes the supersession: a retired rule is not citable.
     */
    private fun seedReviewedScripts(
        workspace: UUID,
        scripts: List<String>,
    ) {
        val email = workspaceCreator(workspace)
        val datasource = "row701-src-" + generatedPassword("s").takeLast(8).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource).shouldBeEmpty()
        val rule = NeedsReviewFixtures.recordRule(email, datasource, "A row is a row.", supersedes = null, workspace = workspace)
        for (script in scripts) {
            NeedsReviewFixtures.writeCitingScript(page, script, rule)
            for (round in 1..RELEASES) {
                if (round > 1) NeedsReviewFixtures.writeCitingScript(page, script, rule, round, workingHash(script))
                api("POST", "/api/v1/templates/release", """{"name":"$script"}""", workingHash(script))
            }
            NeedsReviewFixtures.writeCitingScript(page, script, rule, RELEASES + 1, workingHash(script))
        }
        NeedsReviewFixtures.recordRule(email, datasource, "A row is a row with a name.", supersedes = rule, workspace = workspace)
    }

    /** The working version's hash (the draft's when one exists), read fresh before every write, as the integration suites do. */
    private fun workingHash(script: String): String = field(api("GET", "/api/v1/templates?name=$script", null), "body_hash")

    private fun workspaceCreator(workspace: UUID): String {
        val sql = "SELECT u.email FROM workspaces w JOIN users u ON u.id = w.created_by WHERE w.id = ?"
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.prepareStatement(sql).use { query ->
                query.setObject(1, workspace)
                val result = query.executeQuery()
                check(result.next()) { "workspace $workspace has no creator" }
                return result.getString(1)
            }
        }
    }

    private fun evidence(name: String) = Paths.get("build", "browser-evidence").also { Files.createDirectories(it) }.resolve(name)

    private companion object {
        const val MIN_WIDTH = 240
        const val DEFAULT_WIDTH = 300

        /** The browser's maximum column (`--app-browser-max`). */
        const val MAX_WIDTH = 480
        const val SHALLOW_DEPTH = 3.0

        /** Twelve releases, then a draft: the row reads `v13` and `draft` — a two-digit version. */
        const val RELEASES = 12
        val LONG = "x".repeat(64)
        val DEEP = (2..9).joinToString("/", prefix = "row/") { "d$it" }
        val PIPELINE_ROWS = listOf("row/deep/$LONG", "$DEEP/$LONG")

        /**
         * The templates rows of one case, under a root that names its width, so the geometry
         * evidence identifies its fixture. Cross-workspace write correctness is covered by
         * TemplateRepositoryIntegrationTest; these cases measure the rendered row.
         */
        fun scriptRows(width: Int): List<String> {
            val leaf = "${LONG.drop(8)}.jsonata"
            return listOf("t$width/deep/$leaf", (2..9).joinToString("/", prefix = "t$width/", postfix = "/$leaf") { "d$it" })
        }
    }
}
