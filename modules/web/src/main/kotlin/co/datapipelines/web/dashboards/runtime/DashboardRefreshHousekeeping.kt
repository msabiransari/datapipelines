package co.datapipelines.web.dashboards.runtime

import co.datapipelines.persistence.FailureShape
import co.datapipelines.visualization.DashboardRefreshRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException

/**
 * Closes the `dashboard_refreshes` rows a crash left `RUNNING` (metadata-db §8.4, the implementation spec's §18
 * premise 8) — [co.datapipelines.executor.StaleExecutionSweeper]'s shape, one level up. A refresh that dies with its
 * instance takes with it the only coroutine that would have written its terminal state; without this its row is
 * `RUNNING` for ever while the browser waits on a stream that ended (the coroutine-cancellation lesson's tell).
 *
 * A refresh past `max-refresh-seconds` plus one abort poll cannot still be running anywhere — §9.6 caps every deadline —
 * so the cutoff is a fact, not a guess. `TIMED_OUT` with `reason` `instance_lost` when one of its executions was aborted
 * by the stale-execution sweep, else `deadline_passed`. Every replica may run it (one idempotent guarded `UPDATE`); a
 * tick that fails is logged by class and SQLState only and retried.
 */
class DashboardRefreshSweeper(
    private val refreshes: DashboardRefreshRepository,
    private val staleAfterSeconds: Long,
) {
    init {
        require(staleAfterSeconds > 0) { "staleAfterSeconds must be positive, was $staleAfterSeconds" }
    }

    /** One sweep tick; returns the refreshes closed (0 also when the tick failed). */
    @Suppress("SwallowedException")
    fun sweepOnce(): Int {
        val closed =
            try {
                refreshes.sweepStale(staleAfterSeconds)
            } catch (e: DataAccessException) {
                LOG.warn("event=dashboard.refresh_sweep_failed error={} sql_state={}", FailureShape.cause(e), FailureShape.sqlState(e))
                return 0
            }
        closed.forEach {
            LOG.warn(
                "event=dashboard.refresh_swept refresh_id={} message=\"a RUNNING refresh outlived its deadline; closed TIMED_OUT\"",
                it,
            )
        }
        return closed.size
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(DashboardRefreshSweeper::class.java)
    }
}

/**
 * Retention of finished refreshes (metadata-db §8.1, §18 premise 7): deleted with the execution events, on the same hourly
 * tick and the same cutoff (`datapipelines.executions.event-retention-days`). `pipeline_executions` is never touched — the
 * cascade takes only the refresh's own link rows — and a `RUNNING` refresh is never deleted (only `finished_at IS NOT NULL`
 * rows are candidates).
 */
class DashboardRefreshRetention(
    private val refreshes: DashboardRefreshRepository,
    private val retentionDays: Long,
) {
    init {
        require(retentionDays > 0) { "retentionDays must be positive, was $retentionDays" }
    }

    /** One retention step; returns the rows purged (0 also when the step failed). */
    @Suppress("SwallowedException")
    fun retainOnce(): Int {
        val purged =
            try {
                refreshes.deleteFinishedOlderThan(retentionDays)
            } catch (e: DataAccessException) {
                LOG.warn("event=dashboard.refresh_retention_failed error={} sql_state={}", FailureShape.cause(e), FailureShape.sqlState(e))
                return 0
            }
        if (purged > 0) LOG.info("event=dashboard.refreshes_purged count={} retention_days={}", purged, retentionDays)
        return purged
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(DashboardRefreshRetention::class.java)
    }
}
