package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * #398 A.3's route decision, pinned before the workspace route shipped on it: on this base,
 * Spring MVC resolves patterns with `PathPatternParser` (Boot 3's default — the app sets no
 * `spring.mvc.pathmatch` override, and the only `WebMvcConfigurer` on the tree registers the
 * marketing site's asset tree), so the brief's question — "does the capture-everything
 * pattern under /templates capture a dotted, multi-segment name while the literal
 * `/templates` and `/templates/editor` still win?" — has a YES answer, asserted here against
 * the parser Spring itself routes with and its own specificity comparator:
 *
 * - a dotted, multi-segment name (`demo/top_carrier.sql`) is captured WHOLE — a dot is a
 *   literal character under the parser, and there is no suffix-pattern matching to truncate;
 * - a two-segment `.html` name captures the same way (no static-resource capture: the app's
 *   resource handlers cover /css, /js, /vendor and /site trees — none under the /templates
 *   prefix);
 * - the literal routes win on `PathPattern.SPECIFICITY_COMPARATOR` (the comparator sorts
 *   more-specific-first: a literal segment outranks a capture, and a capture-everything
 *   pattern is the least specific), so the editor redirect and the catalog are never
 *   swallowed;
 * - `/templates` alone does NOT match the capture-everything pattern — a name is required;
 * - the bare trailing-slash path DOES match, with an EMPTY captured name — the workspace
 *   handler answers it with the family's 404 (an empty name is not a legal template name),
 *   which is the handler's grammar check, not the routing's;
 * - a path that climbs a segment (`..`) is not the parser's business — it is refused
 *   upstream by Spring Security's firewall, and the workspace handler validates every
 *   captured name against `TemplateNameGrammar` before it looks anything up, so the value
 *   can never reach a read as a folder path.
 *
 * If the parser assumptions ever fail here, `PathPatternParser` is no longer the routing
 * strategy: move the canonical page to `GET /templates/workspace?name=` (the brief's
 * fallback) and re-aim.
 */
class TemplateWorkspaceRouteTest {
    private val parser = PathPatternParser()

    /** The patterns exactly as the three handlers of the `/templates` family map them. */
    private val canonical: PathPattern = parser.parse(CANONICAL_PATTERN)
    private val catalog: PathPattern = parser.parse("/templates")
    private val editor: PathPattern = parser.parse("/templates/editor")

    private data class Routed(
        val pattern: PathPattern?,
        val name: String?,
    )

    /** Spring's own routing: the most specific matching pattern (the comparator sorts more-specific-first). */
    private fun route(path: String): Routed {
        val container = PathContainer.parsePath(path)
        val candidates = listOf(catalog, editor, canonical).filter { it.matches(container) }
        val best = candidates.minWithOrNull(PathPattern.SPECIFICITY_COMPARATOR)
        val name = best?.matchAndExtract(container)?.uriVariables?.get("name")
        return Routed(best, name)
    }

    @Test
    fun `a dotted multi-segment template name is captured whole`() {
        val hit = route("/templates/demo/top_carrier.sql")
        hit.pattern shouldBe canonical
        hit.name shouldBe "/demo/top_carrier.sql"
    }

    @Test
    fun `a two-segment html name is captured, never a static resource`() {
        val hit = route("/templates/demo/page.html")
        hit.pattern shouldBe canonical
        hit.name shouldBe "/demo/page.html"
    }

    @Test
    fun `a deep path is still one capture`() {
        val deep = (1..9).joinToString("/") { "s$it" } + "/leaf.sql"
        val hit = route("/templates/$deep")
        hit.pattern shouldBe canonical
        hit.name shouldBe "/$deep"
    }

    @Test
    fun `the literal editor route wins over the capture`() {
        route("/templates/editor").pattern shouldBe editor
    }

    @Test
    fun `the catalog route is exact and never the capture`() {
        route("/templates").pattern shouldBe catalog
    }

    @Test
    fun `a path outside the family matches nothing of it`() {
        route("/templatesx/demo/a.sql").pattern shouldBe null
        route("/templatesx").pattern shouldBe null
    }

    @Test
    fun `the capture requires a name - the bare trailing slash captures only the slash`() {
        val hit = route("/templates/")
        hit.pattern shouldBe canonical
        hit.name shouldBe "/"
        // The handler's answer is the family's 404: a bare slash is not a legal template
        // name, and the grammar check is where that is decided — never the routing.
    }

    @Test
    fun `a climb segment is not the parser's business - the grammar check refuses the name`() {
        // The parser matches the path literally; a captured name with a climb segment is
        // refused by TemplateNameGrammar in the handler (and by Spring Security's firewall
        // upstream). Pinned here so the routing's own behavior is on the record.
        val hit = route("/templates/../secret")
        hit.pattern shouldBe canonical
        hit.name shouldBe "/../secret"
    }

    @Test
    fun `the capture-everything pattern is the least specific of the family`() {
        PathPattern.SPECIFICITY_COMPARATOR.compare(canonical, catalog) shouldNotBe 0
        (PathPattern.SPECIFICITY_COMPARATOR.compare(canonical, catalog) > 0) shouldBe true
        (PathPattern.SPECIFICITY_COMPARATOR.compare(canonical, editor) > 0) shouldBe true
    }

    private companion object {
        /** What the workspace controller maps — the doc's §7.6 spelling is this string. */
        const val CANONICAL_PATTERN = "/templates/{*name}"
    }
}
