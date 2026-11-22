package co.datapipelines.web.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The scoping rule by rule (#1065 A.1, D2, D3): literal input, literal expected output, one case per rule,
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
                "@keyframes k { from { opacity: 0 } to { translate: 0 0.5rem } }"
        scoped(source) shouldBe
            "@font-face { font-family: \"F\"; src: url(a.woff2) }\n" +
            "@keyframes k { from { opacity: 0 } to { translate: 0 8px } }\n" +
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
    fun `a source that cannot be scoped is refused, naming it`(
        label: String,
        source: String,
        message: String,
    ) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope(source, SCOPE) }
        println("1065|A1|refusal|$label|${refusal.message}")
        refusal.message.orEmpty() shouldContain message
    }

    @Test
    fun `R1 the lander's case keeps the string and the address and converts the width`() {
        val result =
            ScopedStylesheet.scope(".card { content: \"1rem\"; background-image: url(\"/images/1rem.svg\"); width: 1rem }", SCOPE)
        result.text shouldBe block("& .card { content: \"1rem\"; background-image: url(\"/images/1rem.svg\"); width: 16px }")
        result.counts.remLengths shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("remTexts")
    fun `R1 rem inside a string or a url token is kept, a length beside it converts, in a declaration`(
        label: String,
        declaration: String,
        expected: String,
    ) {
        val output = scoped(".a { $declaration }")
        println("1065|R1|declaration|$label|$output")
        output shouldBe block("& .a { $expected }")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("remTexts")
    fun `R1 rem inside a string or a url token is kept, a length beside it converts, in a keyframes body`(
        label: String,
        declaration: String,
        expected: String,
    ) {
        val output = scoped("@keyframes k { to { $declaration } }")
        println("1065|R1|keyframes|$label|$output")
        output shouldBe "@keyframes k { to { $expected } }\n" + block("")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rootTokens")
    fun `R2 a root form inside a selector function is refused, naming the selector`(selector: String) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope("$selector { color: red }", SCOPE) }
        println("1065|R2|refusal|$selector|${refusal.message}")
        refusal.message.orEmpty() shouldContain "a root selector the scoping does not know: $selector"
    }

    @Test
    fun `R2 the beginning is read as a token too, an escaped root form included`() {
        scoped("body|a { c: d }") shouldBe block("& body|a { c: d }")
        scoped("html\\.x .y { c: d }") shouldBe block("& html\\.x .y { c: d }")
        scoped("bodyé { c: d }") shouldBe block("& bodyé { c: d }")
        scoped(":root-x .y { c: d }") shouldBe block("& :root-x .y { c: d }")
        scoped("\\62 ody .y { c: d }") shouldBe block("&:is(&) .y { c: d }")
        scoped("BODY.dark .y { c: d }") shouldBe block("&:is(&).dark .y { c: d }")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rootLookalikes")
    fun `R2 a selector that only reads like a root form is admitted under the scope`(selector: String) {
        val output = scoped("$selector { color: red }")
        println("1065|R2|admitted|$selector|$output")
        output shouldBe block("& $selector { color: red }")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapedTexts")
    fun `R3 a name, a url or a unit is read by its decoded value, the address and the name kept byte for byte`(
        label: String,
        source: String,
        expected: String,
        remLengths: Int,
    ) {
        val result = ScopedStylesheet.scope(source, SCOPE)
        println("1065|R3|text|$label|${result.text}|remLengths=${result.counts.remLengths}")
        result.text shouldBe expected
        result.counts.remLengths shouldBe remLengths
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapedRootTokens")
    fun `R3 a root form behind an escaped or unspaced of, or a sibling behind an escaped space, is refused`(
        selector: String,
        message: String,
    ) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope("$selector { color: red }", SCOPE) }
        println("1065|R3|refusal|$selector|${refusal.message}")
        refusal.message.orEmpty() shouldContain "$message: $selector"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapedLookalikes")
    fun `R3 an escaped keyword or name before a lookalike is admitted under the scope`(selector: String) {
        val output = scoped("$selector { color: red }")
        println("1065|R3|admitted|$selector|$output")
        output shouldBe block("& $selector { color: red }")
    }

    @Test
    fun `R3 the page element and the root are read decoded and case folded, as their plain spelling`() {
        scoped("\\68tml { font-size: 14px }") shouldBe scoped("html { font-size: 14px }")
        scoped("\\68tml { font-size: 14px }") shouldBe block("& { font-size: 14px; }")
        scoped("html, \\62ody { color: red }") shouldBe block("&, & { color: red; }")
        scoped("body { \\63olor: red }") shouldBe block("& { \\63olor: red; }")
        scoped(":ROOT { --a: 1rem }") shouldBe scoped(":root { --a: 1rem }")
        scoped(":ROOT { --a: 1rem }") shouldBe block("& { --a: 16px }")
        scoped(":ROOT .x { color: red }") shouldBe block("&:is(&) .x { color: red }")
        ScopedStylesheet.scope("\\68tml, \\62ody { color: red }", SCOPE).counts.rootExact shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapedRefusals")
    fun `R3 an escaped page element, an escaped at-rule name or an unreadable escape is refused, naming it`(
        label: String,
        source: String,
        message: String,
    ) {
        val refusal = shouldThrow<ScopedStylesheet.Refusal> { ScopedStylesheet.scope(source, SCOPE) }
        println("1065|R3|refusal|$label|${refusal.message}")
        refusal.message.orEmpty() shouldContain message
    }

    private fun scoped(source: String): String = ScopedStylesheet.scope(source, SCOPE).text

    /** The scope block around [inner], as the scoping writes it when nothing is hoisted. */
    private fun block(inner: String): String = "$SCOPE {\n$inner\n}\n"

    companion object {
        private const val SCOPE = ".s"

        @JvmStatic
        fun refusals(): List<Arguments> = REFUSALS

        /** R1 (D3): a declaration as written, and as it must leave the scoping; the same row runs in a rule and a keyframe. */
        @JvmStatic
        fun remTexts(): List<Arguments> =
            listOf(
                Arguments.of("a double-quoted string", "content: \"1rem\"", "content: \"1rem\""),
                Arguments.of("a single-quoted string", "content: '1rem'", "content: '1rem'"),
                Arguments.of("an escaped quote before the rem", "content: \"a\\\"1rem\"", "content: \"a\\\"1rem\""),
                Arguments.of("url with a double-quoted address", "background: url(\"/i/1rem.svg\")", "background: url(\"/i/1rem.svg\")"),
                Arguments.of("url with a single-quoted address", "background: url('/i/1rem.svg')", "background: url('/i/1rem.svg')"),
                Arguments.of("url with an unquoted address", "background: url(/img/1rem.svg)", "background: url(/img/1rem.svg)"),
                Arguments.of("url written in upper case", "background: URL(/img/1rem.svg)", "background: URL(/img/1rem.svg)"),
                Arguments.of("a string holding a parenthesis, then a length", "--label: \"(1rem\" 2rem", "--label: \"(1rem\" 32px"),
                Arguments.of(
                    "a url token and a length in one declaration",
                    "background: url(a-1rem.png) 0 1rem",
                    "background: url(a-1rem.png) 0 16px",
                ),
                Arguments.of(
                    "a length inside calc, after a string",
                    "--x: \"1rem\"; width: calc(100% - 2rem)",
                    "--x: \"1rem\"; width: calc(100% - 32px)",
                ),
                Arguments.of(
                    "a length in var after a url",
                    "background: url(x.png); margin: var(--a, 1rem)",
                    "background: url(x.png); margin: var(--a, 16px)",
                ),
                Arguments.of("a name that ends in url", "--myurl: 1rem", "--myurl: 16px"),
            )

        /** R2 (D2): a root form as a selector token anywhere but the supported beginning; each is refused. */
        @JvmStatic
        fun rootTokens(): List<String> =
            listOf(
                ".card:is(body)",
                ".card:not(html)",
                ".card:is(html)",
                ".card:not(body)",
                ".card:where(html)",
                ".card:where(body)",
                ".card:has(html)",
                ".card:has(body)",
                ".card:is(.a, body)",
                ".card:not(:is(html))",
                ".card:is(body.dark)",
                ".card:is(BODY)",
                ".card:has(> body)",
                ".card:nth-child(2n of .a, body)",
                ":root:not(body) .x",
                "html:is(.a) :where(body) .x",
                ".card:is(:root)",
                ".a body",
                ".a > html",
                ".card:is(b\\6f dy)",
                ".card:is(\\62 ody)",
                ".card:is(h\\tml)",
                ".card:is(*|body)",
                ".card::slotted(body)",
                ".card:-webkit-any(html)",
            )

        /** R2: forms that only read like a root form; each is admitted under the scope, its text unchanged. */
        @JvmStatic
        fun rootLookalikes(): List<String> =
            listOf(
                ".body",
                "#html",
                "[data-x=\"body\"]",
                "[data-x=body]",
                "[title=\"a (body) b\"]",
                ".html-view",
                "tbody",
                ".my-body",
                ".a:lang(html)",
                ".a:is(.body, #html, tbody)",
                ".a:not([data-x=\"body\"])",
                ".a:has(> .my-body)",
                ".a:nth-child(2n of .body)",
                ".a:nth-child(odd)",
                ".b\\6f dy",
                ".a body|x",
                ".a::part(body)",
                ".a:dir(ltr) :state(html)",
            )

        /**
         * R3 (D3): a source, its whole scoped text and its count of `rem` lengths. A `url` written with an escape is a url
         * token, its address kept; a unit in any case, escaped or after an exponent is `rem`; a name holding digits and
         * `rem` is a name, kept byte for byte in a declaration and in a keyframes prelude and its use alike.
         */
        @JvmStatic
        fun escapedTexts(): List<Arguments> =
            listOf(
                Arguments.of(
                    "R1 u\\72l in a declaration",
                    ".card { background: u\\72l(/img/1rem.svg); width: 1rem }",
                    "$SCOPE {\n& .card { background: u\\72l(/img/1rem.svg); width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "R1 \\75rl in a declaration",
                    ".card { background: \\75rl(/img/1rem.svg); width: 1rem }",
                    "$SCOPE {\n& .card { background: \\75rl(/img/1rem.svg); width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "R1 u\\72l in a keyframes body",
                    "@keyframes k { to { background: u\\72l(/img/1rem.svg); width: 1rem } }",
                    "@keyframes k { to { background: u\\72l(/img/1rem.svg); width: 16px } }\n$SCOPE {\n\n}\n",
                    1,
                ),
                Arguments.of(
                    "R1 \\75rl in a keyframes body",
                    "@keyframes k { to { background: \\75rl(/img/1rem.svg); width: 1rem } }",
                    "@keyframes k { to { background: \\75rl(/img/1rem.svg); width: 16px } }\n$SCOPE {\n\n}\n",
                    1,
                ),
                Arguments.of(
                    "C1 upper case, mixed case, escaped unit, exponent",
                    ".a { width: 1REM; height: 1Rem; margin: 1\\72 em; padding: 1e1rem }",
                    "$SCOPE {\n& .a { width: 16px; height: 16px; margin: 16px; padding: 160px }\n}\n",
                    4,
                ),
                Arguments.of(
                    "C2 a name with an escaped digit before rem",
                    ".a { animation-name: a\\31rem; width: 1rem }",
                    "$SCOPE {\n& .a { animation-name: a\\31rem; width: 16px }\n}\n",
                    1,
                ),
                Arguments.of(
                    "C2 the same name in a keyframes prelude and its use",
                    "@keyframes a\\31rem { to { width: 1rem } } .a { animation: a\\31rem 1s }",
                    "@keyframes a\\31rem { to { width: 16px } }\n$SCOPE {\n& .a { animation: a\\31rem 1s }\n}\n",
                    1,
                ),
                Arguments.of(
                    "a hash and a name holding rem stay, a length after a name converts",
                    ".a { --c: #a1rem; --n: x-1rem; margin: 0 .5rem; --u: 1remx }",
                    "$SCOPE {\n& .a { --c: #a1rem; --n: x-1rem; margin: 0 8px; --u: 1remx }\n}\n",
                    1,
                ),
            )

        /** R3 (D2): a root form behind an escaped or unspaced `of`, or a sibling combinator behind an escaped space. */
        @JvmStatic
        fun escapedRootTokens(): List<Arguments> =
            listOf(
                Arguments.of(".card:nth-child(2n \\6f f body)", "a root selector the scoping does not know"),
                Arguments.of(".card:nth-last-child(2n o\\66  html)", "a root selector the scoping does not know"),
                Arguments.of(":root:nth-child(2n \\6f f body) .x", "a root selector the scoping does not know"),
                Arguments.of(".card:nth-child(2n of:root)", "a root selector the scoping does not know"),
                Arguments.of(".card:nth-child(even of:is(body))", "a root selector the scoping does not know"),
                Arguments.of(":root.a\\ b + .x", "a root selector that selects a sibling of the container (combinator +)"),
                Arguments.of(":root.a\\31  ~ .x", "a root selector that selects a sibling of the container (combinator ~)"),
            )

        /** R3: the same escaped keyword before a lookalike, and escapes that only read like a root; each admitted. */
        @JvmStatic
        fun escapedLookalikes(): List<String> =
            listOf(
                ".card:nth-child(2n \\6f f .body)",
                ".card:nth-last-child(2n o\\66  #html)",
                ".card:nth-child(odd)",
                ".card:nth-of-type(2n)",
                ".a\\110000",
            )

        /** R3: an escaped page element is refused as its plain spelling is; an escaped at-rule name and an unreadable escape. */
        @JvmStatic
        fun escapedRefusals(): List<Arguments> =
            listOf(
                Arguments.of("C3 escaped body", "\\62ody { margin: 0 }", "neither carried nor dropped: \\62ody { margin: 0 }"),
                Arguments.of("C3 html and escaped body", "html, \\62ody { margin: 0 }", "neither carried nor dropped: html, \\62ody"),
                Arguments.of(
                    "C3 escaped body beside another selector",
                    "\\62ody, .a { color: red }",
                    "html or body listed beside another selector: \\62ody, .a",
                ),
                Arguments.of(
                    "an escaped at-rule name",
                    "@\\6d edia (x) { .a { b: c } }",
                    "an at-rule whose name holds an escape: @\\6d edia",
                ),
                Arguments.of(
                    "an at-rule name that only begins like media",
                    "@mediaé (x) { .a { b: c } }",
                    "an at-rule the scoping does not know: @mediaé",
                ),
                Arguments.of("an escape before a line break in a name", ".a\\\nb { c: d }", "an escape the scoping cannot read"),
                Arguments.of("an escape before a line break in a value", ".a { width: 1rem \\\n }", "an escape the scoping cannot read"),
            )

        /** Each refusal of D2 with its input and the words its message must hold (A.1). */
        private val REFUSALS: List<Arguments> =
            listOf(
                Arguments.of("@import", "@import url(x.css); .a { b: c }", "@import is refused"),
                Arguments.of(
                    "an unknown at-rule",
                    "@starting-style { .a { b: c } }",
                    "an at-rule the scoping does not know: @starting-style",
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
                Arguments.of("a root form not at the start", ".a :root { b: c }", "a root selector the scoping does not know: .a :root"),
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
