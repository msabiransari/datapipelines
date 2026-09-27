package co.datapipelines.parameters

/** Longest reflected author input in a refusal message or `details` value. */
internal const val MAX_REFLECTED_VALUE_LENGTH = 64

/** Longest reflected `path` — `parameters[63].source.constants[999].display_value` needs more than a value. */
internal const val MAX_REFLECTED_PATH_LENGTH = 160

/** Replacement for an ISO control character in reflected text. */
private const val CONTROL_REPLACEMENT = '�'

/**
 * Makes author input safe to echo into a refusal — the house's two carry-forwards, as
 * `typesystem` and `pipeline-contract` spell them (both `internal` there, so this module owns its
 * twin rather than widening either):
 *  - **CF-2, length** — unbounded reflection of author text is response bloat and log flooding;
 *    clipped at [maxLength] plus an ellipsis, BEFORE sanitising so the work is bounded by the cap.
 *  - **CF-1, control characters** — a newline or CR forges log records; every ISO control
 *    character becomes U+FFFD.
 */
internal fun String?.safeEcho(maxLength: Int = MAX_REFLECTED_VALUE_LENGTH): String {
    val raw = this ?: return "null"
    val clipped = if (raw.length <= maxLength) raw else raw.take(maxLength) + "…"
    return if (clipped.none { it.isISOControl() }) {
        clipped
    } else {
        clipped.map { if (it.isISOControl()) CONTROL_REPLACEMENT else it }.joinToString("")
    }
}
