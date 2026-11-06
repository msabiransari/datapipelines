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
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Settles permanent delivery attempts (§8b). Expiry alone is never a negative commit verdict:
 * takeover must finish before the separate revision-log read. Caller supplies its fixed deadline.
 * All injected SQL collaborators, including [leases], must use dedicated bounded JDBC templates.
 *
 * Per-tick work bound (§8b, lane 799b): the work [SettleBudget] is consulted before every row (that is, before the row's
 * first observation), before every later statement group inside it — the RECEIVED conclusion; the PUBLISHING takeover,
 * evidence read and conclusion — and before the projection repair. Once it is spent no new statement group starts; the
 * group already running finishes under its own statement timeouts (the longest, a lease acquire, is two statements plus
 * a deadline comparison when refused), and a takeover already taken is still released (one statement).
 */
class DeliverySettler(
    private val observations: DeliverySettlementRepository,
    private val deliveries: DeliveryLedgerRepository,
    private val ledger: DeliveryLedger,
    private val leases: LakeLeases,
    private val projection: LakeProjectionRepair,
) {
    /**
     * Settles one keyset page of [tableId]'s unsettled deliveries: at most [limit] rows after [afterId] in id order.
     * Busy/live attempts stay unsettled and are passed over. A failing row ends the page with [SettlementDeferred],
     * whose progress resumes after that row; the projection repair runs only while [budget] remains.
     */
    @Suppress("TooGenericExceptionCaught") // A failed repair is reported with the page's cursor, never swallowed.
    fun settle(
        tableId: UUID,
        budget: SettleBudget,
        afterId: UUID?,
        limit: Int,
    ): SettleProgress {
        require(limit > 0) { "settlement page must be positive: $limit" }
        requireOwnStatements()
        val target = observations.target(tableId) ?: return SettleProgress(0, 0, null)
        val page = deliveries.unsettled(tableId, afterId, limit)
        val walked = walk(target, page, budget, afterId)
        val progress = if (walked.complete && page.size < limit) walked.progress.copy(resumeAfter = null) else walked.progress
        if (!budget.expired()) {
            try {
                // Also runs when a previous worker committed and stamped PUBLISHED but died before repair.
                projection.repair(target)
            } catch (failure: Exception) {
                throw SettlementDeferred(progress, failure)
            }
        }
        return progress
    }

    /** Visits [page] in order; stops before the first row the spent [budget] does not let start. */
    private fun walk(
        target: DeliverySettlementTarget,
        page: List<LakeDelivery>,
        budget: SettleBudget,
        afterId: UUID?,
    ): Walk {
        var progress = SettleProgress(0, 0, afterId)
        for (row in page) {
            val outcome = settleRowOrDefer(target, row, budget, progress)
            if (outcome == RowOutcome.OUT_OF_BUDGET) return Walk(progress, complete = false)
            progress = SettleProgress(progress.visited + 1, progress.changed + if (outcome == RowOutcome.CHANGED) 1 else 0, row.id)
        }
        return Walk(progress, complete = true)
    }

    @Suppress("TooGenericExceptionCaught") // A failing row is reported with the cursor that passes it, never swallowed.
    private fun settleRowOrDefer(
        target: DeliverySettlementTarget,
        row: LakeDelivery,
        budget: SettleBudget,
        before: SettleProgress,
    ): RowOutcome =
        try {
            settleRow(target, row, budget)
        } catch (failure: Exception) {
            throw SettlementDeferred(before.copy(resumeAfter = row.id), failure)
        }

    /**
     * Settles the one delivery a request-path caller is blocked on, within that caller's database-clock [deadline]
     * (the admission or append deadline): the same row branches and budget checks as [settle], then the projection repair
     * while the budget remains. The table's other unsettled rows are the sweep's. Failures propagate unchanged.
     */
    fun settleDelivery(
        deliveryId: UUID,
        deadline: Instant,
    ): Int {
        requireOwnStatements()
        val budget = SettleBudget.until(leases, deadline)
        if (budget.expired()) return 0
        val row = deliveries.find(deliveryId) ?: return 0
        val target = observations.target(row.tableId) ?: return 0
        val outcome = settleRow(target, row, budget)
        if (!budget.expired()) projection.repair(target)
        return if (outcome == RowOutcome.CHANGED) 1 else 0
    }

    /** The before-row check: a spent [budget] starts no row, so not even its first observation runs. */
    private fun settleRow(
        target: DeliverySettlementTarget,
        row: LakeDelivery,
        budget: SettleBudget,
    ): RowOutcome {
        if (budget.expired()) return RowOutcome.OUT_OF_BUDGET
        return when (row.state) {
            LakeDeliveryState.RECEIVED -> failEndedExecution(row, budget)
            LakeDeliveryState.PUBLISHING, LakeDeliveryState.UNKNOWN -> settlePublishing(target, row, budget)
            LakeDeliveryState.PUBLISHED, LakeDeliveryState.FAILED -> RowOutcome.UNCHANGED
        }
    }

    private fun failEndedExecution(
        row: LakeDelivery,
        budget: SettleBudget,
    ): RowOutcome {
        if (!observations.executionEnded(row.executionId)) return RowOutcome.UNCHANGED
        if (budget.expired()) return RowOutcome.OUT_OF_BUDGET
        return changedIf(ledger.conclude(row, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.VALIDATION, null), settled = true))
    }

    @Suppress("ReturnCount") // Each spent-budget check exits before its statement group; the release still runs.
    private fun settlePublishing(
        target: DeliverySettlementTarget,
        row: LakeDelivery,
        budget: SettleBudget,
    ): RowOutcome {
        if (observations.attemptLeaseLive(target, row.operationId)) return RowOutcome.UNCHANGED
        if (budget.expired()) return RowOutcome.OUT_OF_BUDGET
        val takeover = UUID.randomUUID()
        when (leases.acquire(LeaseKind.TABLE, target.leaseName, takeover, SETTLE_TTL, budget.deadline, "settle")) {
            is LeaseOutcome.Acquired -> Unit
            LeaseOutcome.Busy, LeaseOutcome.DeadlinePassed -> return RowOutcome.UNCHANGED
        }
        try {
            if (budget.expired()) return RowOutcome.OUT_OF_BUDGET
            // This read MUST follow the takeover's commit, in a separate statement and fresh snapshot.
            val evidence = deliveries.committedEvidence(row.id, row.operationId)
            if (budget.expired()) return RowOutcome.OUT_OF_BUDGET
            val conclusion =
                if (evidence != null) {
                    DeliveryConclusion.Published(evidence.metadataLocation)
                } else {
                    DeliveryConclusion.Failed(LakeDeliveryFailurePhase.PUBLISH, null)
                }
            return changedIf(ledger.conclude(row, conclusion, settled = true))
        } finally {
            leases.release(takeover)
        }
    }

    /** Refused before any statement: inside a caller's transaction a verdict would read that transaction's snapshot. */
    private fun requireOwnStatements() =
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Settlement runs its own statements; a surrounding transaction would read a stale snapshot."
        }

    private fun changedIf(concluded: Boolean): RowOutcome = if (concluded) RowOutcome.CHANGED else RowOutcome.UNCHANGED

    /** A row's settlement: a terminal transition, no change (live, busy, running or lost the race), or budget spent. */
    private enum class RowOutcome { CHANGED, UNCHANGED, OUT_OF_BUDGET }

    /** A page walk's progress; [complete] when every row of the page was visited. */
    private data class Walk(
        val progress: SettleProgress,
        val complete: Boolean,
    )

    private companion object {
        val SETTLE_TTL = Duration.ofMinutes(2)
    }
}
