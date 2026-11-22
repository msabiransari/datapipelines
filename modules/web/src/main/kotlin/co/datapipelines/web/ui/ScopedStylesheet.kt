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
 *   so it is refused; one of the three anywhere else is refused; every other selector becomes `& ` and itself;
 * - a rule whose selectors are only `html` and `body`: each declaration is carried onto the container ([CARRIED]),
 *   dropped ([DROPPED]), or refused naming the rule;
 * - every length in `rem` inside a declaration or a `@keyframes` becomes `px` (× 16, at most four decimals): `rem` is
 *   the HOST's root font size. A group rule's condition keeps its `rem` (there it is the browser's default size).
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

    /** A source this transform refuses to scope; the message names the rule or the at-rule. */
    class Refusal(
        message: String,
    ) : IllegalArgumentException(message)

    /** [source] scoped under [scope] (D2, D3). */
    fun scope(
        source: String,
        scope: String,
    ): Scoped {
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

        private fun atRule(
            statement: CssText.Statement,
            top: Boolean,
            inner: MutableList<String>,
            lifted: MutableList<String>,
        ) {
            val prelude = statement.prelude
            val name = AT_NAME.find(prelude)?.groupValues?.get(1)?.lowercase() ?: throw Refusal("an at-rule without a name: $prelude")
            REFUSED[name]?.let { throw Refusal("the at-rule @$name is refused ($it): ${prelude.take(PRELUDE_SHOWN)}") }
            val body = statement.body
            when {
                name !in HOISTED && name !in GROUPS -> throw Refusal("an at-rule this transform does not know: ${prelude.take(PRELUDE_SHOWN)}")
                body == null -> throw Refusal("an at-rule without a block: ${prelude.take(PRELUDE_SHOWN)}")
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
            val declarations = if (list.any { it.lowercase() in PAGE_ELEMENTS }) pageDeclarations(prelude, list, body) else body
            return list.joinToString(", ", transform = ::selector) + " {" + px(declarations) + "}"
        }

        /** A rule on `html`/`body`: [CARRIED] declarations kept, [DROPPED] ones left out, any other refused. */
        private fun pageDeclarations(
            prelude: String,
            list: List<String>,
            body: String,
        ): String {
            if (list.any { it.lowercase() !in PAGE_ELEMENTS }) throw Refusal("html or body listed beside another selector: $prelude")
            val kept = mutableListOf<String>()
            for (declaration in CssText.splitTopLevel(body, ';').map(String::trim).filter(String::isNotEmpty)) {
                val property = declaration.substringBefore(':').trim().lowercase()
                when (property) {
                    in DROPPED -> dropped += 1
                    in CARRIED -> {
                        carried += 1
                        kept += declaration
                    }
                    else -> throw Refusal("a declaration on the page element that is neither carried nor dropped: $prelude { $declaration }")
                }
            }
            return kept.joinToString("") { " $it;" } + " "
        }

        private fun selector(source: String): String {
            selectors += 1
            if (source.lowercase() in ROOT_FORMS) {
                rootExact += 1
                return "&"
            }
            val root = ROOT_START.find(source)
            if (root != null) {
                val rest = source.substring(root.range.last + 1)
                if (ROOTISH.containsMatchIn(rest)) throw Refusal("a root selector this transform does not know: $source")
                val combinator = firstCombinator(rest)
                if (combinator in SIBLING_COMBINATORS) {
                    throw Refusal("a root selector that selects a sibling of the container (combinator $combinator): $source")
                }
                rootBeginning.merge(combinator ?: "none", 1, Int::plus)
                return "&:is(&)$rest"
            }
            if (ROOTISH.containsMatchIn(source)) throw Refusal("a root selector this transform does not know: $source")
            return "& $source"
        }

        /** [css] with every `rem` length outside a condition as `px` (D3). */
        private fun px(css: String): String =
            REM.replace(css) { match ->
                remLengths += 1
                val value = BigDecimal(match.groupValues[2]).multiply(ROOT_PX).setScale(PX_DECIMALS, RoundingMode.HALF_UP)
                match.groupValues[1] + value.stripTrailingZeros().toPlainString() + "px"
            }
    }

    /**
     * The combinator that follows the root's own compound in [rest] (what is written onto the root without a space):
     * null when nothing follows, `descendant`, `child`, or the sibling forms `+`, `~`, `||`. Brackets and parentheses
     * are skipped whole, so `[data-a~="b"]` and `:has(+ .x)` are part of the compound.
     */
    internal fun firstCombinator(rest: String): String? {
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            if (c == '[' || c == '(') {
                i = CssText.closing(rest, i)
                continue
            }
            if (c.isWhitespace() || c in ">+~|") break
            i += 1
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

    private const val PRELUDE_SHOWN = 80
    private const val PX_DECIMALS = 4
    private val ROOT_PX = BigDecimal(16)
    private val PAGE_ELEMENTS = setOf("html", "body")
    private val ROOT_FORMS = setOf(":root", "html", "body")
    private val SIBLING_COMBINATORS = setOf("+", "~", "||")
    private val AT_NAME = Regex("""^@([-\w]+)""")
    private val ROOT_START = Regex("""^(?::root|(?i:html|body))(?![\w-])""")
    private val ROOTISH = Regex("""(?i):root(?![\w-])|(?<![\w.#\-:\[="'(])(?:html|body)(?![\w-])""")

    /** A length in `rem`: not part of a name (`x-1rem`), its sign kept. */
    private val REM = Regex("""(?<![\w.#-])(-?)(\d*\.?\d+)rem(?![\w-])""")
}

/** The small CSS reader the scoping needs: comments, top-level statements, lists split outside brackets and strings. */
internal object CssText {
    /** One top-level statement: its prelude, and its block's content (null for a statement ended by `;`). */
    data class Statement(
        val prelude: String,
        val body: String?,
    )

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
                else -> out.append(c)
            }
            i += 1
        }
        if (quote != null) throw ScopedStylesheet.Refusal("an unterminated string")
        return out.toString()
    }

    /** The top-level statements of a rule list (comments already removed). */
    fun statements(css: String): List<Statement> {
        val out = mutableListOf<Statement>()
        var i = 0
        while (i < css.length) {
            while (i < css.length && css[i].isWhitespace()) i += 1
            if (i >= css.length) break
            val start = i
            i = preludeEnd(css, i)
            val prelude = css.substring(start, i).trim()
            if (css[i] == ';') {
                out += Statement(prelude, null)
                i += 1
                continue
            }
            val end = blockEnd(css, i, prelude)
            out += Statement(prelude, css.substring(i + 1, end))
            i = end + 1
        }
        return out
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
                '"', '\'' -> i = stringEnd(css, i)
                '{' -> depth += 1
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
    private fun stringEnd(
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
                c == '"' || c == '\'' -> i = stringEnd(text, i)
                c == text[open] -> depth += 1
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
                c == '"' || c == '\'' -> i = stringEnd(text, i)
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
}
