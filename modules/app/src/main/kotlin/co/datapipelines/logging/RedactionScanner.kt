package co.datapipelines.logging

/**
 * The text recognizer behind [LogRedactor.scrubText] (#337-c): ONE left-to-right pass over the
 * ORIGINAL text that finds the two shapes a secret takes inside free text and masks each whole
 * value — `key=value` and `"key": "value"`.
 *
 * **Why one pass, and why not regexes.** The delivered scrubber ran an assignment regex over the
 * text and then a JSON-pair regex over the result. The first pass rewrote text INSIDE a later
 * match's value and, in doing so, consumed an escape: `{"password":"x secret=abc\"tail"}` became
 * `{"password":"x secret=***"tail"}`, the second pass read the orphaned quote as the end of the
 * outer value, and `tail` survived (F5). Reversing the passes only moves the hole. Here a match is
 * decided from the original characters and its extent is final: the earliest recognizable
 * sensitive key wins, its value is consumed WHOLE — escape pairs, nested quotes, anything that
 * looks like another secret — and scanning resumes after it. A nested secret cannot change where
 * its container ends because nothing is rewritten until the pass is over.
 *
 * **Why the work is bounded.** A regex retries a failed suffix scan from every later key start
 * (`password_password_…z:` was quadratic, F6). This scanner's failed attempts are memoized by what
 * they scanned: every start inside one run of key characters shares the same run end, so the run
 * is measured once and a repeat of the same failing tail is refused in O(1). Every other loop
 * consumes characters that the main loop then skips. [work] counts one unit per character
 * inspected and per word compared; a test holds it to a fixed multiple of the input length, so
 * the bound is a counted fact, not a timing on someone's machine.
 *
 * Grammar (kept byte-compatible with the delivered patterns, which #337-b's plants pin):
 * - a *key* is one of [words] (ASCII case-insensitive) plus an optional `_`-introduced run of
 *   `[A-Za-z0-9_.-]`; text before the word (`db_`, `dbP`, `spring.datasource.`) is not part of it;
 * - an *assignment* is key, `[ \t]*=[ \t]*`, then a double-quoted value, a single-quoted value
 *   (escape pairs honoured, an unterminated quote ends at the line end) or a bare token ending
 *   at whitespace, a quote, `,` `;` `)` `]` `}`; it is rewritten to `key=***`;
 * - a *JSON pair* is `"`, any run of key characters ending in a key, `"`, `[ \t]*:[ \t]*`, then a
 *   double-quoted value or a bare scalar (never starting an object or array); everything up to
 *   the value is kept and the value becomes `"***"`.
 *
 * @param words the sensitive keys, lower-case ASCII
 */
internal class RedactionScanner(
    private val text: String,
    private val words: List<String>,
    private val mask: String,
) {
    /** Characters inspected so far: one per scan step. Linear in `text.length` by construction. */
    var work: Long = 0
        private set

    // Start and end of the last run of key characters measured for a suffix, and the identifier
    // end of the last failed assignment attempt: the two memos that keep failed matches linear.
    private var runStart = -1
    private var runEnd = -1
    private var failedIdentifierEnd = -1

    private val length = text.length

    /** [text] with every recognised secret value replaced; the same instance when nothing matched. */
    fun scrub(): String {
        if (!text.contains('=') && !text.contains(':')) return text
        val out = StringBuilder(length)
        var copied = 0
        var i = 0
        while (i < length) {
            work++
            val match =
                when {
                    text[i] == '"' -> jsonPairAt(i)
                    startsWord(text[i]) -> assignmentAt(i)
                    else -> null
                }
            if (match == null) {
                i++
            } else {
                out.append(text, copied, i).append(match.replacement)
                copied = match.end
                i = match.end
            }
        }
        if (copied == 0) return text
        return out.append(text, copied, length).toString()
    }

    private class Match(
        val end: Int,
        val replacement: String,
    )

    /** `key = value` at [start], where a key word begins, or null. */
    private fun assignmentAt(start: Int): Match? {
        val wordLength = wordLengthAt(start)
        if (wordLength == 0) return null
        var identifierEnd = start + wordLength
        if (identifierEnd < length && text[identifierEnd] == '_') identifierEnd = keyRunEnd(identifierEnd)
        if (identifierEnd == failedIdentifierEnd) return null
        val end = assignedValueEnd(identifierEnd)
        if (end < 0) {
            failedIdentifierEnd = identifierEnd
            return null
        }
        return Match(end, text.substring(start, identifierEnd) + "=" + mask)
    }

    /** The end of the value after an identifier ending at [identifierEnd] (`= value`), or -1. */
    private fun assignedValueEnd(identifierEnd: Int): Int {
        var k = skipBlanks(identifierEnd)
        if (k >= length || text[k] != '=') return -1
        k = skipBlanks(k + 1)
        return valueEnd(k, BARE_ASSIGNMENT_STOPS, singleQuotes = true)
    }

    /** `"key": value` at [start], an opening quote, or null. */
    private fun jsonPairAt(start: Int): Match? {
        val close = jsonKeyClose(start)
        val valueStart = if (close < 0) -1 else jsonValueStart(close)
        val end = if (valueStart < 0) -1 else valueEnd(valueStart, BARE_JSON_STOPS, singleQuotes = false)
        return if (end < 0) null else Match(end, text.substring(start, valueStart) + "\"" + mask + "\"")
    }

    /** The index of the quote closing a sensitive JSON key opened at [open], or -1. */
    private fun jsonKeyClose(open: Int): Int {
        var close = open + 1
        while (close < length && isKeyChar(text[close])) {
            close++
            work++
        }
        return if (close < length && text[close] == '"' && endsInKey(open + 1, close)) close else -1
    }

    /** Where the value starts after a key closed at [close] (`"  :  `), or -1 when there is no colon. */
    private fun jsonValueStart(close: Int): Int {
        val colon = skipBlanks(close + 1)
        return if (colon < length && text[colon] == ':') skipBlanks(colon + 1) else -1
    }

    /**
     * The end of the value starting at [from]: a double-quoted string (or single-quoted, when
     * [singleQuotes] — the assignment form only; a JSON scalar never starts with one), else a bare
     * token stopping at whitespace or [bareStops]; -1 when there is no value there.
     */
    private fun valueEnd(
        from: Int,
        bareStops: String,
        singleQuotes: Boolean,
    ): Int {
        if (from >= length) return -1
        val c = text[from]
        val quoted = c == '"' || (singleQuotes && c == '\'')
        val end = if (quoted) quotedEnd(from, c) else bareEnd(from, bareStops)
        return if (end == from) -1 else end
    }

    /** True when some key word starts in `[from, to)` and its optional suffix reaches exactly [to]. */
    private fun endsInKey(
        from: Int,
        to: Int,
    ): Boolean {
        for (p in from until to) {
            work++
            if (!startsWord(text[p])) continue
            val wordLength = wordLengthAt(p)
            if (wordLength > 0 && (p + wordLength == to || text[p + wordLength] == '_')) return true
        }
        return false
    }

    /**
     * The end of the maximal run of key characters that contains [from]. Every start inside one
     * run shares it, so after the first measurement a later start in the same run is O(1).
     */
    private fun keyRunEnd(from: Int): Int {
        if (from in runStart until runEnd) return runEnd
        var k = from
        while (k < length && isKeyChar(text[k])) {
            k++
            work++
        }
        runStart = from
        runEnd = k
        return k
    }

    /**
     * The index just past a quoted value opening at [open]: escape pairs are consumed whole, the
     * closing [quote] is consumed if present, and an unterminated value stops at the line end —
     * a stray quote masks its own line and never the rest of a trace.
     */
    private fun quotedEnd(
        open: Int,
        quote: Char,
    ): Int {
        var k = open + 1
        while (k < length) {
            work++
            val c = text[k]
            when {
                c == quote -> return k + 1
                c == '\r' || c == '\n' -> return k
                c == '\\' -> k += if (k + 1 < length && text[k + 1] != '\r' && text[k + 1] != '\n') 2 else 1
                else -> k++
            }
        }
        return length
    }

    /** The end of a bare token starting at [from]: stops at whitespace, a quote or any of [stops]. */
    private fun bareEnd(
        from: Int,
        stops: String,
    ): Int {
        var k = from
        while (k < length && !isBareStop(text[k], stops)) {
            k++
            work++
        }
        return k
    }

    private fun skipBlanks(from: Int): Int {
        var k = from
        while (k < length && (text[k] == ' ' || text[k] == '\t')) {
            k++
            work++
        }
        return k
    }

    /** Length of the key word starting at [at] (ASCII case-insensitive), or 0. */
    private fun wordLengthAt(at: Int): Int {
        for (word in words) {
            work++
            if (at + word.length > length) continue
            var matches = true
            for (offset in word.indices) {
                if (foldAscii(text[at + offset]) != word[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return word.length
        }
        return 0
    }

    private fun startsWord(c: Char): Boolean = foldAscii(c) in firstLetters

    private val firstLetters: Set<Char> = words.map { it.first() }.toSet()

    private companion object {
        // `[^\s"',;)\]}]` and, for a JSON scalar, also `[` and `{` (an object or array under a
        // sensitive key is matched member by member on its own keys).
        const val BARE_ASSIGNMENT_STOPS = "\",;)]}'"
        const val BARE_JSON_STOPS = "\",;)]}'[{"

        fun isKeyChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-'

        /** Java's `\s`: space, tab, LF, VT, FF, CR. */
        fun isJavaSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

        fun isBareStop(
            c: Char,
            stops: String,
        ): Boolean = isJavaSpace(c) || stops.indexOf(c) >= 0

        /** ASCII-only lower-casing: the delivered patterns were `(?i)` without Unicode case folding. */
        fun foldAscii(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c
    }
}
