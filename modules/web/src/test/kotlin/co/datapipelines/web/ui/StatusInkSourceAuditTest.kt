package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #618 — success and warning TEXT each have ONE name in the first-party sheets:
 * `--app-success-ink` and `--app-warning-ink`, #509's `--app-danger-ink` shape for the other two
 * status roles (`DangerInkSourceAuditTest` holds the danger half).
 *
 * `StatusInkContrastBrowserTest` measures both inks on every vendored theme, but only on the
 * probes one page carries; the editor's version chips, port lines, marker words and dock count,
 * the template workspace's version chips, the login companion's status line and the dashboard
 * event severity live on pages of their own. They are held here instead, at the source: no
 * first-party rule paints `color` with `--accent-success` / `--accent-warning` (the FILL colours,
 * 4.31–4.39:1 as text on professional's inset surface) or with the vendored `--text-success` /
 * `--text-warning` directly (light's warning 4.43:1 and healthcare's 4.23:1 there). Borders,
 * backgrounds, dots and the chip tints keep the accents.
 *
 * The issue's inventory grep (`color\s*:\s*var\(--accent-(success|warning)`) could not see the
 * three raw `--text-warning` reads; the pattern here matches both names, bare or with a literal
 * fallback (`var(--accent-success, #15803d)`, the spelling of nine of the twenty-four sites).
 */
class StatusInkSourceAuditTest {
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

    @ParameterizedTest
    @ValueSource(strings = ["success", "warning"])
    fun `no first-party rule paints status text with the fill accent or the raw vendored text role`(role: String) {
        val stray = hits(strayText(role))
        withClue(stray.joinToString("\n")) { stray shouldBe emptyList() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["success", "warning"])
    fun `each ink is declared once, from the design system's text role, and read by every text site`(role: String) {
        val appCss = sheets.getValue("app.css")
        val declarations = declaration(role).findAll(appCss).map { it.groupValues[1].trim() }.toList()
        declarations.size shouldBe 1
        val declared = declarations.single()
        declared shouldContain "light-dark("
        declared shouldContain "var(--text-$role)"
        // Non-vacuity: the sheets are the real ones, and the readers are all there — the twelve
        // text sites per role #618 moved, over the sheets that carry them.
        val reads = hits(inkRead(role))
        val readerSheets = reads.map { it.substringBefore(':') }.toSet()
        println("618-audit|$role|reads=${reads.size}|sheets=${readerSheets.size}|${readerSheets.sorted().joinToString(",")}")
        val floor = FLOORS.getValue(role)
        sheets.size shouldBeGreaterThanOrEqual MIN_SHEETS
        withClue(reads.joinToString("\n")) {
            reads.size shouldBeGreaterThanOrEqual floor.reads
            readerSheets.size shouldBeGreaterThanOrEqual floor.sheets
        }
    }

    private data class Floor(
        val reads: Int,
        val sheets: Int,
    )

    private companion object {
        /** `color:` (never `border-color:` / `background-color:`) reading the role's accent or text role, bare or with a fallback. */
        fun strayText(role: String) = Regex("""(?<![-\w])color\s*:\s*var\(\s*--(?:accent|text)-$role\s*[,)]""")

        fun inkRead(role: String) = Regex("""(?<![-\w])color\s*:\s*var\(\s*--app-$role-ink\s*\)""")

        fun declaration(role: String) = Regex("""--app-$role-ink\s*:\s*([^;]+);""")

        const val MIN_SHEETS = 10

        /** Counted after #618: success 12 reads over 4 sheets, warning 12 over 5 (24 over 6 sheets in all). */
        val FLOORS =
            mapOf(
                "success" to Floor(reads = 12, sheets = 4),
                "warning" to Floor(reads = 12, sheets = 5),
            )
    }
}
