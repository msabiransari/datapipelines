package co.datapipelines.web.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The scoping transform rule by rule (#1065 A.1, D2, D3): literal input, literal expected output, one case per rule,
 * and every refusal with the words its message must carry. The real sheets are [ScopedStylesheetSheetsTest]'s.
 */
class ScopedStylesheetTest {
    @Test
    fun `a plain selector is written under the scope`() {
        scoped(".a { color: red; }") shouldBe block("& .a { color: red; }")
    }

    @Test
    fun `each selector of a list is written under the scope`() {
        scoped(".a, .b > .c { margin: 0 }") shouldBe block("& .a, & .b > .c { margin: 0 }")
    }

    @Test
    fun `the three exact root forms become the scope itself`() {
        scoped(":root { --t: 1px }") shouldBe block("& { --t: 1px }")
        scoped("html { color: red }") shouldBe block("& { color: red; }")
        scoped("body { font-size: 13px }") shouldBe block("& { font-size: 13px; }")
    }

    @Test
    fun `a rule on body carries the listed declarations and drops min-height`() {
        val result = ScopedStylesheet.scope("body { min-height: 100vh; font-family: x; color: red }", SCOPE)
        result.text shouldBe block("& { font-family: x; color: red; }")
        result.counts.carried shouldBe 2
        result.counts.dropped shouldBe 1
    }

    @Test
    fun `a selector that begins with a root form keeps its weight on the scope`() {
        scoped(":root[data-theme=\"dark\"] { color: red }") shouldBe block("&:is(&)[data-theme=\"dark\"] { color: red }")
        scoped(":root :is(.a, .b) { color: red }") shouldBe block("&:is(&) :is(.a, .b) { color: red }")
        scoped("html.x .y { color: red }") shouldBe block("&:is(&).x .y { color: red }")
    }

    @Test
    fun `a rule inside a group rule is scoped in place and the condition keeps its rem`() {
        scoped("@media (min-width: 40rem) { .a { margin: 1rem } }") shouldBe
            block("@media (min-width: 40rem) {\n& .a { margin: 16px }\n}")
    }

    @Test
    fun `font-face and keyframes are hoisted before the block, a keyframe's rem converted`() {
        val source =
            "@font-face { font-family: \"F\"; src: url(a.woff2) } .a { color: red } " +
                "@keyframes k { from { opacity: 0 } to { transform: translateY(0.5rem) } }"
        scoped(source) shouldBe
            "@font-face { font-family: \"F\"; src: url(a.woff2) }\n" +
            "@keyframes k { from { opacity: 0 } to { transform: translateY(8px) } }\n" +
            block("& .a { color: red }")
    }

    @Test
    fun `rem lengths in a declaration become px, at most four decimals, a name and a sign respected`() {
        scoped(".a { padding: 0.25rem 1.125rem; width: calc(100% - 2rem); margin: -0.5rem; inset: .33333rem; grid-area: a-1rem }") shouldBe
            block("& .a { padding: 4px 18px; width: calc(100% - 32px); margin: -8px; inset: 5.3333px; grid-area: a-1rem }")
    }

    @Test
    fun `a comment holding a brace is removed and a string holding a comment marker is text`() {
        scoped("/* } { */ .a { content: \"/* not a comment */\" }") shouldBe block("& .a { content: \"/* not a comment */\" }")
    }

    @Test
    fun `the forms beside the sibling refusal are admitted`() {
        scoped(":root[data-a~=\"b\"] .x { c: d }") shouldBe block("&:is(&)[data-a~=\"b\"] .x { c: d }")
        scoped(":root:has(+ .x) .y { c: d }") shouldBe block("&:is(&):has(+ .x) .y { c: d }")
        scoped(":root > .a ~ .b { c: d }") shouldBe block("&:is(&) > .a ~ .b { c: d }")
        scoped("html.x .y + .z { c: d }") shouldBe block("&:is(&).x .y + .z { c: d }")
    }

    @Test
    fun `the counts say what was done`() {
        val counts =
            ScopedStylesheet
                .scope(
                    ":root { --a: 1rem } html { color: red } :root.x { b: c } :root > .y { b: c } :root .z { b: c } " +
                        "@media (x) { .q { b: c } } @font-face { font-family: F }",
                    SCOPE,
                ).counts
        counts shouldBe
            ScopedStylesheet.Counts(
                styleRules = 6,
                selectors = 6,
                rootExact = 2,
                rootBeginning = mapOf("child" to 1, "descendant" to 1, "none" to 1),
                hoisted = mapOf("font-face" to 1),
                groups = mapOf("media" to 1),
                carried = 1,
                dropped = 0,
                remLengths = 1,
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `a source the transform cannot scope is refused, naming it`(
        label: String,
        source: String,
        message: String,
    ) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope(source, SCOPE) }
        println("1065|A1|refusal|$label|${refusal.message}")
        refusal.message.orEmpty() shouldContain message
    }

    private fun scoped(source: String): String = ScopedStylesheet.scope(source, SCOPE).text

    /** The scope block around [inner], as the transform writes it when nothing is hoisted. */
    private fun block(inner: String): String = "$SCOPE {\n$inner\n}\n"

    companion object {
        private const val SCOPE = ".s"

        @JvmStatic
        fun refusals(): List<Arguments> = REFUSALS

        /** Each refusal of D2 with its input and the words its message must hold (A.1). */
        private val REFUSALS: List<Arguments> =
            listOf(
                Arguments.of("@import", "@import url(x.css); .a { b: c }", "@import is refused"),
                Arguments.of(
                    "an unknown at-rule",
                    "@starting-style { .a { b: c } }",
                    "an at-rule this transform does not know: @starting-style",
                ),
                Arguments.of(
                    "@property",
                    "@property --x { syntax: '*'; inherits: false }",
                    "@property is refused (it registers a custom property",
                ),
                Arguments.of("@counter-style", "@counter-style x { system: cyclic }", "@counter-style is refused"),
                Arguments.of("@font-feature-values", "@font-feature-values F { @styleset { a: 1 } }", "@font-feature-values is refused"),
                Arguments.of("@-webkit-keyframes", "@-webkit-keyframes k { from { b: c } }", "@-webkit-keyframes is refused"),
                Arguments.of("an at-rule without a block", "@layer a, b;", "an at-rule without a block: @layer a, b"),
                Arguments.of("a root form not at the start", ".a :root { b: c }", "a root selector this transform does not know: .a :root"),
                Arguments.of(
                    ":root + sibling",
                    ":root + .outside { color: red }",
                    "selects a sibling of the container (combinator +): :root + .outside",
                ),
                Arguments.of("html.x ~ sibling", "html.x ~ .outside { color: red }", "(combinator ~): html.x ~ .outside"),
                Arguments.of(
                    "body:not() + sibling, no spaces",
                    "body:not(.a)+.outside { color: red }",
                    "(combinator +): body:not(.a)+.outside",
                ),
                Arguments.of(
                    ":root[data-theme] ~ p",
                    ":root[data-theme=\"dark\"] ~ p { color: red }",
                    "(combinator ~): :root[data-theme=\"dark\"] ~ p",
                ),
                Arguments.of(":root:has() || td", ":root:has(.x) || td { color: red }", "(combinator ||): :root:has(.x) || td"),
                Arguments.of(
                    "a nested source rule",
                    ".a { color: red; .b { color: blue } }",
                    "a source rule already nests (a rule inside its block): .a",
                ),
                Arguments.of("& in a source", "& .a { color: red }", "a source rule already nests (it holds &): & .a"),
                Arguments.of("an unclassified body declaration", "body { margin: 0 }", "neither carried nor dropped: body { margin: 0 }"),
                Arguments.of(
                    "html beside another selector",
                    "html, .a { color: red }",
                    "html or body listed beside another selector: html, .a",
                ),
                Arguments.of("an unbalanced brace (stray)", ".a { b: c } }", "an unbalanced brace: a stray }"),
                Arguments.of("an unbalanced brace (unclosed)", ".a { b: c", "an unbalanced brace: the block after .a is not closed"),
                Arguments.of(
                    "@font-face inside @media",
                    "@media print { @font-face { font-family: F } }",
                    "@font-face inside a group rule",
                ),
                Arguments.of(
                    "@keyframes inside @supports",
                    "@supports (x: y) { @keyframes k { from { b: c } } }",
                    "@keyframes inside a group rule",
                ),
                Arguments.of("an unterminated comment", ".a { b: c } /* open", "an unterminated comment"),
            )
    }
}
