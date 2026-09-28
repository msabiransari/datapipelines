package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * 282 (#282) — the house data table's two files.
 *
 *  - `data-table.css` is tokens-only (CLAUDE.md rule 3), which `AppCssTokenAuditTest` does not
 *    cover (it scopes itself to app.css by name): no literal colour, every font size a step of
 *    the type scale — the rules `SchedulesCssTokenTest` holds schedules.css to;
 *  - both files load from the layout for every page (tables are on most screens, and 090's rule
 *    — ui-screens §3.0 — says no page carries its own sheet): the sheet right AFTER app.css (it
 *    folds app.css's table layers and must win over them) and BEFORE the page sheets whose table
 *    tweaks build on it; the script after shell.js, whose `[data-href]` click handler Enter
 *    reuses;
 *  - the legacy table layers are gone from app.css (its boxed block and the `.u-scroll-x`
 *    rules) — so one rule set draws a table.
 */
class DataTableCssTokenTest {
    private fun read(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)) { path }.readText()

    private val css = read("static/css/data-table.css")

    private val declarations: List<Pair<Int, String>> =
        css
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .lines()
            .mapIndexed { i, line -> i + 1 to line.trim() }
            .filter { (_, line) -> line.contains(':') && line.endsWith(";") }

    @Test
    fun `no literal colour in any declaration`() {
        val literal = Regex("""#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(|:\s*(white|black|red|green|blue|gray|grey|orange|yellow)\s*;""")
        declarations.size shouldBeGreaterThan 60
        val found = declarations.filter { (_, line) -> literal.containsMatchIn(line) }.map { (n, line) -> "data-table.css:$n $line" }
        withClue("a colour must be a design token") { found.shouldBeEmpty() }
    }

    @Test
    fun `every font size is a step of the type scale`() {
        val found =
            declarations
                .filter { (_, line) -> line.startsWith("font-size:") && !line.contains("var(--text-") }
                .map { (n, line) -> "data-table.css:$n $line" }
        found.shouldBeEmpty()
    }

    @Test
    fun `the sheet loads after app css and before the page sheets, the script after the shell`() {
        val layout = read("templates/layouts/default.html")
        val head = layout.substringBefore("</head>")
        val sheet = head.indexOf("@{/css/data-table.css}")
        sheet shouldBeGreaterThan -1
        (sheet > head.indexOf("@{/css/app.css}")) shouldBe true
        (sheet < head.indexOf("@{/css/template-tree.css}")) shouldBe true
        (sheet < head.indexOf("@{/css/schedules.css}")) shouldBe true
        val body = layout.substringAfter("</head>")
        val script = body.indexOf("@{/js/data-table.js}")
        script shouldBeGreaterThan -1
        (script > body.indexOf("@{/js/shell.js}")) shouldBe true
    }

    @Test
    fun `the folded table layers are gone from the sheets they lived in`() {
        val app = read("static/css/app.css").withoutComments()
        // app.css's boxed block (076 §D / 079 §E) — now data-table.css's.
        app shouldNotContain ".ds-table {"
        app shouldNotContain ".ds-table thead th"
        app shouldNotContain ".u-scroll-x"
        app shouldNotContain ".app-card-table .ds-table"
        // …and live here.
        css shouldContain ".dt-viewport > .ds-table thead th {"
        css shouldContain "position: sticky;"
    }

    private fun String.withoutComments(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

}
