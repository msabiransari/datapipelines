package co.datapipelines.scheduler

import co.datapipelines.typesystem.DatapipelinesException

/**
 * The scheduler's catalogued error codes (pipeline-contract.md §13.19, the single catalog;
 * `ApiErrorCatalog` in `web` maps each to its HTTP status and `ApiErrorCatalogSpecDriftTest` holds
 * the two together). Every refusal a schedule route can answer is one of these, an existing
 * pipeline code the executor adapter reuses (a parameter refusal is exactly an interactive run's),
 * or [IDEMPOTENCY_KEY_REUSED].
 */
object ScheduleErrorCodes {
    /** 404 — no live schedule with that id in the caller's workspace (a deleted one is absent). */
    const val NOT_FOUND = "schedule.not_found"

    /** 404 — no run with that id under that schedule. */
    const val RUN_NOT_FOUND = "schedule.run.not_found"

    /** 409 — the name is held by another live schedule of the workspace. */
    const val NAME_TAKEN = "schedule.name_taken"

    /** 409 — `If-Match` named a revision that is no longer current (someone edited it since). */
    const val REVISION_CONFLICT = "schedule.revision_conflict"

    /** 409 — Run now while a run of the schedule is queued, starting or running (record §3, D-9.6). */
    const val RUN_OVERLAP = "schedule.run.overlap"

    /** 409 — Run now against a blocked schedule; unblock first (record §1.1). Resume never clears a block and is not refused. */
    const val BLOCKED = "schedule.blocked"

    /** 409 — unblock on a schedule that is not blocked. */
    const val NOT_BLOCKED = "schedule.not_blocked"

    /** 409 — the workspace already holds `max-schedules-per-workspace` live schedules (B18, L4). */
    const val LIMIT_PER_WORKSPACE = "schedule.limit.per_workspace"

    /** 400 — a missing or ill-typed request field; `details.field` names it. */
    const val REQUEST_INVALID = "schedule.validation.request_invalid"

    /** 400 — the name breaks the pipeline/template path grammar (record §6). */
    const val NAME_INVALID = "schedule.validation.name_invalid"

    /** 400 — not a five-field Unix cron (seconds, the disabled pattern `-` and bad fields are all this). */
    const val CRON_INVALID = "schedule.validation.cron_invalid"

    /** 400 — not an IANA zone id. */
    const val TIMEZONE_INVALID = "schedule.validation.timezone_invalid"

    /** 400 — two consecutive occurrences closer than `min-interval-seconds` (B18, L4). */
    const val INTERVAL_TOO_SHORT = "schedule.validation.interval_too_short"

    /** 400 — no executor is registered under that id. */
    const val EXECUTOR_UNKNOWN = "schedule.validation.executor_unknown"

    /** 400 — the executor refused the payload's shape, or it breaks the generic size/depth limits. */
    const val PAYLOAD_INVALID = "schedule.validation.payload_invalid"

    /** 400 — the payload names a target the workspace does not hold. */
    const val TARGET_NOT_FOUND = "schedule.validation.target_not_found"

    /**
     * The EXISTING catalog row (pipeline-contract §13.11, 409) a replayed `Idempotency-Key` with a
     * different request answers — the same code an execute replay gets. Spelled here rather than
     * imported: this module may use nothing of `pipeline-contract` but the name grammar
     * (`SchedulerBoundaryTest`), and `ScheduleErrorCodesTest` pins the two spellings equal.
     */
    const val IDEMPOTENCY_KEY_REUSED = "idempotency.key_reused_for_different_request"

    /** Every scheduler-owned code, for the catalog tests. */
    val ALL: Set<String> =
        setOf(
            NOT_FOUND,
            RUN_NOT_FOUND,
            NAME_TAKEN,
            REVISION_CONFLICT,
            RUN_OVERLAP,
            BLOCKED,
            NOT_BLOCKED,
            LIMIT_PER_WORKSPACE,
            REQUEST_INVALID,
            NAME_INVALID,
            CRON_INVALID,
            TIMEZONE_INVALID,
            INTERVAL_TOO_SHORT,
            EXECUTOR_UNKNOWN,
            PAYLOAD_INVALID,
            TARGET_NOT_FOUND,
        )
}

/** A refusal the scheduler raises; the surface renders it through the catalog like any other code. */
class ScheduleException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : DatapipelinesException(code, message, details, cause)
