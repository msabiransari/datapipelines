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

    /**
     * The exception list is the audit's weak point: with both of today's roots named in it,
     * the sweep above would stay green while saying nothing. Pinning its SIZE is what makes
     * a third exception a deliberate, reviewed act instead of a one-line regex tweak — the
     * same reason `InlineWidthAuditTest` asserts its allowlist is empty.
     */
    @Test
    fun `the named exceptions are exactly the two that are argued for`() {
        CLOAKED_BY_ANCESTOR.size shouldBe 2
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
         * The two Alpine roots that do not carry their own `x-cloak`, each with the reason
         * recorded so it has to be re-argued rather than inherited — the house allowlist
         * pattern (`InlineWidthAuditTest.ALLOWED`).
         *
         *  - the per-result failure view lives inside `#pe-pane-results`, a dock pane that
         *    carries `x-cloak` itself, so it is already hidden with its ancestor;
         *  - `.pe-root` MUST NOT be cloaked. Measured (090): `display:none` on it hides
         *    `#cy-canvas` while Cytoscape initialises against that element, so the graph is
         *    built in a zero-size container and never recovers — no node cards render, and
         *    `PipelineEditorDetailsBrowserTest` waits out its 30s on a `.pe-card-open` that
         *    resolves in the DOM and never becomes visible. The unbound content inside this
         *    root (the banner, the six dock panes) is cloaked individually, which is where
         *    the flash would have been; the root itself is a flex container of
         *    server-rendered chrome. Cloaking it trades a flash nobody measured for an
         *    editor that does not work.
         */
        val CLOAKED_BY_ANCESTOR =
            setOf(
                """x-data="{ view: failureView(entry.record) }"""",
                """x-data="pipelineEditor()"""",
            )
    }
}
