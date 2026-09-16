package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ReducedMotion
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The public header, in a real browser — 130's Product arms restored by 148 (#115: the seven
 * feature pages had moved to /explore and the footer with 145; the owner asked for the header
 * path back), on 145's five-page row.
 *
 *  - **(a) the row and the phone menu agree:** the wide row is the Product summary, then
 *    [PRIMARY_PATHS] — every one a registry page or the docs index — then the host-aware
 *    application entry (`/login`); the disclosure's links are [PRODUCT_PATHS] in order; the
 *    phone menu's group carries the same seven with the same labels and the same primaries
 *    follow it; on `/how-it-works` that link alone is `aria-current="page"` in both menus, and
 *    on `/semantic-layer` the disclosure's link is current AND its summary wears the mark;
 *  - **(b) the disclosure opens on click and keyboard, closes on Escape with focus on the
 *    summary, on a click outside, and when focus leaves it — never trapping focus:** at
 *    1280×900 Tab from the open summary lands on the first link; Tab past the seventh closes
 *    the panel and lands on the next row item;
 *  - **(c) one row that fits its box, at the breakpoint and above:** at 1024×768 (the width the
 *    wide row appears at), 1280×900 and 1440×1000, every `.nav-wide` item — the summary
 *    included — shares one top (± 1px), the header is the design's one-row height
 *    ([HEADER_HEIGHT_PX]) and the row's max-content width fits the container's content box
 *    under BOTH labels the layout can render (read from the template, never typed here);
 *  - **(d) the phone group is visible and operable, down to the shortest phone:** at 390×844 and
 *    320×568 the hamburger opens the panel, the group's heading labels it, all seven links are
 *    visible and each one can be brought inside the viewport (the panel scrolls; a panel
 *    hanging from a sticky header cannot be scrolled INTO), and a touch on a link navigates;
 *  - **(e) breakpoint and overflow coherence:** a disclosure opened at 1280 is closed after a
 *    resize to 390 and a phone menu opened at 390 is closed after a resize to 1280 (no orphan
 *    open state); with the relevant menu open, the document has no horizontal overflow at 320,
 *    390, 768 and 1440;
 *  - **(f) reduced motion:** under `prefers-reduced-motion: reduce` the chevron's transition is
 *    the design system's 0.01ms floor and the disclosure still opens and closes;
 *  - **(g) the seven are still in /explore and the footer:** the directory and the site map
 *    keep linking them — the header is an addition, not a move back.
 *
 * Anonymous throughout: the site is public.
 */
class SiteHeaderMenuBrowserTest : BrowserSuite() {
    @Test
    fun `the row leads with Product, the disclosure links the seven, the phone menu agrees and current pages are marked`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigate("/how-it-works")
        withClue("the row's summary reads Product") {
            page.locator(".nav-wide > .nav-menu > summary").textContent().trim() shouldBe "Product"
        }
        val row = links(".nav-wide > a")
        withClue("the row's links after the disclosure, in order") { row.map { it.first } shouldBe PRIMARY_PATHS + "/login" }
        val panel = links(".nav-menu > .nav-panel > a")
        withClue("the disclosure's links, in order") { panel.map { it.first } shouldBe PRODUCT_PATHS }
        panel.size shouldBe PRODUCT_PATHS.size
        panel.forEach { (path, label) -> withClue("$path has a label") { label.isNotBlank() shouldBe true } }

        val group = links(".nav-mobile .nav-group > a")
        withClue("the phone group is the same seven, same labels, same order") { group shouldBe panel }
        val phone = links(".nav-mobile .nav-panel > a")
        withClue("the phone menu's primaries follow the group") { phone.map { it.first } shouldBe PRIMARY_PATHS + "/login" }
        withClue("exactly the page being read is current, in both menus") {
            currentHrefs(".site-header a") shouldBe listOf("/how-it-works", "/how-it-works")
        }

        navigate("/semantic-layer")
        withClue("on a feature page, its link is current in both menus and nothing else is") {
            currentHrefs(".site-header a") shouldBe listOf("/semantic-layer", "/semantic-layer")
        }
        val summaryWeight = page.evaluate("() => getComputedStyle(document.querySelector('.nav-menu > summary')).fontWeight") as String
        val boldToken = page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--weight-bold').trim()") as String
        withClue("the summary wears the current mark (weight $summaryWeight, bold token $boldToken)") { summaryWeight shouldBe boldToken }
    }

    @Test
    fun `the disclosure opens by click and keyboard, closes on Escape, outside click and focus leaving, and never traps focus`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigate("/")
        withClue("closed on load") { page.locator(".nav-menu[open]").count() shouldBe 0 }

        page.click(".nav-menu > summary")
        page.locator(".nav-menu[open]").waitFor()
        val first = page.locator(".nav-menu > .nav-panel > a").first()
        withClue("the panel is visible once open") { first.isVisible() shouldBe true }
        page.keyboard().press("Tab")
        withClue("Tab from the summary lands on the panel's first link") { activeHref() shouldBe PRODUCT_PATHS.first() }
        val hidden = page.locator(".nav-menu > .nav-panel > a").all().filterNot { it.isVisible() }
        withClue("every link is visible once open") { hidden.shouldBeEmpty() }

        page.keyboard().press("Escape")
        page.locator(".nav-menu:not([open])").waitFor()
        withClue("Escape returns focus to the summary") { activeTag() shouldBe "SUMMARY" }

        // Keyboard open: Enter on the focused summary is the element's own behaviour.
        page.keyboard().press("Enter")
        page.locator(".nav-menu[open]").waitFor()
        repeat(PRODUCT_PATHS.size) { page.keyboard().press("Tab") }
        withClue("seven Tabs reach the seventh link") { activeHref() shouldBe PRODUCT_PATHS.last() }
        page.keyboard().press("Tab")
        page.locator(".nav-menu:not([open])").waitFor()
        withClue("Tab past the last link leaves the panel closed behind, on the next row item") {
            activeHref() shouldBe PRIMARY_PATHS.first()
        }

        page.click(".nav-menu > summary")
        page.locator(".nav-menu[open]").waitFor()
        // Top-left corner of the hero title: page content, clear of the open panel's box.
        page.locator("main h1").first().click(Locator.ClickOptions().setPosition(8.0, 8.0))
        page.locator(".nav-menu:not([open])").waitFor()
        withClue("a click outside closes the panel") { page.locator(".nav-menu[open]").count() shouldBe 0 }
    }

    @Test
    fun `the header row is one row that fits its box at the breakpoint and above under the widest last label`() {
        startTrace()
        val lastLabels = lastItemLabels()
        withClue("both last-item labels read from the layout") { lastLabels.size shouldBe 2 }
        val offenders = mutableListOf<String>()
        var measured = 0
        WIDE_VIEWPORTS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            navigate("/")
            withClue("${w}x$h: the wide row is the one shown") { page.locator(".nav-wide").isVisible() shouldBe true }
            lastLabels.forEach { label ->
                page.evaluate("(label) => { document.querySelector('.nav-wide > a:last-child > span').textContent = label }", label)
                @Suppress("UNCHECKED_CAST")
                val row = page.evaluate(ROW_JS) as Map<String, Any>
                measured++
                val where = "${w}x$h, last='$label'"

                @Suppress("UNCHECKED_CAST")
                val items = row["items"] as List<Map<String, Any>>
                withClue("$where: the row has the summary and the links") { items.size shouldBe PRIMARY_PATHS.size + 2 }
                val tops = items.map { (it["top"] as Number).toDouble() }
                if (tops.max() - tops.min() > TOLERANCE) {
                    offenders += "$where: items on more than one line — tops ${items.map { it["label"] to it["top"] }}"
                }
                val height = (row["headerHeight"] as Number).toDouble()
                if (Math.abs(height - HEADER_HEIGHT_PX) > TOLERANCE) {
                    offenders += "$where: header height $height px (the design's one row: $HEADER_HEIGHT_PX)"
                }
                val box = (row["box"] as Number).toDouble()
                val need = (row["need"] as Number).toDouble()
                val spare = "%.1f".format(box - need)
                println("148 (c) $where: row needs ${"%.1f".format(need)}px of a ${"%.0f".format(box)}px box ($spare px spare)")
                if (need > box) offenders += "$where: the row needs ${"%.1f".format(need)}px, the box is ${"%.0f".format(box)}px"
            }
        }
        withClue("the sweep measured every combination") { measured shouldBe WIDE_VIEWPORTS.size * lastLabels.size }
        withClue("header row offenders") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `the phone group is labelled, every link reachable inside the viewport, and a touch on a link navigates`() {
        startTrace()
        val touch = newBrowserContext(Browser.NewContextOptions().setHasTouch(true))
        try {
            val p = touch.newPage()
            PHONE_VIEWPORTS.forEach { (w, h) ->
                p.setViewportSize(w, h)
                p.navigate("$baseUrl/")
                p.waitForLoadState(LoadState.NETWORKIDLE)
                withClue("${w}x$h: the wide row is hidden on a phone") { p.locator(".nav-wide").isVisible() shouldBe false }
                p.locator(".nav-mobile > summary").tap()
                p.locator(".nav-mobile[open]").waitFor()
                val heading = p.locator(".nav-mobile .nav-group > .menu-heading")
                withClue("${w}x$h: the group's heading is visible") { heading.isVisible() shouldBe true }
                heading.textContent().trim() shouldBe "Product"
                withClue("${w}x$h: the group is labelled by its heading") {
                    p.locator(".nav-mobile .nav-group").getAttribute("aria-labelledby") shouldBe heading.getAttribute("id")
                }
                val groupLinks = p.locator(".nav-mobile .nav-group > a").all()
                withClue("${w}x$h: seven links in the group") { groupLinks.size shouldBe PRODUCT_PATHS.size }
                withClue("${w}x$h: every group link is visible") { groupLinks.filterNot { it.isVisible() }.shouldBeEmpty() }
                val outside =
                    (groupLinks + p.locator(".nav-mobile .nav-panel > a").all()).mapNotNull { link ->
                        link.scrollIntoViewIfNeeded()
                        @Suppress("UNCHECKED_CAST")
                        val r = link.evaluate(LINK_BOX_JS) as Map<String, Any>
                        val top = (r["top"] as Number).toDouble()
                        val bottom = (r["bottom"] as Number).toDouble()
                        if (top < 0 || bottom > h) "${r["href"]} at $top..$bottom (viewport $h)" else null
                    }
                withClue("${w}x$h: every link can be brought inside the viewport") { outside.shouldBeEmpty() }
                val panel = p.locator(".nav-mobile > .nav-panel")
                val panelBottom = (panel.evaluate("e => e.getBoundingClientRect().bottom") as Number).toDouble()
                withClue("${w}x$h: the panel ends inside the viewport (it scrolls, it does not hang off the screen)") {
                    (panelBottom <= h + TOLERANCE) shouldBe true
                }
            }
            // Touch: the second link, after the panel is opened by touch.
            val target = p.locator(".nav-mobile .nav-group > a[href='${PRODUCT_PATHS[1]}']")
            target.scrollIntoViewIfNeeded()
            target.tap()
            p.waitForURL("$baseUrl${PRODUCT_PATHS[1]}")
            withClue("a touch on a group link navigates to it") { p.url() shouldBe "$baseUrl${PRODUCT_PATHS[1]}" }
        } finally {
            touch.close()
        }
    }

    @Test
    fun `a menu opened in one layout is closed in the other, and an open menu never overflows sideways`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigate("/")
        page.click(".nav-menu > summary")
        page.locator(".nav-menu[open]").waitFor()
        page.setViewportSize(390, 844)
        // The folded-away row is display:none, so the wait is for the attribute, not visibility.
        page.locator(".nav-menu:not([open])").waitFor(ATTACHED)
        withClue("the disclosure closed when the row folded away") { page.locator(".nav-menu[open]").count() shouldBe 0 }

        page.click(".nav-mobile > summary")
        page.locator(".nav-mobile[open]").waitFor()
        page.setViewportSize(1280, 900)
        page.locator(".nav-mobile:not([open])").waitFor(ATTACHED)
        withClue("the phone menu closed when the row unfolded") { page.locator(".nav-mobile[open]").count() shouldBe 0 }

        val offenders = mutableListOf<String>()
        OVERFLOW_VIEWPORTS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            navigate("/")
            val selector = if (page.locator(".nav-wide").isVisible()) ".nav-menu" else ".nav-mobile"
            page.click("$selector > summary")
            page.locator("$selector[open]").waitFor()
            val over = (page.evaluate(OVERFLOW_JS) as Number).toLong()
            if (over > 0) offenders += "${w}x$h with $selector open: ${over}px of horizontal overflow"
        }
        withClue("overflow with a menu open") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `under reduced motion the chevron does not animate and the disclosure still opens and closes`() {
        startTrace()
        val reduced = newBrowserContext(Browser.NewContextOptions().setReducedMotion(ReducedMotion.REDUCE))
        try {
            val p = reduced.newPage()
            p.setViewportSize(1280, 900)
            p.navigate("$baseUrl/")
            p.waitForLoadState(LoadState.NETWORKIDLE)
            val duration = seconds(p.evaluate(CHEVRON_DURATION_JS) as String)
            // motion.css's reduced-motion rule forces 0.01ms (so transitionend still fires), not a
            // literal zero: the assertion is the design system's floor, not the string "0s".
            withClue("the chevron's transition is the reduced-motion floor ($duration s)") {
                (duration <= REDUCED_MOTION_FLOOR_S) shouldBe true
            }
            p.click(".nav-menu > summary")
            p.locator(".nav-menu[open]").waitFor()
            withClue("open") { p.locator(".nav-menu > .nav-panel > a").first().isVisible() shouldBe true }
            p.keyboard().press("Escape")
            p.locator(".nav-menu:not([open])").waitFor()
        } finally {
            reduced.close()
        }
        // The positive control on the same measurement: without the preference the chevron has a duration.
        page.setViewportSize(1280, 900)
        navigate("/")
        val live = seconds(page.evaluate(CHEVRON_DURATION_JS) as String)
        withClue("without the preference the chevron transition has a real duration ($live s)") {
            (live > REDUCED_MOTION_FLOOR_S) shouldBe true
        }
    }

    @Test
    fun `the seven feature pages are still linked from explore and from the footer`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigate("/explore")
        val explore = links("main a[href]").map { it.first }.toSet()
        withClue("/explore links the seven") { explore shouldContainAll PRODUCT_PATHS }
        val footer = links("footer a[href]").map { it.first }.toSet()
        withClue("the footer links the seven") { footer shouldContainAll PRODUCT_PATHS }
        // Non-vacuity: seven, not "at least one".
        PRODUCT_PATHS.size shouldBe 7
    }

    private fun navigate(path: String) {
        page.navigate("$baseUrl$path")
        page.waitForLoadState(LoadState.NETWORKIDLE)
    }

    /** `(href, label)` for every anchor the selector matches, in DOM order. */
    private fun links(selector: String): List<Pair<String, String>> = links(page, selector)

    private fun links(
        on: Page,
        selector: String,
    ): List<Pair<String, String>> {
        @Suppress("UNCHECKED_CAST")
        val raw =
            on.evaluate(
                "(sel) => Array.from(document.querySelectorAll(sel))" +
                    ".map(a => [a.getAttribute('href') || '', (a.textContent || '').trim().replace(/\\s+/g, ' ')])",
                selector,
            ) as List<List<String>>
        return raw.map { it[0] to it[1] }
    }

    private fun currentHrefs(selector: String): List<String> =
        page.locator("$selector[aria-current=\"page\"]").all().map { it.getAttribute("href") }

    /** A computed `transitionDuration` (`0.15s`, `1e-05s`, `0s`) as seconds. */
    private fun seconds(duration: String): Double = duration.removeSuffix("s").toDouble()

    private fun activeHref(): String? =
        page.evaluate("() => document.activeElement && document.activeElement.getAttribute('href')") as String?

    private fun activeTag(): String? = page.evaluate("() => document.activeElement && document.activeElement.tagName") as String?

    /**
     * The two labels the layout renders for the row's last item (`th:text="${publicOrigin} ? 'A' : 'B'"`),
     * read from the template on the app's classpath. The browser suite runs on localhost, which is
     * the deployment branch; the public origin's label is wider, and the row has to fit under both.
     */
    private fun lastItemLabels(): List<String> {
        val layout =
            checkNotNull(javaClass.classLoader.getResource("templates/site/_layout.html")) { "the site layout is on the classpath" }
                .readText()
        return LAST_LABEL
            .findAll(layout)
            .flatMap { m -> listOf(m.groupValues[1], m.groupValues[2]) }
            .distinct()
            .toList()
    }

    private companion object {
        /** The five primary pages and the docs, in the header's order (145 §2). */
        val PRIMARY_PATHS = listOf("/how-it-works", "/use-cases", "/pricing", "/explore", "/docs")

        /** The seven, in the header's order (130, restored by 148). Existence is proven against the rendered registry, not assumed. */
        val PRODUCT_PATHS =
            listOf("/semantic-layer", "/dp-lake", "/federated-query", "/text-to-sql-agent", "/mcp-tools", "/demo-data", "/security")

        /** The breakpoint width (`--site-nav-wide-min` = 64rem = 1024px) and 145's two wide widths. */
        val WIDE_VIEWPORTS = listOf(1024 to 768, 1280 to 900, 1440 to 1000)
        val PHONE_VIEWPORTS = listOf(390 to 844, 320 to 568)
        val OVERFLOW_VIEWPORTS = listOf(320 to 568, 390 to 844, 768 to 1024, 1440 to 900)
        const val TOLERANCE = 1.0

        /** motion.css under `prefers-reduced-motion: reduce`: every transition is 0.01ms. */
        const val REDUCED_MOTION_FLOOR_S = 0.00001
        val ATTACHED: Locator.WaitForOptions = Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED)

        /** `--site-header-height` (4.5rem = 72px) plus the header's 1px hairline. */
        const val HEADER_HEIGHT_PX = 73.0

        val LAST_LABEL = Regex("""th:text="\$\{publicOrigin} \? '([^']+)' : '([^']+)'"""")

        /** A link's box against the viewport, and its href for the report. */
        const val LINK_BOX_JS =
            "e => { const r = e.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, href: e.getAttribute('href') } }"
        const val OVERFLOW_JS = "() => document.documentElement.scrollWidth - document.documentElement.clientWidth"

        /** The chevron's transition duration — `0s` under reduced motion (motion.css zeroes the token). */
        const val CHEVRON_DURATION_JS =
            "() => getComputedStyle(document.querySelector('.nav-menu > summary'), '::after').transitionDuration"

        /**
         * The row as laid out, and the row as it WANTS to be: a hidden `max-content` clone of
         * `.header-inner` lays every flex item out at its unshrunk width with nothing wrapping;
         * its width against the real content box is the fit. Padding is zeroed on the clone so
         * both numbers are content-box numbers. Items are the links AND the disclosure's summary.
         */
        const val ROW_JS =
            """() => {
                 const inner = document.querySelector('.header-inner');
                 const cs = getComputedStyle(inner);
                 const box = inner.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
                 const clone = inner.cloneNode(true);
                 clone.style.cssText = 'position:absolute;visibility:hidden;width:max-content;max-width:none;padding:0;left:0;top:0';
                 document.body.appendChild(clone);
                 const need = clone.getBoundingClientRect().width;
                 clone.remove();
                 const items = Array.from(document.querySelectorAll('.nav-wide > a, .nav-wide > details > summary')).map(e => {
                   const r = e.getBoundingClientRect();
                   return { label: (e.textContent || '').trim().replace(/\s+/g, ' '), top: r.top };
                 });
                 return {
                   box: box, need: need, items: items,
                   headerHeight: document.querySelector('header.site-header').getBoundingClientRect().height
                 };
               }"""
    }
}
