package co.datapipelines.config

import co.datapipelines.web.schedules.PipelineJobExecutor

/**
 * §7 / §3.2 (#325) — the executor's lifecycle-write bound against the scheduler's start wait. Owns its
 * file like `RedisRules`: the validator's companion is the registry, not the home, of the §3.x families.
 */
internal object ExecutorRules {
    private const val KEY = "datapipelines.executor.lifecycle-write-timeout-seconds"

    /**
     * The launch's own setup before the RUNNING insert is dispatched (resolving the pipeline, building the
     * executor): the share of the start wait the insert's bound may not take.
     */
    internal const val START_WAIT_HEADROOM_SECONDS = 5L

    /** The largest bound the scheduler's start wait still outlasts: 30 s − 5 s = 25 s. */
    internal val LIFECYCLE_WRITE_CEILING_SECONDS: Long =
        PipelineJobExecutor.DEFAULT_START_WAIT.seconds - START_WAIT_HEADROOM_SECONDS

    /**
     * `datapipelines.executor.lifecycle-write-timeout-seconds` must be a whole number of seconds in
     * `1..`[LIFECYCLE_WRITE_CEILING_SECONDS]. A scheduled run's start barrier waits
     * [PipelineJobExecutor.DEFAULT_START_WAIT] for the RUNNING row; a bound at or past that wait lets the
     * barrier give up first, so a database that cannot take the insert ends the run `unknown` instead of
     * `record_unwritable` — the scheduler can no longer say that no node ran. Unset uses the yml default
     * (10); the binder trims, so this judges the trimmed text; blank is refused.
     */
    fun checkLifecycleWriteCeiling(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val raw = snapshot.executorLifecycleWriteTimeoutSeconds ?: return
        val seconds = raw.trim().toLongOrNull()
        when {
            seconds == null || seconds < 1 -> {
                violations += "$KEY is '$raw'; §3.2 requires a whole number of seconds of at least 1."
            }

            seconds > LIFECYCLE_WRITE_CEILING_SECONDS -> {
                violations +=
                    "$KEY is '$raw'; §3.2 caps it at $LIFECYCLE_WRITE_CEILING_SECONDS s (the scheduler's " +
                    "${PipelineJobExecutor.DEFAULT_START_WAIT.seconds} s start wait less $START_WAIT_HEADROOM_SECONDS s): " +
                    "past it a scheduled run whose RUNNING row cannot be written ends 'unknown', not 'record_unwritable'."
            }
        }
    }
}
