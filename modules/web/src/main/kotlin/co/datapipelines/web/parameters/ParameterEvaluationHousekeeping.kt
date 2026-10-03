package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException

/**
 * Closes the `parameter_evaluations` rows whose terminal write never landed (metadata-db §8.5; the workspace spec
 * §2.3) — [co.datapipelines.web.dashboards.runtime.DashboardRefreshSweeper]'s shape, but on the HOURLY retention tick:
 * an evaluation that dies with its instance (or whose terminal write failed) leaves its row `RUNNING` for ever.
 *
 * Every evaluate is bounded by `datapipelines.parameters.evaluate-timeout-seconds`, so a row older than that plus
 * [MARGIN_SECONDS] cannot still be running anywhere — the cutoff is a fact, not a guess. The row becomes `INCOMPLETE`,
 * never `TIMEOUT`: nobody knows how the lost attempt ended. One guarded `UPDATE` per tick over the partial running
 * index; every replica may run it; a failed tick is logged by class and SQLState only and retried next hour.
 */
class ParameterEvaluationSweeper(
    private val evaluations: ParameterEvaluationRepository,
    evaluateTimeoutSeconds: Long,
) {
    init {
        require(evaluateTimeoutSeconds > 0) { "evaluateTimeoutSeconds must be positive, was $evaluateTimeoutSeconds" }
    }

    /** The cutoff the `UPDATE` uses: the evaluate deadline plus the margin. */
    val staleAfterSeconds: Long = evaluateTimeoutSeconds + MARGIN_SECONDS

    /** One sweep step; returns the records closed (0 also when the step failed). */
    @Suppress("SwallowedException")
    fun sweepOnce(): Int {
        val closed =
            try {
                evaluations.sweepStale(staleAfterSeconds)
            } catch (e: DataAccessException) {
                LOG.warn("event=parameter.evaluation_sweep_failed error={} sql_state={}", FailureShape.cause(e), FailureShape.sqlState(e))
                return 0
            }
        closed.forEach {
            LOG.warn(
                "event=parameter.evaluation_swept evaluation_id={} " +
                    "message=\"a RUNNING evaluation outlived its deadline; closed INCOMPLETE\"",
                it,
            )
        }
        return closed.size
    }

    companion object {
        /**
         * One minute past the deadline: the terminal write follows the deadline at once, so the margin only has to cover
         * the gap between the instance's clock (which stamps `started_at`) and the database's `NOW()`, and a slow write.
         */
        const val MARGIN_SECONDS = 60L

        private val LOG = LoggerFactory.getLogger(ParameterEvaluationSweeper::class.java)
    }
}

/**
 * Retention of finished evaluation records (metadata-db §8.1; the workspace spec §2.3, the owner's §11.5 ruling): the
 * executions' EVENT retention — `datapipelines.executions.event-retention-days`, no key of its own — applied on the same
 * hourly tick, the cutoff on `started_at`. ONE bounded `DELETE` per tick ([batchSize] records, oldest first, the query
 * rows cascading) — the `AuditLogRetention` batch shape without its loop: a full batch means a backlog, said once at
 * WARN, and the next hour takes the next batch. A `RUNNING` row is never deleted.
 */
class ParameterEvaluationRetention(
    private val evaluations: ParameterEvaluationRepository,
    private val retentionDays: Long,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(retentionDays > 0) { "retentionDays must be positive, was $retentionDays" }
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
    }

    /** One retention step; returns the records purged (0 also when the step failed). */
    @Suppress("SwallowedException")
    fun retainOnce(): Int {
        val purged =
            try {
                evaluations.deleteFinishedOlderThan(retentionDays, batchSize)
            } catch (e: DataAccessException) {
                LOG.warn(
                    "event=parameter.evaluation_retention_failed error={} sql_state={}",
                    FailureShape.cause(e),
                    FailureShape.sqlState(e),
                )
                return 0
            }
        if (purged > 0) LOG.info("event=parameter.evaluations_purged count={} retention_days={}", purged, retentionDays)
        if (purged >= batchSize) {
            LOG.warn(
                "event=parameter.evaluation_retention_incomplete count={} " +
                    "message=\"records older than the cutoff remain; the next tick continues\"",
                purged,
            )
        }
        return purged
    }

    companion object {
        /** Records per tick — one short statement; their query rows (≤ `max-parameters-per-set` each) cascade with them. */
        const val DEFAULT_BATCH_SIZE = 5_000

        private val LOG = LoggerFactory.getLogger(ParameterEvaluationRetention::class.java)
    }
}
