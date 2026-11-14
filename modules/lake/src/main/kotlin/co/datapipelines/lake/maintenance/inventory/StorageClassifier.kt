package co.datapipelines.lake.maintenance.inventory

import co.datapipelines.lake.maintenance.cleanup.CleanupObservation
import co.datapipelines.lake.maintenance.cleanup.ExpiryFloor
import co.datapipelines.lake.maintenance.cleanup.ObservedRef
import co.datapipelines.lake.maintenance.cleanup.ObservedRefType
import co.datapipelines.lake.maintenance.cleanup.ObservedSnapshot
import co.datapipelines.lake.maintenance.keep.KeepCalendar
import co.datapipelines.lake.maintenance.remove.FileKind
import co.datapipelines.lake.maintenance.remove.ListedObject

/** How the cleanup's walk reached one object key: every kind it was reached as, every snapshot it was reached through. */
data class ReachedKey(
    val kinds: Set<FileKind>,
    val snapshotIds: Set<Long>,
)

/**
 * The classifier's whole input (brief 964 D3): the listing as it stands after the cleanup's removals, the walk folded to
 * object key → [ReachedKey], the current snapshot id, the snapshots the current metadata lists with their commit times,
 * the refs, the floor the cleanup's expiry used, and the table's location. Nothing here is read from the store.
 */
data class ClassifierInput(
    /** `s3://<bucket>/<prefix><namespace>/<table id>`, as the cleanup hands it. */
    val location: String,
    val listing: List<ListedObject>,
    val reached: Map<String, ReachedKey>,
    val currentSnapshotId: Long?,
    val snapshots: List<ObservedSnapshot>,
    val refs: List<ObservedRef>,
    val floor: ExpiryFloor,
) {
    companion object {
        /** The input of one cleanup's observation; a visit outside the table's location names no listed key and is dropped. */
        fun of(observation: CleanupObservation): ClassifierInput {
            val keys = TableLocation.parse(observation.location)
            val kinds = HashMap<String, MutableSet<FileKind>>()
            val snapshots = HashMap<String, MutableSet<Long>>()
            observation.visits.forEach { visit ->
                val key = keys.keyOf(visit.location) ?: return@forEach
                kinds.getOrPut(key) { HashSet() } += visit.kind
                visit.snapshotId?.let { snapshots.getOrPut(key) { HashSet() } += it }
            }
            return ClassifierInput(
                location = observation.location,
                listing = observation.inventory,
                reached = kinds.mapValues { (key, k) -> ReachedKey(k, snapshots[key].orEmpty()) },
                currentSnapshotId = observation.currentSnapshotId,
                snapshots = observation.snapshots,
                refs = observation.refs,
                floor = observation.floor,
            )
        }
    }
}

/** One listed object and the row it was placed in. */
data class Placement(
    val listed: ListedObject,
    val row: StorageRow,
)

/** The placement step behind the breakdown: production is [StorageClassifier]; a test may stand one in (B8's mismatch). */
fun interface StorageClassification {
    fun place(input: ClassifierInput): List<Placement>
}

/**
 * Record §11b call 46's six rows, first fit (brief 964 D2): one [Placement] per listed object, in listing order. A pure
 * function: the same input gives the same placements. Figures only — nothing in the remover or the cleanup reads it to
 * choose a key (D7).
 *
 * A snapshot is **keep-held** when the current metadata lists it, its commit time is before the floor this cleanup's
 * expiry used (an applied floor; a table with no floor has none), and a `dp-keep-` or `dp-hold-` TAG points at it. Every
 * other snapshot the walk reached a file through is a **history** snapshot, a tagged one the window still covers
 * included; an earlier protected revision listing a keep-held snapshot does not make it history (it is one snapshot id).
 * An object the rows cannot place goes to [StorageRow.NOT_REFERENCED] (the silence rule).
 */
object StorageClassifier : StorageClassification {
    /** The tag prefix of a hold (record call 43); a keep's is [KeepCalendar.PREFIX]. */
    const val HOLD_PREFIX = "dp-hold-"

    private const val DP_DIR = "dp/"
    private val METADATA_KINDS =
        setOf(FileKind.METADATA, FileKind.MANIFEST_LIST, FileKind.MANIFEST, FileKind.STATISTICS, FileKind.PARTITION_STATISTICS)
    private val CONTENT_KINDS = setOf(FileKind.DATA, FileKind.DELETE)

    override fun place(input: ClassifierInput): List<Placement> {
        val prefix = TableLocation.parse(input.location).keyPrefix
        val keepHeld = keepHeld(input)
        return input.listing.map { listed -> Placement(listed, rowOf(listed.key, prefix, input, keepHeld)) }
    }

    /** The snapshot ids that are keep-held in this input (D2). */
    fun keepHeld(input: ClassifierInput): Set<Long> {
        val floor = (input.floor as? ExpiryFloor.Applied)?.instant ?: return emptySet()
        val tagged =
            input.refs
                .filter { it.type == ObservedRefType.TAG && (it.name.startsWith(KeepCalendar.PREFIX) || it.name.startsWith(HOLD_PREFIX)) }
                .map { it.snapshotId }
                .toSet()
        return input.snapshots
            .filter { it.committedAt.isBefore(floor) && it.snapshotId in tagged }
            .map { it.snapshotId }
            .toSet()
    }

    private fun rowOf(
        key: String,
        prefix: String,
        input: ClassifierInput,
        keepHeld: Set<Long>,
    ): StorageRow {
        if (!key.startsWith(prefix)) return StorageRow.NOT_REFERENCED
        if (key.removePrefix(prefix).startsWith(DP_DIR)) return StorageRow.DP_RECORDS
        val reach = input.reached[key] ?: return StorageRow.NOT_REFERENCED
        val snapshots = reach.snapshotIds
        return when {
            reach.kinds.any { it in METADATA_KINDS } -> StorageRow.TABLE_METADATA
            reach.kinds.none { it in CONTENT_KINDS } || snapshots.isEmpty() -> StorageRow.NOT_REFERENCED
            input.currentSnapshotId != null && input.currentSnapshotId in snapshots -> StorageRow.CURRENT
            snapshots.any { it !in keepHeld } -> StorageRow.HISTORY
            else -> StorageRow.KEEP_HELD
        }
    }
}

/**
 * A table location `s3://<bucket>/<path>` split into its bucket and its object-key prefix (`<path>/`), and the object key
 * of a location the walk reached — the same rule as the remover's `RemovalTarget.keyOf` (`s3://`, `s3a://`, `s3n://`
 * of this bucket; anything else names no key of this table). A parity test holds the two together.
 */
data class TableLocation(
    val bucket: String,
    val keyPrefix: String,
) {
    fun keyOf(location: String): String? {
        val scheme = SCHEMES.firstOrNull { location.startsWith(it) } ?: return null
        val rest = location.removePrefix(scheme)
        val slash = rest.indexOf('/')
        if (slash <= 0 || rest.substring(0, slash) != bucket) return null
        return rest.substring(slash + 1)
    }

    companion object {
        private val SCHEMES = listOf("s3://", "s3a://", "s3n://")

        fun parse(location: String): TableLocation {
            val scheme = SCHEMES.firstOrNull { location.startsWith(it) }
            require(scheme != null) { "a table location is an s3 location" }
            val rest = location.removePrefix(scheme).trimEnd('/')
            val slash = rest.indexOf('/')
            require(slash > 0 && slash < rest.length - 1) { "a table location names a bucket and a path" }
            return TableLocation(rest.substring(0, slash), rest.substring(slash + 1) + "/")
        }
    }
}
