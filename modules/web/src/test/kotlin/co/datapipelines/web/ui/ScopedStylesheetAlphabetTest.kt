package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Why the gate closes the class (#1065 round 4; owner ruling 2026-11-22, "Refuse at entry"): after the gate the reader
 * meets, outside strings and comments, only the alphabet (U+0020 to U+007E without the backslash, tab, LF, CR), and on
 * that alphabet every character-category call the class makes answers what CSS Syntax §4.2 says. The theorem is walked
 * character by character; a guard over the class's SOURCE keeps the list of such calls closed, each call named with the
 * assertion that covers it.
 */
class ScopedStylesheetAlphabetTest {
    @Test
    fun `the gate's alphabet is the ruling's, character for character`() {
        val wrong = (Char.MIN_VALUE..Char.MAX_VALUE).filter { CssText.inAlphabet(it) != inRulingAlphabet(it) }
        wrong.map { "U+%04X".format(it.code) }.shouldBeEmpty()
    }

    @Test
    fun `on the alphabet, each character-category call of the class answers as CSS Syntax 4_2 does`() {
        val alphabet = alphabet()
        val shown = alphabet.joinToString("") { if (it.code < SPACE) "\\u%04X".format(it.code) else "$it" }
        println("1065|R4|alphabet|size=${alphabet.size}|$shown")
        alphabet shouldContainAll listOf(' ', '\t', '\n', '\r', '0', '9', 'a', 'Z', '_', '-', '(')
        val wrong =
            alphabet.flatMap { c ->
                val css = cssAnswers(c)
                val kotlin =
                    mapOf(
                        "isWhitespace" to c.isWhitespace(),
                        "isDigit" to c.isDigit(),
                        "isLetterOrDigit" to c.isLetterOrDigit(),
                        "trim" to c.toString().trim().isEmpty(),
                        "trimStart" to c.toString().trimStart().isEmpty(),
                    )
                kotlin.filter { (call, answer) -> answer != css.getValue(call) }.map { (call, answer) ->
                    "U+%04X %s: Kotlin says %s, CSS says %s".format(c.code, call, answer, css.getValue(call))
                }
            }
        println("1065|R4|theorem|walked=${alphabet.size}|disagreements=${wrong.size}")
        wrong.shouldBeEmpty()
    }

    @Test
    fun `the class's source holds only the character-category and trimming calls the theorem covers`() {
        val source = TestRepoFiles.read(SOURCE)
        val found =
            VOCABULARY
                .findAll(source)
                .map { it.groupValues[1] }
                .groupingBy { it }
                .eachCount()
        println("1065|R4|source-guard|found=$found|covered=${COVERED.keys}")
        val uncovered = found.keys - COVERED.keys
        val stale = COVERED.keys - found.keys
        (
            uncovered.map { "$it: a call the theorem does not cover (add it to COVERED with its assertion)" } +
                stale.map { "$it: listed in COVERED but no longer in the source (remove it)" }
        ).shouldBeEmpty()
    }

    @Test
    fun `the source guard sees each call it exists for`() {
        val planted =
            listOf(
                "c.isLetter()",
                "s.lowercase()",
                "s.uppercase()",
                "s.trimEnd()",
                "s.isBlank()",
                "Regex(\"\\\\s+\")",
                "\"x\".toRegex()",
                "a.equals(b, ignoreCase = true)",
                "Character.isSpaceChar(c)",
                "c.isUpperCase()",
                "s.lines()",
                "c.digitToInt()",
            )
        val missed = planted.filter { line -> VOCABULARY.findAll(line).none { it.groupValues[1] !in COVERED } }
        missed.shouldBeEmpty()
        VOCABULARY.findAll("a.isWhitespace() b.trim() c.trimStart() d.isDigit() e.isLetterOrDigit()").count() shouldBe 5
    }

    /** The production gate's alphabet, so that a character added to it is walked here. */
    private fun alphabet(): List<Char> = (Char.MIN_VALUE..Char.MAX_VALUE).filter(CssText::inAlphabet)

    /** The ruling's alphabet, written here: U+0020 to U+007E without the backslash, tab, LF, CR. */
    private fun inRulingAlphabet(c: Char): Boolean = (c in ' '..'~' && c != '\\') || c == '\t' || c == '\n' || c == '\r'

    /** CSS Syntax §4.2 on one character of the alphabet (where a CR has become a newline by preprocessing). */
    private fun cssAnswers(c: Char): Map<String, Boolean> {
        val whitespace = c == ' ' || c == '\t' || c == '\n' || c == '\r'
        val digit = c in '0'..'9'
        val letter = c in 'a'..'z' || c in 'A'..'Z'
        return mapOf(
            "isWhitespace" to whitespace,
            "isDigit" to digit,
            "isLetterOrDigit" to (letter || digit),
            "trim" to whitespace,
            "trimStart" to whitespace,
        )
    }

    private companion object {
        const val SPACE = 0x20
        const val SOURCE = "modules/web/src/main/kotlin/co/datapipelines/web/ui/ScopedStylesheet.kt"

        /**
         * The calls the class may make whose answer depends on a character's Unicode category or case, each with the
         * assertion of the theorem that covers it on the alphabet. A call outside this list is red until it is added here
         * with an assertion; a listed call the source no longer makes is red until it is removed.
         */
        val COVERED: Map<String, String> =
            mapOf(
                "isWhitespace" to "isWhitespace: exactly space, tab, LF, CR",
                "isDigit" to "isDigit: exactly 0 to 9",
                "isLetterOrDigit" to "isLetterOrDigit: exactly an ASCII letter or digit",
                "trim" to "trim: removes exactly space, tab, LF, CR",
                "trimStart" to "trimStart: removes exactly space, tab, LF, CR",
            )

        /** Every Kotlin and Java call that classifies, folds or trims by Unicode category, and every regular expression. */
        val VOCABULARY =
            Regex(
                "\\b(" +
                    "isWhitespace|isDigit|isLetter|isLetterOrDigit|isUpperCase|isLowerCase|isTitleCase|isSpaceChar|isISOControl|" +
                    "isDefined|isJavaIdentifierStart|isJavaIdentifierPart|isIdentifierIgnorable|isSurrogate|isHighSurrogate|" +
                    "isLowSurrogate|isBlank|isNotBlank|isNullOrBlank|ifBlank|trim|trimStart|trimEnd|trimIndent|trimMargin|" +
                    "lowercase|uppercase|lowercaseChar|uppercaseChar|titlecase|titlecaseChar|capitalize|decapitalize|" +
                    "toLowerCase|toUpperCase|digitToInt|digitToIntOrNull|lines|lineSequence|ignoreCase|Character|Regex|toRegex" +
                    ")\\b",
            )
    }
}
