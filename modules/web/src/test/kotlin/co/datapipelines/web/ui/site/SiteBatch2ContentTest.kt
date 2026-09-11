package co.datapipelines.web.ui.site

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

/**
 * Batch 2's content contract for the SEVEN new intent pages (111 §D), extended by 115 §A.3
 * to the `/how-it-works` engineering page and by 116 to the `/demo-data` page — measured on
 * the render through the real controller, like every other site guard:
 *
 *  1. the H1 carries the page's primary query phrase, in the searcher's words (the map below
 *     is the phrase, not the whole H1 — the H1 may dress it up, the phrase must be there);
 *  2. the body links at least three other internal targets from within `<main>` — nav,
 *     footer and breadcrumbs excluded, because a page that only the chrome links to is a
 *     page in the sitemap and nowhere else;
 *  3. a `.faq` block renders (the one-list mechanism, JSON-LD included);
 *  4. the stripped text is at least [MIN_WORDS] — the owner's SEO floor for an intent page.
 *
 * Falsified before landing by shortening `/for/agencies` below the floor in a scratch edit
 * (the red assertion is pasted in the handback).
 */
class SiteBatch2ContentTest {
    private val rendered: Map<SitePage, String> by lazy { SWEEP.associateWith { SitePageRenderer.render(it) } }

    @Test
    fun `the sweep sees all nine pages`() {
        rendered.keys.map { it.path }.toSet() shouldBe SWEEP.map { it.path }.toSet()
    }

    @Test
    fun `each H1 carries its primary query phrase`() {
        rendered.forEach { (page, html) ->
            val h1 = H1.find(html)?.groupValues?.get(1)
            withClue("${page.path}: no <h1>") { h1.shouldNotBeBlank() }
            val phrase = H1_PHRASES.getValue(page.path)
            withClue("${page.path}: H1 is '$h1'") {
                h1!!.lowercase() shouldContain phrase.lowercase()
            }
        }
    }

    @Test
    fun `each body links at least three internal targets beyond nav, footer and breadcrumbs`() {
        rendered.forEach { (page, html) ->
            val main = MAIN.find(html)?.value
            withClue("${page.path}: no <main> element") { main.shouldNotBeBlank() }
            // The breadcrumb nav is the first </nav> inside main; everything after it is body.
            val body = main!!.substringAfter("</nav>")
            val targets =
                HREF
                    .findAll(body)
                    .map { it.groupValues[1] }
                    .filter { it.startsWith("/") && !it.startsWith("//") }
                    .map { it.substringBefore('#').substringBefore('?') }
                    .filter { it.isNotEmpty() && it != "/" }
                    .toSet()
            withClue("${page.path}: body links only $targets") {
                targets.size shouldBeGreaterThanOrEqual MIN_BODY_LINKS
            }
        }
    }

    @Test
    fun `each page renders a faq block`() {
        rendered.forEach { (page, html) ->
            withClue("${page.path}: no .faq block") {
                html shouldContain """<div class="faq">"""
            }
        }
    }

    @Test
    fun `each page clears the word floor`() {
        rendered.forEach { (page, html) ->
            val stripped = STRIP.replace(html, " ").trim()
            val words = stripped.split(WHITESPACE).size
            withClue("${page.path}: $words words stripped") {
                words shouldBeGreaterThanOrEqual MIN_WORDS
            }
        }
    }

    @Test
    fun `each template source clears the word floor`() {
        // The RENDERED floor above is weaker than the owner's brief: the FAQ answers and the
        // FAQPage JSON-LD ride on the model, so a page could shed half its body and still
        // clear 1,200 rendered words. This is the §A measure — the template source, stripped
        // with the same rule the handback counts with — and it is the one that bites.
        SWEEP.forEach { page ->
            val source = TestRepoFiles.read("modules/web/src/main/resources/templates/${page.view}.html")
            val stripped = STRIP.replace(source, " ").trim()
            val words = stripped.split(WHITESPACE).size
            withClue("${page.path}: template source is $words words stripped") {
                words shouldBeGreaterThanOrEqual MIN_WORDS
            }
        }
    }

    /**
     * 115 §A.1 — the home page's primary phrase moved off the engineer's vocabulary: the
     * pillar page `/mcp-server-for-sql-databases` keeps "SQL MCP server" in its own title and
     * H1, so `/` targets "no data team required". Pinned here (not in the sweep above)
     * because the home page is not a batch-2 intent page and carries no word floor.
     */
    @Test
    fun `the home H1 carries its new primary phrase`() {
        val html = SitePageRenderer.render(SitePages.HOME)
        val h1 = H1.find(html)?.groupValues?.get(1)
        withClue("/: no <h1>") { h1.shouldNotBeBlank() }
        withClue("/: H1 is '$h1'") {
            h1!!.lowercase() shouldContain "no data team required"
        }
    }

    private companion object {
        const val MIN_WORDS = 1_200
        const val MIN_BODY_LINKS = 3

        /** The seven batch-2 intent pages (111), the engineering page (115 §A.3), the demo-data page (116)
         *  and the semantic-layer page (119 §B). /pricing is deliberately absent: 600-900 words is the
         *  honest length for "there is no price", so its pins live in SiteOpenSourceSignalsTest instead. */
        val SWEEP: List<SitePage> =
            listOf(
                SitePages.COMPARE_FIVETRAN,
                SitePages.COMPARE_POSTGRES_ONLY,
                SitePages.TABLEAU_PREP,
                SitePages.TABLEAU_ROADMAP,
                SitePages.FOR_AGENCIES,
                SitePages.FOR_SAAS_TEAMS,
                SitePages.FOR_ANALYSTS,
                SitePages.HOW_IT_WORKS,
                SitePages.DEMO_DATA,
                SitePages.SEMANTIC_LAYER,
            )

        /** The searcher's phrase each H1 must carry — the phrase, not the whole H1. */
        val H1_PHRASES: Map<String, String> =
            mapOf(
                SitePages.COMPARE_FIVETRAN.path to "fivetran alternative",
                SitePages.COMPARE_POSTGRES_ONLY.path to "do i need a data warehouse",
                SitePages.TABLEAU_PREP.path to "tableau prep alternative",
                SitePages.TABLEAU_ROADMAP.path to "embedded dashboards",
                SitePages.FOR_AGENCIES.path to "client reporting api",
                SitePages.FOR_SAAS_TEAMS.path to "embedded analytics",
                SitePages.FOR_ANALYSTS.path to "ai sql assistant with governance",
                SitePages.HOW_IT_WORKS.path to "how it works",
                SitePages.DEMO_DATA.path to "demo data",
                SitePages.SEMANTIC_LAYER.path to "semantic layer",
            )

        val H1 = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
        val MAIN = Regex("""<main\b[\s\S]*?</main>""")
        val HREF = Regex("""<a[^>]*\shref="([^"]*)"""")
        val STRIP = Regex("<[^>]+>")
        val WHITESPACE = Regex("""\s+""")
    }
}
