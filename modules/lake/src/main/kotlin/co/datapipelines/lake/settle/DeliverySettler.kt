package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.delivery.DeliveryConclusion
import co.datapipelines.lake.delivery.DeliveryLedger
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.lake.delivery.LakeDelivery
import co.datapipelines.lake.delivery.LakeDeliveryFailurePhase
import co.datapipelines.lake.delivery.LakeDeliveryState
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Settles permanent delivery attempts (§8b). Expiry alone is never a negative commit verdict:
 * takeover must finish before the separate revision-log read. Caller supplies its fixed deadline.
 * All injected SQL collaborators, including [leases], must use dedicated bounded JDBC templates.
 */
class DeliverySettler(
    private val observations: DeliverySettlementRepository,
    private val deliveries: DeliveryLedgerRepository,
    private val ledger: DeliveryLedger,
    private val leases: LakeLeases,
) {
    /** Returns the number of changed delivery rows; busy/live attempts remain unsettled. */
    fun settle(
        tableId: UUID,
        deadline: Instant,
    ): Int {
        val target = observations.target(tableId) ?: return 0
        return deliveries.unsettled(tableId).sumOf { row ->
            when (row.state) {
                LakeDeliveryState.RECEIVED -> failEndedExecution(row)
                LakeDeliveryState.PUBLISHING, LakeDeliveryState.UNKNOWN -> settlePublishing(target, row, deadline)
                LakeDeliveryState.PUBLISHED, LakeDeliveryState.FAILED -> 0
            }
        }
    }

    private fun failEndedExecution(row: LakeDelivery): Int {
        if (!observations.executionEnded(row.executionId)) return 0
        return if (ledger.conclude(row, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.VALIDATION, null))) 1 else 0
    }

    private fun settlePublishing(
        target: DeliverySettlementTarget,
        row: LakeDelivery,
        deadline: Instant,
    ): Int {
        if (observations.attemptLeaseLive(target, row.operationId)) return 0
        val takeover = UUID.randomUUID()
        when (leases.acquire(LeaseKind.TABLE, target.leaseName, takeover, SETTLE_TTL, deadline, "settle")) {
            is LeaseOutcome.Acquired -> Unit
            LeaseOutcome.Busy, LeaseOutcome.DeadlinePassed -> return 0
        }
        try {
            // This read MUST follow the takeover's commit, in a separate statement and fresh snapshot.
            val evidence = deliveries.committedEvidence(row.id, row.operationId)
            val conclusion =
                if (evidence != null) {
                    DeliveryConclusion.Published(evidence.metadataLocation)
                } else {
                    DeliveryConclusion.Failed(LakeDeliveryFailurePhase.PUBLISH, null)
                }
            return if (ledger.conclude(row, conclusion)) 1 else 0
        } finally {
            leases.release(takeover)
        }
    }

    private companion object {
        val SETTLE_TTL = Duration.ofMinutes(2)
    }
}
