package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2 — `schedules.css` is tokens-only (CLAUDE.md rule 3), which `AppCssTokenAuditTest`
 * does not cover (it scopes itself to app.css, by name). Two rules, both about the declaration
 * VALUES the sheet writes:
 *
 *  - no literal colour — no `#hex`, `rgb()/rgba()`, `hsl()/hsla()` or named colour keyword;
 *    a colour is a `var(--…)` so the six themes and both modes follow it;
 *  - no raw `px` type size — `font-size` is a `var(--text-…)` step of the one scale (076 §D).
 *
 * And it loads from the layout HEAD (ui-screens §3.0): /schedules is a boosted route, and a
 * page-scoped sheet paints a frame without its rules.
 */
class SchedulesCssTokenTest {
    private val css = read("static/css/schedules.css")

    private fun read(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)) { path }.readText()

    private val declarations: List<Pair<Int, String>> =
        css
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .lines()
            .mapIndexed { i, line -> i + 1 to line.trim() }
            .filter { (_, line) -> line.contains(':') && line.endsWith(";") }

    @Test
    fun `no literal colour in any declaration`() {
        val literal = Regex("""#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(|:\s*(white|black|red|green|blue|gray|grey|orange|yellow)\s*;""")
        declarations.size shouldBeGreaterThan 40
        val found = declarations.filter { (_, line) -> literal.containsMatchIn(line) }.map { (n, line) -> "schedules.css:$n $line" }
        withClue("a colour must be a design token") { found.shouldBeEmpty() }
    }

    @Test
    fun `every font size is a step of the type scale`() {
        val found =
            declarations
                .filter { (_, line) -> line.startsWith("font-size:") && !line.contains("var(--text-") }
                .map { (n, line) -> "schedules.css:$n $line" }
        found.shouldBeEmpty()
    }

    @Test
    fun `the sheet loads from the layout head, after the explorer sheet it builds on`() {
        val layout = read("templates/layouts/default.html")
        val head = layout.substringBefore("</head>")
        head shouldContain "@{/css/schedules.css}"
        (head.indexOf("@{/css/schedules.css}") > head.indexOf("@{/css/template-tree.css}")) shouldBe true
    }
}
