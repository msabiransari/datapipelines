package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 090 §B — every Alpine root is cloaked, and the rule that makes cloaking mean anything is
 * app-wide.
 *
 * An `x-data` root is markup that is WRONG until Alpine has initialised it: `x-show` has not
 * hidden anything yet, `x-text` still holds whatever the template author typed, and
 * `alpine.min.js` is `defer`red, so the browser paints that state first. `x-cloak` plus a
 * `[x-cloak]{display:none}` rule is the whole remedy — and the attribute is inert without the
 * rule, which is what made this worth auditing rather than eyeballing: the rule lived only in
 * `pipeline-editor.css`, a PAGE-scoped sheet, so the six `x-cloak` attributes in the editor
 * worked and an Alpine root added on any other screen would have silently flashed.
 *
 * Two assertions, deliberately at different layers:
 *   - the SOURCE sweep below (the round's brief: "no element with `[x-data]` lacking
 *     `[x-cloak]` exists in the source"), which is what stops the next Alpine root from
 *     arriving uncloaked;
 *   - the CSS assertion, which is what stops the attribute from quietly becoming decoration
 *     again. A sweep that only counted attributes would have passed on the exact codebase
 *     that shipped the defect.
 *
 * Nesting counts: an `x-data` inside an already-cloaked ancestor is hidden with it, and
 * demanding its own attribute would be noise. The sweep therefore reads the source as text
 * and asks whether the root's own tag carries `x-cloak` — the nested case is handled by
 * naming it in [CLOAKED_BY_ANCESTOR], with the ancestor recorded, so a future move out of
 * that ancestor has to be re-argued rather than inherited.
 */
class AlpineCloakAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `the sweep covers the templates`() {
        // Non-vacuity: a resolver that stopped matching would make every assertion below
        // pass by auditing nothing. (MISTAKES.md, "Coverage is not existence".)
        templates.size shouldBeGreaterThanOrEqual 30
    }

    @Test
    fun `the sweep actually finds Alpine roots`() {
        // The second non-vacuity floor, and the sharper one: the app has few Alpine roots,
        // so a pattern that matched none would look exactly like a clean audit.
        templates.values.sumOf { X_DATA_TAG.findAll(it).count() } shouldBeGreaterThanOrEqual 2
    }

    @Test
    fun `every Alpine root carries x-cloak, or is named as covered by a cloaked ancestor`() {
        val violations =
            templates.flatMap { (name, source) ->
                X_DATA_TAG
                    .findAll(source)
                    .map { it.value }
                    .filterNot { tag -> tag.contains("x-cloak") }
                    .filterNot { tag -> CLOAKED_BY_ANCESTOR.any { tag.contains(it) } }
                    .map { tag -> "$name has an uncloaked Alpine root: ${tag.take(90)}" }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `the cloak rule is declared in the app-wide stylesheet, not in a page-scoped one`() {
        val appCss =
            resolver
                .getResource("classpath:static/css/app.css")
                .inputStream
                .readBytes()
                .decodeToString()
        appCss shouldContain "[x-cloak]"
        appCss shouldContain "display: none !important"
    }

    private companion object {
        /** An opening tag that declares an Alpine component. */
        val X_DATA_TAG = Regex("""<[a-zA-Z][^>]*\sx-data[^>]*>""")

        /**
         * Alpine roots whose visibility is already owned by a cloaked ancestor. One entry:
         * `pipelines/editor.html`'s per-result failure view, which lives inside
         * `#pe-pane-results` — a dock pane that carries `x-cloak` itself.
         */
        val CLOAKED_BY_ANCESTOR = setOf("""x-data="{ view: failureView(entry.record) }"""")
    }
}
