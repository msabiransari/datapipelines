package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `/sitemap.xml` (073 §D): generated from the page registry and the packaged docs, never
 * hand-maintained.
 *
 * The counts are asserted against those two SOURCES rather than against a number, which is
 * the whole point of generating it — a page added to [SitePages] or a doc added to the jar is
 * in the sitemap with nothing to remember, and this test is what proves that stayed true.
 * The document is PARSED, not string-matched: an unescaped character would produce a file
 * every crawler rejects and no test would notice.
 */
class SitemapControllerTest {
    private val docs = SitePageRenderer.docs
    private val docSlugs = docs.index().flatMap { group -> group.docs.map { it.slug } }

    @Test
    fun `every registry page, the docs index and every packaged doc are listed exactly once`() {
        val locations = controller().locations()

        // Non-vacuity before equality: an empty catalog would make "no duplicates" trivially true.
        check(docSlugs.size >= MIN_DOCS) { "only ${docSlugs.size} packaged docs — the catalog is not loading" }
        locations.size shouldBe SitePages.ALL.size + 1 + docSlugs.size
        locations.distinct().size shouldBe locations.size
        locations.filterNot { it.startsWith(SITE_ORIGIN) } shouldBe emptyList()
        SitePages.ALL.map { it.canonical }.filterNot { it in locations } shouldBe emptyList()
        docSlugs.map { "$SITE_ORIGIN/docs/$it" }.filterNot { it in locations } shouldBe emptyList()
    }

    /**
     * The hole the count assertions above cannot see: they derive the expected set from the
     * registry, so a page whose ROUTE exists but whose registry row was never added stays
     * consistent with itself and invisible. This walks the controller's own `@GetMapping`
     * values instead — a served page missing from the sitemap is a page nobody finds.
     */
    @Test
    fun `every route the site controller serves appears in the sitemap`() {
        val routes =
            listOf(SitePagesController::class, SiteV2Batch2Controller::class, SiteHubController::class)
                .flatMap { controller -> controller.java.declaredMethods.toList() }
                .mapNotNull { it.getAnnotation(GetMapping::class.java)?.value?.firstOrNull() }
                .flatMap { path ->
                    if (path.contains("{")) SitePages.ENGINES.map { SitePages.ENGINE_PREFIX + it.slug } else listOf(path)
                }.distinct()

        // Non-vacuity: seven handlers, of which one templated route expands to the engine pages.
        check(routes.size >= MIN_ROUTES) { "the reflection scan found only ${routes.size} routes" }

        val locations = controller().locations()
        routes.filterNot { "$SITE_ORIGIN$it" in locations } shouldBe emptyList()
    }

    @Test
    fun `the response is a parseable urlset with one loc per location`() {
        val response = MockHttpServletResponse()
        val xml = controller().sitemap(response)

        val document =
            DocumentBuilderFactory
                .newInstance()
                .also { it.isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(xml.byteInputStream())

        document.documentElement.localName shouldBe "urlset"
        document.getElementsByTagNameNS(SITEMAP_NS, "url").length shouldBe controller().locations().size
        document.getElementsByTagNameNS(SITEMAP_NS, "loc").length shouldBe controller().locations().size
        // 145 §7: no lastmod anywhere — the build time stamped every URL as modified on every
        // deploy, and there is no per-page date to give instead. Live and export now agree.
        document.getElementsByTagNameNS(SITEMAP_NS, "lastmod").length shouldBe 0
        response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "max-age=86400, public"
    }

    /** 145: the two hub routes are registry rows and sitemap entries like every other page. */
    @Test
    fun `the 145 hub pages are listed`() {
        val locations = controller().locations()
        listOf(SitePages.USE_CASES, SitePages.EXPLORE).map { it.canonical }.filterNot { it in locations } shouldBe emptyList()
    }

    private fun controller(): SitemapController = SitemapController(docs)

    private companion object {
        const val SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9"

        /** The packaged spec set is ~25 docs; well under it means the classpath scan broke. */
        const val MIN_DOCS = 15

        /** The engine pages plus the one-off cluster pages plus the pillar. */
        const val MIN_ROUTES = 13
    }
}
