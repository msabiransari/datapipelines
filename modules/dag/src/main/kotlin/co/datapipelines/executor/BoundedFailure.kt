package co.datapipelines.executor

import co.datapipelines.typesystem.body.StructuredBodyDiagnostics

/**
 * The failure record of an execution that carries a structured request body (#1038; published-
 * mutation requirement §4 and §6): a bounded code and metadata, and nothing a caller or author
 * typed.
 *
 * Why a body execution needs its own form. The record an execution normally reports carries the
 * engine's or driver's own message (a unique-key violation quotes the offending key, a script
 * engine quotes the expression and value it choked on), the details the raising module chose (a
 * strict rejection lists the first reasons the transformer wrote, an invariant its author's
 * message), the rendered SQL and, under `error-detail=full`, the exception chain. Each of those
 * can quote the request body, a value the transformer extracted from it, or free text an author
 * wrote. The record is persisted (`execution_events`, `error_json`, `node_stats_json`), replayed
 * and streamed, and a stored error can be served to a caller later, so the text must be removed
 * where the record is made, before any of those see it — not filtered at each projection.
 *
 * What stays: the catalogued [MappedError.code]; the failing node's authored context
 * ([MappedError.node]: node id, type, datasource name, template id and version); and the details
 * in [SAFE_DETAILS] — node id, phase, counts and the bounded rule word. The message is the code.
 * Everything else is dropped by default: a new detail key is hidden until it is added here.
 */
internal object BoundedFailure {
    /**
     * The detail keys a bounded record keeps — the one list the authored test runner applies too
     * ([StructuredBodyDiagnostics.SAFE_DETAILS]): identifiers the pipeline author chose and counts
     * the platform computed. `reason` is the decoder's bounded wire word, `rule` a fixed
     * contract-rule word; neither is ever a supplied value. Deliberately absent: `first_reasons`,
     * `invariant`, `message`, `column`, `value`, `sql` and anything not named there.
     */
    val SAFE_DETAILS: Set<String> get() = StructuredBodyDiagnostics.SAFE_DETAILS

    /** [error] reduced to the bounded form. Idempotent. */
    fun of(error: MappedError): MappedError =
        MappedError(
            code = error.code,
            message = error.code,
            details = StructuredBodyDiagnostics.bounded(error.details),
            node = error.node,
            sql = null,
            exception = null,
        )

    /**
     * The cause a bounded failure is thrown with: carries the code and no text of the original
     * failure, so a log line or a wrapping exception's message cannot quote it. No stack trace.
     */
    class Cause(
        code: String,
    ) : RuntimeException(code, null, false, false)
}
