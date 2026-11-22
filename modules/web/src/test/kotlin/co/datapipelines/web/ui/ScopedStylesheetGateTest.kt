package co.datapipelines.web.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The gate of the scoping (#1065 round 4; owner ruling 2026-11-22, "Refuse at entry"): "The transform refuses, at build
 * and naming the place, any backslash escape or non-ASCII character outside comments and quoted strings." Literal input,
 * literal expected output or the whole message. A raw character is written with a Kotlin escape (` `), so the CSS
 * holds the character itself; a CSS escape is written `\\` (one backslash in the CSS).
 */
class ScopedStylesheetGateTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedByTheGate")
    fun `a character outside the alphabet, outside a comment and a string, is refused naming it, its line and the line`(
        label: String,
        source: String,
        message: String,
    ) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope(source, SCOPE) }
        println("1065|R4|gate|$label|${refusal.message}")
        refusal.message shouldBe message
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedAsBefore")
    fun `a string or a comment that never ends keeps the refusal it had before the gate`(
        label: String,
        source: String,
        message: String,
    ) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope(source, SCOPE) }
        println("1065|R4|unterminated|$label|${refusal.message}")
        refusal.message.orEmpty() shouldContain message
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("admitted")
    fun `an escape or a non-ASCII character inside a string or a comment is admitted, the string kept byte for byte`(
        label: String,
        source: String,
        expected: String,
        remLengths: Int,
    ) {
        val result = ScopedStylesheet.scope(source, SCOPE)
        println("1065|R4|admitted|$label|${result.text}|remLengths=${result.counts.remLengths}")
        result.text shouldBe expected
        result.counts.remLengths shouldBe remLengths
    }

    @Test
    fun `CRLF line endings scope as the LF form does, the same counts and the same text but for the CR`() {
        val lf =
            ":root { --a: 1rem }\n.a,\n.b { width: 1rem }\nbody { color: red; min-height: 1px }\n" +
                "@media (min-width: 40rem) {\n  .c { margin: 2rem }\n}\n@keyframes k {\n  to { top: 1rem }\n}\n"
        val crlf = lf.replace("\n", "\r\n")
        val fromLf = ScopedStylesheet.scope(lf, SCOPE)
        val fromCrlf = ScopedStylesheet.scope(crlf, SCOPE)
        println("1065|R4|crlf|lf=${fromLf.counts}|crlf=${fromCrlf.counts}")
        fromCrlf.counts shouldBe fromLf.counts
        fromCrlf.text.replace("\r", "") shouldBe fromLf.text
        fromLf.counts.remLengths shouldBe 4
    }

    @Test
    fun `a tab is whitespace to the scoping, between tokens and as a combinator`() {
        scoped(".a\t{\twidth:\t1rem\t}") shouldBe block("& .a {\twidth:\t16px\t}")
        scoped(":root\t.x { c: d }") shouldBe block("&:is(&)\t.x { c: d }")
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { scoped(":root.a\t+ .outside { c: d }") }
        refusal.message.orEmpty() shouldContain "selects a sibling of the container (combinator +)"
    }

    @Test
    fun `a string written straight after the root's part is skipped whole when the first combinator is read`() {
        val ascii = shouldThrow<ScopedStylesheet.Refusal> { scoped(":root\"a b\" + .outside { c: d }") }
        ascii.message.orEmpty() shouldContain "selects a sibling of the container (combinator +)"
        val unicode = shouldThrow<ScopedStylesheet.Refusal> { scoped(":root\"a b\" + .outside { c: d }") }
        unicode.message.orEmpty() shouldContain "selects a sibling of the container (combinator +)"
    }

    private fun scoped(source: String): String = ScopedStylesheet.scope(source, SCOPE).text

    private fun block(inner: String): String = "$SCOPE {\n$inner\n}\n"

    companion object {
        private const val SCOPE = ".s"
        private const val OUTSIDE = "a character the scoping refuses outside a quoted string"
        private const val ESCAPE = "an escape outside a quoted string"
        private const val BREAK = "a line break inside a quoted string"

        /** The R3.1 sibling and the R3.2 names of the third verdict, raw and escaped (item 2). */
        private val VERDICT_THREE: List<Arguments> =
            listOf(
                ":root.a b + .outside { color: rgb(1, 2, 3) }" to "U+1680",
                ":root.a b + .outside { color: rgb(1, 2, 3) }" to "U+00A0",
                ":root.a b + .outside { color: rgb(1, 2, 3) }" to "U+2003",
                ":root.a b ~ .outside { color: rgb(1, 2, 3) }" to "U+2003",
                ":root.a\\1680 b + .outside { color: rgb(1, 2, 3) }" to "U+005C",
                ".a { animation-name: ١1rem; width: 1rem }" to "U+0661",
                ".a { animation-name: １1rem; width: 1rem }" to "U+FF11",
                ".a { animation-name: \\661 1rem; width: 1rem }" to "U+005C",
                ".a { animation-name: \\ff11 1rem; width: 1rem }" to "U+005C",
                "@keyframes ١1rem { to { width: 1rem } } .a { animation-name: ١1rem }" to "U+0661",
                "@keyframes １1rem { to { width: 1rem } } .a { animation-name: １1rem }" to "U+FF11",
                "@keyframes \\661 1rem { to { width: 1rem } } .a { animation-name: \\661 1rem }" to "U+005C",
                "@keyframes \\ff11 1rem { to { width: 1rem } } .a { animation-name: \\ff11 1rem }" to "U+005C",
            ).map { (source, codePoint) ->
                val words = if (codePoint == "U+005C") ESCAPE else OUTSIDE
                Arguments.of("verdict 3: $source", source, "$words ($codePoint, line 1): $source")
            }

        /** Every other character the gate refuses, where it stands, and the message it gives (item 5 and the message). */
        private val OTHERS: List<Arguments> =
            listOf(
                Arguments.of("a form feed", ".a {\u000C width: 1rem }", "$OUTSIDE (U+000C, line 1): .a {\u000C width: 1rem }"),
                Arguments.of("a vertical tab", ".a {\u000B width: 1rem }", "$OUTSIDE (U+000B, line 1): .a {\u000B width: 1rem }"),
                Arguments.of("NUL", ".a\u0000 { b: c }", "$OUTSIDE (U+0000, line 1): .a\u0000 { b: c }"),
                Arguments.of("DEL", ".a\u007F { b: c }", "$OUTSIDE (U+007F, line 1): .a\u007F { b: c }"),
                Arguments.of("U+0080", ".a\u0080 { b: c }", "$OUTSIDE (U+0080, line 1): .a\u0080 { b: c }"),
                Arguments.of("a lone high surrogate", ".a\uD800 { b: c }", "$OUTSIDE (U+D800, line 1): .a\uD800 { b: c }"),
                Arguments.of("a lone low surrogate", ".a\uDC00 { b: c }", "$OUTSIDE (U+DC00, line 1): .a\uDC00 { b: c }"),
                Arguments.of("a surrogate pair names its code point", ".a😀 { b: c }", "$OUTSIDE (U+1F600, line 1): .a😀 { b: c }"),
                Arguments.of("a backslash at the end of the source", ".a { b: c } \\", "$ESCAPE (U+005C, line 1): .a { b: c } \\"),
                Arguments.of(
                    "an unquoted url holding a non-ASCII letter",
                    ".a { background: url(/i/é.svg) }",
                    "$OUTSIDE (U+00E9, line 1): .a { background: url(/i/é.svg) }",
                ),
                Arguments.of("a raw LF in a string", ".a { content: \"a\nb\" }", "$BREAK (U+000A, line 1): .a { content: \"a"),
                Arguments.of("a raw CR in a string", ".a { content: 'a\rb' }", "$BREAK (U+000D, line 1): .a { content: 'a"),
                Arguments.of(
                    "a raw form feed in a string",
                    ".a { content: \"a\u000Cb\" }",
                    "$BREAK (U+000C, line 1): .a { content: \"a\u000Cb\" }",
                ),
                Arguments.of(
                    "the line is counted over LF",
                    ".a { b: c }\n.b { d: e }\n.c .d { f: g }\n",
                    "$OUTSIDE (U+00A0, line 3): .c .d { f: g }",
                ),
                Arguments.of(
                    "the line is counted over CRLF as one break",
                    ".a { b: c }\r\n.b { d: e }\r\n.c .d { f: g }\r\n",
                    "$OUTSIDE (U+00A0, line 3): .c .d { f: g }",
                ),
                Arguments.of(
                    "the line is counted over a lone CR",
                    ".a { b: c }\r.b { d: e }\r.c .d { f: g }\r",
                    "$OUTSIDE (U+00A0, line 3): .c .d { f: g }",
                ),
                Arguments.of(
                    "a comment's characters are skipped, the first one outside is named",
                    "/* é\n \\62 */ .a é { b: c }",
                    "$OUTSIDE (U+00E9, line 2):  \\62 */ .a é { b: c }",
                ),
                Arguments.of(
                    "a string's characters are skipped, an escaped quote inside it too",
                    ".a { content: \"x\\\"é\" } .bé { c: d }",
                    "$OUTSIDE (U+00E9, line 1): .a { content: \"x\\\"é\" } .bé { c: d }",
                ),
                Arguments.of(
                    "the line is shown up to 80 characters",
                    ".a" + " ".repeat(90) + "é { b: c }",
                    "$OUTSIDE (U+00E9, line 1): .a" + " ".repeat(78),
                ),
            )

        @JvmStatic
        fun refusedByTheGate(): List<Arguments> = VERDICT_THREE + OTHERS

        @JvmStatic
        fun refusedAsBefore(): List<Arguments> =
            listOf(
                Arguments.of("an unterminated string", ".a { content: \"abc }", "an unterminated string"),
                Arguments.of("an unterminated string holding a non-ASCII letter", ".a { content: 'é }", "an unterminated string"),
                Arguments.of("an unterminated comment", ".a { b: c } /* open", "an unterminated comment at offset 12"),
                Arguments.of(
                    "an unterminated comment holding a non-ASCII letter",
                    ".a { b: c } /* é",
                    "an unterminated comment at offset 12",
                ),
            )

        /** Admitted (item 4): an escape or a non-ASCII character inside a string or a comment, the string byte for byte. */
        @JvmStatic
        fun admitted(): List<Arguments> =
            listOf(
                Arguments.of(
                    "an escape and glyphs in content and font-family",
                    ".a { content: \"\\2014  → é\"; font-family: \"Ñ\", serif; width: 1rem }",
                    "$SCOPE {\n& .a { content: \"\\2014  → é\"; font-family: \"Ñ\", serif; width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "the same in single quotes",
                    ".a { content: '\\2014  → é'; font-family: 'Ñ', serif; width: 1rem }",
                    "$SCOPE {\n& .a { content: '\\2014  → é'; font-family: 'Ñ', serif; width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "an escape and glyphs in a comment",
                    "/* \\2014  → é Ñ   \\62ody */ .a { width: 1rem }",
                    "$SCOPE {\n& .a { width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "a quoted url holding a glyph and an escape",
                    ".a { background: url(\"/img/é\\1rem.svg\"); width: 1rem }",
                    "$SCOPE {\n& .a { background: url(\"/img/é\\1rem.svg\"); width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "a single-quoted url holding a glyph and an escape",
                    ".a { background: url('/img/é\\1rem.svg') }",
                    "$SCOPE {\n& .a { background: url('/img/é\\1rem.svg') }\n}\n",
                    0,
                ),
                Arguments.of(
                    "an escaped line break inside a string",
                    ".a { content: \"a\\\nb\"; width: 1rem }",
                    "$SCOPE {\n& .a { content: \"a\\\nb\"; width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "a non-ASCII space inside an attribute value's string",
                    ":root[data-x=\"a b\"] .y { width: 1rem }",
                    "$SCOPE {\n&:is(&)[data-x=\"a b\"] .y { width: 16px }\n}\n",
                    1,
                ),
            )
    }
}
