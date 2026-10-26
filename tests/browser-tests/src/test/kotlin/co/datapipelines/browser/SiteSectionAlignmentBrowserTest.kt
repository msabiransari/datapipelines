package co.datapipelines.browser

import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 698b — standalone marketing sections use their whole content container, measured in a
 * real browser. The owner's screenshot (2026-10-25, 1183px): `/compare/fivetran-airbyte`'s
 * `#fa-jobs` body sat in the left 62ch while the next section's card grid spanned the
 * container. The fix marks two consumers (site.css's 698b block): `.text-columns` flows a
 * text body in two balanced columns from 64rem, and `.section-call` puts a closing call's
 * buttons at the container's right edge. The markup inventory is
 * SitePublicPresentationGuardTest rule 5; this is the geometry.
 *
 * Routes are DISCOVERED from `/sitemap.xml` (marketing routes only), so every page a consumer
 * renders on is measured, engine pages and demo-data's per-family lists included. Per width:
 *
 *  - **desktop (1183, the screenshot's width, and 1440)**: every `.text-columns` box starts
 *    and ends at the container's content edges (± 1.5px, the gutter excluded), computes two
 *    columns, and has text in its right half; every `.section-call` lede starts at the
 *    content's left edge, its buttons end at the right edge, beside the lede and on its
 *    bottom line;
 *  - **tablet (768) and phone (390, 320)**: one column, inside the content box, starting at
 *    its left edge; no page scrolls sideways.
 *
 * Non-vacuity: the 1183 sweep measures at least [MIN_COLUMNS] column bodies and
 * [MIN_CALLS] calls, and `#fa-jobs` is among them. Negative controls at 1440: the
 * reference `#fa-cost` grid spans the container (what the fix matches), `/sql-dashboards`'
 * deliberate `.split` keeps two side-by-side halves, an intro lede (`#sec-title` on
 * `/how-it-works`) keeps its measure, and a public doc's reading column stays within 48rem.
 *
 * Falsified at birth by removing site.css's 698b media block (the old left-only
 * composition): the desktop assertion names `#fa-jobs div.prose.text-columns` with a
 * ~530px right gap at 1183. Anonymous throughout.
 */
class SiteSectionAlignmentBrowserTest : BrowserSuite() {
    @Test
    fun `standalone section text spans the container at desktop and reads as one column below`() {
        startTrace()
        page.navigate("$baseUrl/sitemap.xml")
        val all =
            LOC
                .findAll(page.content())
                .map { it.groupValues[1].removePrefix(SITE_ORIGIN).ifEmpty { "/" } }
                .toList()
        val routes = all.filterNot { it.startsWith("/docs") }
        withClue("marketing route discovery is non-vacuous") { routes.size shouldBeGreaterThanOrEqual MIN_MARKETING_ROUTES }

        val offenders = mutableListOf<String>()
        val consumerRoutes = mutableListOf<String>()
        val measured = mutableListOf<String>()
        var columns = 0
        var calls = 0
        page.setViewportSize(SCREENSHOT_WIDTH, VIEWPORT_HEIGHT)
        routes.forEach { route ->
            val shape = measure(route)
            columns += shape.columns.size
            calls += shape.calls.size
            if (shape.columns.isNotEmpty() || shape.calls.isNotEmpty()) consumerRoutes += route
            shape.columns.forEach { measured += "$route ${it["label"]}" }
            desktopOffenders(route, SCREENSHOT_WIDTH, shape, offenders)
        }
        withClue("column bodies measured at $SCREENSHOT_WIDTH") { columns shouldBeGreaterThanOrEqual MIN_COLUMNS }
        withClue("closing calls measured at $SCREENSHOT_WIDTH") { calls shouldBeGreaterThanOrEqual MIN_CALLS }
        withClue("the screenshot's section is measured") { measured shouldContain ANCHOR }

        page.setViewportSize(WIDE_WIDTH, VIEWPORT_HEIGHT)
        consumerRoutes.forEach { route -> desktopOffenders(route, WIDE_WIDTH, measure(route), offenders) }
        NARROW_WIDTHS.forEach { w ->
            page.setViewportSize(w, VIEWPORT_HEIGHT)
            consumerRoutes.forEach { route -> narrowOffenders(route, w, measure(route), offenders) }
        }
        println("698b alignment sweep: ${routes.size} routes, ${consumerRoutes.size} with consumers, $columns column bodies, $calls calls at $SCREENSHOT_WIDTH")
        withClue("section alignment offenders") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `deliberate splits, intro ledes and the docs reading column keep their own widths`() {
        startTrace()
        page.setViewportSize(WIDE_WIDTH, VIEWPORT_HEIGHT)
        val grid = probe("/compare/fivetran-airbyte", "#fa-cost ~ .features-grid")
        withClue("#fa-cost's grid spans the content box (the reference): $grid") {
            near(grid["left"], 0.0) shouldBe true
            near(grid["right"], 0.0) shouldBe true
        }
        val split = probe("/sql-dashboards", "#sd-embed ~ .split")
        withClue("/sql-dashboards' .split keeps two halves side by side: $split") { split["sideBySide"] shouldBe true }
        val lede = probe("/how-it-works", "#sec-title + .section-lede")
        withClue("the intro lede above the security list keeps its measure: $lede") {
            ((lede["right"] as Number).toDouble() > MIN_MEASURE_GAP) shouldBe true
        }
        page.navigate("$baseUrl/sitemap.xml")
        val doc = LOC.findAll(page.content()).map { it.groupValues[1].removePrefix(SITE_ORIGIN) }.first { it.startsWith("/docs/") }
        val body = probe(doc, ".doc-body")
        withClue("$doc's reading column stays within 48rem: $body") { ((body["width"] as Number).toDouble() <= DOC_MEASURE_PX + 1) shouldBe true }
    }

    private data class Shape(
        val columns: List<Map<String, Any>>,
        val calls: List<Map<String, Any>>,
        val docOver: Double,
    )

    @Suppress("UNCHECKED_CAST")
    private fun measure(route: String): Shape {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val raw = page.evaluate(SWEEP_JS) as Map<String, Any>
        return Shape(raw["columns"] as List<Map<String, Any>>, raw["calls"] as List<Map<String, Any>>, (raw["docOver"] as Number).toDouble())
    }

    @Suppress("UNCHECKED_CAST")
    private fun probe(
        route: String,
        selector: String,
    ): Map<String, Any> {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val found = page.evaluate(PROBE_JS, selector) as Map<String, Any>?
        return checkNotNull(found) { "$route renders no $selector — the negative control would be vacuous" }
    }

    private fun desktopOffenders(
        route: String,
        w: Int,
        shape: Shape,
        offenders: MutableList<String>,
    ) {
        shape.columns.forEach { c ->
            val at = "$route at $w: ${c["label"]}"
            if (!near(c["left"], 0.0) || !near(c["right"], 0.0)) offenders += "$at spans ${c["left"]}..${c["right"]}px inside the content box (want 0 and 0)"
            if (c["count"] != "2") offenders += "$at computes column-count ${c["count"]} (want 2)"
            if (c["secondColumn"] != true) offenders += "$at has no text in its right half"
        }
        shape.calls.forEach { c ->
            val at = "$route at $w: section-call ${c["label"]}"
            if (!near(c["ledeLeft"], 0.0)) offenders += "$at lede starts ${c["ledeLeft"]}px from the content edge"
            if (!near(c["buttonsRight"], 0.0)) offenders += "$at buttons end ${c["buttonsRight"]}px short of the right edge"
            if (c["beside"] != true) offenders += "$at buttons are not beside the lede"
            if (!near(c["bottomDelta"], 0.0, CALL_BOTTOM_TOLERANCE)) offenders += "$at buttons sit ${c["bottomDelta"]}px off the lede's bottom"
        }
        if (shape.docOver > 0) offenders += "$route at $w scrolls sideways by ${shape.docOver}px"
    }

    private fun narrowOffenders(
        route: String,
        w: Int,
        shape: Shape,
        offenders: MutableList<String>,
    ) {
        shape.columns.forEach { c ->
            val at = "$route at $w: ${c["label"]}"
            if (c["count"] != "auto") offenders += "$at computes column-count ${c["count"]} (want one column)"
            if (!near(c["left"], 0.0) || (c["right"] as Number).toDouble() < -TOLERANCE) offenders += "$at spans ${c["left"]}..${c["right"]}px (want inside the content box)"
        }
        if (shape.docOver > 0) offenders += "$route at $w scrolls sideways by ${shape.docOver}px"
    }

    private fun near(
        value: Any?,
        want: Double,
        tolerance: Double = TOLERANCE,
    ): Boolean = Math.abs((value as Number).toDouble() - want) <= tolerance

    private companion object {
        val LOC = Regex("""<loc>([^<]+)</loc>""")
        const val SITE_ORIGIN = "https://datapipelines.co"
        const val SCREENSHOT_WIDTH = 1183
        const val WIDE_WIDTH = 1440
        val NARROW_WIDTHS = listOf(768, 390, 320)
        const val VIEWPORT_HEIGHT = 900
        const val TOLERANCE = 1.5
        const val CALL_BOTTOM_TOLERANCE = 2.0
        const val MIN_MARKETING_ROUTES = 41
        /** The 2026-10-26 offline render (websiteExport): 63 marked bodies + demo-data's three
         *  per-family provenance lists; 21 marked calls, the engine page's rendered on all 8 engines. */
        const val MIN_COLUMNS = 66
        const val MIN_CALLS = 28
        const val ANCHOR = "/compare/fivetran-airbyte #fa-jobs div.prose.text-columns"
        const val MIN_MEASURE_GAP = 100.0
        const val DOC_MEASURE_PX = 768.0

        /** Offsets are measured from the container's CONTENT box (padding = the gutter). */
        val SWEEP_JS =
            """() => {
                 const content = (c) => { const r = c.getBoundingClientRect(); const s = getComputedStyle(c);
                   return { left: r.left + parseFloat(s.paddingLeft), right: r.right - parseFloat(s.paddingRight) }; };
                 const label = (el) => { const sec = el.closest('section'); const h = sec && sec.querySelector('h2[id]');
                   return (h ? '#' + h.id : '?') + ' ' + el.tagName.toLowerCase() + '.' + Array.from(el.classList).join('.'); };
                 const round = (n) => Math.round(n * 10) / 10;
                 const columns = Array.from(document.querySelectorAll('.text-columns')).map(el => {
                   const c = content(el.closest('.container')); const r = el.getBoundingClientRect();
                   const range = document.createRange(); range.selectNodeContents(el);
                   const rects = Array.from(range.getClientRects()).filter(q => q.width > 0 && q.height > 0);
                   return { label: label(el), left: round(r.left - c.left), right: round(c.right - r.right),
                            count: getComputedStyle(el).columnCount,
                            secondColumn: rects.some(q => q.left >= r.left + r.width / 2) };
                 });
                 const calls = Array.from(document.querySelectorAll('.section-call')).map(el => {
                   const c = content(el); const b = el.querySelector(':scope > .btn-row').getBoundingClientRect();
                   const l = el.querySelector(':scope > .section-lede').getBoundingClientRect();
                   return { label: label(el), ledeLeft: round(l.left - c.left), buttonsRight: round(c.right - b.right),
                            beside: b.left >= l.right, bottomDelta: round(b.bottom - l.bottom) };
                 });
                 return { columns: columns, calls: calls,
                          docOver: document.documentElement.scrollWidth - document.documentElement.clientWidth };
               }"""

        /** One element's box against its container's content box; null when absent. */
        val PROBE_JS =
            """(sel) => { const el = document.querySelector(sel); if (!el) return null;
                 const host = el.closest('.container') || el.parentElement; const r = el.getBoundingClientRect();
                 const hr = host.getBoundingClientRect(); const s = getComputedStyle(host);
                 const kids = Array.from(el.children).map(k => k.getBoundingClientRect());
                 return { left: r.left - hr.left - parseFloat(s.paddingLeft), right: hr.right - parseFloat(s.paddingRight) - r.right,
                          width: r.width, sideBySide: kids.length === 2 && kids[1].left >= kids[0].right }; }"""
    }
}
