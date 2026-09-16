package co.datapipelines.web.ui.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils

/**
 * The website transition's migration guard (145 §3, §8.1) — the 59-route floor and the
 * outline every route must still render, as a SOURCE-CONTROLLED fixture.
 *
 * `site/site-baseline-outline.json` was derived from the frozen 2026-09-16 source baseline
 * (59 sitemap routes: 35 marketing, 24 docs) by the lane's `make-outline-fixture.py`. For an
 * UNCHANGED page it pins every anchor id and every heading/FAQ text the baseline recorded;
 * for a REWRITTEN page (`/`, `/how-it-works`, `/pricing`; `/faq` for one corrected question)
 * it pins the baseline anchors and headings the redesign RETAINS and names every retirement
 * with its reason. A docs route pins the route — the Markdown owns its own headings.
 *
 * Three claims, each one a way the migration could silently lose something:
 *  1. **No route disappears.** Every fixture route is in the registry (or the docs catalog)
 *     and renders through its real controller; the two 145 additions render too, so the
 *     sitemap floor is 59 + 2.
 *  2. **No anchor disappears.** A deep link into a specialist page, a docs fragment or a
 *     retained section keeps resolving.
 *  3. **No heading disappears.** The section, sub-section and FAQ outline a page had —
 *     the reader's map of its content — is still on the page. Text is compared, not level,
 *     so a heading that legitimately moved down a level (the old /how-it-works H1 is the
 *     engineering depth's H2) is still found.
 *
 * Falsified at birth: removing one engine's registry row named its route here; deleting
 * `id="free-costs"` from the pricing page named the anchor; renaming a comparison page's H2
 * named the heading. Non-vacuity is asserted on the fixture itself — a fixture that shrank
 * would guard nothing.
 */
class SiteRouteFloorTest {
    private val fixture: JsonNode by lazy {
        val stream =
            checkNotNull(javaClass.classLoader.getResourceAsStream("site/site-baseline-outline.json")) {
                "site/site-baseline-outline.json is not on the test classpath"
            }
        MAPPER.readTree(stream)
    }

    private val routes: Map<String, JsonNode> by lazy {
        fixture["routes"].properties().associate { (path, node) -> path to node }
    }

    private val registry: Map<String, SitePage> = SitePages.ALL.associateBy { it.path }

    private val docSlugs: Set<String> by lazy {
        SitePageRenderer.docs
            .index()
            .flatMap { g -> g.docs.map { it.slug } }
            .toSet()
    }

    @Test
    fun `the fixture is the 59-route baseline and the registry carries it plus the two additions`() {
        withClue("the fixture holds the whole baseline") { routes.size shouldBe BASELINE_ROUTES }
        val marketing = routes.filterValues { it["status"].asText() != "doc" }.keys
        val docs = routes.filterValues { it["status"].asText() == "doc" }.keys
        marketing.size shouldBe BASELINE_MARKETING
        docs.size shouldBe BASELINE_DOCS
        // Non-vacuity on the structure: the unchanged pages carry a real outline.
        val pinnedHeadings = routes.values.filter { it.has("headings") }.sumOf { it["headings"].size() }
        pinnedHeadings shouldBeGreaterThanOrEqual MIN_PINNED_HEADINGS

        withClue("every baseline marketing route is a registry row") {
            marketing.filterNot { it in registry }.shouldBeEmpty()
        }
        withClue("every baseline docs route is a packaged doc") {
            docs.filterNot { it == "/docs" || it.removePrefix("/docs/") in docSlugs }.shouldBeEmpty()
        }
        withClue("the registry is the baseline plus exactly the two 145 additions") {
            registry.keys shouldContainAll marketing
            (registry.keys - marketing) shouldBe setOf(SitePages.USE_CASES.path, SitePages.EXPLORE.path)
        }
    }

    @Test
    fun `every baseline anchor and heading still renders on its route`() {
        val lost = mutableListOf<String>()
        var anchorsChecked = 0
        var headingsChecked = 0
        routes.forEach { (path, node) ->
            if (node["status"].asText() == "doc") return@forEach
            val html = SitePageRenderer.render(registry.getValue(path))
            val main = main(html)
            val ids = ID.findAll(main).map { it.groupValues[1] }.toSet()
            val headings = headingTexts(main)
            node["anchors"].forEach { anchor ->
                anchorsChecked++
                if (anchor.asText() !in ids) lost += "$path lost anchor #${anchor.asText()}"
            }
            node["headings"].forEach { heading ->
                headingsChecked++
                val text = heading["text"].asText()
                if (text !in headings) lost += "$path lost heading '$text'"
            }
        }
        withClue("the sweep checked something") {
            (anchorsChecked >= MIN_ANCHORS && headingsChecked >= MIN_PINNED_HEADINGS) shouldBe true
        }
        withClue("baseline anchors/headings no longer rendered") { lost.shouldBeEmpty() }
    }

    @Test
    fun `every docs route renders anonymously with one h1 and its own anchors`() {
        val broken =
            routes
                .filter { (_, node) -> node["status"].asText() == "doc" }
                .keys
                .mapNotNull { path ->
                    val html =
                        if (path == "/docs") {
                            SitePageRenderer.renderDocsIndex()
                        } else {
                            SitePageRenderer.renderDoc(path.removePrefix("/docs/"))
                        }
                    val main = main(html)
                    val h1s = H1.findAll(main).count()
                    val ids = ID.findAll(main).count()
                    when {
                        h1s != 1 -> "$path renders $h1s h1 elements"
                        ids < 1 -> "$path renders no anchor at all"
                        else -> null
                    }
                }
        broken.shouldBeEmpty()
    }

    @Test
    fun `every retirement on a rewritten page names its reason`() {
        val unexplained =
            routes.flatMap { (path, node) ->
                val retired = node["retired"] ?: return@flatMap emptyList()
                (retired["anchors"].properties() + retired["headings"].properties())
                    .filter { (_, reason) -> reason.asText().length < MIN_REASON_CHARS }
                    .map { (what, _) -> "$path: '$what' retired without a reason" }
            }
        unexplained.shouldBeEmpty()
        // Non-vacuity: the rewrite retired things, and every one is on record.
        routes.count { (_, node) -> node.has("retired") } shouldBe REWRITTEN_ROUTES
    }

    private fun main(html: String): String {
        val from = html.indexOf("<main")
        val to = html.lastIndexOf("</main>")
        check(from >= 0 && to > from) { "no <main> landmark in the render" }
        return html.substring(from, to)
    }

    private fun headingTexts(main: String): Set<String> =
        HEADING
            .findAll(main)
            .map { HtmlUtils.htmlUnescape(TAG.replace(it.groupValues[1], " ")).replace(WHITESPACE, " ").trim() }
            .toSet()

    private companion object {
        val MAPPER = ObjectMapper()
        const val BASELINE_ROUTES = 59
        const val BASELINE_MARKETING = 35
        const val BASELINE_DOCS = 24
        const val REWRITTEN_ROUTES = 4
        const val MIN_PINNED_HEADINGS = 400
        const val MIN_ANCHORS = 150
        const val MIN_REASON_CHARS = 20

        val ID = Regex("""\sid="([^"]+)"""")
        val H1 = Regex("""<h1[\s>]""")
        val HEADING = Regex("""<(?:h[1-6]|summary)\b[^>]*>(.*?)</(?:h[1-6]|summary)>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")
        val WHITESPACE = Regex("""\s+""")
    }
}
