package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #509 — danger TEXT has ONE name in the first-party sheets: `--app-danger-ink`.
 *
 * `DangerInkContrastBrowserTest` measures the token on every vendored theme, but it can only
 * render the sites one page carries; the editor's card foot, the dock count, the schedule
 * callout and the dashboard messages live on pages of their own. They are held here instead,
 * at the source: no first-party rule paints `color` with `--accent-danger` (the FILL red,
 * 3.06–3.89:1 on the dark surfaces) or with the vendored `--text-danger` directly (today's red in
 * light, 4.09:1 on ocean's inset surface). Borders and backgrounds keep `--accent-danger`: a
 * component boundary is held to 3:1, and the solid danger button carries the vendored white.
 *
 * The brief's own inventory grep (`color:\s*var\(--accent-danger\)`) missed the seven
 * `pipeline-editor.css` sites, which spell the read with a fallback (`var(--accent-danger,
 * #dc2626)`); the pattern here matches both spellings and both names.
 */
class DangerInkSourceAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val sheets: Map<String, String> =
        resolver
            .getResources("classpath:static/css/*.css")
            .filter { it.filename != null }
            .associate { resource ->
                val name = requireNotNull(resource.filename)
                name to
                    resource.inputStream
                        .readBytes()
                        .decodeToString()
                        .withoutBlockComments()
            }

    private fun String.withoutBlockComments(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")

    private fun hits(pattern: Regex): List<String> =
        sheets.flatMap { (name, css) ->
            css.lines().filter { pattern.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
        }

    @Test
    fun `no first-party rule paints danger text with the fill red or the raw vendored text red`() {
        val stray = hits(STRAY_TEXT)
        withClue(stray.joinToString("\n")) { stray shouldBe emptyList() }
    }

    @Test
    fun `the ink is declared once, from the design system's danger roles, and read by every text site`() {
        val appCss = sheets.getValue("app.css")
        val declarations = DECLARATION.findAll(appCss).map { it.groupValues[1].trim() }.toList()
        declarations.size shouldBe 1
        val declared = declarations.single()
        declared shouldContain "light-dark("
        declared shouldContain "var(--text-danger)"
        // Non-vacuity: the sheets are the real ones, and the readers are all there — the
        // twenty text sites #509 moved (seventeen fill-red reads plus three raw text-red reads).
        sheets.size shouldBeGreaterThanOrEqual MIN_SHEETS
        hits(INK_READ).size shouldBeGreaterThanOrEqual MIN_INK_READS
    }

    @Test
    fun `no danger fill or raw text alias is consumed as text`() {
        val aliases =
            sheets.flatMap { (name, css) ->
                Regex("""(--[\w-]+)\s*:\s*var\(\s*--(?:accent|text)-danger\s*[,)]""")
                    .findAll(css)
                    .filterNot { it.groupValues[1] == "--app-danger-ink" }
                    .map { it.groupValues[1] to "$name: ${it.value}" }
                    .toList()
            }
        val stray =
            aliases.flatMap { (alias, declaration) ->
                hits(Regex("""(?<![-\w])color\s*:\s*var\(\s*${Regex.escape(alias)}\s*[,)]"""))
                    .map { "$declaration -> $it" }
            }
        withClue(stray.joinToString("\n")) { stray shouldBe emptyList() }
    }

    private companion object {
        /** `color:` (never `border-color:` / `background-color:`) reading either red, bare or with a fallback. */
        val STRAY_TEXT = Regex("""(?<![-\w])color\s*:\s*var\(\s*--(?:accent-danger|text-danger)\s*[,)]""")
        val INK_READ = Regex("""(?<![-\w])color\s*:\s*var\(\s*--app-danger-ink\s*\)""")
        val DECLARATION = Regex("""--app-danger-ink\s*:\s*([^;]+);""")

        const val MIN_SHEETS = 10
        const val MIN_INK_READS = 20
    }
}
