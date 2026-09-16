package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The public header, in a real browser — 130's arms re-set by 145 to the approved five-page
 * navigation (the Product disclosure is gone; its seven pages moved to /explore and the
 * footer, and arm (d) proves the move rather than the removal).
 *
 *  - **(a) the row is the five and the docs:** the wide nav's links equal, in order,
 *    [PRIMARY_PATHS] — every one a registry page or the docs index — followed by the
 *    host-aware application entry (`/login`); on `/how-it-works` that item alone carries
 *    `aria-current="page"`; the phone menu carries the same links in the same order;
 *  - **(b) the phone menu opens, closes on Escape with focus on the summary, closes on a click
 *    outside, and never traps focus:** at 390×844 the hamburger opens the panel and Tab lands
 *    on its first link; Escape closes it and focus is back on the summary; opened again, a click
 *    on the page closes it;
 *  - **(c) one row, that fits its box, under the widest last label:** at 1280×900 and
 *    1440×1000 every `.nav-wide` item shares one top (± 1px), the header is the design's
 *    one-row height ([HEADER_HEIGHT_PX]: 4.5rem plus the hairline), and the row's max-content
 *    width fits the container's content box under BOTH labels the layout can render (`Try the
 *    live demo` on the public origin, `Sign in` on a deployment — read from the template, never
 *    typed here);
 *  - **(d) the seven moved, they did not vanish:** the differentiator pages 130 put in the
 *    Product disclosure are linked from `/explore` AND from every page's footer.
 *
 * Anonymous throughout: the site is public.
 */
class SiteHeaderMenuBrowserTest : BrowserSuite() {
    @Test
    fun `the wide row links the five pages and the docs in order, marks the current page, and the phone menu agrees`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigate("/how-it-works")
        val row = links(".nav-wide > a")
        withClue("the row's links, in order") { row.map { it.first } shouldBe PRIMARY_PATHS + "/login" }
        row.forEach { (path, label) -> withClue("$path has a label") { label.isNotBlank() shouldBe true } }
        val current = page.locator(".nav-wide > a[aria-current=\"page\"]").all().map { it.getAttribute("href") }
        withClue("exactly the page being read is marked current") { current shouldBe listOf("/how-it-works") }

        val phone = links(".nav-mobile .nav-panel > a")
        withClue("the phone menu is the same list, same order") { phone.map { it.first } shouldBe PRIMARY_PATHS + "/login" }
        withClue("the phone menu marks the same current page") {
            val marked = page.locator(".nav-mobile .nav-panel > a[aria-current=\"page\"]").all().map { it.getAttribute("href") }
            marked shouldBe listOf("/how-it-works")
        }
    }

    @Test
    fun `the phone menu opens, closes on Escape with focus on the summary, and closes on a click outside`() {
        startTrace()
        page.setViewportSize(390, 844)
        navigate("/")
        withClue("the wide row is hidden on a phone") { page.locator(".nav-wide").isVisible() shouldBe false }
        withClue("closed on load") { page.locator(".nav-mobile[open]").count() shouldBe 0 }

        page.click(".nav-mobile > summary")
        page.locator(".nav-mobile[open]").waitFor()
        val first = page.locator(".nav-mobile .nav-panel > a").first()
        withClue("the panel is visible once open") { first.isVisible() shouldBe true }
        page.keyboard().press("Tab")
        withClue("Tab from the summary lands on the panel's first link") { activeHref() shouldBe PRIMARY_PATHS.first() }
        val hidden = page.locator(".nav-mobile .nav-panel > a").all().filterNot { it.isVisible() }
        withClue("every link is visible once the menu is open") { hidden.shouldBeEmpty() }

        page.keyboard().press("Escape")
        page.locator(".nav-mobile:not([open])").waitFor()
        withClue("Escape returns focus to the summary") { activeTag() shouldBe "SUMMARY" }

        page.click(".nav-mobile > summary")
        page.locator(".nav-mobile[open]").waitFor()
        // Top-left corner of the hero title: page content, clear of the open panel's box.
        page.locator("main h1").first().click(Locator.ClickOptions().setPosition(8.0, 8.0))
        page.locator(".nav-mobile:not([open])").waitFor()
        withClue("a click outside closes the panel") { page.locator(".nav-mobile[open]").count() shouldBe 0 }
    }

    @Test
    fun `the header row is one row that fits its box at both widths under the widest last label`() {
        startTrace()
        val lastLabels = lastItemLabels()
        withClue("both last-item labels read from the layout") { lastLabels.size shouldBe 2 }
        val offenders = mutableListOf<String>()
        var measured = 0
        WIDE_VIEWPORTS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            navigate("/")
            lastLabels.forEach { label ->
                page.evaluate("(label) => { document.querySelector('.nav-wide > a:last-child > span').textContent = label }", label)
                @Suppress("UNCHECKED_CAST")
                val row = page.evaluate(ROW_JS) as Map<String, Any>
                measured++
                val where = "${w}x$h, last='$label'"

                @Suppress("UNCHECKED_CAST")
                val items = row["items"] as List<Map<String, Any>>
                withClue("$where: the row has items") { items.isNotEmpty() shouldBe true }
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
                println("145 (c) $where: row needs ${"%.1f".format(need)}px of a ${"%.0f".format(box)}px box ($spare px spare)")
                if (need > box) offenders += "$where: the row needs ${"%.1f".format(need)}px, the box is ${"%.0f".format(box)}px"
            }
        }
        withClue("the sweep measured every combination") { measured shouldBe WIDE_VIEWPORTS.size * lastLabels.size }
        withClue("header row offenders") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `the seven differentiator pages are linked from explore and from the footer`() {
        startTrace()
        page.setViewportSize(1280, 900)
        navigate("/explore")
        val explore = links("main a[href]").map { it.first }.toSet()
        withClue("/explore links the seven") { explore shouldContainAll MOVED_PATHS }
        val footer = links("footer a[href]").map { it.first }.toSet()
        withClue("the footer links the seven") { footer shouldContainAll MOVED_PATHS }
        // Non-vacuity: seven, not "at least one".
        MOVED_PATHS.size shouldBe 7
    }

    private fun navigate(path: String) {
        page.navigate("$baseUrl$path")
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
        /** The five primary pages and the docs, in the header's order (145 §2). */
        val PRIMARY_PATHS = listOf("/how-it-works", "/use-cases", "/pricing", "/explore", "/docs")

        /** 130's seven, now reached through /explore and the footer. */
        val MOVED_PATHS =
            listOf("/semantic-layer", "/dp-lake", "/federated-query", "/text-to-sql-agent", "/mcp-tools", "/demo-data", "/security")

        val WIDE_VIEWPORTS = listOf(1280 to 900, 1440 to 1000)
        const val TOLERANCE = 1.0

        /** `--site-header-height` (4.5rem = 72px) plus the header's 1px hairline. */
        const val HEADER_HEIGHT_PX = 73.0

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
                 const items = Array.from(document.querySelectorAll('.nav-wide > a')).map(e => {
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
