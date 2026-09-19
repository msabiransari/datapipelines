package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 160 — the site on phones, at 390×844 with a mobile, touch context, over EVERY page the
 * route floor enumerates. The routes are DISCOVERED from `/sitemap.xml` — the same
 * enumeration `SiteRouteFloorTest` pins (the 59-route baseline + the two 145 additions,
 * plus the packaged docs), generated from the page registry and the docs catalog — so a
 * page added to the site is guarded the day it is listed, and nothing is retyped here.
 *
 * The defect this catches (160, measured under DevTools emulation 2026-09-18): the home
 * knowledge section's `.grid-2` computed `grid-template-columns: 0px 326px` — four text
 * blocks at 0 px width, one letter per line — because `.walk-card`'s unconditional
 * `grid-column: 2` leaked outside the walkthrough grid. The DOCUMENT did not overflow, so
 * the existing document-level sweeps stayed green: the defect is only visible at ELEMENT
 * level. Three conditions, each a way a phone layout can silently break:
 *
 *  1. a block-level element carrying ≥ 12 characters of visible text while measuring
 *     narrower than 40 px and taller than 60 px — text squeezed into a sliver;
 *  2. an element whose `scrollWidth` exceeds the viewport and which is not itself inside a
 *     deliberate swipe region — content wider than the phone that the reader cannot reach;
 *  3. the document's `scrollWidth` exceeding the viewport — the page scrolls sideways.
 *
 * Condition 2's swipe exemption (a deviation from the brief's literal wording, recorded in
 * the 164 handback): the site's code blocks, doc tables and DAG embeds ship
 * `display: block; overflow-x: auto` — a scrollable BOX that fits the viewport with its
 * content reachable by swiping, which is the shipped, reviewed phone pattern. Taken
 * literally, `scrollWidth > viewport` flags that region and every descendant (3,948
 * elements on the pre-fix sweep), a guard born permanently red. The exemption covers only
 * `overflow-x: auto/scroll` — content hidden behind `overflow: hidden/clip` is UNREACHABLE
 * and still counts as an offender.
 *
 * Offenders are deduplicated to their outermost element and labelled with their nearest
 * `id` ancestor (`p.lede in section#learned-context`), so a red run names the section.
 * Anonymous throughout; one viewport, so the sweep is 61+ navigations.
 */
class SiteMobileLayoutBrowserTest : BrowserSuite() {
    @Test
    fun `every sitemap route renders at 390 without squeezed text blocks or sideways overflow`() {
        startTrace()
        page.navigate("$baseUrl/sitemap.xml")
        val routes =
            LOC
                .findAll(page.content())
                .map { it.groupValues[1].removePrefix(SITE_ORIGIN).ifEmpty { "/" } }
                .toList()
        withClue("route discovery is non-vacuous (the 59 baseline routes + the 2 additions)") {
            routes.size shouldBeGreaterThanOrEqual MIN_ROUTES
        }
        routes.count { !it.startsWith("/docs") } shouldBe MARKETING_ROUTES

        val phone =
            newBrowserContext(
                Browser
                    .NewContextOptions()
                    .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    .setIsMobile(true)
                    .setHasTouch(true),
            )
        val phonePage = phone.newPage()
        try {
            val offenders = mutableListOf<String>()
            val table = mutableListOf<String>()
            routes.forEach { route ->
                phonePage.navigate("$baseUrl$route")
                phonePage.waitForLoadState(LoadState.NETWORKIDLE)
                @Suppress("UNCHECKED_CAST")
                val shape = phonePage.evaluate(SWEEP_JS) as Map<String, Any>

                @Suppress("UNCHECKED_CAST")
                val squeezed = shape["squeezed"] as List<String>

                @Suppress("UNCHECKED_CAST")
                val wide = shape["wide"] as List<String>
                val docOver = (shape["docOver"] as Number).toLong()
                squeezed.forEach { offenders += "$route: squeezed text block — $it" }
                wide.forEach { offenders += "$route: element wider than the ${VIEWPORT_WIDTH}px viewport — $it" }
                if (docOver > 0) offenders += "$route: document scrollWidth ${VIEWPORT_WIDTH + docOver}px > viewport"
                table += "| `$route` | ${squeezed.size} | ${wide.size + (if (docOver > 0) 1 else 0)} |"
            }
            withClue("the sweep visited every route") { table.size shouldBe routes.size }
            println("390px sweep — per page (zero-width text blocks / overflow offenders):")
            println("| page | zero-width | overflow |")
            println("|------|-----------|----------|")
            table.forEach(::println)
            println("390px offenders:")
            offenders.forEach(::println)
            withClue("390px layout offenders (160's guard)") { offenders.shouldBeEmpty() }
        } finally {
            phone.close()
        }
    }

    private companion object {
        val LOC = Regex("""<loc>([^<]+)</loc>""")

        const val VIEWPORT_WIDTH = 390
        const val VIEWPORT_HEIGHT = 844
        const val SITE_ORIGIN = "https://datapipelines.co"

        /** 35 baseline marketing routes + 24 docs routes + the two 145 additions + the 173 comparison page. */
        const val MIN_ROUTES = 62
        const val MARKETING_ROUTES = 38

        /** A squeezed text block carries at least this much text in a sliver this thin and tall. */
        const val MIN_TEXT_CHARS = 12
        const val MAX_BLOCK_WIDTH_PX = 40
        const val MIN_BLOCK_HEIGHT_PX = 60

        /**
         * The three 160 conditions in one pass. `squeezed` reports outermost offenders only —
         * an element whose ANCESTOR is already reported adds no information — each labelled
         * with the nearest `id` ancestor so the page area is named.
         */
        val SWEEP_JS =
            """() => {
                 const vw = document.documentElement.clientWidth;
                 const docOver = document.documentElement.scrollWidth - vw;
                 const skip = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'LINK', 'META', 'HEAD', 'TITLE']);
                 const label = (e) => {
                   const cls = typeof e.className === 'string' && e.className.trim()
                     ? '.' + e.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
                   const self = e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + cls;
                   if (e.id) return self;
                   const host = e.closest('[id]');
                   return host ? self + ' in ' + host.tagName.toLowerCase() + '#' + host.id : self;
                 };
                 const all = Array.from(document.body.querySelectorAll('*'))
                   .filter(e => !skip.has(e.tagName));
                 const inSwipeRegion = (e) => {
                   for (let n = e; n && n !== document.documentElement; n = n.parentElement) {
                     const ox = getComputedStyle(n).overflowX;
                     if (ox === 'auto' || ox === 'scroll') return true;
                   }
                   return false;
                 };
                 const squeezedAll = all.filter(e => {
                   const r = e.getBoundingClientRect();
                   if (r.width >= $MAX_BLOCK_WIDTH_PX || r.height <= $MIN_BLOCK_HEIGHT_PX) return false;
                   const display = getComputedStyle(e).display;
                   if (display === 'inline' || display === 'none') return false;
                   return (e.innerText || '').trim().length >= $MIN_TEXT_CHARS;
                 });
                 const squeezed = squeezedAll
                   .filter(e => !squeezedAll.some(o => o !== e && o.contains(e)))
                   .map(label);
                 const wide = all
                   .filter(e => e.scrollWidth > vw && !inSwipeRegion(e))
                   .map(e => label(e) + ' (' + e.scrollWidth + 'px)');
                 return { squeezed: squeezed, wide: wide, docOver: docOver };
               }"""
    }
}
