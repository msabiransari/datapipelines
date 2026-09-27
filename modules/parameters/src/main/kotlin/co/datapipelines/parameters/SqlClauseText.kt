package co.datapipelines.parameters

/**
 * A selector's SQL with its comments, string literals and quoted identifiers blanked — the text
 * P7's ORDER BY check reads ([SelectorDryRun]), so a clause inside a block comment, a `'…'` literal or
 * a `"…"` identifier never counts and a `--` inside a literal is never taken for a comment.
 *
 * ## One left-to-right pass, linear however the SQL was written
 *
 * Each construct is skipped from its opening to its end and the scan never restarts inside it, so
 * every character is read at most twice (once, plus once more as a one-character lookahead). The
 * first version stripped with three regexes, and the lazy block-comment regex over a body of
 * unterminated comment openings retries from every opening: measured quadratic — 5.8 s at 65,535 characters, about 90 s at the
 * 262,144-character template body cap — on a save anyone holding authoring could send. It also
 * stripped comments BEFORE literals, so `SELECT '--' AS x ORDER BY 1` lost its ORDER BY.
 *
 * ## Lexing, and what it may get wrong
 *
 * `--` to the end of the line; a slash-star block comment to its star-slash (not nested); `'…'` with `''` as the escape; `"…"` with
 * `""`. An unterminated construct runs to the end of the text. Each becomes ONE space, so the tokens
 * on either side are never glued together. Not modelled: Postgres's nested block comments and
 * dollar-quoted strings, MySQL's `#` comments and backslash escapes. Each can make a clause inside
 * them count (the softer direction) or hide text after them from the check — never more than the
 * three regexes did, and the runtime's row order stays the author's contract (P7).
 */
internal object SqlClauseText {
    /** [sql] with every comment, string literal and quoted identifier replaced by one space. */
    fun blanked(sql: CharSequence): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val end =
                when {
                    c == '-' && sql.has(i + 1, '-') -> endOfLine(sql, i + 2)
                    c == '/' && sql.has(i + 1, '*') -> endOfBlock(sql, i + 2)
                    c == '\'' || c == '"' -> endOfQuoted(sql, i + 1, c)
                    else -> NOT_SKIPPED
                }
            if (end == NOT_SKIPPED) {
                out.append(c)
                i++
            } else {
                out.append(' ')
                i = end
            }
        }
        return out.toString()
    }

    private const val NOT_SKIPPED = -1

    private fun CharSequence.has(
        index: Int,
        c: Char,
    ): Boolean = index < length && this[index] == c

    /** The index of the newline ending a `--` comment (the newline itself is kept), or the end. */
    private fun endOfLine(
        sql: CharSequence,
        from: Int,
    ): Int {
        var i = from
        while (i < sql.length && sql[i] != '\n') i++
        return i
    }

    /** The index just past the star-slash closing a block comment, or the end. */
    private fun endOfBlock(
        sql: CharSequence,
        from: Int,
    ): Int {
        var i = from
        while (i < sql.length) {
            if (sql[i] == '*' && sql.has(i + 1, '/')) return i + 2
            i++
        }
        return sql.length
    }

    /** The index just past the [quote] closing a literal or identifier (a doubled quote is its escape), or the end. */
    private fun endOfQuoted(
        sql: CharSequence,
        from: Int,
        quote: Char,
    ): Int {
        var i = from
        while (i < sql.length) {
            if (sql[i] == quote) {
                if (!sql.has(i + 1, quote)) return i + 1
                i += 2
            } else {
                i++
            }
        }
        return sql.length
    }
}
