package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 130 — the header's Product menu, in a real browser (owner R13, 2026-09-14: the
 * differentiator pages were footer-only; a disclosure in the header carries them).
 *
 *  - **(a) the seven are the seven:** the Product panel's links equal, in order, [PRODUCT_PATHS],
 *    every one a registry page (the footer is rendered from `SitePages.NAV`, so a page dropped
 *    from the registry vanishes from the footer and this arm names it), and the phone menu's
 *    Product group carries the same seven with the same labels — two menus, one list;
 *  - **(b) open, close, close:** click the summary → the panel is visible and Tab lands on its
 *    first link; Escape → closed and focus is back on the summary; open again and click the
 *    page outside → closed;
 *  - **(c) one row, with a three-digit star count:** at 1280×900 and 1440×1000, every `.nav-wide`
 *    item shares one top (± 1px), the header is the one-row height it has on `origin/main`
 *    (`04d6baec`: 61px, both widths), AND the row's max-content width fits the container's content
 *    box. That last clause is the guard T238 asked for and did not get: the row's budget is
 *    the container's 1152px content box at BOTH widths (`--container-xl` caps it), and when
 *    the row overflows it, nothing wraps or grows — the theme toggle folds to two lines inside
 *    its 2.75rem item height and the brand's icon shrinks, so height and tops stay exactly as
 *    they were. Measured on `origin/main` with `999` stars: 14px over. The count is set to `999`
 *    in the DOM (the model attribute is a build-time constant), the toggle is cycled through
 *    its three labels (the widest is `Theme: Light`), and the last item is measured under BOTH
 *    labels the layout can render (`Try the live demo` on the public origin, `Sign in` on a
 *    deployment — read from the template, never typed here);
 *  - **(d) the phone menu carries the group:** at 390×844 the hamburger opens and the seven are
 *    inside it, under the `Product` heading that labels the group;
 *  - **(e) an unopened disclosure changes nothing below the header:** the proof strip's top on `/`
 *    is the same with the `<details>` in the DOM and with it removed, and inside the fold at
 *    both of `SiteRhythmBrowserTest`'s viewports.
 *
 * Anonymous throughout: the site is public.
 */
class SiteHeaderMenuBrowserTest : BrowserSuite() {
    @Test
    fun `the Product panel links the seven registry pages in order and the phone group agrees`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigateHome()
        val panel = links(".nav-menu > .nav-panel > a")
        withClue("the Product panel's links, in order") { panel.map { it.first } shouldBe PRODUCT_PATHS }
        // Non-vacuity, stated: seven, not "at least one".
        panel.size shouldBe PRODUCT_PATHS.size
        val footer = links("footer a[href]").map { it.first }.toSet()
        withClue("every Product link is a registry page (the footer renders SitePages.NAV)") {
            footer shouldContainAll PRODUCT_PATHS
        }
        val group = links(".nav-mobile .nav-group > a")
        withClue("the phone menu's Product group is the same seven, same labels, same order") { group shouldBe panel }
        panel.forEach { (path, label) -> withClue("$path has a label") { label.isNotBlank() shouldBe true } }
    }

    @Test
    fun `the disclosure opens on click, closes on Escape with focus on the summary, and closes on a click outside`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigateHome()
        withClue("closed on load") { page.locator(".nav-menu[open]").count() shouldBe 0 }

        page.click(".nav-menu > summary")
        page.locator(".nav-menu[open]").waitFor()
        val first = page.locator(".nav-menu > .nav-panel > a").first()
        withClue("the panel is visible once open") { first.isVisible() shouldBe true }
        page.keyboard().press("Tab")
        withClue("Tab from the summary lands on the panel's first link") {
            activeHref() shouldBe PRODUCT_PATHS.first()
        }

        page.keyboard().press("Escape")
        page.locator(".nav-menu:not([open])").waitFor()
        withClue("Escape returns focus to the summary") { activeTag() shouldBe "SUMMARY" }

        page.click(".nav-menu > summary")
        page.locator(".nav-menu[open]").waitFor()
        // Top-left corner of the hero title: page content, and clear of the open panel's box.
        page.locator("main h1").first().click(Locator.ClickOptions().setPosition(8.0, 8.0))
        page.locator(".nav-menu:not([open])").waitFor()
        withClue("a click outside closes the panel") { page.locator(".nav-menu[open]").count() shouldBe 0 }
    }

    @Test
    fun `the header row is one row that fits its box at both widths with a three-digit star count`() {
        startTrace()
        val lastLabels = lastItemLabels()
        withClue("both last-item labels read from the layout") { lastLabels.size shouldBe 2 }
        val offenders = mutableListOf<String>()
        var measured = 0
        WIDE_VIEWPORTS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            navigateHome()
            page.evaluate("() => document.querySelectorAll('.nav-star-count').forEach(e => { e.textContent = '$STARS' })")
            lastLabels.forEach { label ->
                page.evaluate("(label) => { const a = document.querySelector('.nav-wide > a:last-child'); a.textContent = label }", label)
                // The toggle cycles auto → light → dark on click; measure the row under each label.
                repeat(TOGGLE_MODES) {
                    @Suppress("UNCHECKED_CAST")
                    val row = page.evaluate(ROW_JS) as Map<String, Any>
                    measured++
                    val where = "${w}x$h, last='$label', toggle='${row["toggle"]}'"

                    @Suppress("UNCHECKED_CAST")
                    val items = row["items"] as List<Map<String, Any>>
                    withClue("$where: the row has items") { items.isNotEmpty() shouldBe true }
                    val tops = items.map { (it["top"] as Number).toDouble() }
                    if (tops.max() - tops.min() > TOLERANCE) {
                        offenders += "$where: items on more than one line — tops ${items.map { it["label"] to it["top"] }}"
                    }
                    val height = (row["headerHeight"] as Number).toDouble()
                    if (Math.abs(height - HEADER_HEIGHT_PX) > TOLERANCE) {
                        offenders += "$where: header height $height px (one row on origin/main: $HEADER_HEIGHT_PX)"
                    }
                    val box = (row["box"] as Number).toDouble()
                    val need = (row["need"] as Number).toDouble()
                    val spare = "%.1f".format(box - need)
                    println("130 (c) $where: row needs ${"%.1f".format(need)}px of a ${"%.0f".format(box)}px box ($spare px spare)")
                    if (need > box) {
                        offenders += "$where: the row needs ${"%.1f".format(need)}px, the box is ${"%.0f".format(box)}px — " +
                            "the toggle or the brand is being crushed to fit"
                    }
                    page.click(".theme-toggle")
                }
            }
        }
        withClue("the sweep measured every combination") { measured shouldBe WIDE_VIEWPORTS.size * lastLabels.size * TOGGLE_MODES }
        withClue("header row offenders") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `the phone menu carries the Product group under its heading`() {
        startTrace()
        page.setViewportSize(390, 844)
        navigateHome()
        withClue("the wide row is hidden on a phone") { page.locator(".nav-wide").isVisible() shouldBe false }
        page.click(".nav-mobile > summary")
        page.locator(".nav-mobile[open]").waitFor()
        val heading = page.locator(".nav-mobile .nav-group > .menu-heading")
        withClue("the group's heading is visible") { heading.isVisible() shouldBe true }
        heading.textContent().trim() shouldBe "Product"
        val labelledBy = page.locator(".nav-mobile .nav-group").getAttribute("aria-labelledby")
        withClue("the group is labelled by its heading") { labelledBy shouldBe heading.getAttribute("id") }
        val group = links(".nav-mobile .nav-group > a")
        group.map { it.first } shouldBe PRODUCT_PATHS
        val hidden = page.locator(".nav-mobile .nav-group > a").all().filterNot { it.isVisible() }
        withClue("every link in the group is visible once the menu is open") { hidden.shouldBeEmpty() }
        // The heading comes first: its box sits above the first link's.
        val headingBottom = (heading.evaluate("e => e.getBoundingClientRect().bottom") as Number).toDouble()
        val firstLink = page.locator(".nav-mobile .nav-group > a").first()
        val firstTop = (firstLink.evaluate("e => e.getBoundingClientRect().top") as Number).toDouble()
        withClue("the heading sits above the links") { (headingBottom <= firstTop + TOLERANCE) shouldBe true }
    }

    @Test
    fun `an unopened disclosure leaves the proof strip where origin main has it`() {
        startTrace()
        val offenders = mutableListOf<String>()
        RHYTHM_VIEWPORTS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            navigateHome()
            @Suppress("UNCHECKED_CAST")
            val r = page.evaluate(STRIP_JS) as Map<String, Any>
            val withMenu = (r["withMenu"] as Number).toDouble()
            val without = (r["without"] as Number).toDouble()
            val header = (r["header"] as Number).toDouble()
            println("130 (e) ${w}x$h: strip top $withMenu px with the disclosure, $without px without; header $header px")
            withClue("${w}x$h: a .strip rendered — the fold check is not vacuous") { (withMenu >= 0) shouldBe true }
            if (Math.abs(withMenu - without) > TOLERANCE) offenders += "${w}x$h: the disclosure moved the strip from $without to $withMenu"
            if (withMenu > h) offenders += "${w}x$h: proof strip starts at $withMenu px, below the ${h}px fold"
            if (w >= WIDE_MIN_PX && Math.abs(header - HEADER_HEIGHT_PX) > TOLERANCE) {
                offenders += "${w}x$h: header $header px (origin/main: $HEADER_HEIGHT_PX)"
            }
        }
        withClue("fold offenders") { offenders.shouldBeEmpty() }
    }

    private fun navigateHome() {
        page.navigate("$baseUrl/")
        page.waitForLoadState(LoadState.NETWORKIDLE)
    }

    /** `(href, label)` for every anchor the selector matches, in DOM order. */
    private fun links(selector: String): List<Pair<String, String>> {
        @Suppress("UNCHECKED_CAST")
        val raw =
            page.evaluate(
                "(sel) => Array.from(document.querySelectorAll(sel))" +
                    ".map(a => [a.getAttribute('href') || '', (a.textContent || '').trim().replace(/\\s+/g, ' ')])",
                selector,
            ) as List<List<String>>
        return raw.map { it[0] to it[1] }
    }

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
        /** The seven, in the header's order (owner R13). Existence is proven against the rendered registry, not assumed. */
        val PRODUCT_PATHS =
            listOf("/semantic-layer", "/dp-lake", "/federated-query", "/text-to-sql-agent", "/mcp-tools", "/demo-data", "/security")

        val WIDE_VIEWPORTS = listOf(1280 to 900, 1440 to 1000)
        val RHYTHM_VIEWPORTS = listOf(1440 to 1000, 390 to 844)
        const val WIDE_MIN_PX = 768
        const val STARS = "999"
        const val TOGGLE_MODES = 3
        const val TOLERANCE = 1.0

        /** The header on `origin/main` (`04d6baec`), 1280×900 and 1440×1000: 60px min-height + 1px border. */
        const val HEADER_HEIGHT_PX = 61.0

        val LAST_LABEL = Regex("""th:text="\$\{publicOrigin} \? '([^']+)' : '([^']+)'"""")

        /**
         * The row as laid out, and the row as it WANTS to be: a hidden `max-content` clone of
         * `.header-inner` lays every flex item out at its unshrunk width with nothing wrapping;
         * its width against the real content box is the fit. Padding is zeroed on the clone so
         * both numbers are content-box numbers.
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
                   headerHeight: document.querySelector('header.site-header').getBoundingClientRect().height,
                   toggle: (document.querySelector('.theme-toggle') || {}).textContent || ''
                 };
               }"""

        /** The strip's top with the disclosure in the DOM and with it removed (restored after). */
        const val STRIP_JS =
            """() => {
                 const strip = document.querySelector('.strip');
                 const top = () => strip ? strip.getBoundingClientRect().top : -1;
                 const withMenu = top();
                 const menu = document.querySelector('.nav-menu');
                 const parent = menu && menu.parentNode, next = menu && menu.nextSibling;
                 if (menu) menu.remove();
                 const without = top();
                 if (menu) parent.insertBefore(menu, next);
                 return { withMenu: withMenu, without: without,
                          header: document.querySelector('header.site-header').getBoundingClientRect().height };
               }"""
    }
}
