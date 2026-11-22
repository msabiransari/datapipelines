package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.executor.BoundedValueCapture
import co.datapipelines.executor.CaptureValueStore
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Builds the value recorder of a captured published mutation (#1043; published-mutation-apis §6, §7) from the decision
 * its reservation stored at admission — never from the current session state, so a retry, a takeover and a stop after
 * admission all see the one decision the winning reservation owns.
 *
 * [budgetBytes] is `datapipelines.script.max-value-bytes`: the total serialized bytes one execution may keep across
 * every node.
 *
 * It owns the DIAGNOSTIC write scope every recorder drains on: its own supervisor job (no business run's job is its
 * parent, so a business deadline or cancellation never reaches a diagnostic write, and a diagnostic failure never
 * reaches a run) on at most [writers] IO threads, so diagnostic writes together hold at most [writers] metadata
 * connections at a time. [close] — called by the container at shutdown — cancels the scope: a write still queued then
 * is not attempted, and its run reads as incomplete.
 */
class CaptureRecorders(
    private val store: CaptureValueStore,
    private val budgetBytes: Long,
    private val meters: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
    writers: Int = DEFAULT_WRITERS,
) : AutoCloseable {
    init {
        require(budgetBytes > 0) { "the capture byte budget must be positive, was $budgetBytes" }
        require(writers > 0) { "the diagnostic writers must be positive, was $writers" }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // limitedParallelism: stable in use, the bound on metadata connections
    private val writes =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO.limitedParallelism(writers) +
                CoroutineName("execution-capture-writes") +
                CoroutineExceptionHandler { _, e ->
                    LOG.warn("event=execution.capture.write_failed error={}", e.javaClass.simpleName)
                },
        )

    /** The recorder of [reservation], or null when its admission was not captured (the default). */
    fun of(reservation: PublishedMutationReservation): BoundedValueCapture? {
        val session = reservation.captureSessionId ?: return null
        val expiresAt = requireNotNull(reservation.captureExpiresAt) { "a captured reservation carries its detail expiry" }
        return BoundedValueCapture(
            executionId = reservation.executionId,
            workspaceId = reservation.workspaceId,
            sessionId = session,
            expiresAt = expiresAt,
            budgetBytes = budgetBytes,
            store = store,
            writes = writes,
            meters = meters,
            clock = clock,
        )
    }

    override fun close() {
        writes.cancel()
    }

    companion object {
        /** At most this many diagnostic writes run at once, across every captured execution of this instance. */
        const val DEFAULT_WRITERS = 2

        private val LOG = LoggerFactory.getLogger(CaptureRecorders::class.java)
    }
}
