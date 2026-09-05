package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import java.time.Instant
import java.util.Properties
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
        val locations = controller(buildTime = null).locations()

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
            SitePagesController::class
                .java
                .declaredMethods
                .mapNotNull { it.getAnnotation(GetMapping::class.java)?.value?.firstOrNull() }
                .flatMap { path ->
                    if (path.contains("{")) SitePages.ENGINES.map { SitePages.ENGINE_PREFIX + it.slug } else listOf(path)
                }.distinct()

        // Non-vacuity: seven handlers, of which one templated route expands to six pages.
        check(routes.size >= MIN_ROUTES) { "the reflection scan found only ${routes.size} routes" }

        val locations = controller(buildTime = null).locations()
        routes.filterNot { "$SITE_ORIGIN$it" in locations } shouldBe emptyList()
    }

    @Test
    fun `the response is a parseable urlset with one loc per location`() {
        val response = MockHttpServletResponse()
        val xml = controller(buildTime = null).sitemap(response)

        val document =
            DocumentBuilderFactory
                .newInstance()
                .also { it.isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(xml.byteInputStream())

        document.documentElement.localName shouldBe "urlset"
        document.getElementsByTagNameNS(SITEMAP_NS, "url").length shouldBe controller(null).locations().size
        document.getElementsByTagNameNS(SITEMAP_NS, "loc").length shouldBe controller(null).locations().size
        // No build-info on the classpath here, so lastmod is omitted rather than invented.
        document.getElementsByTagNameNS(SITEMAP_NS, "lastmod").length shouldBe 0
        response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "max-age=86400, public"
    }

    @Test
    fun `with build info present every url carries that build's lastmod`() {
        val built = Instant.parse("2026-09-04T10:15:30Z")
        val xml = controller(buildTime = built).sitemap(MockHttpServletResponse())

        val document =
            DocumentBuilderFactory
                .newInstance()
                .also { it.isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(xml.byteInputStream())

        val lastmods = document.getElementsByTagNameNS(SITEMAP_NS, "lastmod")
        lastmods.length shouldBe controller(null).locations().size
        lastmods.item(0).textContent shouldBe "2026-09-04T10:15:30Z"
    }

    private fun controller(buildTime: Instant?): SitemapController = SitemapController(docs, buildPropertiesProvider(buildTime))

    /**
     * A minimal [ObjectProvider] over an optional bean — the production wiring's shape, so the
     * "no build-info on the classpath" branch is exercised by the same code path that runs
     * under `bootRun`, not by a special case.
     */
    private fun buildPropertiesProvider(time: Instant?): ObjectProvider<BuildProperties> =
        object : ObjectProvider<BuildProperties> {
            private val value: BuildProperties? =
                time?.let {
                    BuildProperties(
                        Properties().apply {
                            setProperty("group", "co.datapipelines")
                            setProperty("artifact", "datapipelines-app")
                            setProperty("version", "1.0.0")
                            setProperty("time", it.toEpochMilli().toString())
                        },
                    )
                }

            override fun getObject(vararg args: Any?): BuildProperties = getObject()

            override fun getObject(): BuildProperties = checkNotNull(value) { "no BuildProperties" }

            override fun getIfAvailable(): BuildProperties? = value

            override fun getIfUnique(): BuildProperties? = value
        }

    private companion object {
        const val SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9"

        /** The packaged spec set is ~25 docs; well under it means the classpath scan broke. */
        const val MIN_DOCS = 15

        /** Six engine pages plus six one-off cluster pages plus the pillar. */
        const val MIN_ROUTES = 13
    }
}
