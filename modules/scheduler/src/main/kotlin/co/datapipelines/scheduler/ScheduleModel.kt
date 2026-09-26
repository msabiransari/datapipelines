package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * One `schedules` row (metadata-db §4.22; scheduler design revision §7).
 *
 * The scheduler knows WHEN and UNDER WHAT CONDITIONS a job fires, never what the job is:
 * [executorId] names the registered executor, and [payload] / [parameters] are opaque JSON the
 * executor validated at save (record §5). [targetRef] is the executor's generic reference to what
 * the job targets (`pipeline:<name>` for the pipeline executor, B15) — indexed so "which schedules
 * run this?" is answerable, and never parsed here.
 */
data class Schedule(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    /** Optimistic-concurrency counter — the `ETag` / `If-Match` value. Not a releasable version (record §1.1). */
    val revision: Int,
    val executorId: String,
    val payloadSchemaVersion: Int,
    val payload: JsonNode,
    val parameters: JsonNode,
    val targetRef: String,
    val cron: String,
    val timezone: String,
    val missedRunPolicy: MissedRunPolicy,
    val enabled: Boolean,
    val blockedReason: String?,
    val blockedAt: Instant?,
    val blockedRunId: UUID?,
    val nextDueAt: Instant?,
    val createdBy: UUID,
    val updatedBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    /** Blocked takes display precedence over paused (record §1.1). */
    val blocked: Boolean get() = blockedAt != null

    /** The operational condition shown for a schedule: `blocked`, `paused` or `enabled`. */
    val condition: String
        get() =
            when {
                blocked -> "blocked"
                !enabled -> "paused"
                else -> "enabled"
            }
}

/** `schedules.missed_run_policy` (record §3): `skip` records a missed occurrence, `latest` catches up the newest one. */
enum class MissedRunPolicy(
    val wire: String,
) {
    SKIP("skip"),
    LATEST("latest"),
    ;

    companion object {
        fun fromWire(value: String): MissedRunPolicy? = entries.firstOrNull { it.wire == value }
    }
}

/** `schedule_runs.origin` — how a run came to exist (record §3, §7). */
enum class RunOrigin(
    val wire: String,
) {
    CRON("cron"),
    CATCH_UP("catch_up"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromWire(value: String): RunOrigin = entries.first { it.wire == value }
    }
}

/**
 * `schedule_runs.state` — R6's normative table (record §7.1). The ACTIVE states hold the
 * schedule's single overlap slot (a partial unique index); the rest are terminal except
 * [UNKNOWN], which a later real terminal may still update (A5).
 */
enum class RunState(
    val wire: String,
) {
    QUEUED("queued"),
    STARTING("starting"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ABORTED("aborted"),
    UNKNOWN("unknown"),
    NOT_STARTED("not_started"),
    SKIPPED("skipped"),
    ;

    /** Holds the schedule's overlap slot (record §3, "No overlap"). */
    val active: Boolean get() = this == QUEUED || this == STARTING || this == RUNNING

    companion object {
        fun fromWire(value: String): RunState = entries.first { it.wire == value }

        /** The states an execution's terminal can map onto (the reconciler writes only these). */
        val FINISHED: Set<RunState> = setOf(SUCCEEDED, FAILED, CANCELLED, ABORTED, UNKNOWN)
    }
}

/**
 * The run reasons the SCHEDULER writes (record §7.1). An executor's refusals carry their own reason
 * strings through the port ([Preparation.Refused], [StartOutcome.NotStarted]) — stored, never
 * parsed.
 */
object RunReasons {
    const val MISSED = "missed"
    const val OVERLAP = "overlap"
    const val SCHEDULE_PAUSED = "schedule_paused"
    const val SCHEDULE_BLOCKED = "schedule_blocked"
    const val SCHEDULE_DELETED = "schedule_deleted"
    const val CAPACITY = "capacity"
    const val START_UNCONFIRMED = "start_unconfirmed"
    const val START_FAILED = "start_failed"
}

/** The reason a schedule is blocked by an `unknown` run (record §2.1). An executor refusal blocks under its own reason. */
const val BLOCKED_BY_UNKNOWN_RUN = "run_unknown"

/**
 * One `schedule_runs` row (record §7). [payload], [parameters] and [prepared] are the frozen,
 * executor-owned snapshots; [executionId] is the opaque execution reference minted at the claim.
 */
data class ScheduleRun(
    val id: UUID,
    val scheduleId: UUID?,
    val workspaceId: UUID,
    val origin: RunOrigin,
    val scheduledAt: Instant?,
    val referenceAt: Instant,
    val referenceTimezone: String,
    val admitBy: Instant,
    val scheduleRevision: Int?,
    val executorId: String,
    val payloadSchemaVersion: Int,
    val payload: JsonNode,
    val parameters: JsonNode,
    val prepared: JsonNode?,
    val actorUserId: UUID,
    val requestedBy: UUID?,
    val executionId: UUID?,
    val state: RunState,
    val reason: String?,
    val worker: String?,
    val attempts: Int,
    val createdAt: Instant,
    val claimedAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val updatedAt: Instant,
)

/**
 * The trail's kinds (`schedule_run_events.kind`, record §7.1, R10). Slice 4's notification kinds
 * widen the table's CHECK when they land; none is reserved here.
 */
enum class TrailKind(
    val wire: String,
) {
    RECORDED("recorded"),
    CAPACITY_RETRY("capacity_retry"),
    CLAIMED("claimed"),
    EXECUTION_STARTED("execution_started"),
    NOT_STARTED("not_started"),
    SKIPPED("skipped"),
    FINISHED("finished"),
    UNKNOWN("unknown"),
    UPDATED_AFTER_UNKNOWN("updated_after_unknown"),
    UNBLOCKED("unblocked"),
    ;

    companion object {
        fun fromWire(value: String): TrailKind = entries.first { it.wire == value }
    }
}

/** One append-only `schedule_run_events` row (R10). Never updated. */
data class TrailEvent(
    val runId: UUID,
    val seq: Int,
    val kind: TrailKind,
    val reason: String?,
    val at: Instant,
    val worker: String?,
    val details: JsonNode,
)
