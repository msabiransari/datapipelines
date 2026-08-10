package co.datapipelines.events

import org.slf4j.LoggerFactory

/**
 * Where the executor publishes execution events (dag-executor.md §10).
 *
 * The implementation routes each event to the live SSE stream (if a consumer is attached), the
 * post-completion Redis event log owned by `web`, and the durable `execution_events` record. None
 * of that is the executor's business — it emits, unconditionally and in order.
 *
 * **An emitter must never block on a reader.** Events keep flowing while nobody is listening; a
 * missing consumer is handled by the SSE layer's disconnect-grace timer (§8.3), not by
 * backpressure into the executor.
 */
fun interface EventEmitter {
    /** Publishes [event]. Implementations must not throw for "nobody is listening". */
    suspend fun emit(event: ExecutionEvent)

    companion object {
        /** Discards every event — the default for tests and for a deployment with no consumers. */
        val NONE = EventEmitter { }

        /** Logs each event at DEBUG. Useful in dev; never the production emitter. */
        fun logging(): EventEmitter =
            EventEmitter { event ->
                LoggerFactory
                    .getLogger(EventEmitter::class.java)
                    .debug("execution {} event {}", event.executionId, event.type.wire)
            }
    }
}
