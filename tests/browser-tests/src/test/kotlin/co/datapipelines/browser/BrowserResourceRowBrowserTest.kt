package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #604 — the record's resource row, MEASURED in the browser (ui-screens §3.4, the
 * application-shell record §5/§7): the browser surface is the overlay token, a row is
 * 12px padding and an 8px radius, the name is its own 12px title that wraps instead of
 * clipping, the badges sit in a trailing metadata line that starts under the title's
 * last line, the icon tops the title's first line, rows sit 4px apart, hover tints the
 * row, and selection is primary ink over the selected surface (never the link blue).
 *
 * Every number is a computed style or a box read off the real page at 1440×900,
 * collected first and printed (`604-measure|…` in the XML's system-out, the handback's
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
            titleHeight: tr.height,
            iconTop: icr.top, titleTop: tr.top, titleBottom: tr.bottom, titleLeft: tr.left,
            metaTop: mr.top, metaLeft: mr.left,
            controlHeight: cr.height, controlWidth: cr.width,
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
        readings.forEach { (label, value) -> println("604-measure|$case|$label|$value") }
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("604-$name.png")).setFullPage(false))
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
                          return {panel:p, badges, panelScroll:browser.scrollWidth, panelClient:browser.clientWidth,
                            treeScroll:scroll.scrollWidth, treeClient:scroll.clientWidth,
                            inside:badges.length > 0 && badges.every(r => r.left >= p.left - 0.5 && r.right <= p.right + 0.5
                              && r.top >= p.top - 0.5 && r.bottom <= p.bottom + 0.5),
                            noOverflow:browser.scrollWidth <= browser.clientWidth && scroll.scrollWidth <= scroll.clientWidth};
                        }""",
                        listOf(panel, path),
                    ) as Map<*, *>
                println("541-measure|$theme|$path|$metrics")
                withClue("$theme $path badge bounds: $metrics") { metrics["inside"] shouldBe true }
                withClue("$theme $path scrollWidth <= clientWidth: $metrics") { metrics["noOverflow"] shouldBe true }
            }
            Files.createDirectories(Paths.get("build/browser-evidence"))
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/browser-evidence/541-badges-$theme.png")))
        }
        ensureTheme(back)
    }

    /** The resting state: geometry, the overlay surface, hover on a sibling, the long name wraps. */
    @Test
    fun `a row is 12px padding, 8px radius, a wrapping 12px title over a badge line, 4px from its neighbour, on the overlay surface`() {
        startTrace()
        readyTree()
        seedPipeline("row/alpha")
        seedPipeline("row/beta")
        seedPipeline("row/" + "x".repeat(64))
        openTree()
        page.waitForSelector(folder("row"))
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
            near("row.alpha.iconTop", num("row.alpha.titleTop"), 1.0)
            near("row.alpha.metaTop", num("row.alpha.titleBottom") + 8.0, 1.0)
            near("row.alpha.metaLeft", num("row.alpha.titleLeft"), 1.0)
            near("row.beta.itemTop", num("row.alpha.itemBottom") + 4.0, 0.5)
            matchesToken("row.beta.hover.controlBg", "row.beta.surfaceHover")
            atLeast("row.long.titleHeight", 2 * num("row.long.titleLineHeight") - 0.5)
            atLeast("row.long.controlHeight", num("row.long.titleHeight") + 24.0 - 0.5)
            // a badge-less row (the folder) carries no metadata line at all — its control is
            // exactly padding + title + padding, with no dead space beneath the title
            near("row.folder.controlHeight", num("row.folder.padTop") + num("row.folder.titleHeight") + num("row.folder.padBottom"), 1.0)
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

    /** #516's keyboard ring stays on the LINE and takes the card's radius; the control draws none. */
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
                controlOutlineStyle: ccs.outlineStyle };
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
        }
    }
}
