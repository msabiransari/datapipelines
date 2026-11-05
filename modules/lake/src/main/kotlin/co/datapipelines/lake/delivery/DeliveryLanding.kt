package co.datapipelines.lake.delivery

import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.LakeTableStatus
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/** One qualified managed target, resolved from the pipeline's append outputs. */
data class DeliveryTarget(
    val namespace: String,
    val table: String,
)

/** Admission and immutable delivery identity, before the executor opens its staging database. */
class DeliveryLanding(
    private val admission: DeliveryAdmission,
    private val listing: TrustedDeliveryListing,
    private val definitions: LakeDefinitionRepository,
    private val ledger: DeliveryLedger,
    private val settler: DeliverySettler,
) {
    /**
     * [reserve] reserves scratch without opening a database. Only scratch exhaustion returns null.
     * Ownership transfers to the result; every failure here releases the slot and unopened scratch.
     */
    @Suppress("TooGenericExceptionCaught") // Preserve the primary refusal while conditionally closing partial admission.
    fun <T : AutoCloseable> land(
        key: DeliveryKey,
        targets: List<DeliveryTarget>,
        executionId: UUID,
        pipelineId: UUID,
        reserve: (Long) -> T?,
    ): LandedDelivery<T> {
        val admitted = admission.acquire(reserve) ?: throw refusal(Lake.CAPACITY_BUSY, "Delivery admission capacity is busy.", true)
        val accepted = mutableListOf<DeliveryReceipt>()
        try {
            val manifest = listing.list(key, admitted.deadline)
            targets.forEach { target ->
                accepted += receive(target, manifest, executionId, pipelineId, admitted.deadline)
            }
            return LandedDelivery(admitted, manifest, accepted.toList(), listing, ledger)
        } catch (failure: Throwable) {
            try {
                failReceived(accepted, LakeDeliveryFailurePhase.LISTING, (failure as? DatapipelinesException)?.code)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            try {
                admitted.close()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    @Suppress("ThrowsCount") // Distinct target status and immutable-identity refusals retain their catalogued codes.
    private fun receive(
        target: DeliveryTarget,
        manifest: DeliveryManifest,
        executionId: UUID,
        pipelineId: UUID,
        deadline: Instant,
    ): DeliveryReceipt {
        val table = definitions.findTable(target.namespace, target.table) ?: throw unavailable()
        when (table.status) {
            LakeTableStatus.ACTIVE -> Unit
            LakeTableStatus.DEPRECATED -> throw refusal(Lake.TABLE_DEPRECATED, "A deprecated table does not accept deliveries.")
            LakeTableStatus.ARCHIVED, LakeTableStatus.PURGED -> throw unavailable()
        }
        val request = ReceiveDelivery(table.id, manifest, UUID.randomUUID(), executionId, pipelineId)
        var receipt = ledger.receive(request)
        if (receipt is DeliveryReceipt.NeedsSettlement) {
            settler.settle(table.id, deadline)
            receipt = ledger.receive(request)
        }
        return when (receipt) {
            is DeliveryReceipt.Accepted, is DeliveryReceipt.AlreadyPublished -> receipt
            is DeliveryReceipt.Changed -> throw refusal(Lake.DELIVERY_CHANGED, "This delivery id already names different input files.")
            is DeliveryReceipt.NeedsSettlement -> throw refusal(Lake.TABLE_BUSY, "The table has an unconfirmed publication.", true)
        }
    }

    private fun failReceived(
        receipts: List<DeliveryReceipt>,
        phase: LakeDeliveryFailurePhase,
        code: String?,
    ) {
        receipts.filterIsInstance<DeliveryReceipt.Accepted>().forEach {
            ledger.conclude(
                it.delivery,
                DeliveryConclusion.Failed(phase, code),
            )
        }
    }

    private fun unavailable() = refusal(Lake.TABLE_UNAVAILABLE, "The managed table is unavailable for delivery ingestion.")

    private fun refusal(
        code: String,
        message: String,
        retryable: Boolean = false,
    ) = DatapipelinesException(code, message, mapOf("retryable" to retryable))
}
