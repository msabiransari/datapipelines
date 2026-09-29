package co.datapipelines.scripting

/**
 * The compile-time nesting ceiling (#314): refuses a body that nests past
 * [CEILING] bracket levels BEFORE the library parses it.
 *
 * The library's parser recurses per nesting level (`processAST`), so a deeply
 * nested body can overflow the COMPILING thread's stack — a `StackOverflowError`,
 * an Error, not a catalogued refusal — before the engine's evaluate-time depth
 * count (`maxDepth`) is ever reached. Measured 2026-09-29: 500 nested array
 * constructors overflowed a 256 KB stack inside `Jsonata.jsonata`, the recorded
 * frames dominated by the library's parser (and 297's conformance run saw the
 * same shape once on a default stack under load — #314's 1-in-13 red, load
 * dependent because the JIT decides frame sizes).
 *
 * The scan is one linear pass with O(1) stack: no recursion, no library call.
 * It is a BOUND, not a parser. Strings, comments and regex literals are skipped
 * so their brackets never count (a false refusal would be worse than a late
 * one); where a `/` could be division or a regex start, the value-position
 * heuristic below decides, and when it guesses "division" it UNDER-counts —
 * a body crafted to hide nesting that way still cannot surface an Error,
 * because the engine's StackOverflowError boundary guards turn whatever
 * overflows into the same catalogued DEPTH refusal.
 *
 * [CEILING] is a stack-safety constant of the JVM and this library version,
 * not a workload knob, so it is not configurable: it sits well below the depth
 * the observed overflow needed (500) and below the evaluate-time default
 * (`ScriptEngine.DEFAULT_MAX_DEPTH` = 100) — the two bounds compose, they do
 * not compete: compile bounds STATIC nesting (parser stack safety), evaluate
 * bounds RUNTIME depth (steps and non-tail lambda recursion, which no static
 * scan can see).
 */
internal object JsonataNestingScan {
    /** The highest static bracket nesting a body may carry and still parse. */
    const val CEILING = 64

    /** True when [body] nests past [CEILING] — the engine refuses it at compile. */
    fun exceeds(body: String): Boolean {
        val depth = intArrayOf(0)
        // The last significant (non-whitespace) character — a regex literal may
        // begin only where a VALUE can, so `a / b` is division but `(a) /re/`
        // after an operator is a literal. Start of input is a value position.
        var prev = ' '
        var i = 0
        while (i < body.length) {
            val c = body[i]
            val span = spanEnd(body, i, c, prev)
            if (span != null) {
                i = span.first
                // A string or regex literal ENDS a value: whatever follows is an
                // operator or division. A comment is whitespace — it changes
                // nothing (span.second is the don't-touch marker then).
                if (span.second != ' ') prev = span.second
            } else {
                if (countBracket(depth, c)) return true
                if (!c.isWhitespace()) prev = c
            }
            i++
        }
        return false
    }

    /**
     * The index of the last character of the SPAN starting at [i] — a string
     * literal, a comment or a regex literal — whose brackets never count, paired
     * with the `prev` the span leaves (`' '` = leave unchanged: a comment), or
     * null when [i] is an ordinary character.
     */
    private fun spanEnd(
        body: String,
        i: Int,
        c: Char,
        prev: Char,
    ): Pair<Int, Char>? =
        when {
            c == '\'' || c == '"' -> Pair(skipString(body, i, c), c)
            c == '/' && body.startsWith("//", i) -> Pair(skipLineComment(body, i), ' ')
            c == '/' && body.startsWith("/*", i) -> Pair(skipBlockComment(body, i), ' ')
            c == '/' && valuePosition(prev) -> Pair(skipRegex(body, i), '/')
            else -> null
        }

    /**
     * Counts one ordinary character's bracket on [depth] (openers +1, closers -1,
     * never below zero — an unbalanced body is the parser's syntax error, not this
     * scan's). True when an opener pushed the nesting past [CEILING].
     */
    private fun countBracket(
        depth: IntArray,
        c: Char,
    ): Boolean {
        when (c) {
            '(', '[', '{' -> depth[0]++
            ')', ']', '}' -> if (depth[0] > 0) depth[0]--
            else -> return false
        }
        return depth[0] > CEILING
    }

    /** The refusal sentence every surface shows — one wording for the compile refusal. */
    fun refusal(): String =
        "the expression nests deeper than $CEILING bracket levels — refused before parsing: " +
            "the library's parser recurses per level and a deeper body can overflow the " +
            "compiling thread's stack"

    /** A `/` after one of these begins a regex literal; after a value's end it is division. */
    private fun valuePosition(prev: Char): Boolean =
        when (prev) {
            ' ', '(', '[', '{', ',', ':', ';', '=', '!', '&', '|', '?', '+', '-', '*', '%', '>', '<' -> true
            else -> false
        }

    /** The index of [quote]'s closing occurrence, honouring `\` escapes; EOF when unterminated. */
    private fun skipString(
        body: String,
        start: Int,
        quote: Char,
    ): Int {
        var i = start + 1
        while (i < body.length) {
            when (body[i]) {
                '\\' -> i++
                quote -> return i
            }
            i++
        }
        return body.length - 1
    }

    /** The index of a line comment's newline; EOF when unterminated. */
    private fun skipLineComment(
        body: String,
        start: Int,
    ): Int {
        val nl = body.indexOf('\n', start)
        return if (nl < 0) body.length - 1 else nl
    }

    /** The index of a block comment's closing `/`; EOF when unterminated. */
    private fun skipBlockComment(
        body: String,
        start: Int,
    ): Int {
        val end = body.indexOf("*/", start)
        return if (end < 0) body.length - 1 else end + 1
    }

    /**
     * The index of a regex literal's closing unescaped `/`. A newline ends the
     * guess (a regex literal cannot span lines) — the scan then under-counts
     * whatever follows, which the boundary guards cover.
     */
    private fun skipRegex(
        body: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < body.length) {
            when (body[i]) {
                '\\' -> i++
                '/' -> return i
                '\n' -> return i
            }
            i++
        }
        return body.length - 1
    }
}
