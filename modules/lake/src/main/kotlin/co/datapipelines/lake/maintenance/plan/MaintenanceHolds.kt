package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.profiles.LakeTablePause
import co.datapipelines.lake.profiles.MaintenanceWindow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The window-and-pause gate (brief 959 D6; the P6 lane cut's L13): ONE pure function, used by the plan and, through
 * [ProfileMaintenanceGate], by the worker before each part.
 *
 * - the profile's window holds compaction only (`HH:MM-HH:MM` in UTC; a window may cross midnight; a start equal to the
 *   end is the whole day);
 * - `pause.compaction_until` holds compaction and manifest rewrite; `pause.cleanup_until` holds cleanup; a pause holds
 *   while `now` is before its instant;
 * - nothing holds purge deletion, and a part name the planner does not know is never held.
 *
 * A pause is answered before the window: both hold, and the pause says until when.
 */
object MaintenanceHolds {
    const val REASON_OUTSIDE_WINDOW = "outside window"

    fun hold(
        part: String,
        window: MaintenanceWindow,
        pause: LakeTablePause,
        now: Instant,
    ): PartHold? =
        when (part) {
            MaintenancePartNames.COMPACTION -> paused(pause.compactionUntil, now) ?: outside(window, now)
            MaintenancePartNames.MANIFEST_REWRITE -> paused(pause.compactionUntil, now)
            MaintenancePartNames.CLEANUP -> paused(pause.cleanupUntil, now)
            else -> null
        }

    /** True when [now] falls inside [window], read in UTC. */
    fun inside(
        window: MaintenanceWindow,
        now: Instant,
    ): Boolean {
        if (window.isWholeDay) return true
        val time = LocalTime.ofInstant(now, ZoneOffset.UTC)
        return if (window.start < window.end) {
            !time.isBefore(window.start) && time.isBefore(window.end)
        } else {
            !time.isBefore(window.start) || time.isBefore(window.end)
        }
    }

    /** The record's words for a pause: `paused until <instant>`. */
    fun pausedReason(until: Instant): String = "paused until $until"

    private fun paused(
        until: Instant?,
        now: Instant,
    ): PartHold? = until?.takeIf { now.isBefore(it) }?.let { PartHold(MaintenanceOutcome.PAUSED_UNTIL, pausedReason(it), it) }

    private fun outside(
        window: MaintenanceWindow,
        now: Instant,
    ): PartHold? = if (inside(window, now)) null else PartHold(MaintenanceOutcome.OUTSIDE_WINDOW, REASON_OUTSIDE_WINDOW)
}
