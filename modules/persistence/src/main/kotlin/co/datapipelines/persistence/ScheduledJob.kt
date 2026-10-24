package co.datapipelines.persistence

/**
 * The `@Scheduled` jobs whose failed ticks are counted (#326, #636) — the closed tag set of
 * [FAILED_TICK_METER] (observability §4.3: a tag from a closed set only). Names and values only:
 * this module carries no Micrometer (module-structure §5.19); each job registers the counter with
 * its own registry, under these strings, where it logs its failed-tick line. The jobs live in
 * three modules (`dag`, `auth`, `web`), and this is the one module below all three.
 */
enum class ScheduledJob(
    /** The `job` tag's value. */
    val tag: String,
) {
    /** The stale-execution sweep (`dag`'s `StaleExecutionSweeper`, every 15 s). */
    STALE_SWEEP("stale_sweep"),

    /** The `execution_events` retention (`dag`'s `ExecutionEventRetention`, hourly). */
    EVENT_RETENTION("event_retention"),

    /** The `audit_log` retention (`auth`'s `AuditLogRetention`, hourly). */
    AUDIT_RETENTION("audit_retention"),

    /** The keys purge (`auth`'s `KeyRetentionPurge`, hourly; counted by the retention tick's step). */
    KEYS_PURGE("keys_purge"),

    /** The dashboard refresh sweep (`web`'s `DashboardRefreshSweeper`, every 60 s; #636). */
    DASHBOARD_REFRESH_SWEEP("dashboard_refresh_sweep"),

    /** The finished dashboard refreshes' retention (`web`'s `DashboardRefreshRetention`, hourly; #636). */
    DASHBOARD_REFRESH_RETENTION("dashboard_refresh_retention"),

    /** The parameter evaluations' stale sweep (`web`'s `ParameterEvaluationSweeper`, hourly; #636). */
    PARAMETER_EVALUATION_SWEEP("parameter_evaluation_sweep"),

    /** The finished parameter evaluations' retention (`web`'s `ParameterEvaluationRetention`, hourly; #636). */
    PARAMETER_EVALUATION_RETENTION("parameter_evaluation_retention"),
    ;

    companion object {
        /** observability.md §4.1: one failed tick of a [ScheduledJob] — a statement cancelled at its bound is one. */
        const val FAILED_TICK_METER = "datapipelines.scheduled_jobs.tick_failed"

        /** The meter's one tag. */
        const val JOB_TAG = "job"

        /** The meter's description, the same wherever it is registered. */
        const val FAILED_TICK_DESCRIPTION = "failed ticks of a scheduled job, a statement cancelled at its bound included"
    }
}
