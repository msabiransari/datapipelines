package co.datapipelines.lake.delivery

import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution-owned landing state. Close the staging database before this resource. The executor
 * invokes [close] in its NonCancellable cleanup so RECEIVED attempts cannot outlive a normal unwind.
 */
class LandedDelivery<T : AutoCloseable> internal constructor(
    private val admitted: AdmittedDelivery<T>,
    val manifest: DeliveryManifest,
    val receipts: List<DeliveryReceipt>,
    private val listing: TrustedDeliveryListing,
    private val ledger: DeliveryLedger,
) : AutoCloseable {
    val reservation: T get() = admitted.reservation
    val deadline: Instant get() = admitted.deadline
    private val closed = AtomicBoolean(false)
    private var failurePhase = LakeDeliveryFailurePhase.STAGING
    private var failureCode: String? = null

    /** Runs only as the staging factory's trusted initializer, after its limits and before its lock. */
    fun initialize(
        connection: Connection,
        materializer: DeliveryMaterializer,
        statements: LakeStatementExecution = LakeStatementExecution.DIRECT,
        configureStorage: (Connection) -> Unit,
    ) {
        configureStorage(connection)
        materializer.materialize(connection, manifest, deadline, statements)
    }

    /** The delivery node's SQL must finish before this re-list, and before any append can start. */
    fun readCompleted() {
        val current = listing.list(manifest.key, deadline)
        if (current.fingerprint != manifest.fingerprint) {
            failureCode = Lake.DELIVERY_CHANGED_DURING_READ
            throw DatapipelinesException(Lake.DELIVERY_CHANGED_DURING_READ, "The delivery changed while staging read its files.")
        }
        failurePhase = LakeDeliveryFailurePhase.VALIDATION
    }

    /** Records a sanitized domain code; the persisted phase follows whether the delivery read finished. */
    fun failed(code: String?) {
        failureCode = code
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            concludeAll()?.let { throw it }
        } finally {
            admitted.close()
        }
    }

    private fun concludeAll(): Exception? {
        var failure: Exception? = null
        receipts.filterIsInstance<DeliveryReceipt.Accepted>().forEach { receipt ->
            concludeReceived(receipt)?.let { stampFailure ->
                if (failure == null) failure = stampFailure else failure.addSuppressed(stampFailure)
            }
        }
        return failure
    }

    // Its frozen RECEIVED state and operation id make this a no-op once publication began.
    @Suppress("TooGenericExceptionCaught") // Attempt every receipt's cleanup even if another stamp failed.
    private fun concludeReceived(receipt: DeliveryReceipt.Accepted): Exception? =
        try {
            ledger.conclude(receipt.delivery, DeliveryConclusion.Failed(failurePhase, failureCode))
            null
        } catch (failure: Exception) {
            failure
        }
}
