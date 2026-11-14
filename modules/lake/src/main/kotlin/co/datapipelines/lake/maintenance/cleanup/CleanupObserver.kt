package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.remove.ListedObject
import co.datapipelines.lake.maintenance.remove.ReachVisit
import co.datapipelines.lake.maintenance.remove.StopReason
import java.time.Instant
import java.util.UUID

/**
 * What the cleanup hands on after its removals (brief 962 D2b, frozen here; 964 implements it). [observed] is called once,
 * in step 5 of a cleanup, under the table lease, ONLY when the remover's result carries an inventory: a complete pass, or a
 * pass stopped after its listing. A refused pass has no inventory and makes no call, so whatever an implementation stored
 * before stays (absent is never an empty listing).
 *
 * An implementation that throws an [Exception] never changes the cleanup: the part logs `lake.cleanup.observer_failed`,
 * records `observer: failed` and ends as the remover's result says. The default, [NONE], does nothing.
 */
fun interface CleanupObserver {
    fun observed(observation: CleanupObservation)

    companion object {
        val NONE = CleanupObserver { }
    }
}

/**
 * One cleanup's listing, walk, refs and floor (D2b): 964 needs nothing else, and never lists or walks again.
 *
 * - [listingTime]: the catalog's clock read just before the remover's pass (step 4);
 * - [currentSnapshotId], [snapshots], [refs]: the table as loaded AFTER the expiry (no current snapshot, no snapshots and
 *   no refs for a never-published table);
 * - [floor]: what step 3 passed to the expiry, or [ExpiryFloor.NotApplicable] for a never-published table;
 * - [inventory]: the remover's listing minus its confirmed deletes; [visits]: its walk, one per (key, revision, snapshot);
 * - [pass]: complete, or stopped and why.
 */
data class CleanupObservation(
    val runId: UUID,
    val tableId: UUID,
    /** The table's location, `<storage root>/<namespace>/<table id>`. */
    val location: String,
    val listingTime: Instant,
    val currentSnapshotId: Long?,
    val snapshots: List<ObservedSnapshot>,
    val refs: List<ObservedRef>,
    val floor: ExpiryFloor,
    val inventory: List<ListedObject>,
    val visits: List<ReachVisit>,
    val pass: ObservedPass,
)

/** A snapshot the current metadata lists, with its commit time. */
data class ObservedSnapshot(
    val snapshotId: Long,
    val committedAt: Instant,
)

/** A ref of the current metadata: its name, its type and the snapshot it points at. */
data class ObservedRef(
    val name: String,
    val type: ObservedRefType,
    val snapshotId: Long,
)

enum class ObservedRefType(
    val wire: String,
) {
    TAG("tag"),
    BRANCH("branch"),
}

/** The expiry floor a cleanup used: an instant, or no floor at all for a table with no snapshot (never null, never invented). */
sealed interface ExpiryFloor {
    data class Applied(
        val instant: Instant,
    ) : ExpiryFloor

    data object NotApplicable : ExpiryFloor

    /** The run row's and the log's spelling: the ISO instant, or `not_applicable`. */
    val wire: String
        get() =
            when (this) {
                is Applied -> instant.toString()
                NotApplicable -> NOT_APPLICABLE
            }

    companion object {
        const val NOT_APPLICABLE = "not_applicable"
    }
}

/** Whether the pass that produced the inventory was complete, or stopped after its listing (and why). */
sealed interface ObservedPass {
    data object Complete : ObservedPass

    data class Stopped(
        val reason: StopReason,
    ) : ObservedPass
}
