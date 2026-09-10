package co.datapipelines.executor

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Duration
import java.time.Instant

/**
 * The crash sweep's caller (metadata-db §8.3, deployment.md §6.2): flips abandoned `RUNNING` rows
 * to `ABORTED` with `pipeline.execution.instance_lost`, via [ExecutionRepository.sweepStaleRunning].
 *
 * Abandoned means one of two things (108 §D): the row's `heartbeat_at` is older than three
 * heartbeat intervals — ~45 seconds, and the condition that actually matters — or it carries no
 * heartbeat at all and is older than `datapipelines.executions.stale-timeout-minutes`, the
 * pre-V21 backstop that keeps a rolling upgrade from reaping live runs.
 *
 * ## No leader election — every replica may run this (and does)
 * The sweep is one `UPDATE … WHERE status='RUNNING' AND started_at < :t`: naturally idempotent.
 * Two replicas sweeping the same rows race to the same terminal write, and the loser simply
 * updates zero rows. A lock would buy nothing but a new failure mode, so none exists — the
 * next reader reaching for one should re-read this paragraph first.
 *
 * ## The DELETE-on-a-stale-row secondary failure (ARCH-AUDIT M2, C3)
 * `DELETE /executions/{id}` against a stale `RUNNING` row writes a Redis cancellation flag no
 * live instance polls and returns 204 — a silent no-op. The sweep closes that window **by
 * construction**: the row reaches `ABORTED` at the next tick, and from then on the same DELETE
 * is refused with `pipeline.execution.not_running` instead of lying. The flag itself expires by
 * TTL; nothing reads it either way.
 *
 * ## Failure handling
 * A metadata-DB fault fails the tick, never the scheduler: the exception is logged and the
 * next tick retries. A sweep that crashed its scheduling thread would re-create exactly the
 * stuck-`RUNNING` hole it exists to close.
 */
class StaleExecutionSweeper(
    private val executions: ExecutionRepository,
    private val staleTimeout: Duration,
    /**
     * `datapipelines.executor.heartbeat-seconds` (108 §D). The reaping cutoff is THREE of these:
     * one missed beat is a slow tick, two is a suspicious box, three is an instance that is gone.
     * Anything tighter reaps live executions on a loaded machine — which is the failure mode that
     * costs data, where waiting an extra 30 seconds costs an operator nothing.
     */
    private val heartbeatInterval: Duration,
) {
    init {
        require(!staleTimeout.isNegative && !staleTimeout.isZero) { "staleTimeout must be positive, was $staleTimeout" }
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) {
            "heartbeatInterval must be positive, was $heartbeatInterval"
        }
    }

    /**
     * One sweep tick.
     *
     * @return the number of rows swept (0 also when the tick failed — see the class KDoc).
     */
    @Suppress("SwallowedException")
    fun sweepOnce(): Int {
        val now = Instant.now()
        val cutoff = now.minus(staleTimeout)
        val heartbeatCutoff = now.minus(heartbeatInterval.multipliedBy(MISSED_BEATS))
        val swept =
            try {
                executions.sweepStaleRunning(cutoff, heartbeatCutoff)
            } catch (e: DataAccessException) {
                LOG.warn("event=execution.sweep_failed cutoff={} heartbeat_cutoff={} message=\"{}\"", cutoff, heartbeatCutoff, e.message)
                return 0
            }
        if (swept > 0) {
            LOG.info(
                "event=execution.swept count={} cutoff={} heartbeat_cutoff={} " +
                    "message=\"stale RUNNING executions marked ABORTED (pipeline.execution.instance_lost)\"",
                swept,
                cutoff,
                heartbeatCutoff,
            )
        }
        return swept
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(StaleExecutionSweeper::class.java)

        /** How many heartbeats an instance may miss before it is declared gone — see the ctor. */
        const val MISSED_BEATS = 3L
    }
}
