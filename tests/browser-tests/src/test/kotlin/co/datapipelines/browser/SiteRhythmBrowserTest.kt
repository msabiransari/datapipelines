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
 *  - **(a)** every `.section` h2 → next-element gap equals the one head→body token
 *    (`--site-gap-head`: 24px desktop / 20px phone, ± 1px) — the rule that replaced the
 *    five different gaps the measurement found (0, 16, 20, 24 and 32px);
 *  - **(b)** every `.card` carries the token padding (24px from 64rem, 20px below);
 *  - **(c)** on `/how-it-works` no two cards sharing a grid row differ in height by more
 *    than 40% unless both are ≤ 260px — the stretch is gone (`align-items: start`) AND the
 *    90-word body budget keeps neighbours comparable;
 *  - **(d)** on `/` the proof strip's top edge is inside the fold (1440×1000 and 390×844) —
 *    the hero shot may never push the buyer numbers off the first screen;
 *  - **(e)** no page scrolls sideways (`scrollWidth == clientWidth` on the document) — the
 *    phone check that pins the "390 was the window-minimum artefact" finding.
 *
 * Anonymous throughout: the site's pages are public by construction, so no seeding.
 */
class SiteRhythmBrowserTest : BrowserSuite() {
    private val sitePages = listOf("/", "/how-it-works", "/demo-data", "/faq")
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

    @Test
    fun `the proof strip is above the fold and no page scrolls sideways`() {
        startTrace()
        val offenders = mutableListOf<String>()
        viewports.forEach { (w, h) ->
            page.setViewportSize(w, h)
            sitePages.forEach { route -> sideways(route, w, h, offenders) }
            stripAboveFold(w, h, offenders)
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

    /** (d) — on `/`, the strip's TOP edge must start inside the viewport. */
    private fun stripAboveFold(
        w: Int,
        h: Int,
        offenders: MutableList<String>,
    ) {
        page.navigate("$baseUrl/")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        val stripTop =
            (page.evaluate("() => { const s = document.querySelector('.strip'); return s ? s.getBoundingClientRect().top : -1 }") as Number)
                .toDouble()
        withClue("/ at ${w}x$h: no .strip rendered — the fold check would be vacuous") {
            (stripTop >= 0) shouldBe true
        }
        if (stripTop > h) offenders += "/ at ${w}x$h: proof strip starts at ${stripTop}px, below the ${h}px fold"
    }

    private companion object {
        const val TOLERANCE = 1.0
        const val DESKTOP_GAP = 24.0
        const val PHONE_GAP = 20.0
        const val LEDE_GAP = 16.0
        const val DESKTOP_CARD_PADDING = 24.0
        const val PHONE_CARD_PADDING = 20.0
        const val MAX_ROW_RATIO = 1.4
        const val SHORT_CARD_PX = 260.0

        /** Every `.section` h2 that has a following element sibling, with the rendered gap
         *  and whether that sibling is the section lede (the 16px carve-out). */
        private const val HEAD_GAPS_JS =
            """() => Array.from(document.querySelectorAll('.section h2'))
                 .map(h2 => ({ h2: h2, next: h2.nextElementSibling }))
                 .filter(function (x) { return x.next })
                 .map(function (x) {
                   const label = x.h2.id || (x.h2.textContent || '').trim().slice(0, 40);
                   const gap = Math.round(x.next.getBoundingClientRect().top - x.h2.getBoundingClientRect().bottom);
                   const lede = x.next.classList ? x.next.classList.contains('section-lede') : false;
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
