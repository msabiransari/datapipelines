package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 076 §A width-policy guard, WIDENED in 079 §D from "no inline width" to **no inline
 * `style=` at all**, with an allowlist that is empty and must stay empty.
 *
 * 076's ban was on inline `max-width` and `grid-template-columns`, because four screens had
 * each picked their own pixel count and the single width policy was gone. The general case
 * is the same failure one level up: an inline declaration is a decision taken inside one
 * template, invisible to every other template and to every audit, and 330 of them across 30
 * screens is how a design system quietly becomes 30 designs. Two further reasons the narrow
 * ban could not reach:
 *
 *  - the only literal COLOUR left in any app template was an inline
 *    `background: rgba(0,0,0,0.5)` on three modal backdrops — a black wash that a dark
 *    theme renders over an already-black page. A rule about `max-width` could never
 *    have found it;
 *  - an app that carries inline styles can never adopt a Content Security Policy without
 *    `style-src 'unsafe-inline'`, which is most of what a style CSP is for.
 *
 * The replacement is the `u-*` utility layer plus semantic `app-*` classes, both at the foot
 * of app.css, both token-only.
 *
 * The sweep covers every app template — layouts/, the marketing site/ tree and the two
 * public docs views are EXCLUDED: the shell and the site own their own rules and are audited
 * by their own guards (SiteAssetAuditTest and friends). [ALLOWED] is the escape hatch and it
 * is EMPTY: an entry added to it is a debt with a name, not a quiet exception, and the test
 * below fails if anyone tries to use it without recording why.
 *
 * ## The one shape that is NOT an inline style, and why it is allowed
 *
 * 104's splitter (`static/js/splitter.js`) sizes two panes by writing ONE CSS custom
 * property — `--pe-dock-pane-h`, `--tplx-tree-w` — onto `document.documentElement` at
 * runtime. That is deliberately outside this audit and must stay allowed:
 *
 *  - it is a SCRIPT setting `element.style`, not a `style="…"` attribute in a template. The
 *    regex below cannot see it, a CSP's `style-src` does not forbid it (that directive is
 *    about the attribute and about `<style>`), and the ban's actual subject — a width
 *    decision taken inside one template and invisible to every other — does not apply: the
 *    value is a USER's, not an author's;
 *  - it is a custom property on the ROOT, so the stylesheets keep owning the geometry. Both
 *    `pipeline-editor.css` and `template-tree.css` declare the shipped default as the
 *    `var()` fallback, which is what makes "reset to default" mean "remove the property".
 *
 * The line the audit still draws, and which no round may cross: no `width`, `height`,
 * `max-width` or `grid-template-columns` written onto a CONTENT element, by a template or by
 * a script. One property, on the root, read by a stylesheet.
 *
 * ## HTML built in Kotlin — the hole this audit used to name, now closed (097 §C)
 *
 * Five controllers emitted markup as strings and some of it carried an inline style:
 * `AdminUsersPartialController`'s user table, `UserSettingsController.errorSpan`,
 * `TemplateEditorController`'s preview cards and edit refusal, `TemplatePartialController`'s
 * create refusal and `DatasourcePartialController`'s register refusal. A template sweep could
 * not see any of it, which is what made "no inline styles" a claim about the templates rather
 * than about the product.
 *
 * All five are Thymeleaf fragments now, and the SAME pattern runs over every module's
 * `main/kotlin` sources. The Kotlin sweep is the strict one: it does not distinguish a string
 * literal from prose, so a KDoc that wants to talk about this rule has to spell the attribute
 * out in words — which is exactly the constraint the template sweep already puts on template
 * comments, and cheaper than a Kotlin parser that could be argued with.
 *
 * (Kotlin nests block comments, so a glob written into a KDoc opens one. Spell directories
 * out here rather than learning that again.)
 */
class InlineWidthAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        run {
            val appTemplates =
                resolver
                    .getResources("classpath*:templates/**/*.html")
                    .toList()
                    .filter { it.filename != null }
                    .filterNot { resource ->
                        val path = resource.uri.path
                        path.contains("/templates/layouts/") ||
                            path.contains("/templates/site/") ||
                            resource.filename == "index-public.html" ||
                            resource.filename == "doc-public.html"
                    }
            appTemplates.associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }
        }

    /**
     * Every `.kt` under a module's main sources. The resolver walks the compiled classpath for
     * templates; Kotlin sources are not on it, so this walks the tree from the module root —
     * the test runs with `modules/web` as its working directory, and the repository root is
     * two levels up.
     */
    private val kotlinSources: Map<String, String> =
        java.io
            .File("../..")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/kotlin/") }
            .filterNot { it.path.contains("/build/") }
            .associate { it.path.substringAfter("/modules/") to it.readText() }

    @Test
    fun `the sweep covers the app templates`() {
        // Non-vacuity: the app ships dozens of templates. A resolver that stopped matching
        // would make the audit below pass by auditing nothing.
        templates.size shouldBeGreaterThanOrEqual 30
    }

    @Test
    fun `no app template carries an inline style attribute`() {
        templates.entries.shouldNotBeEmpty()

        val violations =
            templates
                .filterKeys { it !in ALLOWED }
                .flatMap { (name, source) ->
                    STYLE_ATTR
                        .findAll(source)
                        .map { it.groupValues[1] }
                        .map { "$name carries an inline style: style=\"$it\"" }
                }
        violations shouldBe emptyList()
    }

    /**
     * 097 §C — the same ban, over Kotlin. `InlineWidthAuditTest` swept templates only, so the
     * five controllers that built markup as strings were outside every audit the product had;
     * two of them carried inline colours and two more carried hand-picked widths.
     *
     * Falsified by re-adding one: a `style="color:red"` string in any main Kotlin source turns
     * this red.
     */
    @Test
    fun `no Kotlin source builds markup carrying an inline style attribute`() {
        val violations =
            kotlinSources
                .filterKeys { it !in ALLOWED_KOTLIN }
                .flatMap { (name, source) ->
                    STYLE_ATTR
                        .findAll(source)
                        .map { "$name carries an inline style: style=" + '"' + it.groupValues[1] + '"' }
                }
        violations shouldBe emptyList()
    }

    @Test
    fun `the Kotlin sweep is not vacuous - it reaches the sources that DO build markup`() {
        // Non-vacuity in the shape that matters: the sweep must reach files that emit HTML at
        // all, or a resolver that stopped matching would make the audit above pass by auditing
        // nothing. ToastHtml is the codebase's one deliberate Kotlin markup builder (§5.1
        // Shape A/B/C's out-of-band wrapper), pinned by ToastMarkupParityTest.
        kotlinSources.size shouldBeGreaterThanOrEqual 200
        kotlinSources.keys.count { it.endsWith("ui/ToastHtml.kt") } shouldBe 1
        kotlinSources.values.count { it.contains("<div") } shouldBeGreaterThanOrEqual 1
    }

    @Test
    fun `the allowlist is empty`() {
        // The allowlist EXISTS so that a future exception has to be written down, reviewed and
        // dated rather than smuggled in as a regex tweak. It is empty today and that is the
        // state this asserts — the guard the 076 lesson asked for ("an allowlist entry carries
        // reason + intended adopter + date, so it expires meaningfully instead of rotting").
        ALLOWED shouldBe emptySet<String>()
        ALLOWED_KOTLIN shouldBe emptySet<String>()
    }

    @Test
    fun `no app template carries a literal colour`() {
        // The companion finding: `rgba(0,0,0,0.5)` on three modal backdrops was the only
        // literal colour in any app template, and it survived the 076 audit because that
        // audit was about width. Colour belongs to the theme, always.
        val violations =
            templates.flatMap { (name, source) ->
                LITERAL_COLOUR
                    .findAll(source)
                    .map { "$name carries a literal colour: ${it.value}" }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `spot check - the classes replaced the inline widths`() {
        // The 076 §A conversions, pinned so a revert of the template half fails loudly:
        // the settings stack and a list-page search box carry the class, not the pixels.
        val settings = templates.getValue("settings/index.html")
        settings shouldNotContain "max-width"
        (settings.contains("app-reading")) shouldBe true

        val pipelineList = templates.getValue("pipelines/list.html")
        pipelineList shouldNotContain "max-width"
        (pipelineList.contains("app-search-input")) shouldBe true
    }

    private companion object {
        /**
         * A STATIC `style="…"` attribute. `x-bind:style` / `:style` / `th:style` are excluded by
         * the look-behind: those are DYNAMIC bindings (080's cards set a `--type` custom property
         * per node that way, as the approved mock did), and a CSP forbids the static attribute,
         * not a script setting `element.style`.
         */
        val STYLE_ATTR = Regex("""(?<![:\w-])style="([^"]*)"""")

        /**
         * Empty, and asserted empty above. An entry here would be a template that may keep an
         * inline style, and it must carry the reason, the round that will clear it and the
         * date — see the `the allowlist is empty` test.
         */
        val ALLOWED: Set<String> = emptySet()

        /**
         * The Kotlin half's allowlist (097 §C). Empty, and asserted empty: an entry here would
         * be a source file that may keep an inline style, and it must carry the reason, the
         * round that will clear it and the date.
         */
        val ALLOWED_KOTLIN: Set<String> = emptySet()

        /**
         * `#rrggbb`, `#rrggbbaa`, and the functional colour notations.
         *
         * THREE-digit hex is deliberately NOT matched: `#abc` is indistinguishable from an
         * htmx target id, and this codebase is full of `hx-target="#..."`. Six digits is
         * where the real risk lives (a colour pasted from a mock) and it is unambiguous —
         * a pattern that cannot tell a violation from an id would be worse than no pattern,
         * because it would be silenced with an exception list.
         */
        val LITERAL_COLOUR = Regex("""(?:#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?\b|\b(?:rgba?|hsla?)\()""")
    }
}
