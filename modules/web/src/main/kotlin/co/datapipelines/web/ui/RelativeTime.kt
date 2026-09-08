package co.datapipelines.web.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * "in 84 days" / "3 hours ago" — the coarse, human half of a timestamp (091, ui-screens §4.18).
 *
 * The keys table shows a RELATIVE age with the absolute instant on hover, because both questions
 * are real: "is this key about to expire?" is answered by the relative form at a glance, and
 * "exactly when?" by the tooltip. Rendered on the SERVER rather than by a script, so the value
 * is in the HTML a test can read and a screenshot can show, and so it cannot disagree with the
 * row it sits in when htmx swaps half the table.
 *
 * Deliberately coarse: one unit, no "1 month 3 days". A key's expiry is an operational fact, not
 * a countdown, and the absolute value is one hover away.
 */
object RelativeTime {
    /** The absolute form the tooltip shows: minute precision, explicitly UTC. */
    private val ABSOLUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /** `2026-12-01 00:00 UTC` — never a local rendering: the server's zone is nobody's. */
    fun absolute(instant: Instant): String = ABSOLUTE.format(instant)

    /**
     * A past instant as an age: `just now`, `7 minutes ago`, `3 hours ago`, `12 days ago`.
     * A FUTURE instant reads as `just now` rather than a negative age — the only way to get one
     * is a clock skew between two instances, and "in -3 minutes" would be a worse answer.
     */
    fun since(
        instant: Instant,
        now: Instant,
    ): String {
        val elapsed = Duration.between(instant, now)
        if (elapsed.isNegative || elapsed.toMinutes() < 1) return "just now"
        return "${coarse(elapsed)} ago"
    }

    /**
     * A future instant as a horizon: `in 5 minutes`, `in 3 hours`, `in 84 days`. A PAST instant
     * reads `expired` — an expiry in the past is the fact that matters, not how long ago.
     */
    fun until(
        instant: Instant,
        now: Instant,
    ): String {
        val remaining = Duration.between(now, instant)
        if (remaining.isNegative || remaining.isZero) return "expired"
        if (remaining.toMinutes() < 1) return "in under a minute"
        return "in ${coarse(remaining)}"
    }

    /** The largest whole unit that fits, singular-aware. */
    private fun coarse(duration: Duration): String =
        when {
            duration.toDays() > 0 -> plural(duration.toDays(), "day")
            duration.toHours() > 0 -> plural(duration.toHours(), "hour")
            else -> plural(duration.toMinutes(), "minute")
        }

    private fun plural(
        count: Long,
        unit: String,
    ): String = if (count == 1L) "1 $unit" else "$count ${unit}s"
}
