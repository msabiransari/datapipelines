package co.datapipelines.web.ui

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The scoping of a stylesheet under one selector (#1065 D2, D3; js-rendered record, note of 2026-11-21 "The stylesheet
 * inside the container"): a pure function, text and a scope selector in, text out. No request value ever reaches it.
 *
 * The output is the hoisted at-rules ([HOISTED]: `@font-face` and `@keyframes`, global by nature, unchanged but for
 * D3), then ONE block `<scope> { … }` holding every other rule by CSS nesting. Per statement of the source:
 * - comments are removed (aware of strings); the text is split into top-level statements aware of strings and
 *   parentheses; an unbalanced brace is refused;
 * - [GROUPS] stay in place and their content is scoped the same way; a hoisted at-rule inside one is refused;
 * - every other at-rule is refused by name ([REFUSED], each with its reason, or as unknown), and so is an at-rule
 *   without a block: each would act on the host's page from outside the block;
 * - a source rule that nests, or whose selector holds `&`, is refused;
 * - each selector, split at top-level commas: exactly `:root`, `html` or `body` becomes `&`; one that BEGINS with one
 *   of the three becomes `&:is(&)` and the rest (the rule keeps its weight), unless the first combinator after the
 *   root's own part is a sibling one (`+`, `~`, `||`): that would select an element of the host's beside the container,
 *   so it is refused; one of the three anywhere else is refused, at any depth of parentheses (the selector is read
 *   token by token, [SELECTOR_FUNCTIONS] say which function's argument is a selector); every other selector becomes
 *   `& ` and itself;
 * - a rule whose selectors are only `html` and `body`: each declaration is carried onto the container ([CARRIED]),
 *   dropped ([DROPPED]), or refused naming the rule;
 * - every length in `rem` inside a declaration or a `@keyframes` becomes `px` (× 16, at most four decimals): `rem` is
 *   the HOST's root font size. A group rule's condition keeps its `rem` (there it is the browser's default size), and so
 *   does the text of a string, of a name or of a `url(…)` token, which is not a length: it leaves byte for byte as it
 *   entered. The value is read as a run of tokens ([CssText.mapDimension]): only a number whose unit is `rem` changes.
 *
 * The gate comes first (#1065 round 4; owner ruling 2026-11-22, "Refuse at entry"; quoted with this class's noun, the
 * ruling's being the one the vocabulary guard retires): "The scoping refuses, at build and naming the place, any
 * backslash escape or non-ASCII character outside comments and quoted strings."
 * [CssText.gate] reads the source ONCE, before any rule is read: outside a comment and a quoted string only U+0020 to
 * U+007E without the backslash, tab, LF and CR pass; a string holding an unescaped line break (LF, CR or form feed) is
 * refused; a string or a comment that never ends is refused as before. The refusal names the code point, the line and
 * that line's text. Inside a comment or a string anything passes (a glyph or an escape in `content: "…"` leaves byte for
 * byte). So the reader below meets only that alphabet outside strings, where Kotlin's whitespace, digit and letter are
 * CSS's (ScopedStylesheetAlphabetTest walks it).
 *
 * Every decision made by a CSS name, keyword or unit (a root form, a page element, a pseudo-class, an at-rule, a
 * property on the page element, `url`, the unit `rem`) is made on the whole token, ASCII case folded, read by the one
 * name reader [CssText.name]: `1REM` is a length, `:ROOT` is `:root`, `html-view` is not `html`. What the reader cannot
 * classify with certainty is refused, naming it: never a guess toward admitting a selector or rewriting text.
 *
 * A refusal ([Refusal]) is a refusal of the build's own input: the real sheets are scoped by a test, so a sheet this
 * cannot scope is a red test naming the rule, never a wrong stylesheet in front of a host.
 */
object ScopedStylesheet {
    /** The at-rules written before the block, unchanged: what they define is defined for the host's whole page. */
    val HOISTED: Set<String> = setOf("font-face", "keyframes")

    /** The group rules kept in place inside the block, their content scoped the same way. */
    val GROUPS: Set<String> = setOf("media", "supports", "container", "layer")

    /** The at-rules refused BY NAME, each with why it cannot stand inside the block. Any other at-rule is unknown. */
    val REFUSED: Map<String, String> =
        mapOf(
            "import" to "it loads another sheet whose rules would not be scoped",
            "charset" to "it is only valid as a file's first statement, which the joined sheet does not keep",
            "namespace" to "it changes how every selector of the host's page is read",
            "scope" to "it brings its own scoping root, which may lie outside the container",
            "page" to "it styles the printed page of the host",
            "property" to "it registers a custom property for the whole document",
            "counter-style" to "it defines a counter style for the whole document",
            "font-feature-values" to "it defines font feature names for the whole document",
            "-webkit-keyframes" to "an animation is written as keyframes, which is hoisted; this prefixed form is not",
        )

    /** Declarations of a rule on `html`/`body` carried onto the container, each with what it gives the dashboard. */
    val CARRIED: Map<String, String> =
        mapOf(
            "font-family" to "the theme's text face, inherited by every text of the dashboard",
            "font-size" to "the base text size every inherited size of the dashboard starts from",
            "line-height" to "the base line height the dashboard's text inherits",
            "color" to "the theme's text colour, inherited by the dashboard",
            "background-color" to "the theme's page surface, painted under the dashboard (a host rule by id wins over it)",
            "-webkit-text-size-adjust" to "keeps a mobile browser from enlarging the dashboard's text on its own",
            "-moz-tab-size" to "the tab width of preformatted text, inherited",
            "tab-size" to "the tab width of preformatted text, inherited",
            "-webkit-font-smoothing" to "the text rendering of the product's pages, inherited",
            "-moz-osx-font-smoothing" to "the text rendering of the product's pages, inherited",
            "text-rendering" to "the text rendering of the product's pages, inherited",
        )

    /** Declarations of a rule on `html`/`body` dropped, each with why the container must not carry it. */
    val DROPPED: Map<String, String> =
        mapOf("min-height" to "a page's height, not a container's: carried, it made the container 1,913 px tall")

    /** What one scoping did (the real-sheet test prints these beside the brief's). */
    data class Counts(
        val styleRules: Int,
        val selectors: Int,
        val rootExact: Int,
        val rootBeginning: Map<String, Int>,
        val hoisted: Map<String, Int>,
        val groups: Map<String, Int>,
        val carried: Int,
        val dropped: Int,
        val remLengths: Int,
    )

    /** The scoped text and its [counts]. */
    data class Scoped(
        val text: String,
        val counts: Counts,
    )

    /** A source the scoping refuses; the message names the rule or the at-rule. */
    class Refusal(
        message: String,
    ) : IllegalArgumentException(message)

    /** [source] scoped under [scope] (D2, D3). */
    fun scope(
        source: String,
        scope: String,
    ): Scoped {
        CssText.gate(source)
        val run = Run()
        val (inner, hoisted) = run.rules(CssText.stripComments(source), top = true)
        val text = hoisted.joinToString("") { it + "\n" } + scope + " {\n" + inner.joinToString("\n") + "\n}\n"
        return Scoped(text, run.counts())
    }

    /** One scoping's state: the counters it reports. */
    private class Run {
        private var styleRules = 0
        private var selectors = 0
        private var rootExact = 0
        private val rootBeginning = sortedMapOf("child" to 0, "descendant" to 0, "none" to 0)
        private val hoisted = sortedMapOf<String, Int>()
        private val groups = sortedMapOf<String, Int>()
        private var carried = 0
        private var dropped = 0
        private var remLengths = 0

        fun counts() = Counts(styleRules, selectors, rootExact, rootBeginning, hoisted, groups, carried, dropped, remLengths)

        /** The scoped rules of a rule list (inside the block) and, at the top level, the hoisted at-rules. */
        fun rules(
            css: String,
            top: Boolean,
        ): Pair<List<String>, List<String>> {
            val inner = mutableListOf<String>()
            val lifted = mutableListOf<String>()
            for (statement in CssText.statements(css)) {
                val prelude = statement.prelude
                if (prelude.startsWith("@")) {
                    atRule(statement, top, inner, lifted)
                } else {
                    inner += styleRule(prelude, statement.body ?: throw Refusal("a statement that is not a rule: $prelude"))
                }
            }
            return inner to lifted
        }

        @Suppress("ThrowsCount") // each refusal of D2 is its own statement and names the rule it refuses
        private fun atRule(
            statement: CssText.Statement,
            top: Boolean,
            inner: MutableList<String>,
            lifted: MutableList<String>,
        ) {
            val prelude = statement.prelude
            val name = atName(prelude)
            REFUSED[name]?.let { throw Refusal("the at-rule @$name is refused ($it): ${prelude.take(PRELUDE_SHOWN)}") }
            val body = statement.body
            when {
                name !in HOISTED && name !in GROUPS -> {
                    throw Refusal(
                        "an at-rule the scoping does not know: ${prelude.take(PRELUDE_SHOWN)}",
                    )
                }

                body == null -> {
                    throw Refusal("an at-rule without a block: ${prelude.take(PRELUDE_SHOWN)}")
                }

                name in HOISTED -> {
                    if (!top) throw Refusal("@$name inside a group rule (it would be hoisted out of its condition): $prelude")
                    hoisted.merge(name, 1, Int::plus)
                    lifted += "$prelude {${if (name == "keyframes") px(body) else body}}"
                }

                else -> {
                    groups.merge(name, 1, Int::plus)
                    inner += "$prelude {\n" + rules(body, top = false).first.joinToString("\n") + "\n}"
                }
            }
        }

        private fun styleRule(
            prelude: String,
            body: String,
        ): String {
            styleRules += 1
            if ('&' in prelude) throw Refusal("a source rule already nests (it holds &): $prelude")
            if ('{' in CssText.outsideStrings(body)) throw Refusal("a source rule already nests (a rule inside its block): $prelude")
            val list = CssText.splitTopLevel(prelude, ',').map(String::trim).filter(String::isNotEmpty)
            val declarations = if (list.any(::pageElement)) pageDeclarations(prelude, list, body) else body
            return list.joinToString(", ") { selector(it) } + " {" + px(declarations) + "}"
        }

        /** A rule on `html`/`body`: [CARRIED] declarations kept, [DROPPED] ones left out, any other refused. */
        private fun pageDeclarations(
            prelude: String,
            list: List<String>,
            body: String,
        ): String {
            if (!list.all(::pageElement)) throw Refusal("html or body listed beside another selector: $prelude")
            val kept = mutableListOf<String>()
            for (declaration in CssText.splitTopLevel(body, ';').map(String::trim).filter(String::isNotEmpty)) {
                val property = CssText.wholeName(declaration.substringBefore(':').trim())?.let(CssText::fold).orEmpty()
                when (property) {
                    in DROPPED -> {
                        dropped += 1
                    }

                    in CARRIED -> {
                        carried += 1
                        kept += declaration
                    }

                    else -> {
                        throw Refusal(
                            "a declaration on the page element that is neither carried nor dropped: $prelude { $declaration }",
                        )
                    }
                }
            }
            return kept.joinToString("") { " $it;" } + " "
        }

        @Suppress("ThrowsCount") // each refused selector form of D2 is its own statement and names the selector
        private fun selector(source: String): String {
            selectors += 1
            if (exactRoot(source)) {
                rootExact += 1
                return "&"
            }
            val root = rootStart(source)
            if (root != null) {
                val rest = source.substring(root)
                if (namesRoot(rest)) throw Refusal("a root selector the scoping does not know: $source")
                val combinator = firstCombinator(rest)
                if (combinator in SIBLING_COMBINATORS) {
                    throw Refusal("a root selector that selects a sibling of the container (combinator $combinator): $source")
                }
                rootBeginning.merge(combinator ?: "none", 1, Int::plus)
                return "&:is(&)$rest"
            }
            if (namesRoot(source)) throw Refusal("a root selector the scoping does not know: $source")
            return "& $source"
        }

        /** [css] with every `rem` length as `px` (D3); a string, a name and a `url(…)` token are text, kept as they are. */
        private fun px(css: String): String =
            CssText.mapDimension(css, unit = "rem") { magnitude ->
                remLengths += 1
                val value = magnitude.multiply(ROOT_PX).setScale(PX_DECIMALS, RoundingMode.HALF_UP)
                value.stripTrailingZeros().toPlainString() + "px"
            }
    }

    /** The at-rule's name, ASCII case folded. */
    private fun atName(prelude: String): String {
        if (prelude.length < 2 || !CssText.startsName(prelude, 1)) throw Refusal("an at-rule without a name: $prelude")
        return CssText.fold(CssText.name(prelude, 1).first)
    }

    /** Whether [selector] is exactly `:root`, `html` or `body`, read as a token (ASCII case folded). */
    private fun exactRoot(selector: String): Boolean = rootStart(selector) == selector.length

    /** Whether [selector] is exactly `html` or `body`, read as a token: a rule on the page element. */
    private fun pageElement(selector: String): Boolean = !selector.startsWith(":") && exactRoot(selector)

    /**
     * The combinator that follows the root's own compound in [rest] (what is written onto the root without a space):
     * null when nothing follows, `descendant`, `child`, or the sibling forms `+`, `~`, `||`. Brackets and parentheses
     * are skipped whole, so `[data-a~="b"]` and `:has(+ .x)` are part of the compound; so is a quoted string, so a space
     * inside `"a b"` never reads as a combinator.
     */
    internal fun firstCombinator(rest: String): String? {
        var i = 0
        while (i < rest.length && !rest[i].isWhitespace() && rest[i] !in ">+~|") {
            i =
                when (rest[i]) {
                    '[', '(' -> CssText.closing(rest, i)
                    '"', '\'' -> CssText.stringEnd(rest, i) + 1
                    else -> i + 1
                }
        }
        val tail = rest.substring(i).trimStart()
        return when {
            tail.isEmpty() -> null
            tail.startsWith("||") -> "||"
            tail[0] == '>' -> "child"
            tail[0] == '+' || tail[0] == '~' -> tail[0].toString()
            else -> "descendant"
        }
    }

    /**
     * The pseudo-classes and pseudo-elements whose argument is a selector list, each with why (D2, round 2): inside
     * these a root form is a selector token and is refused. A function not listed (`:lang(html)`, `:nth-of-type(2n)`,
     * `::part(x)`, `:dir(ltr)`) takes no selector, and its argument is left alone.
     */
    val SELECTOR_FUNCTIONS: Map<String, String> =
        mapOf(
            "is" to "matches any selector of its list",
            "where" to "matches any selector of its list, with no weight",
            "not" to "matches what none of its list selects",
            "has" to "a relative selector list: what the element contains or precedes",
            "matches" to "the earlier name of :is",
            "-webkit-any" to "the prefixed earlier form of :is",
            "-moz-any" to "the prefixed earlier form of :is",
            "nth-child" to "after its `of` keyword the argument is a selector list; its An+B part never reads as a root form",
            "nth-last-child" to "after its `of` keyword the argument is a selector list; its An+B part never reads as a root form",
            "host" to "a compound selector the shadow host must match",
            "host-context" to "a compound selector the host or one of its ancestors must match",
            "slotted" to "a compound selector the slotted element must match",
            "cue" to "a selector inside a media track's cue",
            "current" to "a selector of the element being presented in time",
            "past" to "a selector of an element presented before the current one",
            "future" to "a selector of an element presented after the current one",
        )

    /**
     * Whether [selector] holds `:root`, `html` or `body` as a selector token (D2), at any depth of the parentheses of a
     * [SELECTOR_FUNCTIONS] function. Read token by token: an attribute selector and a string are skipped whole, a class
     * or an id is a name and never a root.
     */
    internal fun namesRoot(selector: String): Boolean {
        var i = 0
        while (i < selector.length) {
            val (found, next) = token(selector, i)
            if (found) return true
            i = next
        }
        return false
    }

    /** The selector token at [at]: whether it is or holds a root form, and the index after it. */
    private fun token(
        selector: String,
        at: Int,
    ): Pair<Boolean, Int> {
        val c = selector[at]
        return when {
            c == '[' || c == '(' -> {
                false to CssText.closing(selector, at)
            }

            c == '"' || c == '\'' -> {
                false to CssText.stringEnd(selector, at) + 1
            }

            c == '.' || c == '#' -> {
                false to CssText.name(selector, at + 1).second
            }

            c == ':' -> {
                pseudo(selector, at)
            }

            CssText.startsName(selector, at) -> {
                val (name, end) = CssText.name(selector, at)
                val namespacePrefix = selector.startsWith("|", end) && !selector.startsWith("||", end)
                (!namespacePrefix && CssText.fold(name) in PAGE_ELEMENTS) to end
            }

            else -> {
                false to at + 1
            }
        }
    }

    /**
     * The index just after the root form [source] begins with, as a whole token (`:root`, `html` or `body`, ASCII case
     * folded), or null: `html-view`, `body|a` (a namespace prefix) and `:root-x` begin with none.
     */
    private fun rootStart(source: String): Int? {
        val end =
            when {
                source.startsWith(":") -> {
                    CssText.name(source, 1).takeIf { (name, _) -> CssText.fold(name) == "root" }?.second
                }

                source.isNotEmpty() && CssText.startsName(source, 0) -> {
                    CssText.name(source, 0).takeIf { (name, _) -> CssText.fold(name) in PAGE_ELEMENTS }?.second
                }

                else -> {
                    null
                }
            }
        return end?.takeUnless { source.startsWith("|", it) && !source.startsWith("||", it) }
    }

    /**
     * The pseudo-class or pseudo-element at [colon]: whether it is or holds a root form, and the index after it. The
     * argument of a [SELECTOR_FUNCTIONS] function is read whole, an nth function's too: its An+B part (`odd`, `2n+1`)
     * holds no root form, so its `of`, however written (`of .x`, `of:root`), needs no finding.
     */
    private fun pseudo(
        selector: String,
        colon: Int,
    ): Pair<Boolean, Int> {
        val single = !selector.startsWith("::", colon)
        val (raw, end) = CssText.name(selector, if (single) colon + 1 else colon + 2)
        val name = CssText.fold(raw)
        if (!selector.startsWith("(", end)) return (single && name == "root") to end
        val close = CssText.closing(selector, end)
        if (name !in SELECTOR_FUNCTIONS) return false to close
        return namesRoot(selector.substring(end + 1, (close - 1).coerceAtLeast(end + 1))) to close
    }

    private const val PRELUDE_SHOWN = 80
    private const val PX_DECIMALS = 4
    private val ROOT_PX = BigDecimal(16)
    private val PAGE_ELEMENTS = setOf("html", "body")
    private val SIBLING_COMBINATORS = setOf("+", "~", "||")
}

/** The small CSS reader the scoping needs: comments, top-level statements, lists split outside brackets and strings. */
internal object CssText {
    /** One top-level statement: its prelude, and its block's content (null for a statement ended by `;`). */
    data class Statement(
        val prelude: String,
        val body: String?,
    )

    /**
     * The gate (#1065 round 4; owner ruling 2026-11-22): outside a comment and a quoted string every character of [css]
     * is in the alphabet ([inAlphabet]), and a string holds no unescaped line break, where the reader and a browser would
     * disagree on its end. A comment and the rest of a string may hold anything. A string or a comment that never ends is
     * left to [stripComments], which refuses it as before. The first offence is refused, naming its code point, its line
     * and that line's text.
     */
    fun gate(css: String) {
        var i = 0
        while (i < css.length) {
            i =
                when {
                    css.startsWith("/*", i) -> css.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: return
                    css[i] == '"' || css[i] == '\'' -> quotedEnd(css, i) ?: return
                    inAlphabet(css[i]) -> i + 1
                    css[i] == '\\' -> throw offence(css, i, "an escape outside a quoted string")
                    else -> throw offence(css, i, "a character the scoping refuses outside a quoted string")
                }
        }
    }

    /** The gate's alphabet: U+0020 to U+007E without the backslash, and tab, LF and CR (a Windows checkout). */
    fun inAlphabet(c: Char): Boolean = (c in ' '..'~' && c != '\\') || c == '\t' || c == '\n' || c == '\r'

    /** The index after the quote that closes the string opened at [open], or null when none does; a raw line break is refused. */
    private fun quotedEnd(
        css: String,
        open: Int,
    ): Int? {
        var i = open + 1
        while (i < css.length) {
            val c = css[i]
            i =
                when {
                    c == css[open] -> return i + 1
                    c in LINE_BREAKS -> throw offence(css, i, "a line break inside a quoted string")
                    c == '\\' && css.startsWith("\r\n", i + 1) -> i + ESCAPED_CRLF
                    c == '\\' -> i + 2
                    else -> i + 1
                }
        }
        return null
    }

    /** The gate's refusal of the character at [at]: what it is, its code point, its line, and that line's text. */
    private fun offence(
        css: String,
        at: Int,
        what: String,
    ): ScopedStylesheet.Refusal {
        var line = 1
        var start = 0
        for (k in 0 until at) {
            if (css[k] == '\n' || (css[k] == '\r' && !css.startsWith("\n", k + 1))) {
                line += 1
                start = k + 1
            }
        }
        var end = start
        while (end < css.length && css[end] != '\n' && css[end] != '\r') end += 1
        val codePoint = "U+" + "%04X".format(css.codePointAt(at))
        return ScopedStylesheet.Refusal("$what ($codePoint, line $line): ${css.substring(start, end).take(PRELUDE_SHOWN)}")
    }

    /** [css] with every comment replaced by one space; a comment marker inside a string is text. */
    fun stripComments(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        var quote: Char? = null
        while (i < css.length) {
            val c = css[i]
            when {
                quote != null -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < css.length) {
                        out.append(css[i + 1])
                        i += 1
                    } else if (c == quote) {
                        quote = null
                    }
                }

                c == '"' || c == '\'' -> {
                    quote = c
                    out.append(c)
                }

                css.startsWith("/*", i) -> {
                    val end = css.indexOf("*/", i + 2)
                    if (end < 0) throw ScopedStylesheet.Refusal("an unterminated comment at offset $i")
                    out.append(' ')
                    i = end + 1
                }

                else -> {
                    out.append(c)
                }
            }
            i += 1
        }
        if (quote != null) throw ScopedStylesheet.Refusal("an unterminated string")
        return out.toString()
    }

    /** The top-level statements of a rule list (comments already removed). */
    fun statements(css: String): List<Statement> {
        val out = mutableListOf<Statement>()
        var i = nonSpace(css, 0)
        while (i < css.length) {
            val start = i
            i = preludeEnd(css, i)
            val prelude = css.substring(start, i).trim()
            i =
                if (css[i] == ';') {
                    out += Statement(prelude, null)
                    i + 1
                } else {
                    val end = blockEnd(css, i, prelude)
                    out += Statement(prelude, css.substring(i + 1, end))
                    end + 1
                }
            i = nonSpace(css, i)
        }
        return out
    }

    /** The first index at or after [from] that is not whitespace, or the end of [css]. */
    private fun nonSpace(
        css: String,
        from: Int,
    ): Int {
        var i = from
        while (i < css.length && css[i].isWhitespace()) i += 1
        return i
    }

    /** The index of the `;` or `{` that ends the prelude starting at [from]; a stray `}` or no end is refused. */
    private fun preludeEnd(
        css: String,
        from: Int,
    ): Int {
        var i = from
        var depth = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '"' || c == '\'' -> i = stringEnd(css, i)
                c == '(' || c == '[' -> depth += 1
                c == ')' || c == ']' -> depth -= 1
                depth == 0 && (c == ';' || c == '{') -> return i
                depth == 0 && c == '}' -> throw ScopedStylesheet.Refusal("an unbalanced brace: a stray } at offset $i")
            }
            i += 1
        }
        throw ScopedStylesheet.Refusal("a statement without an end: ${css.substring(from).take(PRELUDE_SHOWN)}")
    }

    /** The index of the `}` that closes the block opened at [open]; an unclosed block is refused. */
    private fun blockEnd(
        css: String,
        open: Int,
        prelude: String,
    ): Int {
        var depth = 0
        var i = open
        while (i < css.length) {
            when (css[i]) {
                '"', '\'' -> {
                    i = stringEnd(css, i)
                }

                '{' -> {
                    depth += 1
                }

                '}' -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
            i += 1
        }
        throw ScopedStylesheet.Refusal("an unbalanced brace: the block after ${prelude.take(PRELUDE_SHOWN)} is not closed")
    }

    /** The index of the quote that closes the string opened at [open] (escapes skipped). */
    fun stringEnd(
        css: String,
        open: Int,
    ): Int {
        var i = open + 1
        while (i < css.length && css[i] != css[open]) i += if (css[i] == '\\') 2 else 1
        return i
    }

    /** The index just after the bracket or parenthesis that closes the one at [open] (strings skipped). */
    fun closing(
        text: String,
        open: Int,
    ): Int {
        val close = if (text[open] == '[') ']' else ')'
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> {
                    i = stringEnd(text, i)
                }

                c == text[open] -> {
                    depth += 1
                }

                c == close -> {
                    depth -= 1
                    if (depth == 0) return i + 1
                }
            }
            i += 1
        }
        return text.length
    }

    /** [text] split at every [separator] outside brackets, parentheses and strings. */
    fun splitTopLevel(
        text: String,
        separator: Char,
    ): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> {
                    i = stringEnd(text, i)
                }

                c == '(' || c == '[' -> {
                    i = closing(text, i)
                    continue
                }

                c == separator -> {
                    out += text.substring(start, i)
                    start = i + 1
                }
            }
            i += 1
        }
        out += text.substring(start)
        return out
    }

    /**
     * [text] read as a run of tokens, with every number whose unit (ASCII case folded) is [unit] written as its sign and
     * [convert] of its magnitude. Every other token leaves byte for byte: a string; a name, never looked into (`a1rem`,
     * `x-1rem`); a name followed by `(` that folds to `url` (`url(`, `URL(`), with the address up to its `)`, quoted or
     * not; a hash (`#a1rem`); a number with another unit (`1remx`, `1em`); any other character.
     */
    fun mapDimension(
        text: String,
        unit: String,
        convert: (BigDecimal) -> String,
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val number = numberEnd(text, i)
            if (number > i) {
                i = dimension(text, i, number, unit, convert, out)
                continue
            }
            val end =
                when {
                    c == '"' || c == '\'' -> stringEnd(text, i) + 1
                    startsIdent(text, i) -> identEnd(text, i)
                    c == '#' && i + 1 < text.length && isNameChar(text[i + 1]) -> name(text, i + 1).second
                    else -> i + 1
                }.coerceAtMost(text.length)
            out.append(text, i, end)
            i = end
        }
        return out.toString()
    }

    /**
     * The number at [from] (ending at [number]) and its unit, written to [out]: converted when the unit folds to
     * [unit], byte for byte otherwise. Returns the index after the token.
     */
    private fun dimension(
        text: String,
        from: Int,
        number: Int,
        unit: String,
        convert: (BigDecimal) -> String,
        out: StringBuilder,
    ): Int {
        val (name, after) = if (startsIdent(text, number)) name(text, number) else "" to number
        if (fold(name) != unit) {
            out.append(text, from, after)
        } else {
            val signed = text[from] == '+' || text[from] == '-'
            if (signed) out.append(text[from])
            out.append(convert(BigDecimal(text.substring(if (signed) from + 1 else from, number))))
        }
        return after
    }

    /** The index after the name at [from]; for a name that folds to `url` followed by `(`, after the url token's `)`. */
    private fun identEnd(
        text: String,
        from: Int,
    ): Int {
        val (name, after) = name(text, from)
        return if (fold(name) == "url" && text.startsWith("(", after)) urlEnd(text, after + 1) else after
    }

    /** The index after the CSS number at [from] (sign, digits, fraction, exponent), or [from] when none begins there. */
    private fun numberEnd(
        text: String,
        from: Int,
    ): Int {
        val start = signEnd(text, from)
        val whole = digitsEnd(text, start)
        val fraction = if (text.startsWith(".", whole)) digitsEnd(text, whole + 1) else whole
        val mantissa =
            when {
                fraction > whole + 1 -> fraction
                whole > start -> whole
                else -> return from
            }
        if (mantissa >= text.length || (text[mantissa] != 'e' && text[mantissa] != 'E')) return mantissa
        val digits = signEnd(text, mantissa + 1)
        val exponent = digitsEnd(text, digits)
        return if (exponent > digits) exponent else mantissa
    }

    /** The index after a `+` or `-` at [at], or [at] when none is there. */
    private fun signEnd(
        text: String,
        at: Int,
    ): Int = if (at < text.length && (text[at] == '+' || text[at] == '-')) at + 1 else at

    private fun digitsEnd(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i += 1
        return i
    }

    /** Whether an identifier starts at [at] (CSS Syntax "would start an ident sequence"); `-1` is a number, not a name. */
    private fun startsIdent(
        text: String,
        at: Int,
    ): Boolean {
        if (at >= text.length) return false
        val c = text[at]
        return when (c) {
            '-' -> at + 1 < text.length && (text[at + 1] == '-' || nameStart(text[at + 1]))
            else -> nameStart(c)
        }
    }

    private fun nameStart(c: Char): Boolean = isNameChar(c) && c != '-' && !c.isDigit()

    /** The index just after the `)` that closes a `url(` whose content starts at [from] (a string skipped). */
    private fun urlEnd(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i] != ')') {
            i =
                when (text[i]) {
                    '"', '\'' -> stringEnd(text, i) + 1
                    else -> i + 1
                }
        }
        return i + 1
    }

    /** Whether a CSS name (an identifier) starts at [at]: a letter, `_` or `-` (the gate lets no escape or non-ASCII through). */
    fun startsName(
        text: String,
        at: Int,
    ): Boolean = isNameChar(text[at]) && !text[at].isDigit()

    /** The CSS name starting at [from] (letters, digits, `-` and `_`) and the index just after it. */
    fun name(
        text: String,
        from: Int,
    ): Pair<String, Int> {
        var i = from
        while (i < text.length && isNameChar(text[i])) i += 1
        return text.substring(from, i) to i
    }

    /** The CSS name that is the whole of [text], or null when [text] is not exactly one name. */
    fun wholeName(text: String): String? =
        if (text.isNotEmpty() && startsName(text, 0)) name(text, 0).takeIf { it.second == text.length }?.first else null

    /** [text] with ASCII upper case letters lowered and nothing else changed: how CSS compares a name or a keyword. */
    fun fold(text: String): String = buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_'

    /** [text] with the content of every string removed (the quotes kept), for structural checks. */
    fun outsideStrings(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                i = stringEnd(text, i)
                out.append(c).append(c)
            } else {
                out.append(c)
            }
            i += 1
        }
        return out.toString()
    }

    private const val PRELUDE_SHOWN = 80
    private const val ESCAPED_CRLF = 3
    private const val LINE_BREAKS = "\n\r\u000C"
}
