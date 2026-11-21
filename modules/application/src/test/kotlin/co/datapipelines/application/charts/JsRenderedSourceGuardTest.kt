package co.datapipelines.application.charts

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * R-BAN and the markup sinks (design record 2026-11-14-js-rendered-charts §4.3, §7; brief 985 A.5, G5), over the
 * engine's SOURCE tree: none of the 23 banned patterns in the code of any engine file (comments stripped first; string,
 * template and regular-expression literals kept, so a banned word inside a string still counts), and a markup string
 * only in the string writer, [STRING_WRITER], the one file allowed to hold markup (its escaping is A8's subject, the
 * Node suite's writers test).
 *
 * The scanner is lane 977's `scripts/banned-guard.py`, translated: the same comment stripper (a regular expression is
 * told from a division by the last code character) and the same pattern rule (a pattern that starts or ends with a
 * name character cannot match inside a longer name: `Date` is not `update`, `eval` is not `evaluate`; an opening
 * parenthesis allows space before it). Its control is kept too: every pattern fires in code and none inside comments.
 *
 * The types for tests only (brief 1049 D5, `modules/js-rendered/src/test/js/types/`) are scanned with the same 23
 * patterns: their bytes must agree across the runtimes as the engine's do. They are no engine file and reach no jar.
 *
 * The product runs what this guard scans (brief 986 D10): the chart entry's program, [JsRenderedChartEngine.PROGRAM], is
 * these files in load order, read from the source tree, then the entry's own function — which holds no banned pattern
 * and no markup either — and its Source is counted (not internal: the record's §5.3, G2).
 */
class JsRenderedSourceGuardTest {
    private val engineDir = ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/main/resources/${JsRenderedEngineFiles.ROOT}")

    /** Every `.js` file under the engine directory, by its path relative to it. */
    private val files: Map<String, String> by lazy {
        engineDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".js") }
            .associate { it.relativeTo(engineDir).invariantSeparatorsPath to it.readText() }
            .toSortedMap()
    }

    /** Every `.js` file of the test types (brief 1049 D5), by file name. */
    private val testTypes: Map<String, String> by lazy {
        ChartGuardFiles
            .repoFile("${ChartGuardFiles.MODULE}/src/test/js/types")
            .listFiles { f -> f.isFile && f.name.endsWith(".js") }
            .orEmpty()
            .associate { it.name to it.readText() }
            .toSortedMap()
    }

    @Test
    fun `the scanned files are exactly the engine's load order (non-vacuity)`() {
        files.keys shouldBe JsRenderedEngineFiles.names.toSet()
        files.size shouldBeGreaterThanOrEqual FILE_FLOOR
        (STRING_WRITER in files) shouldBe true
        println("JsRenderedSourceGuardTest: ${files.size} engine files, ${testTypes.size} test type files")
    }

    @Test
    fun `no test type file holds a banned pattern or a markup string in its code`() {
        testTypes.size shouldBeGreaterThanOrEqual TEST_TYPE_FLOOR
        testTypes.flatMap { (name, text) -> scan(text).map { "types/$name:${it.line} ${it.pattern} (${it.match})" } } shouldBe emptyList()
        testTypes.flatMap { (name, text) -> markupStrings(text).map { "types/$name: $it" } } shouldBe emptyList()
    }

    @Test
    fun `the product's chart program is exactly these files in load order and the entry, counted, its entry clean`() {
        val fromTree = JsRenderedEngineFiles.names.joinToString(separator = "") { files.getValue(it) + "\n" }
        JsRenderedChartEngine.TEXT shouldBe fromTree + JsRenderedChartEngine.ENTRY + "\n"
        JsRenderedChartEngine.PROGRAM.name shouldBe JsRenderedChartEngine.SOURCE_NAME
        JsRenderedChartEngine.PROGRAM.counted shouldBe true
        scan(JsRenderedChartEngine.ENTRY) shouldBe emptyList()
        markupStrings(JsRenderedChartEngine.ENTRY) shouldBe emptyList()
    }

    @Test
    fun `no engine file holds a banned pattern in its code`() {
        BANNED.size shouldBe BANNED_COUNT
        files.flatMap { (name, text) -> scan(text).map { "$name:${it.line} ${it.pattern} (${it.match})" } } shouldBe emptyList()
    }

    @Test
    fun `only the string writer holds a markup string`() {
        val outside = files.filterKeys { it != STRING_WRITER }.flatMap { (name, text) -> markupStrings(text).map { "$name: $it" } }
        outside shouldBe emptyList()
        // The string writer does hold markup: the rule saw the one place it allows.
        markupStrings(files.getValue(STRING_WRITER)).size shouldBeGreaterThan 0
    }

    @Test
    fun `control - every pattern fires in code, none inside comments`() {
        val inCode = BANNED.joinToString("\n") { "x = a ${sample(it)} b;" }
        scan(inCode).map { it.pattern }.toSet() shouldBe BANNED.toSet()
        val inComments = BANNED.joinToString("\n") { "// ${sample(it)}\n/* ${sample(it)} */\n/** ${sample(it)} */" }
        scan(inComments) shouldBe emptyList()
        // A longer name is not the pattern; a string or a regular expression literal is code.
        scan("update(); evaluate(); myFunction(1); toLocaleDateStringX;").map { it.pattern } shouldBe emptyList()
        scan("var s = \"Date\"; var r = /Intl/;").map { it.pattern }.toSet() shouldBe setOf("Date", "Intl")
        markupStrings("var a = 1 < 2, b = \"<svg>\", c = '</text>';") shouldBe listOf("\"<svg>\"", "'</text>'")
    }

    /** A call-shaped occurrence of a pattern for the control. */
    private fun sample(pattern: String): String =
        if (pattern.endsWith("(")) {
            "${pattern}1)"
        } else if (pattern ==
            "**"
        ) {
            "2 ** 3"
        } else {
            "$pattern(1)"
        }

    private data class Hit(
        val line: Int,
        val pattern: String,
        val match: String,
    )

    private fun scan(text: String): List<Hit> {
        val code = stripComments(text)
        return BANNED.flatMap { p ->
            regexOf(p).findAll(code).map { m -> Hit(code.substring(0, m.range.first).count { it == '\n' } + 1, p, m.value) }.toList()
        }
    }

    /** The string and template literals of the code whose text opens a tag (`<` then a letter, `/` or `!`). */
    private fun markupStrings(text: String): List<String> = lex(text).strings.filter { MARKUP.containsMatchIn(it) }

    private companion object {
        /** The string writer: the one engine file that holds markup strings (brief 985 Security, "Rendering"). */
        const val STRING_WRITER = "31-write-string.js"

        /**
         * The engine's file count: 23 at brief 1049's base, its two files (17-trig.js, 18-frames.js), brief 1050's one
         * (19-colour.js), brief 1052's 21-lookup.js and brief 1051's 18a-bare.js.
         */
        const val FILE_FLOOR = 28

        /**
         * Brief 1049's three test types (test_plain, test_polar, test_quiet), brief 1052's test_marks and brief 1051's five
         * (test_points, test_grid, test_two_axes, test_bins, test_bare).
         */
        const val TEST_TYPE_FLOOR = 9

        const val BANNED_COUNT = 23

        /** Record §4.3 R-BAN: lane 977's banned list (`results/banned.json`), in its order. */
        val BANNED =
            listOf(
                "Math.log10",
                "Math.log(",
                "Math.LN10",
                "Math.pow",
                "**",
                "Math.exp",
                "Math.sin",
                "Math.cos",
                "Math.atan2",
                "Date",
                "Intl",
                "toLocaleString",
                "toLocaleDateString",
                "toLocaleTimeString",
                "localeCompare",
                "Math.random",
                "performance",
                "eval",
                "Function(",
                "innerHTML",
                "outerHTML",
                "insertAdjacentHTML",
                "document.write",
            )

        val MARKUP = Regex("<[A-Za-z/!]")

        private val NAME_START = Regex("^[A-Za-z_$]")
        private val NAME_END = Regex("[A-Za-z0-9_$]$")

        /** banned-guard.py's `regex_of`. */
        fun regexOf(pattern: String): Regex {
            val body = Regex.escape(pattern).let { if (pattern.endsWith("(")) it.replace("(\\E", "\\E\\s*\\(") else it }
            val head = if (NAME_START.containsMatchIn(pattern)) "(?<![A-Za-z0-9_$])" else ""
            val tail = if (NAME_END.containsMatchIn(pattern)) "(?![A-Za-z0-9_$])" else ""
            return Regex(head + body + tail)
        }

        /** banned-guard.py's `strip_comments`: every comment replaced by spaces, line breaks kept. */
        fun stripComments(src: String): String = lex(src).code

        fun lex(src: String): Lexed = JsLexer(src).run()
    }
}

/** The code with comments blanked, and its string and template literals (quotes included) in order. */
private class Lexed(
    val code: String,
    val strings: List<String>,
)

/**
 * banned-guard.py's `strip_comments` as a small lexer: every comment replaced by spaces with its line breaks kept (so
 * line numbers hold); string, template and regular-expression literals kept as code; the string and template literals
 * collected. A `/` opens a regular expression when the last code character is one of [REGEX_AFTER] or there is none.
 */
private class JsLexer(
    private val src: String,
) {
    private val out = StringBuilder(src.length)
    private val strings = mutableListOf<String>()
    private var i = 0
    private var prev = NONE

    fun run(): Lexed {
        while (i < src.length) step()
        return Lexed(out.toString(), strings)
    }

    private fun step() {
        val c = src[i]
        val next = src.getOrElse(i + 1) { NONE }
        when {
            c == '/' && next == '/' -> {
                blank(src.indexOf('\n', i).let { if (it < 0) src.length else it })
            }

            c == '/' && next == '*' -> {
                blank(src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 })
            }

            c in QUOTES -> {
                literal(c)
            }

            c == '/' && prev in REGEX_AFTER -> {
                regex()
            }

            else -> {
                out.append(c)
                if (!c.isWhitespace()) prev = c
                i++
            }
        }
    }

    /** Blanks the source from i to [end], keeping line breaks. */
    private fun blank(end: Int) {
        for (k in i until end) out.append(if (src[k] == '\n') '\n' else ' ')
        i = end
    }

    private fun literal(quote: Char) {
        var j = i + 1
        while (j < src.length && src[j] != quote) j += if (src[j] == '\\') 2 else 1
        val end = minOf(j + 1, src.length)
        out.append(src, i, end)
        strings += src.substring(i, end)
        i = end
        prev = quote
    }

    private fun regex() {
        var j = i + 1
        var klass = false
        while (j < src.length && !endsRegex(j, klass)) {
            when (src[j]) {
                '\\' -> j++
                '[' -> klass = true
                ']' -> klass = false
            }
            j++
        }
        val end = minOf(j + 1, src.length)
        out.append(src, i, end)
        i = end
        prev = '/'
    }

    /** A line break, or a slash outside a character class, ends a regular expression literal. */
    private fun endsRegex(
        j: Int,
        inClass: Boolean,
    ): Boolean = src[j] == '\n' || (src[j] == '/' && !inClass)

    private companion object {
        const val NONE = '\u0000'
        const val QUOTES = "'\"`"
        const val REGEX_AFTER = "(,=:[!&|?{};+-*%<>~^\u0000"
    }
}
