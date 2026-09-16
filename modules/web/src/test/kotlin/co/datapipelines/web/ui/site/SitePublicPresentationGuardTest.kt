package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The public site's presentation rules, as guards that cover the PUBLIC site (145 §5, §8.4
 * — `AppCssTokenAuditTest` covers the app's sheet only, and nothing covered these before):
 *
 *  1. **Tokens only.** `site.css` states a concrete colour (hex, rgb/rgba, hsl/hsla) in ONE
 *     place — the site token block — and every rule below it resolves colour through a
 *     token. A literal anywhere else fails, with its line.
 *  2. **No inline styling, anywhere on the public surface.** No `style=` or `th:style`
 *     attribute, no `<style>` element, no `on*=` handler attribute, in any site template or
 *     public docs view; and no inline `<script>` body in the layout (JSON-LD blocks are data
 *     and are excluded by their type). `site.js` injects no style: no `.style`, no
 *     `cssText`, no `innerHTML`/`insertAdjacentHTML`/`document.write`.
 *  3. **Light only.** Neither the site sheet nor the generated bundle carries a dark media
 *     query or a `data-theme` selector; the bundle's theme source is `themes/light.css`; the
 *     layout carries no theme toggle and no theme storage key.
 *  4. **No external font or asset host** in the site sheets (the vendored-fonts audit covers
 *     the app's; this covers the site's `url()`s and `@import`s).
 *
 * Each sweep proves it saw something (template count, rule count, a positive control on
 * its own pattern) before it asserts nothing was found — falsified at birth by injecting a
 * `style="color: red"` into a template and a `#ff0000` below the token block.
 */
class SitePublicPresentationGuardTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private fun read(path: String): String =
        resolver
            .getResource("classpath:$path")
            .inputStream
            .readBytes()
            .decodeToString()

    private val templates: Map<String, String> =
        (
            resolver.getResources("classpath*:templates/site/*.html").toList() +
                resolver.getResources("classpath*:templates/docs/*-public.html").toList()
        ).filter { it.filename != null }
            .associate { it.filename!! to it.inputStream.readBytes().decodeToString() }

    private val siteCss: String = read("static/site/css/site.css")
    private val bundle: String = read("static/site/css/site-chrome.css")
    private val siteJs: String = read("static/site/js/site.js")

    @Test
    fun `site css states concrete colours only inside the site token block`() {
        val tokenBlockStart = siteCss.indexOf(TOKEN_BLOCK_MARKER)
        check(tokenBlockStart > 0) { "site.css lost its SITE TOKENS marker — fix this guard, do not delete it" }
        val tokenBlockEnd = siteCss.indexOf("\n}\n", tokenBlockStart)
        val outside = withoutComments(siteCss.substring(0, tokenBlockStart) + siteCss.substring(tokenBlockEnd))
        val literals =
            outside
                .lines()
                .withIndex()
                .filter { (_, line) -> COLOUR_LITERAL.containsMatchIn(line) }
                .map { (i, line) -> "site.css:${i + 1} (outside the token block): ${line.trim()}" }
        withClue("colour literals outside the site token block") { literals.shouldBeEmpty() }
        // Non-vacuity, both ways: the token block itself carries the palette, and the
        // pattern fires on a literal.
        COLOUR_LITERAL.findAll(siteCss.substring(tokenBlockStart, tokenBlockEnd)).count() shouldBeGreaterThanOrEqual MIN_PALETTE_LITERALS
        COLOUR_LITERAL.containsMatchIn("color: #ff0000;") shouldBe true
        COLOUR_LITERAL.containsMatchIn("background: rgba(0, 0, 0, .5);") shouldBe true
        siteCss.lines().size shouldBeGreaterThanOrEqual MIN_SITE_CSS_LINES
    }

    @Test
    fun `no public template carries an inline style, a style element or a handler attribute`() {
        templates.size shouldBeGreaterThanOrEqual MIN_TEMPLATES
        val offenders =
            templates.flatMap { (name, source) ->
                withoutComments(source).lines().withIndex().mapNotNull { (i, line) ->
                    when {
                        INLINE_STYLE.containsMatchIn(line) -> "$name:${i + 1} inline style: ${line.trim()}"
                        STYLE_ELEMENT.containsMatchIn(line) -> "$name:${i + 1} style element: ${line.trim()}"
                        HANDLER_ATTR.containsMatchIn(line) -> "$name:${i + 1} handler attribute: ${line.trim()}"
                        else -> null
                    }
                }
            }
        withClue("inline styling on the public surface") { offenders.shouldBeEmpty() }
        // Positive controls: the patterns see what they are for.
        INLINE_STYLE.containsMatchIn("""<div style="color: red">""") shouldBe true
        INLINE_STYLE.containsMatchIn("""<div th:style="${'$'}{x}">""") shouldBe true
        HANDLER_ATTR.containsMatchIn("""<a onclick="go()">""") shouldBe true
    }

    @Test
    fun `the layout has no inline script body and the site script injects no style`() {
        val layout = templates.getValue("_layout.html")
        val inlineScripts =
            SCRIPT
                .findAll(layout)
                .filter { "application/ld+json" !in it.groupValues[1] && "src=" !in it.groupValues[1] }
                .map { it.value.take(80) }
                .toList()
        withClue("inline script bodies in the layout") { inlineScripts.shouldBeEmpty() }
        // The one script the layout loads is the deferred site.js.
        layout shouldContain "site/js/site.js"

        val injections =
            siteJs.lines().withIndex().filter { (_, line) -> STYLE_INJECTION.containsMatchIn(line) }.map { (i, line) ->
                "site.js:${i + 1}: ${line.trim()}"
            }
        withClue("style injection in site.js") { injections.shouldBeEmpty() }
        STYLE_INJECTION.containsMatchIn("el.style.width = '3px'") shouldBe true
        siteJs.lines().size shouldBeGreaterThanOrEqual MIN_SITE_JS_LINES
    }

    @Test
    fun `the public site is light only`() {
        listOf("site.css" to siteCss, "site-chrome.css" to bundle).forEach { (name, css) ->
            val code = withoutComments(css)
            withClue("$name carries a dark media query") { code shouldNotContain "prefers-color-scheme" }
            withClue("$name carries a data-theme selector") { code shouldNotContain "data-theme" }
        }
        bundle shouldContain "static/vendor/design-system/themes/light.css"
        bundle shouldNotContain "themes/auto.css"
        siteCss shouldContain "color-scheme: light"
        val layout = templates.getValue("_layout.html")
        layout shouldNotContain "theme-toggle"
        layout shouldNotContain "dp-site-theme"
        layout shouldNotContain "themes/dark.css"
        siteJs shouldNotContain "dp-site-theme"
    }

    @Test
    fun `the site sheets load nothing from another host`() {
        listOf("site.css" to siteCss, "site-fonts-mono.css" to read("static/site/css/site-fonts-mono.css")).forEach { (name, css) ->
            val external = EXTERNAL_URL.findAll(css).map { it.value }.toList()
            withClue("$name reaches another host") { external.shouldBeEmpty() }
            withClue("$name imports a sheet") { css shouldNotContain "@import" }
        }
        // Non-vacuity: the site sheet does declare its faces by relative url().
        FONT_URL.findAll(siteCss).count() shouldBeGreaterThanOrEqual MIN_FONT_URLS
    }

    /** The text with every CSS or HTML comment blanked (newlines kept, so a finding's line is real). */
    private fun withoutComments(text: String): String = COMMENT.replace(text) { match -> match.value.filter { it == '\n' } }

    private companion object {
        const val TOKEN_BLOCK_MARKER = "SITE TOKENS"
        const val MIN_PALETTE_LITERALS = 20
        const val MIN_SITE_CSS_LINES = 1500
        const val MIN_SITE_JS_LINES = 100
        const val MIN_TEMPLATES = 37
        const val MIN_FONT_URLS = 2

        val COLOUR_LITERAL = Regex("""(?:#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?)\()""")
        val INLINE_STYLE = Regex("""\s(?:th:)?style\s*=""")
        val STYLE_ELEMENT = Regex("""<style\b""", RegexOption.IGNORE_CASE)
        val HANDLER_ATTR = Regex("""\son[a-z]+\s*=\s*["']""", RegexOption.IGNORE_CASE)
        val SCRIPT = Regex("""<script\b([^>]*)>""")
        val STYLE_INJECTION = Regex("""\.style\b|cssText|innerHTML|insertAdjacentHTML|document\.write""")
        val EXTERNAL_URL = Regex("""url\(\s*["']?(?:https?:)?//""")
        val FONT_URL = Regex("""url\("\.\./\.\./vendor/fonts/[^"]+\.woff2"\)""")
        val COMMENT = Regex("""/\*.*?\*/|<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    }
}
