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
 * ## What this audit deliberately does NOT reach, and who should
 *
 * **HTML built in Kotlin.** Five controllers emit markup as strings, and some of it still
 * carries an inline style — `UserSettingsController.errorSpan`, `TemplateEditorController`'s
 * server-side twin of the preview-failure card, and the row builders that must stay
 * byte-identical to a Thymeleaf fragment. A template sweep cannot see any of it. That is a
 * real remaining hole and it is named here rather than left to be rediscovered: whichever
 * round owns Kotlin-emitted HTML should widen the pattern to `main/kotlin` sources, and the
 * byte-pinned row builders must move in lockstep with their fragments when it does
 * (`TemplateHtmxRenderAuditTest` is what makes that safe).
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

    @Test
    fun `the allowlist is empty`() {
        // The allowlist EXISTS so that a future exception has to be written down, reviewed and
        // dated rather than smuggled in as a regex tweak. It is empty today and that is the
        // state this asserts — the guard the 076 lesson asked for ("an allowlist entry carries
        // reason + intended adopter + date, so it expires meaningfully instead of rotting").
        ALLOWED shouldBe emptySet<String>()
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
        val STYLE_ATTR = Regex("""\bstyle="([^"]*)"""")

        /**
         * Empty, and asserted empty above. An entry here would be a template that may keep an
         * inline style, and it must carry the reason, the round that will clear it and the
         * date — see the `the allowlist is empty` test.
         */
        val ALLOWED: Set<String> = emptySet()

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
