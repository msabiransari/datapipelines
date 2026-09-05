package co.datapipelines.web.ui.site

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The sitemap document itself (073 §D) — a pure function of a URL list and an optional
 * timestamp, deliberately separate from [SitemapController].
 *
 * Separate because two callers need the same bytes: the live route, and the static export
 * (`websiteExport`) that produces the S3 cold fallback. A second implementation in the
 * exporter is how the emergency copy ships a sitemap the app does not agree with.
 */
object SitemapXml {
    /** W3C Datetime, which is what the sitemap protocol's `lastmod` takes. */
    private val LASTMOD: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    /**
     * Renders [locations] as a `urlset`. [lastmod] is omitted entirely when null: the field
     * is optional in the protocol, and a fabricated date is worse than an absent one.
     */
    fun render(
        locations: List<String>,
        lastmod: Instant?,
    ): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""").append('\n')
            locations.forEach { loc ->
                append("  <url>\n    <loc>").append(loc).append("</loc>\n")
                lastmod?.let { append("    <lastmod>").append(LASTMOD.format(it)).append("</lastmod>\n") }
                append("  </url>\n")
            }
            append("</urlset>\n")
        }
}
