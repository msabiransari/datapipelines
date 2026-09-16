package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
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
 * **No `lastmod`** (145 §7). Until 145 the field carried the BUILD time for every URL —
 * honest for "the artifact changed", but a crawler reads `lastmod` as "this PAGE changed",
 * and a deploy that touched one doc stamped all sixty-odd URLs as modified. A per-page
 * modification date would need a date system nobody maintains; the field is optional in
 * the protocol, so it is omitted everywhere — the live route and the static export now
 * agree on that (`SiteExportMain` never had a build time to give).
 */
@Controller
class SitemapController(
    private val docs: DocsCatalog,
) {
    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    @ResponseBody
    fun sitemap(response: HttpServletResponse): String {
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(SITEMAP_MAX_AGE_HOURS, TimeUnit.HOURS).cachePublic().headerValue,
        )
        return SitemapXml.render(locations(), lastmod = null)
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
