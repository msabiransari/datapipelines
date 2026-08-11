package co.datapipelines.web.metrics

import co.datapipelines.web.sse.ExecutionStream
import co.datapipelines.web.sse.ExecutionStreamRegistry
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
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
            when (stream.terminalKind) {
                "pipeline_completed" -> REASON_COMPLETED
                "pipeline_failed" -> REASON_FAILED
                "execution_aborted" -> REASON_ABORTED
                else -> REASON_CLIENT_DISCONNECT
            }
        registry
            .timer(SSE_STREAM_DURATION, "close_reason", reason)
            .record(Duration.ofMillis((System.currentTimeMillis() - stream.openedAtMillis).coerceAtLeast(0)))
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

        const val REASON_COMPLETED = "completed"
        const val REASON_FAILED = "failed"
        const val REASON_ABORTED = "aborted"
        const val REASON_CLIENT_DISCONNECT = "client_disconnect"

        const val OUTCOME_HIT = "hit"
        const val OUTCOME_EXPIRED = "expired"
        const val OUTCOME_NOT_FOUND = "not_found"
    }
}
