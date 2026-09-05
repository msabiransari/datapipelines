package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * `GET /sitemap.xml` (073 §D) — the crawler's index of everything public here.
 *
 * GET-only, anonymous, read-only: it is generated from two in-memory lists — the
 * [SitePages.ALL] registry and [DocsCatalog]'s packaged slugs, both fixed at startup — so
 * the route touches no database and no principal, exactly like the pages it lists.
 *
 * **Generated, not authored.** A hand-maintained sitemap.xml is a file that silently stops
 * matching the site; here a page is in the sitemap because it is in the registry, and a doc
 * is in it because it is packaged in the jar. `SitemapControllerTest` asserts the two counts
 * against those sources, and the E2E sweep fetches every `<loc>` anonymously — a `<loc>`
 * that does not answer 200 fails the build rather than the crawl.
 *
 * `lastmod` is the BUILD time, which is the honest answer for content packaged in the jar:
 * every page and every doc in it changed, at the latest, when this artifact was built. A
 * per-file date would be a lie in the other direction (the markdown's mtime in a Docker
 * layer is not when anyone edited it). Where no `build-info.properties` is on the classpath
 * — a plain `bootRun`, or a test slice — the field is omitted rather than faked: `lastmod`
 * is optional in the protocol, and a fabricated date is worse than none.
 */
@Controller
class SitemapController(
    private val docs: DocsCatalog,
    buildProperties: ObjectProvider<BuildProperties>,
) {
    private val buildTime: Instant? = buildProperties.ifAvailable?.time

    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    @ResponseBody
    fun sitemap(response: HttpServletResponse): String {
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(SITEMAP_MAX_AGE_HOURS, TimeUnit.HOURS).cachePublic().headerValue,
        )
        return SitemapXml.render(locations(), buildTime)
    }

    /**
     * Every indexable absolute URL: the registry's pages, the docs index, and one entry per
     * packaged doc slug. Order is registry order then docs order — stable across restarts,
     * so a diff of two fetches shows content changes and nothing else.
     */
    internal fun locations(): List<String> =
        SitePages.ALL.map { it.canonical } +
            listOf("$SITE_ORIGIN/docs") +
            docs.index().flatMap { group -> group.docs.map { "$SITE_ORIGIN/docs/${it.slug}" } }

    private companion object {
        /** A day: the sitemap changes only on deploy, and crawlers re-fetch it on their own schedule. */
        const val SITEMAP_MAX_AGE_HOURS = 24L
    }
}
