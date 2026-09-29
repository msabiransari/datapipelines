package co.datapipelines.visualization

/** Longest reflected author input in a refusal message or `details` value. */
internal const val MAX_REFLECTED_VALUE_LENGTH = 64

/** Longest reflected `path` — `tests.cases[19].fixtures.revenue[999].amount` needs more than a value. */
internal const val MAX_REFLECTED_PATH_LENGTH = 160

/** Replacement for an ISO control character in reflected text. */
private const val CONTROL_REPLACEMENT = '�'

/**
 * Makes author input safe to echo into a refusal — the house's two carry-forwards, as `parameters`'
 * `ReflectedText` spells them (`internal` there, so this module owns its twin rather than widening it):
 *  - **CF-2, length** — clipped at [maxLength] plus an ellipsis, BEFORE sanitising so the work is bounded.
 *  - **CF-1, control characters** — a newline or CR forges log records; every ISO control character
 *    becomes U+FFFD.
 *
 * A refusal echoes a KEY or a PATH, never a value from the body (the spec's §3, the security brief): the
 * values this module holds — a renderer configuration, a fixture row, a literal — are the author's data.
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
