package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 119 §A.8 — the site's vertical rhythm, in a real browser. The unit guards read markup;
 * this reads the RENDERED page the way the owner's 2026-09-11 measurement did
 * (`getComputedStyle`/`getBoundingClientRect`, Playwright, real viewports), because the
 * spacing defects that round found were only visible as computed geometry:
 *
 *  - **(a)** every SECTION headline's gap to its body equals the one head→body token
 *    (`--site-gap-head`: 44px at 1440 / 28px at 390, ± 1px) — the rule that replaced the
 *    five different gaps the measurement found (0, 16, 20, 24 and 32px). 145 scopes the
 *    sweep to the structures the rule governs: an h2 that is a `.container`'s own child,
 *    the FAQ block's h2, and the approved preview's `.section-title` opener (whose h2 → p
 *    gap is the LEDE gap, 16px, like a `.section-lede`). A walkthrough step's or a card's
 *    own h2 has its component's rhythm and is not a section headline;
 *  - **(b)** every `.card` carries the token padding (24px from 64rem, 20px below);
 *  - **(c)** on `/how-it-works` no two cards sharing a grid row differ in height by more
 *    than 40% unless both are ≤ 260px — the stretch is gone (`align-items: start`) AND the
 *    90-word body budget keeps neighbours comparable;
 *  - **(d)** on `/` the worked example (`#example`, the recorded run) starts inside the
 *    fold at 1440×1000, and at 390×844 the hero's primary call to action does — the hero
 *    may never push what the buyer came for off the first screen (145: the proof strip
 *    became the worked example; the invariant is the same);
 *  - **(e)** no page scrolls sideways (`scrollWidth == clientWidth` on the document) — the
 *    phone check that pins the "390 was the window-minimum artefact" finding.
 *
 * Anonymous throughout: the site's pages are public by construction, so no seeding.
 */
class SiteRhythmBrowserTest : BrowserSuite() {
    private val sitePages = listOf("/", "/how-it-works", "/demo-data", "/faq", "/use-cases", "/pricing", "/explore")
    private val viewports = listOf(1440 to 1000, 390 to 844)

    @Test
    fun `every section's head gap equals the token, on every page at both widths`() {
        startTrace()
        val offenders = mutableListOf<String>()
        viewports.forEach { (w, h) ->
            val expected = if (w >= 768) DESKTOP_GAP else PHONE_GAP
            page.setViewportSize(w, h)
            sitePages.forEach { route -> headGaps(route, w, h, expected, offenders) }
        }
        withClue("head gaps off the token") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `cards carry the token padding and no grid row stretches a short card tall`() {
        startTrace()
        val offenders = mutableListOf<String>()
        viewports.forEach { (w, h) ->
            val expectedPadding = if (w >= 1024) DESKTOP_CARD_PADDING else PHONE_CARD_PADDING
            page.setViewportSize(w, h)
            sitePages.forEach { route -> cardPadding(route, w, h, expectedPadding, offenders) }
            rowStretch(offenders, w, h)
        }
        withClue("card padding / row-stretch offenders") { offenders.shouldBeEmpty() }
    }

    /**
     * (f) — **no two blocks of text touch, on any page the site links, at both widths.** The
     * owner's screenshots (2026-09-14): a paragraph on a button row, a quote on a box, a code
     * block on a paragraph, two paragraphs with no gap — 90 such pairs across the 27 pages,
     * every one at 0px, because the reset zeroes margins and the stylesheet spaced blocks one
     * selector at a time. The pages are DISCOVERED from the header and footer of `/`, so a
     * page added to the site is measured the day it is linked. The walk: every block-level
     * element's block-level children in RENDERED order (a phone hero puts the strip above the
     * figure with `order`; row-flex and grid parents skipped — their gap is the gap property),
     * adjacent pairs where at least one is a text block, rendered gap < 8px.
     */
    @Test
    fun `no two blocks of text touch on any linked page at both widths`() {
        startTrace()
        page.navigate("$baseUrl/")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        @Suppress("UNCHECKED_CAST")
        val routes = (page.evaluate(LINKED_ROUTES_JS) as List<String>).distinct().sorted()
        withClue("route discovery is non-vacuous") { (routes.size >= MIN_LINKED_ROUTES) shouldBe true }
        val offenders = mutableListOf<String>()
        var pairsSeen = 0L
        viewports.forEach { (w, h) ->
            page.setViewportSize(w, h)
            routes.forEach { route ->
                page.navigate("$baseUrl$route")
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                @Suppress("UNCHECKED_CAST")
                val result = page.evaluate(TIGHT_PAIRS_JS) as Map<String, Any>
                pairsSeen += (result["pairs"] as Number).toLong()
                @Suppress("UNCHECKED_CAST")
                (result["tight"] as List<String>).forEach { offenders += "$route at ${w}x$h: $it" }
            }
        }
        withClue("the walk measured something") { (pairsSeen > 0) shouldBe true }
        // Every offender on stdout: the assertion below names only the first.
        offenders.forEach { println("119 (f) $it") }
        withClue("block siblings touching (< 8px)") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `the worked example and the primary call are above the fold and no page scrolls sideways`() {
        startTrace()
        val offenders = mutableListOf<String>()
        viewports.forEach { (w, h) ->
            page.setViewportSize(w, h)
            sitePages.forEach { route -> sideways(route, w, h, offenders) }
            aboveFold(w, h, offenders)
        }
        withClue("fold / overflow offenders") { offenders.shouldBeEmpty() }
    }

    /** (a) on one page at one width. The lede carve-out is the RULE, not an exception:
     *  a `.section-lede` deliberately keeps 16px under its h2 and passes the head gap on
     *  to ITS first sibling (119 §A.1) — so an h2 → lede gap asserts 16, a plain h2 →
     *  body gap asserts the token, and a lede → body gap asserts the token too. */
    private fun headGaps(
        route: String,
        w: Int,
        h: Int,
        expected: Double,
        offenders: MutableList<String>,
    ) {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        @Suppress("UNCHECKED_CAST")
        val gaps = page.evaluate(HEAD_GAPS_JS) as List<Map<String, Any>>
        withClue("$route at ${w}x$h: no section h2 with a following element — the sweep checked nothing") {
            gaps.shouldNotBeEmpty()
        }
        gaps.forEach { g ->
            val gap = (g["gap"] as Number).toDouble()
            val isLede = g["nextIsLede"] as Boolean
            val want = if (isLede) LEDE_GAP else expected
            if (Math.abs(gap - want) > TOLERANCE) {
                offenders += "$route at ${w}x$h: h2 '${g["id"]}' -> next gap $gap px (want $want)"
            }
        }
    }

    /** (b) on one page at one width. */
    private fun cardPadding(
        route: String,
        w: Int,
        h: Int,
        expected: Double,
        offenders: MutableList<String>,
    ) {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        @Suppress("UNCHECKED_CAST")
        val paddings =
            page.evaluate(
                "() => Array.from(document.querySelectorAll('.card'))" +
                    ".map(c => parseFloat(getComputedStyle(c).paddingTop))",
            ) as List<Double>
        paddings.forEachIndexed { i, p ->
            if (Math.abs(p - expected) > TOLERANCE) {
                offenders += "$route at ${w}x$h: card $i padding ${p}px (token $expected)"
            }
        }
    }

    /** (c) — the how-it-works grids: rows whose tallest card stretches far past the shortest. */
    private fun rowStretch(
        offenders: MutableList<String>,
        w: Int,
        h: Int,
    ) {
        page.navigate("$baseUrl/how-it-works")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        @Suppress("UNCHECKED_CAST")
        val rows = page.evaluate(ROW_HEIGHTS_JS) as List<List<List<Any>>>
        withClue("how-it-works at ${w}x$h: no grid rows found — the sweep checked nothing") {
            rows.shouldNotBeEmpty()
        }
        rows.forEach { bucket ->
            if (bucket.size < 2) return@forEach
            val heights: List<Double> = bucket.map { ((it as List<*>)[0] as Number).toDouble() }
            val labels: List<String> = bucket.map { (it as List<*>)[1].toString() }
            val shortest = heights.min()
            val tallest = heights.max()
            val stretch = tallest / shortest
            if (stretch > MAX_ROW_RATIO && shortest > SHORT_CARD_PX) {
                offenders +=
                    "how-it-works at ${w}x$h: cards $labels heights $heights — tallest is ${"%.2f".format(stretch)}x the shortest"
            }
        }
    }

    /** (e) on one page at one width. */
    private fun sideways(
        route: String,
        w: Int,
        h: Int,
        offenders: MutableList<String>,
    ) {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        // The whole document is the scroll container on the site (no app shell); any pixel
        // of horizontal overflow is a defect at either width.
        val over =
            (page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number)
                .toLong()
        if (over > 0) offenders += "$route at ${w}x$h: $over px of horizontal overflow — ${culprits()}"
    }

    /** The elements whose right edge is past the viewport — "356px over" is not actionable; the chain is. */
    private fun culprits(): String =
        page
            .evaluate(
                """
                () => Array.from(document.querySelectorAll('*'))
                  .filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
                  .sort((a, b) => b.getBoundingClientRect().right - a.getBoundingClientRect().right)
                  .slice(0, 6)
                  .map(e => {
                    const id = n => n.tagName + (n.className && typeof n.className === 'string' && n.className.trim()
                      ? '.' + n.className.trim().split(/\s+/).join('.') : '');
                    const chain = [];
                    for (let n = e; n && chain.length < 4; n = n.parentElement) chain.push(id(n));
                    const r = e.getBoundingClientRect();
                    return chain.join(' < ') + ' [' + Math.round(r.left) + '..' + Math.round(r.right) + ']'
                  })
                  .join(' | ')
                """.trimIndent(),
            ).toString()

    /** (d) — on `/`, the worked example's TOP edge (wide) or the primary call's (phone) must start inside the viewport. */
    private fun aboveFold(
        w: Int,
        h: Int,
        offenders: MutableList<String>,
    ) {
        page.navigate("$baseUrl/")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        val selector = if (w >= 768) "#example" else ".hero-copy .btn-primary"
        val top =
            (
                page.evaluate(
                    "(sel) => { const s = document.querySelector(sel); return s ? s.getBoundingClientRect().top : -1 }",
                    selector,
                ) as Number
            ).toDouble()
        withClue("/ at ${w}x$h: no $selector rendered — the fold check would be vacuous") {
            (top >= 0) shouldBe true
        }
        if (top > h) offenders += "/ at ${w}x$h: $selector starts at ${top}px, below the ${h}px fold"
    }

    private companion object {
        const val TOLERANCE = 1.0
        const val MIN_LINKED_ROUTES = 20

        /** Same-origin paths the header and footer of `/` link, fragments stripped, `/login` excluded (the app, not the site). */
        private const val LINKED_ROUTES_JS =
            """() => Array.from(document.querySelectorAll('header a[href], footer a[href]'))
                 .map(a => a.getAttribute('href') || '')
                 .filter(h => h.startsWith('/') && !h.startsWith('//'))
                 .map(h => h.split('#')[0].split('?')[0])
                 .filter(h => h && h !== '/login' && !h.startsWith('/docs'))
                 .concat(['/'])"""

        /** Adjacent block siblings inside <main>, at least one a text block, rendered closer than 8px. */
        private const val TIGHT_PAIRS_JS =
            """() => {
                 const TEXTY = new Set(['P','BLOCKQUOTE','PRE','UL','OL','TABLE','H1','H2','H3','H4','FIGURE','DL']);
                 const main = document.querySelector('main') || document.body; const tight = []; let pairs = 0;
                 const isBlock = el => ['block','flex','grid','table','list-item'].includes(getComputedStyle(el).display) && el.getBoundingClientRect().height > 0;
                 for (const el of main.querySelectorAll('*')) {
                   if (!isBlock(el)) continue;
                   const cs = getComputedStyle(el);
                   if (cs.display === 'flex' && cs.flexDirection.startsWith('row')) continue;
                   if (cs.display === 'grid') continue;
                   const kids = Array.from(el.children).filter(k => { const c = getComputedStyle(k); return c.position !== 'absolute' && c.position !== 'fixed' && isBlock(k); }).sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
                   for (let i = 1; i < kids.length; i++) {
                     const a = kids[i - 1], b = kids[i];
                     if (!(TEXTY.has(a.tagName) || TEXTY.has(b.tagName))) continue;
                     pairs++;
                     const gap = b.getBoundingClientRect().top - a.getBoundingClientRect().bottom;
                     if (gap < 8) {
                       const name = x => x.tagName + (x.className ? '.' + String(x.className).trim().split(/\s+/)[0] : '');
                       tight.push(name(el) + ' :: ' + name(a) + ' > ' + name(b) + ' gap=' + Math.round(gap) + 'px "' + (b.textContent || '').trim().slice(0, 40) + '"');
                     }
                   }
                 }
                 return { pairs: pairs, tight: tight };
               }"""
        const val DESKTOP_GAP = 44.0
        const val PHONE_GAP = 28.0
        const val LEDE_GAP = 16.0
        const val DESKTOP_CARD_PADDING = 24.0
        const val PHONE_CARD_PADDING = 20.0
        const val MAX_ROW_RATIO = 1.4
        const val SHORT_CARD_PX = 260.0

        /** Every section headline that has a following element sibling — a `.container`'s own
         *  h2, the FAQ block's, or a `.section-title`'s — with the rendered gap and whether
         *  that sibling is a lede (the `.section-lede` carve-out, or any `.section-title` p). */
        private const val HEAD_GAPS_JS =
            """() => Array.from(document.querySelectorAll('.section .container > h2, .faq-block > h2, .faq-group > h2, .section-title > h2'))
                 .map(h2 => ({ h2: h2, next: h2.nextElementSibling }))
                 .filter(function (x) { return x.next })
                 .map(function (x) {
                   const label = x.h2.id || (x.h2.textContent || '').trim().slice(0, 40);
                   const gap = Math.round(x.next.getBoundingClientRect().top - x.h2.getBoundingClientRect().bottom);
                   const inTitle = x.h2.parentElement.classList.contains('section-title') && x.next.tagName === 'P';
                   const lede = inTitle || (x.next.classList ? x.next.classList.contains('section-lede') : false);
                   return { id: label, gap: gap, nextIsLede: lede };
                 })"""

        /** Card heights (with the card's heading, so a failure names its cards) clustered
         *  into grid rows by their top offset (± 3px). */
        private const val ROW_HEIGHTS_JS =
            """() => {
                 const rows = [];
                 document.querySelectorAll('.feature-group .features-grid').forEach(grid => {
                   const buckets = [];
                   Array.from(grid.querySelectorAll(':scope > .card')).forEach(card => {
                     const r = card.getBoundingClientRect();
                     const label = (card.querySelector('h3') ? card.querySelector('h3').textContent : '?').trim().slice(0, 24);
                     const bucket = buckets.find(b => Math.abs(b.top - r.top) <= 3);
                     if (bucket) bucket.cards.push([Math.round(r.height), label]);
                     else buckets.push({ top: r.top, cards: [[Math.round(r.height), label]] });
                   });
                   buckets.forEach(b => rows.push(b.cards));
                 });
                 return rows;
               }"""
    }
}
