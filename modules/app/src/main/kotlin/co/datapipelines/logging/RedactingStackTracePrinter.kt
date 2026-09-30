package co.datapipelines.logging

import org.springframework.boot.logging.StackTracePrinter
import org.springframework.boot.logging.StandardStackTracePrinter

/**
 * The structured format's stack trace, redacted and bounded (#337, observability.md §9.2 layer 2).
 *
 * Prints with Boot's own [StandardStackTracePrinter] (root first — the same order a driver's
 * exception chain reads on the console), scrubs the WHOLE rendered text through [LogRedactor] — a
 * driver's `SQLException` quoting a JDBC URL is the realistic leak the record names — and only
 * then truncates the scrubbed text to a fixed bound so one pathological chain cannot dominate the
 * log pipeline. The order is the contract: a bound applied first can sever a quoted value from its
 * closing quote. The bound lives here
 * rather than in `logging.structured.json.stacktrace.max-length` because the property configures
 * Boot's DEFAULT printer, which this class replaces; the truncation travels with the printer.
 */
class RedactingStackTracePrinter : StackTracePrinter {
    private val delegate = StandardStackTracePrinter.rootFirst()

    override fun printStackTrace(
        throwable: Throwable,
        appendable: Appendable,
    ) {
        // Redact the COMPLETE rendered text first, bound it second: truncating first cuts a quoted
        // secret's closing quote off, no pattern matches the remainder, and its visible prefix
        // survives (#337-b F3).
        val scrubbed = LogRedactor.scrubText(delegate.printStackTraceToString(throwable))
        appendable.append(
            if (scrubbed.length <= MAX_LENGTH) {
                scrubbed
            } else {
                scrubbed.take(MAX_LENGTH) + TRUNCATED_MARK
            },
        )
    }

    private companion object {
        /** Bounded per §3.1's line shape; a frame list past this is a defect of its own. */
        const val MAX_LENGTH = 8192

        const val TRUNCATED_MARK = "\n... (stack trace truncated)"
    }
}
