package co.datapipelines.executor

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Duration
import java.time.Instant

/**
 * The crash sweep's caller (metadata-db §8.3, deployment.md §6.2): flips `RUNNING` rows older
 * than `datapipelines.executions.stale-timeout-minutes` to `ABORTED` with
 * `pipeline.execution.instance_lost`, via [ExecutionRepository.sweepStaleRunning].
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
) {
    init {
        require(!staleTimeout.isNegative && !staleTimeout.isZero) { "staleTimeout must be positive, was $staleTimeout" }
    }

    /**
     * One sweep tick.
     *
     * @return the number of rows swept (0 also when the tick failed — see the class KDoc).
     */
    @Suppress("SwallowedException")
    fun sweepOnce(): Int {
        val cutoff = Instant.now().minus(staleTimeout)
        val swept =
            try {
                executions.sweepStaleRunning(cutoff)
            } catch (e: DataAccessException) {
                LOG.warn("event=execution.sweep_failed cutoff={} message=\"{}\"", cutoff, e.message)
                return 0
            }
        if (swept > 0) {
            LOG.info(
                "event=execution.swept count={} cutoff={} " +
                    "message=\"stale RUNNING executions marked ABORTED (pipeline.execution.instance_lost)\"",
                swept,
                cutoff,
            )
        }
        return swept
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(StaleExecutionSweeper::class.java)
    }
}
