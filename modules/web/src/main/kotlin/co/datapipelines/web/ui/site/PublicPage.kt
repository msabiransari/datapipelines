package co.datapipelines.web.ui.site

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.ui.Model
import java.util.concurrent.TimeUnit

/**
 * The three things every public page does identically (073), in one place so no page can
 * forget one: the SEO head model, the shared-cache header, and the tool count.
 *
 * The alternative — each controller setting five model attributes by hand — is how a page
 * ships with a missing canonical or a stale title. `SiteSeoMetaTest` sweeps every registry
 * row through the real handler and asserts all five arrive, so a page added without this
 * helper fails the build rather than shipping unindexable.
 */
object PublicPage {
    /**
     * The default social card: the brand image (070's `og-1200x630.png`). A page with a real
     * product screenshot may override `ogImage` after calling this — the homepage does not,
     * because the brand card is what a link to `/` should show.
     */
    const val DEFAULT_OG_IMAGE: String = "/site/brand/og-1200x630.png"

    /**
     * Short: these pages are fingerprint-free, so staleness after a deploy must bound in
     * minutes. Same value and same reasoning as the homepage's (033/D1) — the defence on
     * every public route is a shared-cache TTL, never a rate limiter.
     */
    const val PAGE_MAX_AGE_MINUTES: Long = 5L

    /** Fills the `<head>` model the site layout reads. Returns the view name, so handlers are one line. */
    fun render(
        model: Model,
        response: HttpServletResponse,
        page: SitePage,
        toolCount: Int,
    ): String {
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(PAGE_MAX_AGE_MINUTES, TimeUnit.MINUTES).cachePublic().headerValue,
        )
        model.addAttribute("pageTitle", page.title)
        model.addAttribute("pageDescription", page.description)
        model.addAttribute("canonicalUrl", page.canonical)
        model.addAttribute("ogImage", SITE_ORIGIN + DEFAULT_OG_IMAGE)
        model.addAttribute("currentSitePath", page.path)
        model.addAttribute("toolCount", toolCount)
        model.addAttribute("navPages", SitePages.NAV)
        // Every page's footer links the six engine pages, so the registry — not seven
        // hand-written <li>s — is what the link graph is built from.
        model.addAttribute("engines", SitePages.ENGINES)
        return page.view
    }
}
