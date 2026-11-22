package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.executor.BoundedValueCapture
import co.datapipelines.executor.CaptureValueStore
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

/**
 * Builds the value recorder of a captured published mutation (#1043; published-mutation-apis §6, §7) from the decision
 * its reservation stored at admission — never from the current session state, so a retry, a takeover and a stop after
 * admission all see the one decision the winning reservation owns.
 *
 * [budgetBytes] is `datapipelines.script.max-value-bytes`: the total serialized bytes one execution may keep across
 * every node.
 */
class CaptureRecorders(
    private val store: CaptureValueStore,
    private val budgetBytes: Long,
    private val meters: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(budgetBytes > 0) { "the capture byte budget must be positive, was $budgetBytes" }
    }

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
            meters = meters,
            clock = clock,
        )
    }
}
