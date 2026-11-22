package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.ExecutionCaptureStore
import co.datapipelines.persistence.FailureShape
import co.datapipelines.persistence.ScheduledJob
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Clock

/**
 * Physically deletes runtime capture detail past its expiry (#1043; metadata-db §8.6) — a step of the hourly retention
 * tick. Reads already refuse expired detail whether or not this has run (the store bounds every read by `expires_at`);
 * this only reclaims the space.
 *
 * Bounded and restart-safe: at most [MAX_BATCHES] batches of [BATCH_SIZE] value rows and summaries per tick, oldest
 * first through the `expires_at` indexes, each batch its own statement on the retention tick's 25 s bound. Nothing is
 * remembered between ticks: a backlog, a restart or a failed batch is simply continued by the next tick from the same
 * predicate. A failed step is one `event=execution.capture_retention_failed` WARN (class and SQLState only) and one
 * [ScheduledJob.FAILED_TICK_METER] increment (`job=capture_retention`).
 */
class ExecutionCaptureRetention(
    private val store: ExecutionCaptureStore,
    /** The failed-tick counter's registry; null (direct constructions in tests) counts nothing. */
    metrics: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val failedTicks: Counter? =
        metrics?.let { registry ->
            Counter
                .builder(ScheduledJob.FAILED_TICK_METER)
                .description(ScheduledJob.FAILED_TICK_DESCRIPTION)
                .tag(ScheduledJob.JOB_TAG, ScheduledJob.CAPTURE_RETENTION.tag)
                .register(registry)
        }

    /** One retention step; returns the rows deleted (what was deleted before a failure included). */
    @Suppress("SwallowedException") // logged by class and SQLState and counted; the next tick retries
    fun retainOnce(): Int {
        val now = clock.instant()
        var deleted = 0
        try {
            var batches = 0
            var removed: Int
            do {
                removed = store.purgeExpired(now, BATCH_SIZE)
                deleted += removed
                batches++
            } while (removed > 0 && batches < MAX_BATCHES)
        } catch (e: DataAccessException) {
            LOG.warn("event=execution.capture_retention_failed error={} sql_state={}", FailureShape.cause(e), FailureShape.sqlState(e))
            failedTicks?.increment()
        }
        if (deleted > 0) LOG.info("event=execution.capture_purged rows={}", deleted)
        return deleted
    }

    companion object {
        /** Rows per table per batch: one short statement by index, far under the tick's statement bound. */
        const val BATCH_SIZE = 1_000

        /** Batches per tick: a large backlog drains over a few hours instead of holding the shared scheduler thread. */
        const val MAX_BATCHES = 20

        private val LOG = LoggerFactory.getLogger(ExecutionCaptureRetention::class.java)
    }
}
