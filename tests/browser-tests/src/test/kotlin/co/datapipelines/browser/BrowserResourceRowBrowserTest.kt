package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #701 — the resource row re-cut to ONE line (owner ruling 2026-10-24; the record §5/§7
 * as amended in 4cf4f91d), MEASURED in the browser (ui-screens §3.4): the browser surface
 * is the overlay token, a row is 12px padding and an 8px radius and ONE text line — the
 * 12px title truncates with an ellipsis (the control's `title` carries the full canonical
 * name) and yields width to the badges, which share the line after it and never leave the
 * rail (#541); the glyph is centred on the line, a folder's following its expansion
 * (`folder` closed / `folder-open` open) and a leaf's staying `file`, and neither ever
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
     *  it, the glyph centred, 12px padding, an 8px radius, the 4px rhythm, the overlay
     *  surface, hover on a sibling, the folder glyph following its expansion. */
    @Test
    fun `a row is one line - a 12px title truncating with an ellipsis, its badges on the same line, a centred glyph, 12px padding, 8px radius, 4px between rows, on the overlay surface`() {
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
            near("row.alpha.padRight", 12.0, 0.05)
            near("row.alpha.padBottom", 12.0, 0.05)
            near("row.alpha.padLeft", 12.0, 0.05)
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
            // glyphs: the expanded folder is folder-open, a leaf stays file
            withClue("row.folder.iconHref") { (readings["row.folder.iconHref"] as String).endsWith("#folder-open") shouldBe true }
            withClue("row.alpha.iconHref") { (readings["row.alpha.iconHref"] as String).endsWith("#file") shouldBe true }
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
}
