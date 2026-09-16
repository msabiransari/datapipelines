package co.datapipelines.browser

import com.microsoft.playwright.ConsoleMessage
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Every public route, in a real browser, at the four widths the transition is judged at
 * (145 §8.5: 320, 390, 768, 1440). The routes are DISCOVERED from `/sitemap.xml` — the
 * registry plus the packaged docs — so a page added to the site is measured the day it is
 * listed, and the floor is asserted (59 baseline + 2 additions, plus the docs).
 *
 * Per route and width: the response is a direct 200 (no redirect — the application route's
 * requirement, distinct from the static export's directory redirects), exactly one `<h1>`,
 * no horizontal overflow of the document, the header and footer landmarks render, and the
 * page logs no console error and throws no page error. Anonymous throughout.
 */
class SitePublicRoutesBrowserTest : BrowserSuite() {
    @Test
    fun `every sitemap route answers 200 with one h1, no sideways scroll and no console error at four widths`() {
        startTrace()
        page.navigate("$baseUrl/sitemap.xml")
        val xml = page.content()
        val routes =
            LOC
                .findAll(xml)
                .map { it.groupValues[1].removePrefix("https://datapipelines.co").ifEmpty { "/" } }
                .toList()
        withClue("route discovery is non-vacuous (the 59 baseline routes + the 2 additions)") {
            routes.size shouldBeGreaterThanOrEqual MIN_ROUTES
        }
        routes.count { !it.startsWith("/docs") } shouldBe MARKETING_ROUTES

        val errors = mutableListOf<String>()
        page.onConsoleMessage { m: ConsoleMessage -> if (m.type() == "error") errors += "${page.url()}: console ${m.text()}" }
        page.onPageError { e -> errors += "${page.url()}: page error $e" }

        val offenders = mutableListOf<String>()
        var visits = 0
        VIEWPORTS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            routes.forEach { route ->
                val response = page.navigate("$baseUrl$route")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                visits++
                val status = response?.status() ?: -1
                if (status != 200) offenders += "$route at ${w}x$h: HTTP $status"
                if (page.url() != "$baseUrl$route") offenders += "$route at ${w}x$h: landed on ${page.url()}"
                @Suppress("UNCHECKED_CAST")
                val shape = page.evaluate(SHAPE_JS) as Map<String, Any>
                if ((shape["h1"] as Number).toInt() != 1) offenders += "$route at ${w}x$h: ${shape["h1"]} h1 elements"
                if ((shape["over"] as Number).toLong() > 0) {
                    offenders += "$route at ${w}x$h: ${shape["over"]}px of horizontal overflow — ${shape["culprits"]}"
                }
                if (shape["header"] != true || shape["footer"] != true) offenders += "$route at ${w}x$h: header/footer missing"
            }
        }
        withClue("the sweep visited every route at every width") { visits shouldBe routes.size * VIEWPORTS.size }
        withClue("route/width offenders") { offenders.shouldBeEmpty() }
        withClue("console or page errors") { errors.shouldBeEmpty() }
    }

    private companion object {
        val LOC = Regex("""<loc>([^<]+)</loc>""")
        val VIEWPORTS = listOf(320 to 568, 390 to 844, 768 to 1024, 1440 to 900)

        /** 35 baseline marketing routes + 24 docs routes + the two 145 additions. */
        const val MIN_ROUTES = 61
        const val MARKETING_ROUTES = 37

        /** The page's shape: h1 count, document overflow, the landmarks, and the widest culprits. */
        const val SHAPE_JS =
            """() => {
                 const doc = document.documentElement;
                 const over = doc.scrollWidth - doc.clientWidth;
                 const culprits = over > 0 ? Array.from(document.querySelectorAll('*'))
                   .filter(e => e.getBoundingClientRect().right > doc.clientWidth + 1)
                   .sort((a, b) => b.getBoundingClientRect().right - a.getBoundingClientRect().right)
                   .slice(0, 4)
                   .map(e => e.tagName + (typeof e.className === 'string' && e.className.trim() ? '.' + e.className.trim().split(/\s+/)[0] : ''))
                   .join(' | ') : '';
                 return {
                   h1: document.querySelectorAll('h1').length,
                   over: over,
                   culprits: culprits,
                   header: !!document.querySelector('header.site-header'),
                   footer: !!document.querySelector('footer.site-footer')
                 };
               }"""
    }
}
