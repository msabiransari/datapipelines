package co.datapipelines.web.ui.site

import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The head-and-headings contract for every public page (073 §A, §D), swept over the WHOLE
 * registry rather than spot-checked on one page.
 *
 * Each assertion collects its failures across all pages and compares the list to empty, so a
 * red run names every page that broke rather than the first one. Everything is measured on
 * the render produced by the REAL controller ([SitePageRenderer]) — a guard that assembled
 * its own model would prove the template renders and nothing about what is served.
 *
 * The display limits (70 for a title, 155 for a description) are what a search result shows
 * before truncating. A page over them is not broken, it is CUT, and the sentence the searcher
 * reads stops mid-word — which is why this fails the build rather than warning.
 */
class SiteSeoMetaTest {
    private val rendered: Map<SitePage, String> = SitePages.ALL.associateWith { SitePageRenderer.render(it) }

    @Test
    fun `the registry is non-vacuous and covers the measured page plan`() {
        // A sweep over an empty list passes every assertion below by checking nothing.
        SitePages.ALL shouldHaveAtLeastSize EXPECTED_PAGES
        SitePages.ENGINES.map { it.slug }.toSet() shouldBe
            setOf("postgres", "sql-server", "mysql", "oracle", "sqlite", "duckdb")
    }

    @Test
    fun `every page renders its registry title, within the display limit`() {
        val wrong =
            rendered.mapNotNull { (page, html) ->
                val title =
                    TITLE
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                when {
                    title != page.title -> "${page.path}: <title> is ${title.orEmpty()}, registry says ${page.title}"
                    title.length > TITLE_MAX -> "${page.path}: title is ${title.length} chars (limit $TITLE_MAX)"
                    else -> null
                }
            }
        wrong shouldBe emptyList()
    }

    @Test
    fun `every page renders its registry description, within the display limit`() {
        val wrong =
            rendered.mapNotNull { (page, html) ->
                val description = metaContent(html, "name=\"description\"")
                when {
                    description != page.description -> "${page.path}: description is ${description.orEmpty()}"
                    description.length > DESCRIPTION_MAX -> "${page.path}: description is ${description.length} chars"
                    else -> null
                }
            }
        wrong shouldBe emptyList()
    }

    @Test
    fun `every page carries its canonical and the full social card`() {
        val missing =
            rendered.flatMap { (page, html) ->
                buildList {
                    val canonical = CANONICAL.find(html)?.groupValues?.get(1)
                    if (canonical != page.canonical) add("${page.path}: canonical is $canonical")
                    if (metaContent(html, "property=\"og:title\"") != page.title) add("${page.path}: og:title")
                    if (metaContent(html, "property=\"og:description\"") != page.description) add("${page.path}: og:description")
                    if (metaContent(html, "property=\"og:url\"") != page.canonical) add("${page.path}: og:url")
                    if (metaContent(html, "property=\"og:image\"") != BRAND_CARD) add("${page.path}: og:image")
                    if (metaContent(html, "name=\"twitter:card\"") != "summary_large_image") add("${page.path}: twitter:card")
                    if (metaContent(html, "name=\"twitter:title\"") != page.title) add("${page.path}: twitter:title")
                    if (metaContent(html, "name=\"twitter:image\"") != BRAND_CARD) add("${page.path}: twitter:image")
                }
            }
        missing shouldBe emptyList()
    }

    @Test
    fun `every page has exactly one h1 and skips no heading level`() {
        val broken =
            rendered.mapNotNull { (page, html) ->
                val levels = HEADING.findAll(html).map { it.groupValues[1].toInt() }.toList()
                val h1s = levels.count { it == 1 }
                val skip =
                    levels.zipWithNext().firstOrNull { (previous, next) -> next > previous + 1 }
                when {
                    h1s != 1 -> "${page.path}: $h1s <h1> elements"
                    levels.firstOrNull() != 1 -> "${page.path}: first heading is h${levels.firstOrNull()}"
                    skip != null -> "${page.path}: h${skip.first} is followed by h${skip.second}"
                    else -> null
                }
            }
        broken shouldBe emptyList()
    }

    @Test
    fun `every image carries a descriptive alt`() {
        var images = 0
        val bad =
            rendered.flatMap { (page, html) ->
                IMG.findAll(html).mapNotNull { tag ->
                    images++
                    val alt =
                        ALT
                            .find(tag.value)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                    if (alt.isNullOrBlank() || alt.length < MIN_ALT_CHARS) "${page.path}: ${tag.value.take(80)}" else null
                }
            }
        bad shouldBe emptyList()
        // Non-vacuity: the homepage alone carries ten screenshots. Zero images means the
        // sweep stopped seeing <img> tags, and this test would pass by checking nothing.
        check(images >= MIN_IMAGES) { "the alt sweep saw only $images images across the site" }
    }

    @Test
    fun `no page ships an unprocessed expression or a third-party asset`() {
        val bad =
            rendered.flatMap { (page, html) ->
                buildList {
                    if (" th:" in html) add("${page.path}: an unprocessed th:* attribute survived")
                    if ("\${" in html) add("${page.path}: an unresolved \${} expression survived")
                    ASSET_TAG
                        .findAll(html)
                        // rel="canonical" is metadata, not an asset load: it names the public
                        // origin by design. Same exclusion SiteAssetAuditTest makes.
                        .filter { "rel=\"canonical\"" !in it.value }
                        .mapNotNull { ATTR.find(it.value)?.groupValues?.get(1) }
                        .filter { it.startsWith("http://") || it.startsWith("https://") }
                        .forEach { add("${page.path}: loads $it from a third-party origin") }
                }
            }
        bad shouldBe emptyList()
    }

    private fun metaContent(
        html: String,
        marker: String,
    ): String? =
        Regex("""<meta[^>]*$marker[^>]*>""")
            .find(html)
            ?.value
            ?.let { CONTENT.find(it)?.groupValues?.get(1) }

    private companion object {
        /** Two pillars + six engines + six one-off cluster pages, the measured plan. */
        const val EXPECTED_PAGES = 14
        const val TITLE_MAX = 70
        const val DESCRIPTION_MAX = 155
        const val MIN_ALT_CHARS = 20
        const val MIN_IMAGES = 8
        const val BRAND_CARD = "https://datapipelines.co/site/brand/og-1200x630.png"

        val TITLE = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val CANONICAL = Regex("""<link rel="canonical" href="([^"]*)"""")
        val CONTENT = Regex("""content="([^"]*)"""")
        val HEADING = Regex("""<h([1-6])[\s>]""")
        val IMG = Regex("""<img\b[^>]*>""")
        val ALT = Regex("""\balt="([^"]*)"""")
        val ASSET_TAG = Regex("""<(?:link|script|img)\b[^>]*>""")
        val ATTR = Regex("""\b(?:href|src)="([^"]*)"""")
    }
}
