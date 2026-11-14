package co.datapipelines.lake.maintenance.inventory

import co.datapipelines.lake.maintenance.cleanup.CleanupObservation
import co.datapipelines.lake.maintenance.cleanup.CleanupObserver
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The storage breakdown, one inventory per cleanup (record §11b call 46; brief 964 D1–D5): the cleanup's own listing and
 * walk, handed over AFTER its expiry and removals, placed in six rows and stored as the table's latest inventory. No
 * second listing, no second walk, no read of the object store. Called only when the cleanup's pass listed (a refused
 * pass makes no call, so the stored rows stay); a stopped pass's inventory is stored like any other.
 *
 * If the rows do not add up to the listing, [InventoryTotalsMismatch] is logged (`lake.inventory.totals_mismatch`, the
 * two totals only) and thrown BEFORE anything is written: the table's previous rows stay and the cleanup records
 * `observer: failed` (962 D2b). Any failure here leaves the cleanup's own outcome as it was.
 */
class StorageInventoryObserver(
    private val repository: StorageInventoryRepository,
    private val classification: StorageClassification = StorageClassifier,
) : CleanupObserver {
    override fun observed(observation: CleanupObservation) {
        val input = ClassifierInput.of(observation)
        val breakdown =
            try {
                StorageBreakdown.of(input.listing, classification.place(input))
            } catch (mismatch: InventoryTotalsMismatch) {
                InventoryEvents.totalsMismatch(observation.tableId, observation.runId, mismatch)
                throw mismatch
            }
        repository.replace(observation.tableId, observation.runId, observation.listingTime, breakdown)
    }
}

/** The inventory's log line (observability §3.4X): ids, counts and bytes — never a key, a location or a message. */
internal object InventoryEvents {
    private val log = LoggerFactory.getLogger(StorageInventoryObserver::class.java)

    fun totalsMismatch(
        tableId: UUID,
        runId: UUID,
        mismatch: InventoryTotalsMismatch,
    ) = log.warn(
        "event=lake.inventory.totals_mismatch table_id={} run_id={} listed_objects={} listed_bytes={} row_objects={} row_bytes={}",
        tableId,
        runId,
        mismatch.listedObjects,
        mismatch.listedBytes,
        mismatch.rowObjects,
        mismatch.rowBytes,
    )
}
