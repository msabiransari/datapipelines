package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.measureTime

/**
 * Refuses matchers swallowed by Elvis fallbacks (#360); root discovery follows OrderedTestAnnotationGuardTest.
 * Text scan: mask line/nested block comments, escaped/raw strings, chars, backtick identifiers, regex-group
 * and SQL-cast punctuation; preserve offsets/newlines and literal placeholders that keep statement boundaries.
 * Join dot/Elvis/safe-call/boolean/matcher continuations, open parentheses/brackets and trailing operators.
 * Track parentheses/braces/brackets: a group closed before the matcher makes it safe; report source file/line.
 * Limits: reflection, aliased/backtick-escaped matchers and string template expressions are unsupported.
 * A following line without a continuation prefix is a separate statement; semicolons end statements.
 * Birth: 851 Elvis-bearing lines (237 regex-group, 54 SQL-cast); 29 same-line matcher candidates:
 * one bypass, 27 grouped, one safe qualified. Raw floors measure input; grouped counts measure classification.
 */
class ElvisBoundMatcherGuardTest {
    @Test
    fun `no matcher is bound to an Elvis fallback in the test tree`() {
        val elapsed = measureTime { scan(testSourceFiles()) }
        println("ElvisBoundMatcherGuardTest duration=$elapsed")
    }

    @Test
    fun `classifier refuses bypasses and preserves resolved value assertions`() {
        val bypasses =
            listOf(
                "count ?: 0 shouldBe 0",
                "count ?: (0) shouldBe 0",
                "count ?: 0.shouldBe(0)",
                "count\n    ?: 0 shouldBe 0",
                "count ?: 0\n    shouldBe 0",
                "withClue(\"x\") { count ?: 0 shouldBe 0 }",
                "count ?: (0 shouldBe 0)",
            )
        bypasses.forEach { source ->
            withClue(source) { classify(source).first.size shouldBe 1 }
        }
        val safe =
            listOf(
                *SAFE_EXEMPLARS.toTypedArray(),
                "(count ?: 0) shouldBe 0",
                "((count ?: 0L) < limit).shouldBeTrue()",
                "assertEquals(0, count ?: 0)",
                "// count ?: 0 shouldBe 0",
                "/* outer /* count ?: 0 shouldBe 0 */ still a comment */",
                "\"count ?: 0 shouldBe 0\"",
                "\"escaped \\\" count ?: 0 shouldBe 0\"",
                "\"\"\"count ?: 0 shouldBe 0\"\"\"",
                "Regex(\"(?:should)\")",
                "\"SELECT ?::uuid\"",
                "val v = count ?: 0\nv shouldBe 0",
                "val message = thrown.message ?: \"\"\nmessage shouldContain \"expected\"",
                "val sourceType = warning.sourceType ?: \"\"\nsourceType.length shouldBe 0",
                "values[count ?: 0] shouldBe 0",
                "val v = count ?: 0; v shouldBe 0",
                "(count ?: 0)\n    .shouldBe(0)",
                "withClue(\"( unmatched\") { count ?: true } shouldBe false",
            )
        safe.forEach { source -> withClue(source) { classify(source).first.shouldBeEmpty() } }
    }

    private fun scan(files: List<File>) {
        val offenders = mutableListOf<String>()
        var rawSites = 0
        var rawLines = 0
        var codeSites = 0
        var grouped = 0
        files.forEach { file ->
            val source = file.readText()
            rawSites += ELVIS.findAll(source).count()
            rawLines += source.lineSequence().count { "?:" in it }
            codeSites += ELVIS.findAll(mask(source)).count()
            val (bad, safe) = classify(source)
            grouped += safe
            bad.forEach { offset ->
                val line = source.take(offset).count { it == '\n' } + 1
                val statement = statements(mask(source)).first { offset in it }.let { source.substring(it).trim() }
                offenders += "${file.path}:$line: $statement"
            }
        }
        println(
            "Elvis inventory: files=${files.size} rawLines=$rawLines rawOccurrences=$rawSites " +
                "codeOccurrences=$codeSites grouped=$grouped bypass=${offenders.size}",
        )
        withClue("test-source file floor — empty or narrowed scans are not clean") {
            files.size shouldBeGreaterThanOrEqual FILE_FLOOR
        }
        withClue("raw Elvis occurrence floor — the input surface must not disappear") {
            rawSites shouldBeGreaterThanOrEqual ELVIS_FLOOR
        }
        withClue("grouped-value floor — legitimate assertions must remain visible") {
            grouped shouldBeGreaterThanOrEqual GROUPED_FLOOR
        }
        withClue(
            "An Elvis fallback conditionally executes its matcher; group the resolved value or extract a local:\n" +
                offenders.joinToString("\n"),
        ) {
            offenders.shouldBeEmpty()
        }
    }

    /** Offsets of bypasses, and the number of Elvis expressions followed by a safe grouped matcher. */
    private fun classify(source: String): Pair<List<Int>, Int> {
        val code = mask(source)
        val bad = mutableListOf<Int>()
        var grouped = 0
        statements(code).forEach { range ->
            val statement = code.substring(range)
            ELVIS.findAll(statement).forEach elvisLoop@{ elvis ->
                val matcher = MATCHER.find(statement, elvis.range.last + 1) ?: return@elvisLoop
                if (closesGroup(statement, elvis.range.last + 1, matcher.range.first)) grouped++ else bad += range.first + elvis.range.first
            }
        }
        return bad to grouped
    }

    private fun closesGroup(
        code: String,
        from: Int,
        to: Int,
    ): Boolean {
        val depths = IntArray(3)
        for (at in from until to) {
            when (code[at]) {
                '(' -> depths[0]++
                ')' -> depths[0]--
                '{' -> depths[1]++
                '}' -> depths[1]--
                '[' -> depths[2]++
                ']' -> depths[2]--
            }
            if (depths.any { it < 0 }) return true
        }
        return false
    }

    /** Mask non-code with stable offsets and literal placeholders that preserve statement boundaries. */
    private fun mask(source: String): String {
        val code = source.toCharArray()
        var at = 0
        while (at < source.length) {
            val span = nonCodeSpan(source, at)
            if (span == null) {
                at++
                continue
            }
            for (index in at until span.first) {
                if (code[index] != '\n') code[index] = ' '
            }
            code[at] = span.second
            at = span.first
        }
        return String(code)
    }

    private fun nonCodeSpan(
        source: String,
        at: Int,
    ): Pair<Int, Char>? =
        when {
            source.startsWith("//", at) -> closingEnd(source, at + 2, "\n") to ' '
            source.startsWith("/*", at) -> blockEnd(source, at) to ' '
            source.startsWith("\"\"\"", at) -> closingEnd(source, at + 3, "\"\"\"") to '0'
            source[at] == '`' -> closingEnd(source, at + 1, "`") to 'v'
            source[at] == '"' || source[at] == '\'' -> quotedEnd(source, at) to '0'
            source.startsWith("(?:", at) || source.startsWith("?::", at) -> (at + 3) to ' '
            else -> null
        }

    private fun closingEnd(
        source: String,
        from: Int,
        delimiter: String,
    ): Int {
        val end = source.indexOf(delimiter, from)
        return if (end < 0) source.length else end + delimiter.length
    }

    private fun blockEnd(
        source: String,
        at: Int,
    ): Int {
        var end = at + 2
        var depth = 1
        while (end < source.length && depth > 0) {
            when {
                source.startsWith("/*", end) -> {
                    depth++
                    end += 2
                }

                source.startsWith("*/", end) -> {
                    depth--
                    end += 2
                }

                else -> {
                    end++
                }
            }
        }
        return end
    }

    private fun quotedEnd(
        source: String,
        at: Int,
    ): Int {
        var end = at + 1
        while (end < source.length && source[end] != source[at]) {
            end += if (source[end] == '\\') 2 else 1
        }
        return (end + 1).coerceAtMost(source.length)
    }

    /** Logical statement ranges in the masked text, including continuation lines. */
    private fun statements(code: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        var parens = 0
        var brackets = 0
        code.forEachIndexed { at, char ->
            when (char) {
                '(' -> parens++
                ')' -> parens--
                '[' -> brackets++
                ']' -> brackets--
            }
            if (char != ';' && char != '\n') return@forEachIndexed
            val openGroup = parens > 0 || brackets > 0
            val lineEnd = char == '\n' && !openGroup && !joinsLine(code, start, at)
            if (char == ';' || lineEnd) {
                result += start until at
                start = at + 1
                parens = 0
                brackets = 0
            }
        }
        if (start < code.length) result += start until code.length
        return result
    }

    private fun joinsLine(
        code: String,
        start: Int,
        at: Int,
    ): Boolean {
        var nextAt = at + 1
        while (nextAt < code.length && code[nextAt].isWhitespace()) nextAt++
        val next = code.substring(nextAt, (nextAt + CONTINUATION_WINDOW).coerceAtMost(code.length))
        return CONTINUATION.containsMatchIn(next) || TRAILING.containsMatchIn(code.substring(start, at).trimEnd())
    }

    private fun testSourceFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "modules/pipeline-contract").isDirectory)) dir = dir.parentFile
        val root = checkNotNull(dir) { "repository root not found walking up from ${File("").absolutePath}" }
        return listOf("modules", "tests", "buildSrc").flatMap { top ->
            val base = File(root, top)
            if (!base.isDirectory) return@flatMap emptyList<File>()
            base
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.contains("${File.separator}src${File.separator}test${File.separator}") }
                .filterNot { it.path.contains("${File.separator}build${File.separator}") }
                .filterNot { it.name == "ElvisBoundMatcherGuardTest.kt" }
                .toList()
        }
    }

    private companion object {
        const val CONTINUATION_WINDOW = 100
        const val FILE_FLOOR = 1400
        const val ELVIS_FLOOR = 700
        const val GROUPED_FLOOR = 20
        val ELVIS = Regex("\\?:")
        val MATCHER = Regex("\\bshould[A-Z]\\w*\\b")
        val CONTINUATION = Regex("^(?:\\.|\\?:|\\?\\.|&&|\\|\\||should[A-Z]\\w*\\b)")
        val TRAILING = Regex("(?:\\?:|\\?\\.|\\.|&&|\\|\\|)$")
        val SAFE_EXEMPLARS =
            listOf(
                "{ (thrown.message ?: \"\") shouldBe \"The database refused the statement: syntax error at or near \\\"FORM\\\"\" },",
                "(error.field ?: \"\") shouldContain \"properties.dialect.catalog.ref\"",
                "(refused.message ?: \"\").contains(over) shouldBe false",
                "(refused.message?.contains(\"datapipelines.deployment.authoring-enabled\") ?: false) shouldBe true",
                "withClue(\"INV pins point at live targets (v\$v)\") { (versions[v]?.let { it != 'X' } ?: false) shouldBe true }",
                "withClue(\"evaluate envelope must carry data: \$envelope\") { data?.isNull ?: true } shouldBe false",
                "(byFamily[family.name]?.size ?: 0) shouldBeGreaterThanOrEqual family.floor",
                "(item.group ?: \"\") shouldBe group",
                "(sheetText ?: \"\") shouldBe \"\"",
            )
    }
}
