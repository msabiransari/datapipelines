package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 282 (#282) — the house data table's two files and the sweep that put every table in it.
 *
 *  - `data-table.css` is tokens-only (CLAUDE.md rule 3), which `AppCssTokenAuditTest` does not
 *    cover (it scopes itself to app.css by name): no literal colour, every font size a step of
 *    the type scale — the rules `SchedulesCssTokenTest` holds schedules.css to;
 *  - both files load from the layout for every page (tables are on most screens, and 090's rule
 *    — ui-screens §3.0 — says no page carries its own sheet): the sheet right AFTER app.css (it
 *    folds app.css's table layers and must win over them) and BEFORE the page sheets whose table
 *    tweaks build on it; the script after shell.js, whose `[data-href]` click handler Enter
 *    reuses;
 *  - the legacy table layers are gone from where they lived (app.css's boxed block and
 *    `.u-scroll-x` rules, template-tree.css's `.tplx-fit-table`) — so one rule set draws a table;
 *  - every `<table>` in an app template sits DIRECTLY in a `.dt-viewport` inside a `.dt-frame`
 *    (ui-screens §3.7) — the next table a screen adds is framed, or this names it.
 */
class DataTableCssTokenTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

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

    /**
     * 288 #3 — every animated property names a duration token (`--duration-*`, the vendored
     * design system's motion scale). The skeleton's shimmer carried a literal `1.2s`; the
     * house precedent (app.css's ds-spin) folded the vendored literal into `--duration-slower`.
     * `none` (the reduced-motion kill) and a `0s` delay are not timings.
     */
    @Test
    fun `every animation and transition names a duration token`() {
        val timed = Regex("""(animation|transition):(?![^;]*(\bnone\b|\b0s\b))[^;]*;""")
        val tokenised = Regex("""var\(--duration-""")
        val found =
            declarations
                .filter { (_, line) -> timed.containsMatchIn(line) && !tokenised.containsMatchIn(line) }
                .map { (n, line) -> "data-table.css:$n $line" }
        withClue("a duration must be a design token") { found.shouldBeEmpty() }
    }

    /** 288 #4 — the keyboard-scrollable viewport's visible ring, inside its box. */
    @Test
    fun `the viewport has a visible focus ring`() {
        css shouldContain ".dt-viewport:focus-visible"
        val rule = css.substringAfter(".dt-viewport:focus-visible {", "").substringBefore("}")
        rule shouldContain "outline: 2px solid var(--border-focus);"
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
        read("static/css/template-tree.css").withoutComments() shouldNotContain ".tplx-fit-table"
        read("static/css/pipeline-editor.css").withoutComments() shouldNotContain ".pe-result-table-container thead th"
        // …and live here.
        css shouldContain ".dt-viewport > .ds-table thead th {"
        css shouldContain "position: sticky;"
    }

    @Test
    fun `every table in an app template sits in a data-table frame`() {
        val templates =
            resolver
                .getResources("classpath*:templates/**/*.html")
                .filter { it.filename != null && "/templates/site/" !in it.url.toString() }
                .associate {
                    it.url.toString().substringAfter("/templates/") to
                        it.inputStream
                            .readBytes()
                            .decodeToString()
                            .withoutHtmlComments()
                }
        val tables = templates.flatMap { (name, source) -> TABLE.findAll(source).map { name to it.range.first }.toList() }
        // Non-vacuity: 22 tables in 19 templates on the lane's base (the brief's grep).
        tables.size shouldBeGreaterThanOrEqual 22
        val unframed =
            tables
                .filterNot { (name, at) ->
                    // The two opening tags right before the table: the viewport, then the frame.
                    val before =
                        OPENING_TAG
                            .findAll(templates.getValue(name).substring(0, at))
                            .toList()
                            .takeLast(2)
                            .map { it.value }
                    before.size == 2 && before[1].contains("dt-viewport") && before[0].contains("dt-frame")
                }.map { (name, _) -> name }
        withClue("wrap each in <div class=\"dt-frame …\"><div class=\"dt-viewport\" tabindex=\"-1\"> (ui-screens §3.7)") {
            unframed.shouldBeEmpty()
        }
    }

    private fun String.withoutComments(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private fun String.withoutHtmlComments(): String = replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private companion object {
        val TABLE = Regex("""<table\b""")

        /** An opening `<div …>` — the frame and the viewport are divs, and nothing may sit between them and the table. */
        val OPENING_TAG = Regex("""<(?!/)[a-zA-Z][^>]*>""")
    }
}
