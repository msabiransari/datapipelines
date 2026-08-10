package co.datapipelines.templates

import freemarker.core.ParseException
import freemarker.core.TokenMgrError
import freemarker.template.Configuration
import freemarker.template.Template as FreemarkerTemplate

/**
 * The outcome of parsing a template body once (templates.md §7.1).
 *
 * Save-time validation parses the body **exactly once** and hands the result to both the
 * syntax check and the forbidden-construct scan ([ForbiddenConstructScanner]), so the parser
 * and the scanner cannot disagree about what the body says — the property templates.md §4.2
 * makes normative.
 */
internal sealed interface BodyParse {
    /** The body parsed; [template] carries the AST the §4.2 scan walks. */
    data class Parsed(
        val template: FreemarkerTemplate,
    ) : BodyParse

    /** The body did not parse — `template.validation.syntax_error` (§7). */
    data class SyntaxError(
        val message: String,
        val line: Int,
        val column: Int,
    ) : BodyParse
}

/**
 * Parses a template body under the hardened §4.3 regime, bounded in every dimension.
 *
 * ## Why the bounds are part of the parse, not a caller's problem
 *
 * The body is untrusted input and the parse runs on the request thread at save time, so an
 * adversarial body is a denial-of-service vector unless the parse is bounded *before* it starts:
 *  - **Length** — `datapipelines.templates.max-body-chars` (configuration.md §3.9) is enforced by
 *    the caller ([TemplateValidator]) before this object is reached; nothing here is quadratic,
 *    so the remaining cost is linear in a bounded length.
 *  - **Nesting** — Freemarker's parser is recursive descent, so ~2000 nested parentheses overflow
 *    the JVM stack and throw [StackOverflowError] *through* `validate()` as a 500 rather than a
 *    catalog code. [MAX_NESTING_DEPTH] rejects that shape by a linear pre-scan.
 *  - **Parse cost** — the pre-scan counts brackets, and not every expensive shape has brackets: a
 *    bracket-free `${a+a+a…}` at `max-body-chars` cost **37.3 s** of uninterruptible save-thread
 *    CPU before overflowing. Running the parse on a small stack ([PARSE_STACK_BYTES]) bounds the
 *    cost of *every* such shape — known or not — without guessing which expressions are "too
 *    complex".
 *
 * All three bounds report `template.validation.syntax_error`: a body the parser cannot survive
 * is, from the author's point of view, exactly a body it cannot parse, and §7 names no other code.
 * They are deliberately layered rather than alternatives — the pre-scan is free and catches the
 * common shape, the stack bound catches everything else, and the [StackOverflowError] catch turns
 * whichever one fires into a verdict instead of a 500.
 */
internal object TemplateBodyParser {
    /**
     * Maximum bracket nesting accepted before the parse is attempted.
     *
     * Set far above anything a hand- or LLM-written SQL template reaches (real SQL rarely passes
     * ~20) and far below the ~2000 at which the parser's recursion overflows the stack, so the
     * bound rejects only bodies that were built to break the parser.
     */
    const val MAX_NESTING_DEPTH = 200

    /**
     * The parse-only configuration (§7.1). One shared instance: Freemarker's `Configuration` is
     * thread-safe, and `Template(name, source, cfg)` never consults a template loader.
     */
    private val PARSE_CONFIG: Configuration by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { FreemarkerConfigFactory.parseOnly() }

    /**
     * Stack given to the parse thread — the bound that makes an adversarial parse *fast to fail*
     * instead of slow to fail.
     *
     * Freemarker's parser recurses per operator, and the recursion is super-linear in cost: at
     * `max-body-chars`, a bracket-free `${a+a+a…}` (no forbidden built-in, no deep brackets, so
     * neither the length cap nor [MAX_NESTING_DEPTH] sees it) took **37.3 s** of uninterruptible
     * save-thread CPU on the default stack before overflowing — a save-path denial of service
     * that templates.md §12.3's "a body at max-body-chars must complete within a bounded time"
     * forbids. Parsing on a deliberately small stack reaches the same verdict in **2.7 s**.
     *
     * 512 KB is chosen to sit above what the pre-scan admits, not below it, so the two bounds
     * cannot contradict each other: measured against the pinned jar, a body at
     * [MAX_NESTING_DEPTH] - 1 nested parens parses fine here (it overflows at 256 KB), a
     * realistic 256 KB SQL body parses in ~53 ms, and the adversarial chains overflow in under
     * three seconds. A threshold on user content would have to guess which expressions are
     * "too complex"; this bounds the *cost* instead and needs no such guess.
     */
    const val PARSE_STACK_BYTES = 512L * 1024

    /**
     * Backstop for a parse that neither finishes nor overflows. Nothing observed reaches it —
     * every adversarial body measured either parses or overflows in single-digit seconds — but a
     * request thread must not be able to wait forever on a parse.
     */
    const val PARSE_JOIN_TIMEOUT_MS = 15_000L

    /**
     * Parses [body], never throwing — every failure becomes a [BodyParse.SyntaxError].
     *
     * The parse runs on a short-lived thread with a bounded stack ([PARSE_STACK_BYTES]); see that
     * constant for why. A thread per save-time parse is a negligible cost next to the database
     * round-trips a save already performs, and it is what keeps an adversarial body from holding
     * the request thread for half a minute.
     */
    fun parse(body: String): BodyParse {
        val depth = maxBracketDepth(body)
        if (depth > MAX_NESTING_DEPTH) {
            return BodyParse.SyntaxError(
                message = "Expression nesting depth $depth exceeds the limit of $MAX_NESTING_DEPTH.",
                line = 1,
                column = 1,
            )
        }
        var result: BodyParse? = null
        val worker = Thread(null, { result = parseHere(body) }, "template-parse", PARSE_STACK_BYTES)
        worker.isDaemon = true
        worker.start()
        worker.join(PARSE_JOIN_TIMEOUT_MS)
        return result ?: BodyParse.SyntaxError(
            message = "Freemarker parse error: the body did not parse within ${PARSE_JOIN_TIMEOUT_MS}ms.",
            line = 1,
            column = 1,
        )
    }

    private fun parseHere(body: String): BodyParse =
        try {
            // Parses the raw body only — imports are resolved by the loader at render, not here,
            // and a call into an as-yet-unbound namespace (`<@dates.date_range/>`) is a runtime
            // concern, so it does not fail the parse.
            BodyParse.Parsed(FreemarkerTemplate(PARSE_NAME, body, PARSE_CONFIG))
        } catch (e: ParseException) {
            BodyParse.SyntaxError(
                message = "Freemarker parse error: ${(e.editorMessage ?: e.message).truncateForError()}",
                line = e.lineNumber,
                column = e.columnNumber,
            )
        } catch (e: StackOverflowError) {
            // The parser is recursive descent; a body built to nest deeply overflows the stack and
            // the Error escapes `validate()` as a 500 instead of a catalog code (TPL-SEC-6).
            BodyParse.SyntaxError(
                message = "Freemarker parse error: the body nests too deeply to parse (${e.javaClass.simpleName}).",
                line = 1,
                column = 1,
            )
        } catch (e: TokenMgrError) {
            // Freemarker's lexer raises this for a malformed token. It is a syntax failure wearing
            // the wrong supertype (`Error`, not `Exception`), so it needs its own catch or an
            // author's typo surfaces as a 500. Nothing broader is caught here: a genuine VM
            // failure is not this template's verdict to give.
            BodyParse.SyntaxError(
                message = "Freemarker parse error: ${e.message.truncateForError()}",
                line = 1,
                column = 1,
            )
        }

    /**
     * The deepest run of unclosed `(`, `[` or `{` in [body] — one linear pass, no regex, so it
     * cannot itself become the quadratic cost it exists to prevent.
     */
    private fun maxBracketDepth(body: String): Int {
        var depth = 0
        var max = 0
        for (ch in body) {
            if (ch in OPENING_BRACKETS) {
                depth++
                if (depth > max) max = depth
            } else if (ch in CLOSING_BRACKETS && depth > 0) {
                depth--
            }
        }
        return max
    }

    /** The name a save-time parse reports in its errors — never a real registry key. */
    private const val PARSE_NAME = "(validate)"

    private const val OPENING_BRACKETS = "([{"
    private const val CLOSING_BRACKETS = ")]}"
}
