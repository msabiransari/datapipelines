package co.datapipelines.logging

/**
 * The text recognizer behind [LogRedactor.scrubText] (#337-c): ONE left-to-right pass over the
 * ORIGINAL text that finds the two shapes a secret takes inside free text — `key=value` and
 * `"key": "value"` — and masks the UNION of every span it finds.
 *
 * **Why one pass over the original, and why the union.** The delivered scrubber ran an assignment
 * regex over the text and then a JSON-pair regex over the result. The first pass rewrote text
 * INSIDE a later match's value and, in doing so, consumed an escape: `{"password":"x secret=abc\"tail"}`
 * became `{"password":"x secret=***"tail"}`, the second pass read the orphaned quote as the end of
 * the outer value, and `tail` survived (F5). Reversing the passes only moves the hole. Here every
 * candidate span is decided from the original characters and nothing is rewritten until the pass
 * is over. Spans that overlap — a secret-shaped fragment inside a value, or a nested match whose
 * own value runs PAST the end of the one holding it (`"secret": secret=  tail`) — merge into one
 * region, which is replaced once, by the replacement of the span that STARTED it. A nested match
 * can therefore neither end its container early nor leave its own tail behind.
 *
 * **Why the work is bounded.** A regex retries a failed suffix scan from every later key start
 * (`password_password_…z:` was quadratic, F6), and a scan that steps through a merged region would
 * retry every nested start. Every scan here is memoized by what it measured: all starts inside one
 * run of key characters share its end, all starts inside one bare token share its end, a quoted
 * value scanned from one quote is the same scan from any quote escaped inside it, and the
 * continuation after one identifier end is computed once. [work] counts one unit per character
 * stepped and per word compared; a test holds it to a fixed multiple of the input length, so the
 * bound is a counted fact, not a timing on someone's machine.
 *
 * Grammar (kept byte-compatible with the delivered patterns, which #337-b's plants pin):
 * - a *key* is one of [words] (ASCII case-insensitive) plus an optional `_`-introduced run of
 *   `[A-Za-z0-9_.-]`; text before the word (`db_`, `dbP`, `spring.datasource.`) is not part of it;
 * - an *assignment* is key, `[ \t]*=[ \t]*`, then a double-quoted value, a single-quoted value
 *   (escape pairs honoured, an unterminated quote ends at the line end) or a bare token ending
 *   at whitespace, a quote, `,` `;` `)` `]` `}`; it is replaced by `key=***`;
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
    /** Characters stepped so far: one per scan step. Linear in `text.length` by construction. */
    var work: Long = 0
        private set

    private val length = text.length
    private val firstLetters: Set<Char> = words.map { it.first() }.toSet()

    // One memo per kind of scan, each holding the last measured range and its shared end.
    private val keyRun = RunMemo()
    private val bareAssignment = RunMemo()
    private val bareJson = RunMemo()
    private val doubleQuoted = RunMemo()
    private val singleQuoted = RunMemo()

    // The continuation after the last identifier end tried: the end of its value, or -1.
    private var triedIdentifierEnd = -1
    private var triedValueEnd = -1

    /** A scan's last result: every `from` in `[start, end)` measures the same `end`. */
    private class RunMemo {
        var start = -1
        var end = -1
    }

    /**
     * A recognised span ending at [end]. Its replacement is the text from the span's start to
     * [keepEnd] followed by [suffix]; built only for the span that starts a region, so a thousand
     * nested starts cost nothing.
     */
    private class Span(
        val end: Int,
        val keepEnd: Int,
        val suffix: String,
    )

    /** [text] with every recognised secret value replaced; the same instance when nothing matched. */
    fun scrub(): String {
        if (!text.contains('=') && !text.contains(':')) return text
        val out = StringBuilder(length)
        var copied = 0
        var regionStart = -1
        var regionEnd = 0
        var regionFirst: Span? = null
        for (i in 0 until length) {
            work++
            val span = spanAt(i)
            if (span != null) {
                if (regionFirst == null) {
                    regionStart = i
                    regionEnd = span.end
                    regionFirst = span
                } else if (span.end > regionEnd) {
                    regionEnd = span.end
                }
            }
            if (regionFirst != null && i + 1 >= regionEnd) {
                out.append(text, copied, regionStart).append(text, regionStart, regionFirst.keepEnd).append(regionFirst.suffix)
                copied = regionEnd
                regionFirst = null
            }
        }
        if (copied == 0) return text
        return out.append(text, copied, length).toString()
    }

    private fun spanAt(i: Int): Span? =
        when {
            text[i] == '"' -> jsonPairAt(i)
            startsWord(text[i]) -> assignmentAt(i)
            else -> null
        }

    /** `key = value` at [start], where a key word begins, or null. */
    private fun assignmentAt(start: Int): Span? {
        val wordLength = wordLengthAt(start)
        if (wordLength == 0) return null
        var identifierEnd = start + wordLength
        if (identifierEnd < length && text[identifierEnd] == '_') identifierEnd = keyRunEnd(identifierEnd)
        if (identifierEnd != triedIdentifierEnd) {
            triedIdentifierEnd = identifierEnd
            triedValueEnd = valueAfterIdentifier(identifierEnd)
        }
        return if (triedValueEnd < 0) null else Span(triedValueEnd, identifierEnd, "=$mask")
    }

    /** The end of the value after an identifier ending at [identifierEnd] (`= value`), or -1. */
    private fun valueAfterIdentifier(identifierEnd: Int): Int {
        var k = skipBlanks(identifierEnd)
        if (k >= length || text[k] != '=') return -1
        k = skipBlanks(k + 1)
        return valueEnd(k, assignmentForm = true)
    }

    /** `"key": value` at [start], an opening quote, or null. */
    private fun jsonPairAt(start: Int): Span? {
        val close = jsonKeyClose(start)
        val valueStart = if (close < 0) -1 else jsonValueStart(close)
        val end = if (valueStart < 0) -1 else valueEnd(valueStart, assignmentForm = false)
        return if (end < 0) null else Span(end, valueStart, "\"$mask\"")
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
     * The end of the value starting at [from], or -1 when there is no value there: a double-quoted
     * string, a single-quoted one when [assignmentForm] (a JSON scalar never starts with a single
     * quote), else a bare token — which in the JSON form also stops at `[` and `{`, so an object or
     * array under a sensitive key is matched member by member on its own keys.
     */
    private fun valueEnd(
        from: Int,
        assignmentForm: Boolean,
    ): Int {
        if (from >= length) return -1
        val c = text[from]
        val end =
            when {
                c == '"' -> memoized(doubleQuoted, from, terminatorSlack = 1) { quotedEnd(from, '"') }
                assignmentForm && c == '\'' -> memoized(singleQuoted, from, terminatorSlack = 1) { quotedEnd(from, '\'') }
                assignmentForm -> memoized(bareAssignment, from) { bareEnd(from, BARE_ASSIGNMENT_STOPS) }
                else -> memoized(bareJson, from) { bareEnd(from, BARE_JSON_STOPS) }
            }
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

    /** The end of the maximal run of key characters that contains [from]: measured once per run. */
    private fun keyRunEnd(from: Int): Int =
        memoized(keyRun, from) {
            var k = from
            while (k < length && isKeyChar(text[k])) {
                k++
                work++
            }
            k
        }

    /**
     * The end [measure] finds for a scan starting at [from], reusing the memo's last measurement
     * when [from] falls inside its range: a run of key characters, a bare token, or a quoted value
     * in which [from] is an ESCAPED quote of the same kind (an unescaped one would have ended it),
     * all end where the earlier scan did. A quoted scan's last character may be its own closing
     * quote, which is NOT inside the value: a fresh scan from it starts a new value, so quoted
     * memos pass a [terminatorSlack] of 1 and the closing quote is measured again.
     */
    private inline fun memoized(
        memo: RunMemo,
        from: Int,
        terminatorSlack: Int = 0,
        measure: () -> Int,
    ): Int {
        if (from >= memo.start && from < memo.end - terminatorSlack) return memo.end
        val end = measure()
        memo.start = from
        memo.end = end
        return end
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

    /** The end of a bare token starting at [from]: stops at whitespace or any of [stops]. */
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

    private companion object {
        // `[^\s"',;)\]}]` and, for a JSON scalar, also `[` and `{`.
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
