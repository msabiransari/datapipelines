package co.datapipelines.executor

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Duration
import java.time.Instant

/**
 * The event-retention job's caller (metadata-db §8.1, 050/T60): purges `execution_events` rows
 * whose execution completed more than `datapipelines.executions.event-retention-days` ago, via
 * [ExecutionEventRepository.deleteOlderThan].
 *
 * M2's sibling (`StaleExecutionSweeper`), with the same two standing decisions:
 *
 * - **No leader election — every replica may run this (and does).** The DELETE is keyed on
 *   `completed_at < cutoff`, so two replicas racing delete disjoint-or-same rows and the loser
 *   simply deletes zero. A lock would buy nothing but a new failure mode.
 * - **`pipeline_executions` is never touched.** Retention deletes `execution_events` rows ONLY —
 *   the execution rows themselves are the durable history and outlive their events (metadata-db
 *   §8.1). The retention test pins this with a fixture holding both.
 *
 * ## Failure handling
 * A metadata-DB fault fails the tick, never the scheduler: logged, retried next tick — same
 * reasoning as the sweeper's.
 */
class ExecutionEventRetention(
    private val events: ExecutionEventRepository,
    private val retention: Duration,
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "retention must be positive, was $retention" }
    }

    /**
     * One retention tick.
     *
     * @return rows purged (0 also when the tick failed — see the class KDoc).
     */
    @Suppress("SwallowedException")
    fun retainOnce(): Int {
        val cutoff = Instant.now().minus(retention)
        val purged =
            try {
                events.deleteOlderThan(cutoff)
            } catch (e: DataAccessException) {
                LOG.warn("event=execution.event_retention_failed cutoff={} message=\"{}\"", cutoff, e.message)
                return 0
            }
        if (purged > 0) {
            LOG.info(
                "event=execution.events_purged count={} cutoff={} " +
                    "message=\"retention deleted execution_events for executions completed before the cutoff\"",
                purged,
                cutoff,
            )
        }
        return purged
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(ExecutionEventRetention::class.java)
    }
}
