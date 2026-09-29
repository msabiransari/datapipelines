package co.datapipelines.web.metrics

import co.datapipelines.persistence.BatchingHooks
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.persistence.FallbackReason
import co.datapipelines.web.sse.ExecutionStream
import co.datapipelines.web.sse.ExecutionStreamRegistry
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * The surface's own instruments (observability.md §4) — the ones `dag`'s [ExecutorMetrics]
 * does not already own: SSE stream lifecycle, idempotency outcomes, cursor reads.
 *
 * Tag values come from closed sets only (§4.3): `close_reason`, `outcome`, `format`. No user,
 * execution or correlation id ever becomes a tag.
 */
class WebMetrics(
    private val registry: MeterRegistry,
) {
    /** The `datapipelines.sse.streams.active` gauge, bound once to the live registry. */
    fun bindStreams(streams: ExecutionStreamRegistry) {
        Gauge
            .builder(SSE_STREAMS_ACTIVE, streams) { it.activeStreams.toDouble() }
            .description("Currently-open SSE execution streams")
            .register(registry)
        streams.onStreamClosed = { stream -> streamClosed(stream) }
    }

    /** One stream's lifetime, tagged by how it ended. */
    fun streamClosed(stream: ExecutionStream) {
        val reason =
            when {
                // #230 (P4) — decided BEFORE the terminal question: a revoked stream was cut by
                // policy with no terminal event of its own, and counting it as a client
                // disconnect would feed D7's cancellation story a subscriber it never had.
                // #263 — an expired token is the same policy cut (same final comment, the run
                // keeps running) with a different credential fact: its own tag value.
                stream.isRevoked -> {
                    if (stream.isExpired) REASON_EXPIRED else REASON_REVOKED
                }

                else -> {
                    when (stream.terminalKind) {
                        "pipeline_completed" -> REASON_COMPLETED
                        "pipeline_failed" -> REASON_FAILED
                        "execution_aborted" -> REASON_ABORTED
                        else -> REASON_CLIENT_DISCONNECT
                    }
                }
            }
        registry
            .timer(SSE_STREAM_DURATION, "close_reason", reason)
            .record(Duration.ofMillis((System.currentTimeMillis() - stream.openedAtMillis).coerceAtLeast(0)))
    }

    /**
     * #266 — one batching writer's instruments, all tagged `store` (a closed set: `audit`,
     * `execution_events`, `replay_log`): the batch size distribution and commit time, the enqueue-to-
     * commit lag, the queue depth and bytes, failures by `kind`, direct fallbacks by `reason`,
     * retried batches and dropped submissions. Bound once per writer at startup — the audit writer is
     * built in `auth` before this class exists, which is why the writer's hooks are settable.
     */
    fun bindPersistence(writer: BatchingWriter<*>) {
        val store = writer.name
        Gauge
            .builder(PERSISTENCE_QUEUE_DEPTH, writer) { it.queueDepth().toDouble() }
            .description("Items admitted to a batching writer and not yet finished — queued or inside a commit")
            .tag(TAG_STORE, store)
            .register(registry)
        Gauge
            .builder(PERSISTENCE_QUEUE_BYTES, writer) { it.queuedBytes().toDouble() }
            .description("The same, in bytes")
            .tag(TAG_STORE, store)
            .baseUnit("bytes")
            .register(registry)
        val batchSize =
            DistributionSummary
                .builder(PERSISTENCE_BATCH_SIZE)
                .description("Items per committed batch")
                .tag(TAG_STORE, store)
                .register(registry)
        val batchDuration = Timer.builder(PERSISTENCE_BATCH_DURATION).tag(TAG_STORE, store).register(registry)
        val lag =
            Timer
                .builder(PERSISTENCE_LAG)
                .description("Enqueue to durable, per item")
                .tag(TAG_STORE, store)
                .register(registry)
        val retried = registry.counter(PERSISTENCE_BATCHES_RETRIED, TAG_STORE, store)
        val dropped = registry.counter(PERSISTENCE_DROPPED, TAG_STORE, store)
        writer.hooks =
            object : BatchingHooks {
                override fun onBatch(
                    size: Int,
                    bytes: Long,
                    durationNanos: Long,
                ) {
                    batchSize.record(size.toDouble())
                    batchDuration.record(Duration.ofNanos(durationNanos))
                }

                override fun onBatchRetried(size: Int) = retried.increment()

                override fun onCommitted(lagNanos: Long) = lag.record(Duration.ofNanos(lagNanos))

                override fun onFailure(
                    kind: String,
                    count: Int,
                ) = registry.counter(PERSISTENCE_FAILURES, TAG_STORE, store, TAG_KIND, kind).increment(count.toDouble())

                override fun onFallback(reason: FallbackReason) =
                    registry.counter(PERSISTENCE_FALLBACKS, TAG_STORE, store, TAG_REASON, reason.tag).increment()

                override fun onDropped() = dropped.increment()
            }
    }

    /** A request served from a stored idempotency reservation instead of executing. */
    fun idempotencyHit() {
        registry.counter(IDEMPOTENCY_HITS).increment()
    }

    /** An `idempotency.key_reused_for_different_request` rejection. */
    fun idempotencyConflict() {
        registry.counter(IDEMPOTENCY_CONFLICTS).increment()
    }

    /** One cursor read (observability §4: `format` × `outcome` — `hit`/`expired`/`not_found`). */
    fun cursorRead(
        format: String,
        outcome: String,
    ) {
        registry.counter(CURSOR_READS, "format", format, "outcome", outcome).increment()
    }

    companion object {
        const val SSE_STREAMS_ACTIVE = "datapipelines.sse.streams.active"
        const val SSE_STREAM_DURATION = "datapipelines.sse.stream.duration"
        const val IDEMPOTENCY_HITS = "datapipelines.idempotency.cache.hits"
        const val IDEMPOTENCY_CONFLICTS = "datapipelines.idempotency.conflicts"
        const val CURSOR_READS = "datapipelines.result.cursor.reads"

        const val PERSISTENCE_QUEUE_DEPTH = "datapipelines.persistence.queue.depth"
        const val PERSISTENCE_QUEUE_BYTES = "datapipelines.persistence.queue.bytes"
        const val PERSISTENCE_BATCH_SIZE = "datapipelines.persistence.batch.size"
        const val PERSISTENCE_BATCH_DURATION = "datapipelines.persistence.batch.duration"
        const val PERSISTENCE_LAG = "datapipelines.persistence.lag"
        const val PERSISTENCE_FAILURES = "datapipelines.persistence.failures"
        const val PERSISTENCE_FALLBACKS = "datapipelines.persistence.fallbacks"
        const val PERSISTENCE_BATCHES_RETRIED = "datapipelines.persistence.batches.retried"
        const val PERSISTENCE_DROPPED = "datapipelines.persistence.dropped"
        const val TAG_STORE = "store"
        const val TAG_KIND = "kind"
        const val TAG_REASON = "reason"

        const val REASON_COMPLETED = "completed"
        const val REASON_FAILED = "failed"
        const val REASON_ABORTED = "aborted"
        const val REASON_CLIENT_DISCONNECT = "client_disconnect"

        /** #230 (P4): the stream was cut because its subscriber's authority was revoked. */
        const val REASON_REVOKED = "revoked"

        /** #263: the stream was cut because its subscriber's validated token passed its `exp`. */
        const val REASON_EXPIRED = "expired"

        const val OUTCOME_HIT = "hit"
        const val OUTCOME_EXPIRED = "expired"
        const val OUTCOME_NOT_FOUND = "not_found"
    }
}
