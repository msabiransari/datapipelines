package co.datapipelines.lake.delivery

import co.datapipelines.auth.AuditEventSink
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate

/** Atomic delivery/attempt changes; listing and lease takeover always happen outside these short transactions. */
class DeliveryLedger(
    private val repository: DeliveryLedgerRepository,
    private val transaction: TransactionTemplate,
    private val audit: AuditEventSink? = null,
) {
    /** Immutable fingerprint wins before state handling. PUBLISHING and UNKNOWN never become a fresh attempt here. */
    fun receive(request: ReceiveDelivery): DeliveryReceipt =
        requireNotNull(
            transaction.execute {
                repository.insertIfAbsent(request)
                val previous = repository.lock(request)
                if (previous.fingerprint != request.manifest.fingerprint) {
                    DeliveryReceipt.Changed(previous)
                } else {
                    when (previous.state) {
                        LakeDeliveryState.PUBLISHED -> DeliveryReceipt.AlreadyPublished(previous)
                        LakeDeliveryState.PUBLISHING, LakeDeliveryState.UNKNOWN -> DeliveryReceipt.NeedsSettlement(previous)
                        LakeDeliveryState.RECEIVED, LakeDeliveryState.FAILED -> accept(previous, request)
                    }
                }
            },
        )

    private fun accept(
        previous: LakeDelivery,
        request: ReceiveDelivery,
    ): DeliveryReceipt {
        if (previous.operationId != request.operationId) {
            check(repository.restart(previous, request) == 1) { "Locked delivery changed during admission." }
            repository.finishAttempt(previous, LakeDeliveryAttemptOutcome.FAILED)
        } else {
            check(previous.state == LakeDeliveryState.RECEIVED) { "A failed attempt needs a fresh operation id." }
        }
        val received = requireNotNull(repository.find(previous.id))
        repository.insertAttempt(received)
        return DeliveryReceipt.Accepted(received)
    }

    /** The caller already holds this table's lease. False means a stale attempt owns nothing. */
    fun beginPublishing(row: LakeDelivery): Boolean = repository.beginPublishing(row) == 1

    /**
     * Both rows commit together. A stale state's or operation's conclusion changes neither row.
     * A negative verdict after PUBLISHING requires commit classification or takeover evidence from the caller.
     */
    fun conclude(
        row: LakeDelivery,
        conclusion: DeliveryConclusion,
        settled: Boolean = false,
    ): Boolean {
        require(allowed(row.state, conclusion)) { "Invalid delivery conclusion from ${row.state}." }
        val changed =
            transaction.execute {
                if (repository.conclude(row, conclusion) == 0) {
                    false
                } else {
                    val outcome =
                        when (conclusion) {
                            is DeliveryConclusion.Published -> LakeDeliveryAttemptOutcome.PUBLISHED
                            is DeliveryConclusion.Failed -> LakeDeliveryAttemptOutcome.FAILED
                            is DeliveryConclusion.Unknown -> LakeDeliveryAttemptOutcome.UNKNOWN
                        }
                    check(repository.finishAttempt(row, outcome) == 1) { "Delivery attempt is absent or already terminal." }
                    val event =
                        when (conclusion) {
                            is DeliveryConclusion.Published -> LakeDeliveryAuditEvents.PUBLISHED
                            is DeliveryConclusion.Failed -> LakeDeliveryAuditEvents.FAILED
                            is DeliveryConclusion.Unknown -> LakeDeliveryAuditEvents.UNKNOWN
                        }
                    val details =
                        mapOf(
                            "table_id" to row.tableId.toString(),
                            "delivery_id" to row.id.toString(),
                            "operation_id" to row.operationId.toString(),
                            "outcome" to outcome.name,
                        )
                    audit?.log(event, userId = if (settled) null else repository.executionActor(row.executionId), details = details)
                    if (settled) audit?.log(LakeDeliveryAuditEvents.SETTLED, details = details)
                    true
                }
            } == true
        if (changed) {
            log.info(
                "event=lake.delivery.concluded table_id={} delivery_id={} operation_id={} outcome={} settled={} cause=none sql_state=none",
                row.tableId,
                row.id,
                row.operationId,
                conclusion.javaClass.simpleName,
                settled,
            )
        }
        return changed
    }

    private fun allowed(
        state: LakeDeliveryState,
        conclusion: DeliveryConclusion,
    ): Boolean =
        when (state) {
            LakeDeliveryState.RECEIVED -> {
                conclusion is DeliveryConclusion.Failed && conclusion.phase != LakeDeliveryFailurePhase.PUBLISH
            }

            LakeDeliveryState.PUBLISHING, LakeDeliveryState.UNKNOWN -> {
                conclusion !is DeliveryConclusion.Failed || conclusion.phase == LakeDeliveryFailurePhase.PUBLISH
            }

            LakeDeliveryState.PUBLISHED, LakeDeliveryState.FAILED -> {
                false
            }
        }

    private companion object {
        val log = LoggerFactory.getLogger(DeliveryLedger::class.java)
    }
}
