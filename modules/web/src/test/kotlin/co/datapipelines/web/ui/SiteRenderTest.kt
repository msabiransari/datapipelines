package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The public marketing page (033): renders ANONYMOUSLY (no principal, no workspace — the
 * model is exactly what SiteController hands an anonymous request), with the tool-count
 * fact baked from the catalog, and with chrome free of unresolved expressions (033/B2 —
 * the page has no doc body, so the sweep is whole-page here).
 */
class SiteRenderTest {
    private val templateSource: String =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResource("classpath:templates/site/index.html")
            .inputStream
            .readBytes()
            .decodeToString()

    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    @Test
    fun `the marketing home renders anonymously with the catalog tool count and no unresolved expressions`() {
        val count = McpToolCatalog.NAMES.size
        val html =
            engine.process(
                "site/index",
                webContext().apply { setVariable("toolCount", count) },
            )

        html shouldContain "Agent-native Data Pipelines"
        // The three former hardcoded "18"s now render from the model (033/C4).
        html shouldContain "<span>$count</span> tools cover the full lifecycle"
        html shouldContain "/mcp — $count MCP tools"
        html shouldContain "($count tools)"
        // Assets resolve through the app's own static surface, never the retired website/ copy.
        html shouldContain "href=\"/vendor/design-system/tokens.css\""
        html shouldContain "href=\"/site/css/site.css\""
        html shouldContain "src=\"/site/img/execution-result.png\""
        html shouldContain "src=\"/site/js/site.js\""
        // The app serves this page now — sign-in is a route away.
        html shouldContain "href=\"/login\""

        html shouldNotContain "assets/"
        // " th:" with the leading space — an unprocessed th:* attribute. The root
        // element's xmlns:th namespace declaration survives every render legitimately.
        html shouldNotContain " th:"
        html shouldNotContain ("\${")
    }

    @Test
    fun `SiteController serves the site view with a public cache header and the catalog count`() {
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view = SiteController().home(model, response)

        view shouldBe "site/index"
        model["toolCount"] shouldBe McpToolCatalog.NAMES.size
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
                .findAll(templateSource)
                .flatMap { comment -> DOC_PATH.findAll(comment.value).map { it.value } }
                .distinct()
                .toList()
        // Non-vacuity, on both axes: the page carries dozens of claim comments, and they name a
        // dozen-plus distinct specs. A changed comment shape empties one or both and would
        // otherwise pass by checking nothing.
        val comments = CLAIM_COMMENT.findAll(templateSource).count()
        check(comments >= 40) { "the claim sweep found only $comments claim comments — has the comment shape changed?" }
        check(cited.size >= 12) { "the claim sweep found only ${cited.size} distinct cited docs" }

        val root = repoRoot()
        val missing = cited.filterNot { java.io.File(root, it).isFile }
        missing shouldBe emptyList()
        check(resolver.getResource("classpath:templates/site/index.html").exists())
    }

    /** The "What's in the box" section body — between its own h2 and the next section comment. */
    private fun featuresSection(): String {
        val from = templateSource.indexOf("id=\"cap-title\"")
        val to = templateSource.indexOf("<!-- ============================ DEMO DATA", from)
        check(from > 0 && to > from) { "the features section markers moved — fix this guard, do not delete it" }
        return templateSource.substring(from, to)
    }

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

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

    private companion object {
        /** One feature card, from its opening article tag to its close. */
        val CARD = Regex("""<article class="card[^"]*">.*?</article>""", RegexOption.DOT_MATCHES_ALL)
        val CLAIM = Regex("""<!--\s*claim:""")

        /** A whole claim comment, so several docs cited in one can all be swept. */
        val CLAIM_COMMENT = Regex("""<!--\s*claim:.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val DOC_PATH = Regex("""docs/[A-Za-z0-9._/-]+\.md""")
        val HEADING = Regex("""<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
    }
}
