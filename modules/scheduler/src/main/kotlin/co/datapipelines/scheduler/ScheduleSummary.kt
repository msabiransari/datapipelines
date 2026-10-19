package co.datapipelines.scheduler

import java.time.Instant
import java.util.UUID

/**
 * #584 — the Schedules home's numbers (ui-screens §4.20a): ONE snapshot of the schedules the viewer
 * may see, built by [ScheduleService.summary] over the SAME admitted list §20.1 returns
 * ([ScheduleService.list]), so the home's counts and the explorer's list cannot disagree under the
 * promoter lens by construction. The unlensed `countLive` is never its total.
 *
 * [complete] is false when the workspace holds more live schedules than its cap
 * (`datapipelines.scheduler.max-schedules-per-workspace` lowered after they were created): the
 * snapshot read `cap + 1` rows and cannot count the rest, so a surface says the counts are
 * unavailable rather than show a short number.
 *
 * The rows are a projection ([ScheduleSummaryEntry]): no payload, no recipient address (those are
 * `schedule.update`'s, auth.md §7.6), no block reason (it may quote a pipeline error).
 */
data class ScheduleSummary(
    val complete: Boolean,
    val total: Int,
    val enabled: Int,
    val paused: Int,
    val blocked: Int,
    /** Every [LastRunOutcome], in declaration order, with its count — a zero is present as a zero. */
    val lastRun: Map<LastRunOutcome, Int>,
    /** Enabled, unblocked schedules by `next_due_at`, soonest first — the dispatcher's own predicate. */
    val upNext: List<ScheduleSummaryEntry>,
    /** By `updated_at`, newest first. */
    val recentlyChanged: List<ScheduleSummaryEntry>,
) {
    companion object {
        /** The most rows either list carries. */
        const val LIST_SIZE = 5

        /** The summary of [admitted] (the viewer's whole visible set) with each schedule's latest run state. */
        fun of(
            admitted: List<Schedule>,
            latest: Map<UUID, RunState>,
            complete: Boolean,
        ): ScheduleSummary {
            val outcomes = admitted.groupingBy { LastRunOutcome.of(latest[it.id]) }.eachCount()
            return ScheduleSummary(
                complete = complete,
                total = admitted.size,
                enabled = admitted.count { it.condition == "enabled" },
                paused = admitted.count { it.condition == "paused" },
                blocked = admitted.count { it.condition == "blocked" },
                lastRun = LastRunOutcome.entries.associateWith { outcomes[it] ?: 0 },
                // A paused schedule KEEPS its stale next_due_at (pause writes COALESCE), so the
                // filter is the dispatcher's — enabled AND not blocked — never next_due_at IS NOT NULL.
                upNext =
                    admitted
                        .filter { it.enabled && !it.blocked }
                        .mapNotNull { schedule -> schedule.nextDueAt?.let { schedule to it } }
                        .sortedWith(compareBy<Pair<Schedule, Instant>> { it.second }.thenBy { it.first.name })
                        .take(LIST_SIZE)
                        .map { ScheduleSummaryEntry.of(it.first) },
                recentlyChanged =
                    admitted
                        .sortedWith(compareByDescending<Schedule> { it.updatedAt }.thenBy { it.name })
                        .take(LIST_SIZE)
                        .map(ScheduleSummaryEntry::of),
            )
        }
    }
}

/** One schedule as the home lists it: what a row shows and links, nothing else. */
data class ScheduleSummaryEntry(
    val id: UUID,
    val name: String,
    val condition: String,
    val nextDueAt: Instant?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(schedule: Schedule): ScheduleSummaryEntry =
            ScheduleSummaryEntry(schedule.id, schedule.name, schedule.condition, schedule.nextDueAt, schedule.updatedAt)
    }
}

/**
 * How a schedule's LATEST run ended, in five buckets — THE one place a [RunState] maps to one
 * (`ScheduleSummaryTest` asserts every state sits in exactly one bucket, so a new state fails the
 * test until it is placed). [NEVER_RAN] is the absence of any run, not a state.
 */
enum class LastRunOutcome(
    val states: Set<RunState>,
) {
    SUCCEEDED(setOf(RunState.SUCCEEDED)),

    /** A run that ended without its result, including one whose outcome was lost ([RunState.UNKNOWN]). */
    FAILED(setOf(RunState.FAILED, RunState.ABORTED, RunState.UNKNOWN)),

    IN_PROGRESS(setOf(RunState.QUEUED, RunState.STARTING, RunState.RUNNING)),

    /** Recorded but never executed: refused before start, skipped by policy, or cancelled. */
    DID_NOT_RUN(setOf(RunState.NOT_STARTED, RunState.SKIPPED, RunState.CANCELLED)),

    NEVER_RAN(emptySet()),
    ;

    companion object {
        /** The bucket of [state]; null (no run at all) is [NEVER_RAN]. An unplaced state is a defect, refused loudly. */
        fun of(state: RunState?): LastRunOutcome =
            if (state == null) {
                NEVER_RAN
            } else {
                requireNotNull(entries.firstOrNull { state in it.states }) { "run state ${state.wire} has no last-run bucket" }
            }
    }
}
