package co.datapipelines.typesystem

/** Longest reflected raw input allowed in a rejection reason. */
internal const val MAX_REFLECTED_VALUE_LENGTH = 64

/** Replacement for an ISO control character in reflected text. */
private const val CONTROL_REPLACEMENT = '�'

/**
 * Makes a value that came from an inbound payload safe to echo into a rejection reason — the
 * text [ParameterCoercion] and `ParameterValueValidator` return, which every caller reports
 * verbatim in an error message and `details`.
 *
 * The same two carry-forwards as `pipeline-contract`'s helper of the same name, byte for byte,
 * because the coercion's reasons moved here unchanged (#194) and existing tests pin them:
 *  - **CF-2 — length.** Unbounded reflection of attacker-controlled text into error responses
 *    and logs is response bloat and log flooding; the value is clipped at
 *    [MAX_REFLECTED_VALUE_LENGTH] characters plus an ellipsis.
 *  - **CF-1 — control characters.** A newline or CR in a reflected value forges log records in
 *    a line-oriented log; every ISO control character becomes U+FFFD.
 *
 * Truncation happens **before** sanitising so the work is bounded by the cap, not by the
 * attacker's length. `internal`: `typesystem` exposes values, not a text utility.
 */
internal fun String?.truncateForError(): String {
    val raw = this ?: return "null"
    val clipped = if (raw.length <= MAX_REFLECTED_VALUE_LENGTH) raw else raw.take(MAX_REFLECTED_VALUE_LENGTH) + "…"
    return if (clipped.none { it.isISOControl() }) {
        clipped
    } else {
        clipped.map { if (it.isISOControl()) CONTROL_REPLACEMENT else it }.joinToString("")
    }
}
