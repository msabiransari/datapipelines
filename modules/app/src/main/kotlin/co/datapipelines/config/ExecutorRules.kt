package co.datapipelines.config

import co.datapipelines.web.schedules.PipelineJobExecutor

/**
 * §7 / §3.2 — the executor's keys: the per-dialect statement timeouts (156, #2) and, since #325, the
 * lifecycle-write bound against the scheduler's start wait. Owns its file like `RedisRules`: the
 * validator's companion is the registry, not the home, of the §3.x families.
 */
internal object ExecutorRules {
    private const val KEY = "datapipelines.executor.lifecycle-write-timeout-seconds"

    /** configuration.md §3.2's default (156, #2), read when the operator key is unset. */
    private const val DEFAULT_NODE_QUERY_TIMEOUT_MAX_SECONDS = 900

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

    /**
     * §7 / §3.2 (156, #2) — `node-query-timeout-max-seconds` and every entry of
     * `node-query-timeout-seconds-by-dialect` are validated at boot, fail fast: a value an
     * author's `settings.query_timeout_seconds` could never legally reach (because the
     * ceiling itself is malformed or a dialect default already exceeds it) is worse
     * discovered at the first save than at startup, in the one report every other §7
     * violation reaches.
     *
     * `ExecutorProperties`'s own `init` refuses the same shapes for a DIRECTLY constructed
     * instance (the defensive per-field pattern every key in that class already follows);
     * this is the §7 twin that names the KEYS in the aggregated report, same relationship as
     * `ConfigValidator.checkEndpointsTimeoutOrdering` to `EndpointsProperties.init`. Moved here from
     * the validator's companion by #325, unchanged, to keep that companion under detekt's `LargeClass` cap.
     */
    fun checkExecutorQueryTimeoutByDialect(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val ceilingRaw = snapshot.executorNodeQueryTimeoutMaxSeconds?.trim()
        val ceiling = ceilingRaw?.toIntOrNull()
        if (ceilingRaw != null && (ceiling == null || ceiling <= 0)) {
            violations +=
                "datapipelines.executor.node-query-timeout-max-seconds is '$ceilingRaw'; §3.2 requires a " +
                "positive integer."
            return
        }
        val effectiveCeiling = ceiling ?: DEFAULT_NODE_QUERY_TIMEOUT_MAX_SECONDS
        snapshot.executorNodeQueryTimeoutSecondsByDialect.forEach { (dialect, rawValue) ->
            val where = "datapipelines.executor.node-query-timeout-seconds-by-dialect.$dialect"
            val value = rawValue.trim().toIntOrNull()
            if (value == null || value !in 1..effectiveCeiling) {
                violations +=
                    "$where is '$rawValue'; §3.2 requires a positive integer no greater than " +
                    "node-query-timeout-max-seconds ($effectiveCeiling)."
            }
        }
    }
}
