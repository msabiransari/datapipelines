package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The transform over the REAL sheets (#1065 A.2, D2, D3, D8): the six sheets of the client library's stylesheet, read
 * from the repository and joined as the library joins them, and each theme sheet under its own scope. Each count is
 * printed beside the brief's (measured at `b479c5d81`) with a floor of 1, so an empty read is red and none is pinned.
 *
 * The output is then read back by THIS test's own small reader ([OutputReader]), never by the transform's: every
 * top-level statement is `@font-face`, `@keyframes` or the one scope block; no declaration holds a `rem` length; no
 * selector inside the block lacks `&`; no selector that begins `&:is(&)` goes on with a sibling combinator. And the
 * names the hoisted rules define for the host's whole page equal the names the skill's sentence 6 gives, read from the
 * reference's text.
 */
class ScopedStylesheetSheetsTest {
    @Test
    fun `the six sheets scope with every count at its floor, and the output reads back clean`() {
        val source = SIX.joinToString("") { TestRepoFiles.read("$STATIC/$it") + "\n" }
        val scoped = ScopedStylesheet.scope(source, ClientLibrary.SCOPE)
        val c = scoped.counts
        println(
            "1065|A2|six|rules=${c.styleRules} (brief 1050) selectors=${c.selectors} (1156) exact=${c.rootExact} (18) " +
                "beginning=${c.rootBeginning.values.sum()} ${c.rootBeginning} (46: 12 none, 34 descendant) hoisted=${c.hoisted} " +
                "(6 and 3) groups=${c.groups} (38 and 2) carried=${c.carried} (15) dropped=${c.dropped} (1) rem=${c.remLengths} (72) " +
                "bytesIn=${source.toByteArray().size} (256128 at b479c5d81) bytesOut=${scoped.text.toByteArray().size} (146006)",
        )
        assertSoftly {
            withClue("each count has a floor of 1 (an empty read is red)") {
                listOf(
                    c.styleRules,
                    c.selectors,
                    c.rootExact,
                    c.rootBeginning.values.sum(),
                    c.hoisted["font-face"] ?: 0,
                    c.hoisted["keyframes"] ?: 0,
                    c.groups["media"] ?: 0,
                    c.groups["container"] ?: 0,
                    c.carried,
                    c.dropped,
                    c.remLengths,
                ).forEach { it shouldBeGreaterThanOrEqual 1 }
            }
            // In the same soft scope: a short count does not hide what the reader finds in the output.
            val read = OutputReader.read(scoped.text, ClientLibrary.SCOPE)
            println("1065|A2|six|read-back|${read.summary()}")
            read.assertClean(rootBeginningFloor = 1)
            val sentence = sentenceSix()
            println("1065|A2|names|hoisted families=${read.fontFamilies} animations=${read.keyframes} sentence6=$sentence")
            withClue("the names the hoisted rules define for the whole page are the names the skill's sentence 6 gives") {
                read.fontFamilies shouldBe sentence.first
                read.keyframes shouldBe sentence.second
            }
        }
    }

    @Test
    fun `each theme sheet scopes under its own attribute and reads back clean`() {
        val names = ClientLibrary.THEMES
        withClue("theme sheets (floor 1)") { names.shouldNotBeEmpty() }
        assertSoftly {
            for (name in names) {
                val scope = ClientLibrary.themeScope(name)
                val scoped = ScopedStylesheet.scope(TestRepoFiles.read("$STATIC/vendor/design-system/themes/$name.css"), scope)
                val read = OutputReader.read(scoped.text, scope)
                println(
                    "1065|A2|theme|$name|scope=$scope rules=${scoped.counts.styleRules} exact=${scoped.counts.rootExact} " +
                        "groups=${scoped.counts.groups} bytes=${scoped.text.toByteArray().size}|${read.summary()}",
                )
                withClue("themes/$name.css") {
                    scoped.counts.styleRules shouldBeGreaterThanOrEqual 1
                    scoped.counts.rootExact shouldBeGreaterThanOrEqual 1
                    read.assertClean(rootBeginningFloor = 0)
                    withClue("a theme sheet defines nothing for the whole page") {
                        read.fontFamilies.shouldBeEmpty()
                        read.keyframes.shouldBeEmpty()
                    }
                }
            }
        }
    }

    /**
     * The reader can see each escape it looks for: a rule before the block, a third kind of top-level statement, a
     * second block, a selector without `&`, a sibling after the root's part, a `rem` left in a declaration.
     */
    @Test
    fun `the reader names each escape in a planted output`() {
        val clean = ".s {\n& .a { b: c }\n&:is(&) .x { b: c }\n}\n"
        OutputReader.read(clean, ".s").problems.shouldBeEmpty()
        val plants =
            mapOf(
                "a rule before the block" to ".outside { color: red }\n$clean",
                "a third kind of top-level statement" to "@property --x { syntax: '*' }\n$clean",
                "a second scope block" to "$clean.s {\n& .b { c: d }\n}\n",
                "a selector without &" to ".s {\n.a { b: c }\n}\n",
                "a sibling after the root's part" to ".s {\n&:is(&) + .outside { color: red }\n}\n",
                "a rem left in a declaration" to ".s {\n& .a { margin: 1rem }\n}\n",
                "a rem left in a keyframe" to "@keyframes k { to { top: 2rem } }\n$clean",
            )
        assertSoftly {
            for ((label, text) in plants) {
                val problems = OutputReader.read(text, ".s").problems
                println("1065|A2|reader-plant|$label|$problems")
                withClue(label) { problems.shouldNotBeEmpty() }
            }
        }
    }

    /** Sentence 6 of the skill's "Theme and stylesheet": its font family names and animation names, as sets. */
    private fun sentenceSix(): Pair<Set<String>, Set<String>> {
        val text = TestRepoFiles.read(SKILL).replace(Regex("""\s+"""), " ")
        val match = requireNotNull(SENTENCE_SIX.find(text)) { "no sentence naming the font family names and animation names in $SKILL" }
        val names = { part: String -> Regex("`([^`]+)`").findAll(part).map { it.groupValues[1] }.toSet() }
        return names(match.groupValues[1]) to names(match.groupValues[2])
    }

    /** A reader of the scoped output, written for this test: braces, strings, selectors and declarations only. */
    private class OutputReader(
        val problems: List<String>,
        val fontFamilies: Set<String>,
        val keyframes: Set<String>,
        val counted: Map<String, Int>,
    ) {
        fun summary(): String = "counted=$counted problems=${problems.size}"

        fun assertClean(rootBeginningFloor: Int) {
            withClue("problems the reader found in the output") { problems.shouldBeEmpty() }
            withClue("exactly one scope block") { counted["scopeBlocks"] shouldBe 1 }
            withClue("selectors inside the block (floor 1)") { (counted["selectors"] ?: 0) shouldBeGreaterThanOrEqual 1 }
            withClue("selectors that begin &:is(&), whose first combinator was read (floor $rootBeginningFloor)") {
                (counted["rootBeginning"] ?: 0) shouldBeGreaterThanOrEqual rootBeginningFloor
            }
        }

        companion object {
            fun read(
                text: String,
                scope: String,
            ): OutputReader {
                val problems = mutableListOf<String>()
                val families = mutableSetOf<String>()
                val frames = mutableSetOf<String>()
                val counted = mutableMapOf("scopeBlocks" to 0, "selectors" to 0, "rootBeginning" to 0)
                for ((prelude, body) in blocks(text)) {
                    when {
                        prelude.startsWith("@font-face") -> {
                            FAMILY.find(body)?.let { families += it.groupValues[1].trim().trim('"', '\'') }
                                ?: problems.add("@font-face without a family")
                        }

                        prelude.startsWith("@keyframes ") -> {
                            frames += prelude.removePrefix("@keyframes ").trim()
                            if (REM.containsMatchIn(body)) problems += "a rem length in $prelude"
                        }

                        prelude == scope -> {
                            counted.merge("scopeBlocks", 1, Int::plus)
                            inside(body, problems, counted)
                        }

                        else -> {
                            problems += "a top-level statement that is neither hoisted nor the scope block: ${prelude.take(SHOWN)}"
                        }
                    }
                }
                if (counted["scopeBlocks"] != 1) problems += "scope blocks: ${counted["scopeBlocks"]}"
                return OutputReader(problems, families, frames, counted)
            }

            private fun inside(
                body: String,
                problems: MutableList<String>,
                counted: MutableMap<String, Int>,
            ) {
                for ((prelude, inner) in blocks(body)) {
                    if (prelude.startsWith("@")) {
                        if (!GROUP.containsMatchIn(prelude)) {
                            problems +=
                                "an at-rule inside the block that is not a group: ${prelude.take(SHOWN)}"
                        }
                        inside(inner, problems, counted)
                        continue
                    }
                    if (REM.containsMatchIn(inner)) problems += "a rem length in the declarations of $prelude"
                    splitCommas(prelude).forEach { selector(it, problems, counted) }
                }
            }

            /** One selector inside the block: it holds `&`, and after a root part no sibling combinator follows. */
            private fun selector(
                selector: String,
                problems: MutableList<String>,
                counted: MutableMap<String, Int>,
            ) {
                counted.merge("selectors", 1, Int::plus)
                if (!selector.startsWith("&")) problems += "a selector without &: $selector"
                if (selector.startsWith(ROOT_PART)) {
                    counted.merge("rootBeginning", 1, Int::plus)
                    val after = afterCompound(selector.removePrefix(ROOT_PART)).trimStart()
                    if (SIBLINGS.any(after::startsWith)) problems += "a sibling combinator after the root's part: $selector"
                }
            }

            /** The top-level `prelude { body }` pairs of [text]; a string's braces are text. */
            private fun blocks(text: String): List<Pair<String, String>> {
                val out = mutableListOf<Pair<String, String>>()
                var depth = 0
                var start = 0
                var open = -1
                var quote: Char? = null
                for ((i, ch) in text.withIndex()) {
                    if (quote != null) {
                        if (ch == quote) quote = null
                        continue
                    }
                    when (ch) {
                        '"', '\'' -> {
                            quote = ch
                        }

                        '{' -> {
                            if (depth++ == 0) open = i
                        }

                        '}' -> {
                            if (--depth == 0) {
                                out += text.substring(start, open).trim() to text.substring(open + 1, i)
                                start = i + 1
                            }
                        }
                    }
                }
                return out
            }

            private fun splitCommas(selectors: String): List<String> {
                val out = mutableListOf<String>()
                var depth = 0
                var from = 0
                for ((i, ch) in selectors.withIndex()) {
                    when (ch) {
                        '(', '[' -> {
                            depth++
                        }

                        ')', ']' -> {
                            depth--
                        }

                        ',' -> {
                            if (depth == 0) {
                                out += selectors.substring(from, i).trim()
                                from = i + 1
                            }
                        }
                    }
                }
                return out + selectors.substring(from).trim()
            }

            /** What follows the compound at the start of [rest] (brackets and parentheses are part of it). */
            private fun afterCompound(rest: String): String {
                var depth = 0
                for ((i, ch) in rest.withIndex()) {
                    when {
                        ch == '(' || ch == '[' -> depth++
                        ch == ')' || ch == ']' -> depth--
                        depth == 0 && (ch.isWhitespace() || ch in ">+~|") -> return rest.substring(i)
                    }
                }
                return ""
            }

            private const val SHOWN = 80
            private const val ROOT_PART = "&:is(&)"
            private val SIBLINGS = listOf("+", "~", "||")
            private val FAMILY = Regex("""font-family\s*:\s*([^;]+)""")
            private val REM = Regex("""\drem\b""")
            private val GROUP = Regex("""^@(media|supports|container|layer)\b""")
        }
    }

    private companion object {
        const val STATIC = "modules/web/src/main/resources/static"
        const val SKILL = "modules/mcp-server/src/main/resources/skill/dashboards-embedding.md"
        val SENTENCE_SIX = Regex("""font family names \(([^)]*)\) and \w+ animation names \(([^)]*)\)""")

        /** The six sheets of the client library's stylesheet in its order (#1064 D2), written here, not read from the class. */
        val SIX =
            listOf(
                "vendor/design-system/tokens.css",
                "vendor/design-system/themes/light.css",
                "vendor/design-system/base.css",
                "vendor/design-system/primitives.css",
                "css/app.css",
                "css/dashboards.css",
            )
    }
}
