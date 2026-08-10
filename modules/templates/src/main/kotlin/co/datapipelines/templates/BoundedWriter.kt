package co.datapipelines.templates

import java.io.Writer

/**
 * Signals that a render's accumulated output crossed the size cap (templates.md §4.3, §4.4).
 *
 * A distinct type so [TemplateEngine] can attribute the failure to the *output-size* guard
 * rather than the timeout guard when it names which guard tripped.
 */
internal class OutputSizeExceededException(
    val limitChars: Long,
) : RuntimeException("Template output exceeded the size cap of $limitChars characters.")

/**
 * A [Writer] that accumulates render output and aborts once it exceeds [limitChars].
 *
 * This is the second render guard of templates.md §4.3 (the first is the wall-clock timeout):
 * "a render whose accumulated output exceeds the staging batch memory budget is aborted."
 * Because the budget is owned by Staging §8 and this module does not depend on staging, the
 * cap is injected ([TemplateEngine]'s constructor) rather than read from a templates config
 * key — the mechanism lives here, the number is the caller's.
 *
 * The count is characters, a deterministic proxy for the byte budget that never *under*-counts
 * a UTF-8 body (one char is ≥ one byte). It is also the hard backstop that bounds a runaway
 * render's memory even if the worker thread ignores interruption.
 */
internal class BoundedWriter(
    private val delegate: StringBuilder,
    private val limitChars: Long,
) : Writer() {
    private var written: Long = 0

    override fun write(
        cbuf: CharArray,
        off: Int,
        len: Int,
    ) {
        written += len
        if (written > limitChars) throw OutputSizeExceededException(limitChars)
        delegate.append(cbuf, off, len)
    }

    override fun flush() = Unit

    override fun close() = Unit

    /** The output accumulated so far — read only after a successful render. */
    fun output(): String = delegate.toString()
}
