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

    @Test
    fun `the scanned files are exactly the engine's load order (non-vacuity)`() {
        files.keys shouldBe JsRenderedEngineFiles.names.toSet()
        files.size shouldBeGreaterThanOrEqual FILE_FLOOR
        (STRING_WRITER in files) shouldBe true
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
    private fun sample(pattern: String): String = if (pattern.endsWith("(")) "${pattern}1)" else if (pattern == "**") "2 ** 3" else "$pattern(1)"

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

        /** The engine's file count when this guard was written (16 shared files and bar's template). */
        const val FILE_FLOOR = 17

        const val BANNED_COUNT = 23

        /** Record §4.3 R-BAN: lane 977's banned list (`results/banned.json`), in its order. */
        val BANNED =
            listOf(
                "Math.log10", "Math.log(", "Math.LN10", "Math.pow", "**", "Math.exp", "Math.sin", "Math.cos", "Math.atan2",
                "Date", "Intl", "toLocaleString", "toLocaleDateString", "toLocaleTimeString", "localeCompare", "Math.random",
                "performance", "eval", "Function(", "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write",
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

        /** The code with comments blanked, and its string and template literals (quotes included) in order. */
        class Lexed(
            val code: String,
            val strings: List<String>,
        )

        fun lex(src: String): Lexed {
            val out = StringBuilder(src.length)
            val strings = mutableListOf<String>()
            var i = 0
            var prev = ' '
            var hasPrev = false
            val n = src.length
            while (i < n) {
                val c = src[i]
                val next = if (i + 1 < n) src[i + 1] else '\u0000'
                when {
                    c == '/' && next == '/' -> {
                        val j = src.indexOf('\n', i).let { if (it < 0) n else it }
                        repeat(j - i) { out.append(' ') }
                        i = j
                    }
                    c == '/' && next == '*' -> {
                        val j = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                        for (k in i until j) out.append(if (src[k] == '\n') '\n' else ' ')
                        i = j
                    }
                    c == '\'' || c == '"' || c == '`' -> {
                        val j = closing(src, i, c)
                        out.append(src, i, minOf(j + 1, n))
                        strings += src.substring(i, minOf(j + 1, n))
                        i = j + 1
                        prev = c
                        hasPrev = true
                    }
                    c == '/' && (!hasPrev || prev in REGEX_AFTER) -> {
                        var j = i + 1
                        var klass = false
                        while (j < n && src[j] != '\n') {
                            val d = src[j]
                            if (d == '\\') {
                                j += 2
                                continue
                            }
                            if (d == '[') klass = true else if (d == ']') klass = false else if (d == '/' && !klass) break
                            j++
                        }
                        out.append(src, i, minOf(j + 1, n))
                        i = j + 1
                        prev = '/'
                        hasPrev = true
                    }
                    else -> {
                        out.append(c)
                        if (!c.isWhitespace()) {
                            prev = c
                            hasPrev = true
                        }
                        i++
                    }
                }
            }
            return Lexed(out.toString(), strings)
        }

        private const val REGEX_AFTER = "(,=:[!&|?{};+-*%<>~^"

        /** The index of the quote that closes the literal opened at [start] (or the end), escapes skipped. */
        private fun closing(
            src: String,
            start: Int,
            quote: Char,
        ): Int {
            var j = start + 1
            while (j < src.length && src[j] != quote) j += if (src[j] == '\\') 2 else 1
            return j
        }
    }
}
