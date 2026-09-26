package co.datapipelines.typesystem

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The `pattern` constraint's two ReDoS defences (parameter-engine record §3.5, P31) — a static
 * scan at save and a read budget at match time — and its implicit anchoring.
 *
 * ## Why both
 *
 * `java.util.regex` backtracks, so a pattern an author writes can take exponential time on an
 * input a caller chooses. The static scan refuses the constructs that make a pattern's cost
 * unbounded by construction or unanalysable — backreferences, lookahead/lookbehind, possessive
 * quantifiers, atomic groups — plus the COMMENTS flag (`(?x)`), under which whitespace hides a
 * possessive `a+ +` from any scanner. It cannot refuse every catastrophic pattern: `(a+)+b` uses
 * none of those and still backtracks exponentially. So every match also runs over a
 * [ReadBudgetCharSequence] that throws once the engine has read the input more than the budget
 * allows — the bound holds whatever the pattern is, which the static scan alone never could. A
 * pattern whose recursion outruns the thread's stack before the budget (an alternation inside a
 * repetition, `(a|b)*`, after a few thousand characters) is refused the same way: [matches] catches
 * the overflow at the matcher and reports it as the budget, so no `Error` reaches a caller.
 *
 * ## Anchoring
 *
 * A pattern is implicitly `^…$`: the match is [java.util.regex.Matcher.matches], the whole input,
 * never a find. `[0-9]{4}` refuses `12345` rather than finding four digits inside it.
 */
internal object PatternGuard {
    /** Record §3.5: a pattern longer than this is refused at save. */
    const val MAX_PATTERN_LENGTH = 256

    /** A compiled, safe pattern, or the reason it is not one ([DeclarationProblem.reason] values). */
    sealed interface Compiled {
        data class Safe(
            val pattern: Pattern,
        ) : Compiled

        data class Refused(
            val reason: String,
            val message: String,
        ) : Compiled
    }

    fun compile(source: String): Compiled {
        if (source.length > MAX_PATTERN_LENGTH) {
            return Compiled.Refused("too_long", "pattern is ${source.length} characters; at most $MAX_PATTERN_LENGTH are allowed")
        }
        unsafeConstruct(source)?.let { construct ->
            return Compiled.Refused("unsafe_construct", "pattern uses $construct, which the regex budget cannot analyse")
        }
        return try {
            Compiled.Safe(Pattern.compile(source))
        } catch (e: PatternSyntaxException) {
            Compiled.Refused("syntax", "pattern does not compile: ${e.description.truncateForError()}")
        }
    }

    /** True when [text] matches the whole of [pattern] within [budget] reads; throws [BudgetExceeded] past it. */
    fun matches(
        pattern: Pattern,
        text: String,
        budget: Long,
    ): Boolean =
        try {
            pattern.matcher(ReadBudgetCharSequence(text, budget)).matches()
        } catch (overflow: StackOverflowError) {
            // An alternation inside a repetition (`(a|b)*`) recurses once per iteration in java.util.regex
            // and outruns the thread's stack thousands of reads before the budget (the 194a security pass,
            // finding 1). The frames have unwound and the matcher holds nothing: it is the budget refusal.
            throw BudgetExceeded(overflow)
        }

    /**
     * The first forbidden construct in [source], named, or null. Escapes (`\x`, `\Q…\E`) and
     * character classes are skipped the way the regex parser reads them, so `\+`, `[+]` and a
     * `\\1` (an escaped backslash, then a digit) are not mistaken for the constructs they resemble.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "ReturnCount")
    fun unsafeConstruct(source: String): String? {
        var i = 0
        var classDepth = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                val next = source.getOrNull(i + 1) ?: return null
                when {
                    next == 'Q' -> {
                        val end = source.indexOf("\\E", i + 2)
                        i = if (end < 0) source.length else end + 2
                        continue
                    }

                    next in '1'..'9' -> {
                        return "a backreference (\\$next)"
                    }

                    next == 'k' && source.getOrNull(i + 2) == '<' -> {
                        return "a named backreference (\\k<…>)"
                    }

                    // `\p{L}` / `\P{Alpha}`: the braces are the property's name, not a quantifier — read
                    // past the closing brace so a following `+` is the ordinary quantifier it is.
                    (next == 'p' || next == 'P') && source.getOrNull(i + 2) == '{' -> {
                        val end = source.indexOf('}', i + 2)
                        i = if (end < 0) source.length else end + 1
                        continue
                    }
                }
                i += 2
                continue
            }
            if (classDepth > 0) {
                if (c == '[') classDepth++
                if (c == ']') classDepth--
                i++
                continue
            }
            when {
                c == '[' -> classDepth = 1
                c == '(' && source.startsWith("(?", i) -> groupConstruct(source, i + 2)?.let { return it }
                c in POSSESSIVE_BASES && source.getOrNull(i + 1) == '+' -> return "a possessive quantifier ($c+)"
            }
            i++
        }
        return null
    }

    /** The construct a `(?` group opens at [from], when it is one the scan refuses. */
    private fun groupConstruct(
        source: String,
        from: Int,
    ): String? {
        val rest = source.substring(from)
        return when {
            rest.startsWith("=") || rest.startsWith("!") -> "a lookahead"

            rest.startsWith("<=") || rest.startsWith("<!") -> "a lookbehind"

            rest.startsWith(">") -> "an atomic group"

            INLINE_FLAGS
                .find(rest)
                ?.groupValues
                ?.get(1)
                ?.contains('x') == true -> "the COMMENTS flag (x)"

            else -> null
        }
    }

    /** The quantifier characters a trailing `+` turns possessive (`*+`, `++`, `?+`, `{n,m}+`). */
    private val POSSESSIVE_BASES = setOf('*', '+', '?', '}')

    /** `(?flags)` or `(?flags:` — the letters before `)`/`:`, including a `-` for turned-off ones. */
    private val INLINE_FLAGS = Regex("^([a-zA-Z-]+)[):]")

    /** Thrown past the budget; carries no stack — it is a verdict, not a defect. */
    class BudgetExceeded(
        cause: Throwable? = null,
    ) : RuntimeException("regex read budget exceeded", cause, false, false)
}

/**
 * The input a `pattern` runs over, counting every character the regex engine reads (P31). Past
 * [budget] reads it throws [PatternGuard.BudgetExceeded], so a catastrophically backtracking match
 * ends after a bounded amount of work however the pattern was written.
 *
 * Reads, not time: the count is deterministic — the same pattern and input always stop at the
 * same read, on a fast laptop and on a slow runner alike (MISTAKES: never synchronise on time).
 * `java.util.regex` reads its input only through [get] while matching; [subSequence] serves
 * group extraction after a match and is not counted.
 */
internal class ReadBudgetCharSequence(
    private val text: String,
    private val budget: Long,
) : CharSequence {
    /** Characters read so far — exposed so a test can place the budget exactly at the bound. */
    var reads: Long = 0
        private set

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        reads++
        if (reads > budget) throw PatternGuard.BudgetExceeded()
        return text[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text
}
