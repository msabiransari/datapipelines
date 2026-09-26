package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **The ONE occurrence function** (scheduler design revision §3.2, R1). The dispatcher and every
 * preview call it; nothing else in the system computes when a schedule fires.
 *
 * ## Why not the library's `getNextExecutionTime`
 * db-scheduler's `CronSchedule` anchors the next time on a task's COMPLETION and applies
 * cron-utils' zone handling, measured on 16.12.0 (record §9.3, A9): `30 2 * * *` in
 * America/New_York has no run at all on the spring-forward day, and the every-30-minutes pattern `0,30 1 * * *` runs four times
 * on the fall-back morning while `30 1 * * *` runs once. The owner ruled for OUR rule instead (R1).
 *
 * ## How it works
 * 1. **Parse** with the library's own `CronSchedule` in `CronStyle.UNIX` — the field grammar the
 *    record names, and a six-field pattern is refused by the parser for free. The library's
 *    disabled pattern `-` and anything that is not exactly five fields are refused first.
 * 2. **Match on the local clock**: the parsed pattern is asked for its next match in a fixed UTC
 *    frame, which is the next matching LOCAL date-time with no zone rules applied.
 * 3. **Convert** by our DST rule ([toInstant]): a local time in a GAP runs at the transition
 *    instant (the first valid instant after the gap); a local time in an OVERLAP runs once, at
 *    its first pass (the earlier offset). Several local times that land on one instant are one
 *    occurrence.
 *
 * The conversion is monotonic (a later local time never maps to an earlier instant), so the next
 * occurrence strictly after an instant `t` is the first local match strictly after `t`'s own wall
 * time whose mapped instant is after `t` — see [next]'s loop, which is where the second pass of a
 * fold is skipped.
 */
object OccurrenceFunction {
    /** How far the minimum-spacing guard looks ahead (record §3, L4): every weekday twice. */
    val MIN_SPACING_HORIZON: Duration = Duration.ofDays(14)

    /** Local matches the spacing guard examines at most — 14 days of a pattern that passes a 60 s floor. */
    private const val MAX_SPACING_MATCHES = 20_160

    /** Upper bound on one [next] call's local-match skips: a fold repeats at most one local hour. */
    private const val MAX_FOLD_SKIPS = 1_440

    /** Five whitespace-separated fields — the public contract (record §8: minute precision). */
    private const val CRON_FIELDS = 5

    /**
     * Parses [pattern], refusing anything that is not a five-field Unix cron with
     * `schedule.validation.cron_invalid`. The returned value is the library's parser, held for reuse.
     */
    fun parse(pattern: String): CronPattern {
        val normalized = pattern.trim().split(WHITESPACE).joinToString(" ")
        val fields = normalized.split(' ')
        if (normalized.isEmpty() || fields.size != CRON_FIELDS || fields.any { it.startsWith("@") }) {
            throw cronInvalid(pattern, "a cron has exactly five fields: minute hour day-of-month month day-of-week")
        }
        val schedule =
            try {
                CronSchedule(normalized, ZoneOffset.UTC, CronStyle.UNIX)
            } catch (
                // cron-utils signals every grammar refusal with an unchecked exception (mostly
                // IllegalArgumentException); all of them are the caller's malformed pattern.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                throw cronInvalid(pattern, e.message ?: "unparseable", e)
            }
        return CronPattern(normalized, schedule)
    }

    /** Resolves [timezone] as an IANA region id, refusing a bare offset or an unknown name. */
    fun zone(timezone: String): ZoneId {
        val id =
            try {
                ZoneId.of(timezone.trim())
            } catch (_: DateTimeException) {
                throw timezoneInvalid(timezone)
            }
        // Only a tzdb REGION id is an IANA zone. `ZoneId.of` also accepts a fixed offset ("+02:00")
        // and a prefixed one ("UTC+2"), which would freeze one offset instead of following the
        // zone's DST rules — refused (record §1: an explicit IANA timezone).
        if (id.id !in ZoneId.getAvailableZoneIds()) throw timezoneInvalid(timezone)
        return id
    }

    /** The first occurrence strictly after [after]. Never null: a five-field cron always matches again. */
    fun next(
        pattern: CronPattern,
        zone: ZoneId,
        after: Instant,
    ): Instant {
        var local = LocalDateTime.ofInstant(after, zone)
        repeat(MAX_FOLD_SKIPS + 1) {
            local = pattern.nextLocalAfter(local)
            val candidate = toInstant(local, zone)
            if (candidate.isAfter(after)) return candidate
            // The candidate is a fold's second pass (or a gap time already fired at its transition):
            // our rule runs it once, so it is not an occurrence after `after`. Keep walking.
        }
        error("no occurrence of '${pattern.pattern}' after $after within $MAX_FOLD_SKIPS local matches")
    }

    /** The first occurrence at or after [from]. */
    fun firstAtOrAfter(
        pattern: CronPattern,
        zone: ZoneId,
        from: Instant,
    ): Instant = next(pattern, zone, from.minusMillis(1))

    /** Up to [count] occurrences strictly after [after], in order — the preview (record §6). */
    fun upcoming(
        pattern: CronPattern,
        zone: ZoneId,
        after: Instant,
        count: Int,
    ): List<Occurrence> {
        val out = ArrayList<Occurrence>(count)
        var cursor = after
        repeat(count) {
            cursor = next(pattern, zone, cursor)
            out += Occurrence(cursor, zone.rules.getOffset(cursor), LocalDateTime.ofInstant(cursor, zone))
        }
        return out
    }

    /**
     * The occurrences in `[from, to]`, oldest first, at most [cap] of them; `truncated` when more
     * exist (a long outage's summary counts "at least").
     */
    fun between(
        pattern: CronPattern,
        zone: ZoneId,
        from: Instant,
        to: Instant,
        cap: Int,
    ): Range {
        val found = ArrayList<Instant>()
        if (to.isBefore(from)) return Range(found, truncated = false)
        var cursor = firstAtOrAfter(pattern, zone, from)
        while (!cursor.isAfter(to)) {
            if (found.size == cap) return Range(found, truncated = true)
            found += cursor
            cursor = next(pattern, zone, cursor)
        }
        return Range(found, truncated = false)
    }

    /**
     * The smallest gap between consecutive LOCAL matches in the [MIN_SPACING_HORIZON] after
     * [from], or null when the pattern matches fewer than twice in it. Stops at the first gap
     * below [floor] (the answer is then "too short", whatever the true minimum). Local matches, not
     * instants: a DST fold's skipped second pass must not read as a long gap, nor a gap's
     * transition as a short one — the guard is about the pattern the author wrote (record §3).
     */
    fun minSpacing(
        pattern: CronPattern,
        zone: ZoneId,
        from: Instant,
        floor: Duration,
    ): Duration? {
        val end = LocalDateTime.ofInstant(from, zone).plus(MIN_SPACING_HORIZON)
        var previous = pattern.nextLocalAfter(LocalDateTime.ofInstant(from, zone))
        var smallest: Duration? = null
        var seen = 0
        while (!previous.isAfter(end) && seen < MAX_SPACING_MATCHES) {
            val current = pattern.nextLocalAfter(previous)
            if (current.isAfter(end)) break
            val gap = Duration.between(previous, current)
            if (smallest == null || gap < smallest) smallest = gap
            if (gap < floor) return gap
            previous = current
            seen++
        }
        return smallest
    }

    /**
     * Our DST rule (record §3.2): the instant a LOCAL match fires at in [zone]. A gap maps to the
     * transition instant; an overlap to its earlier instant (the first pass).
     */
    fun toInstant(
        local: LocalDateTime,
        zone: ZoneId,
    ): Instant {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when (offsets.size) {
            // Spring forward: this wall time does not exist; it runs when the clock resumes.
            0 -> rules.getTransition(local).instant

            // Fall back: two instants share this wall time; the first pass is the one with the
            // LARGER offset (the pre-transition one), which is the earlier instant. By total
            // seconds, explicitly: `ZoneOffset.compareTo` orders DESCENDING, so `max()` would
            // pick the second pass (found by the fall-back cases of OccurrenceFunctionTest).
            else -> local.toInstant(offsets.maxBy { it.totalSeconds })
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun cronInvalid(
        pattern: String,
        why: String,
        cause: Throwable? = null,
    ) = ScheduleException(
        ScheduleErrorCodes.CRON_INVALID,
        "'${pattern.take(MAX_ECHO)}' is not a five-field Unix cron ($why).",
        mapOf("cron" to pattern.take(MAX_ECHO)),
        cause,
    )

    private fun timezoneInvalid(timezone: String) =
        ScheduleException(
            ScheduleErrorCodes.TIMEZONE_INVALID,
            "'${timezone.take(MAX_ECHO)}' is not an IANA time zone (for example America/New_York or UTC).",
            mapOf("timezone" to timezone.take(MAX_ECHO)),
        )

    /** Reflected client input is bounded before it reaches an error message. */
    private const val MAX_ECHO = 64
}

/** A parsed five-field cron — the library's matcher over a fixed UTC frame, i.e. over local wall time. */
class CronPattern internal constructor(
    /** The normalized pattern (single spaces). */
    val pattern: String,
    private val matcher: CronSchedule,
) {
    /** The next LOCAL date-time strictly after [local] whose fields match. */
    fun nextLocalAfter(local: LocalDateTime): LocalDateTime {
        val next = matcher.getNextExecutionTime(ExecutionComplete.simulatedSuccess(local.toInstant(ZoneOffset.UTC)))
        return LocalDateTime.ofInstant(next, ZoneOffset.UTC)
    }
}

/** One previewed occurrence: the UTC instant, the zone's offset at it, and the local wall time shown. */
data class Occurrence(
    val at: Instant,
    val offset: ZoneOffset,
    val local: LocalDateTime,
)

/** The occurrences of a window, and whether more existed than the cap allowed. */
data class Range(
    val instants: List<Instant>,
    val truncated: Boolean,
)
