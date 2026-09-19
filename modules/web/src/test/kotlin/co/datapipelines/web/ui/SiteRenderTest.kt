package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.web.ui.site.SiteFacts
import co.datapipelines.web.ui.site.SitePageRenderer
import co.datapipelines.web.ui.site.SitePages
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap

/**
 * The public marketing page (033): renders ANONYMOUSLY (no principal, no workspace — the
 * model is exactly what SiteController hands an anonymous request), with the tool-count
 * fact baked from the catalog, and with chrome free of unresolved expressions (033/B2 —
 * the page has no doc body, so the sweep is whole-page here).
 *
 * 073: the render goes through [SitePageRenderer], which calls the REAL controller. Building
 * the model here instead would have kept passing after the head moved to the SitePages
 * registry — while serving a page with an empty `<title>`.
 */
class SiteRenderTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    @Test
    fun `the marketing home renders anonymously with the recorded demo run and no unresolved expressions`() {
        val html = SitePageRenderer.render(SitePages.HOME)

        // 145: the H1 speaks the outcome (the approved preview); the engineering story lives
        // on /how-it-works (SiteBuyerLanguageTest holds the fold).
        html shouldContain "Clear answers."
        html shouldContain "<title>${SitePages.HOME.title}</title>"
        // Assets resolve through the app's own static surface, never the retired website/ copy.
        // 111 §C: the foundation sheets ride the generated site-chrome.css bundle (one
        // render-blocking request, parity-guarded by SiteCssBundleParityTest). 145: no theme
        // swap sheet and no inline theme script — the public site is light-only.
        html shouldContain "href=\"/site/css/site-chrome.css\""
        html shouldNotContain "themes/auto.css"
        html shouldNotContain "dp-site-theme"
        // 145 §4: the worked example is the recorded demo run (SiteFacts' demo block) shown
        // three ways — the question, the rows as a chart, the endpoint and the agent's steps.
        // No placeholder, no mocked dashboard: a stale or invented product shot is a false claim.
        html shouldContain "class=\"demo\" id=\"example\""
        html shouldContain "Which rideshare company carried the most trips in each borough last quarter?"
        html shouldContain "role=\"tablist\""
        html shouldContain "Staten Island"
        html shouldContain SiteFacts.current().demo.endpoint
        html shouldContain "src=\"/site/js/site.js\""
        // The app serves this page now — sign-in is a route away.
        html shouldContain "href=\"/login\""
        // 160: the video placeholder is gone entirely — "recording coming" was a promise on a
        // marketing page. The slot stays dead until a real recording exists; no fake control.
        html shouldNotContain "pipeline-video"
        html shouldNotContain "Recording coming after testing"
        html shouldNotContain "<video"

        html shouldNotContain "assets/"
        // " th:" with the leading space — an unprocessed th:* attribute. The root
        // element's xmlns:th namespace declaration survives every render legitimately.
        html shouldNotContain " th:"
        html shouldNotContain ("\${")
    }

    @Test
    fun `the engineering page renders the moved sections with the catalog tool count`() {
        // 115 §A.3: the homepage's engineering sections moved here verbatim — the agent loop
        // with its live tool count, and the recorded run. 145 keeps all of it under the
        // walkthrough; the old H1 is the depth's H2 and every anchor survives.
        val count = McpToolCatalog.NAMES.size
        val html = SitePageRenderer.render(SitePages.HOW_IT_WORKS)

        html shouldContain "How it works, for the engineer who has to run it"
        html shouldContain "id=\"hiw-title\""
        html shouldContain "<title>${SitePages.HOW_IT_WORKS.title}</title>"
        // The moved facts, still derived from the catalog rather than transcribed (033/C4).
        html shouldContain "<span>$count</span> tools cover the full lifecycle"
        html shouldContain "/mcp — $count MCP tools"
        html shouldContain "($count tools)"
        // 133 §B.3: the run is a rendered console and the endpoint a rendered panel.
        html shouldContain "class=\"console\""
        // 169 §B: the three screenshot slots are filled with real captures — labelled frames,
        // every picture a driver photograph (see the image guard below).
        listOf("shot-pipeline", "shot-results", "shot-release").forEach { html shouldContain "id=\"$it\"" }
        html shouldNotContain " th:"
        html shouldNotContain ("\${")
    }

    /**
     * 169 §E — every `<img>` on the two capture pages resolves to a PACKAGED file under
     * `static/site/img/`, with its intrinsic size declared (no layout shift), a descriptive
     * alt, and lazy loading except the home strip's first picture (the one image allowed to
     * eager-load, as it sits just under the fold). A missing file, a third-party URL, an
     * undeclared size or a marketing-claim alt is a red build — the page can no longer
     * accumulate a silent broken picture.
     */
    @Test
    fun `every screenshot on the capture pages is a packaged site image with its size declared`() {
        val pages = listOf(SitePages.HOME, SitePages.HOW_IT_WORKS).associateWith { SitePageRenderer.render(it) }

        val images =
            pages.values.sumOf { html -> IMG.findAll(html).count() }
        check(images >= MIN_IMAGES) { "the image sweep saw only $images images across the two pages" }

        val offenders =
            pages.flatMap { (page, html) ->
                IMG.findAll(html).mapNotNull { match ->
                    val tag = match.value
                    imageOffender(page.path, tag)
                }
            }
        offenders shouldBe emptyList()
        // Positive control: the guard sees a real reference and a real absence, not nothing.
        PACKAGE_PATH.matches("/site/img/editor-hero.png") shouldBe true
        PACKAGE_PATH.matches("https://cdn.example.com/pic.png") shouldBe false
    }

    /** The reason [tag] on [path] breaks the capture rules, or null when it complies. */
    private fun imageOffender(
        path: String,
        tag: String,
    ): String? {
        val src = ATTR.find(tag)?.groupValues?.get(1) ?: return "$path: an img without src"
        return packagingOffender(path, src) ?: sizingOffender(path, src, tag)
    }

    /** The packaged-asset rules: a site capture is the page's ONLY allowed image source. */
    private fun packagingOffender(
        path: String,
        src: String,
    ): String? =
        when {
            !PACKAGE_PATH.matches(src) -> "$path: $src is not a packaged /site/img/ capture"
            !resolver.getResource("classpath:static$src").exists() -> "$path: $src resolves to no packaged file"
            else -> null
        }

    /** The layout and labelling rules: declared size, described, lazy unless the home strip's eager lead. */
    private fun sizingOffender(
        path: String,
        src: String,
        tag: String,
    ): String? {
        val eagerLead = path == SitePages.HOME.path && src == EAGER_ALLOWED
        val altLength =
            ALT
                .find(tag)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.length ?: 0
        return when {
            WIDTH_HEIGHT.find(tag) == null -> "$path: $src carries no explicit width and height"
            altLength < MIN_ALT_CHARS -> "$path: $src carries no descriptive alt"
            LAZY !in tag && !eagerLead -> "$path: $src is neither lazy nor the home strip's eager lead"
            else -> null
        }
    }

    @Test
    fun `SiteController serves the site view with a public cache header and the catalog count`() {
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view = SiteController().home(model, response)

        view shouldBe "site/index"
        (model["facts"] as SiteFacts).toolCount shouldBe McpToolCatalog.NAMES.size
        model["canonicalUrl"] shouldBe SitePages.HOME.canonical
        response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "max-age=300, public"
    }

    /**
     * The 024b rule as a GUARD instead of a reminder (070 §D): every feature card in
     * "What's in the box" carries a `<!-- claim: docs/… -->` comment before its heading, and
     * every `docs/<file>.md` any claim on the page cites EXISTS in the tree.
     *
     * The round that introduced the rule was held because the claim table was missing; a
     * reminder is not a mechanism. This reads the TEMPLATE SOURCE, not the render, because the
     * comments are the audit trail for a human reading view-source and Thymeleaf keeps them —
     * but the source is where a missing one is a defect.
     *
     * Scoped to cards (`<article class="card">`) rather than to every `<h3>`: the section's
     * four group labels (Author / Run / Operate / Govern) are headings too and cite nothing,
     * because they assert nothing.
     *
     * 115 §A.3: the section moved from the homepage to `/how-it-works`, so this guard reads
     * that template now — the move must not have moved the claims away from the cards.
     */
    @Test
    fun `every feature card cites a claim, and every cited doc exists`() {
        val section = featuresSection()

        val cards = CARD.findAll(section).map { it.value }.toList()
        // Non-vacuity: a section that stopped matching would pass every check below by
        // having nothing to check — the exact failure this guard exists to prevent.
        check(cards.size >= 15) { "expected the feature grid to still hold its cards, found ${cards.size}" }

        val uncited =
            cards
                .filter { card ->
                    CLAIM
                        .find(card)
                        ?.range
                        ?.first
                        ?.let { it < card.indexOf("<h3") } != true
                }.map { card -> HEADING.find(card)?.groupValues?.get(1) ?: card.take(120) }
        uncited shouldBe emptyList()

        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        // A claim comment may cite SEVERAL docs ("docs/pipeline-contract.md §13,
        // docs/rest-api.md §4.2") — sweep every path in every comment, not the first of each.
        val cited =
            CLAIM_COMMENT
                .findAll(howItWorksSource)
                .flatMap { comment -> DOC_PATH.findAll(comment.value).map { it.value } }
                .distinct()
                .toList()
        // Non-vacuity, on both axes: the page carries dozens of claim comments, and they name a
        // dozen-plus distinct specs. A changed comment shape empties one or both and would
        // otherwise pass by checking nothing.
        val comments = CLAIM_COMMENT.findAll(howItWorksSource).count()
        check(comments >= 40) { "the claim sweep found only $comments claim comments — has the comment shape changed?" }
        check(cited.size >= 12) { "the claim sweep found only ${cited.size} distinct cited docs" }

        val root = repoRoot()
        val missing = cited.filterNot { java.io.File(root, it).isFile }
        missing shouldBe emptyList()
        check(resolver.getResource("classpath:templates/site/how-it-works.html").exists())
    }

    /** The "What's in the box" section body — between its own h2 and the next section comment. */
    private fun featuresSection(): String {
        val from = howItWorksSource.indexOf("id=\"cap-title\"")
        val to = howItWorksSource.indexOf("<!-- ============================ SECURITY", from)
        check(from > 0 && to > from) { "the features section markers moved — fix this guard, do not delete it" }
        return howItWorksSource.substring(from, to)
    }

    /** The engineering page's template source — where 115 moved the "What's in the box" section. */
    private val howItWorksSource: String =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResource("classpath:templates/site/how-it-works.html")
            .inputStream
            .readBytes()
            .decodeToString()

    /**
     * The repo root: walk up from the test JVM's working directory until the `docs` tree the
     * claims cite is there. Derived, not assumed — a module move must not silently make this
     * guard check nothing (it would then find every doc "missing", which is the loud direction).
     */
    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "docs").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${java.io.File("").absolutePath} holds a docs/ directory" }
    }

    private companion object {
        /** One feature card, from its opening article tag to its close. */
        val CARD = Regex("""<article class="card[^"]*">.*?</article>""", RegexOption.DOT_MATCHES_ALL)
        val CLAIM = Regex("""<!--\s*claim:""")

        /** A whole claim comment, so several docs cited in one can all be swept. */
        val CLAIM_COMMENT = Regex("""<!--\s*claim:.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val DOC_PATH = Regex("""docs/[A-Za-z0-9._/-]+\.md""")
        val HEADING = Regex("""<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)

        /** 169: the two capture pages carry this many driver photographs between them. */
        const val MIN_IMAGES = 17
        const val MIN_ALT_CHARS = 20

        /** A packaged capture reference: nothing but the site's own image directory. */
        val PACKAGE_PATH = Regex("""/site/img/[a-z0-9-]+\.png""")

        /** The one eager image: the home strip's lead capture, just under the fold. */
        const val EAGER_ALLOWED = "/site/img/editor-hero.png"

        val IMG = Regex("""<img\b[^>]*>""")
        val ATTR = Regex("""\bsrc="([^"]*)"""")
        val ALT = Regex("""\balt="([^"]*)"""")
        val WIDTH_HEIGHT = Regex("""\bwidth="\d+"[^>]*\sheight="\d+"""")
        const val LAZY = """loading="lazy""""
    }
}
