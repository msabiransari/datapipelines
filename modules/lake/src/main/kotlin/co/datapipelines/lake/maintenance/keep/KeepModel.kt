package co.datapipelines.lake.maintenance.keep

import java.time.Instant

/** An Iceberg ref's type: what DP may touch is decided by it, never by the name alone (brief 960 D6). */
enum class KeepRefType {
    TAG,
    BRANCH,
}

/** A ref the table has: its name, TYPE, snapshot and maximum age in milliseconds (null: Iceberg's default). */
data class ExistingRef(
    val name: String,
    val type: KeepRefType,
    val snapshotId: Long,
    val maxRefAgeMs: Long?,
)

/** One entry of the table's snapshot log: from [at] on, [snapshotId] was the current snapshot. */
data class SnapshotLogEntry(
    val at: Instant,
    val snapshotId: Long,
)

/**
 * What the reconcile reads from the table (brief 960 D1): the snapshot log in its order, the commit time of every
 * snapshot the table still has, and every ref with its type.
 */
data class KeepHistory(
    val log: List<SnapshotLogEntry>,
    val commitTimes: Map<Long, Instant>,
    val refs: List<ExistingRef>,
)

/** Why a period asked for has no keep (lake.md "Profiles"; enums.md `KeepNotKeptReason`). */
enum class KeepNotKeptReason(
    val wire: String,
) {
    /** The snapshot log does not reach the period's end, or names a snapshot the table no longer has: no tag, on no snapshot. */
    HISTORY_NOT_AVAILABLE("history_not_available"),

    /** A BRANCH holds the keep's name: no tag is made and the branch is left as it is. */
    NAME_TAKEN_BY_BRANCH("name_taken_by_branch"),
}

/** A tag to create on [snapshotId], ending at [end] (its maximum age is `end − the snapshot's commit time`). */
data class TagCreate(
    val name: String,
    val snapshotId: Long,
    val maxRefAgeMs: Long,
    val end: Instant,
)

/** An existing tag whose maximum age is set again, from [fromMs] to [toMs], for the end now asked for; never moved. */
data class TagAgeChange(
    val name: String,
    val snapshotId: Long,
    val fromMs: Long?,
    val toMs: Long,
    val end: Instant,
)

/** An existing `dp-keep-` tag no rule asks for any more. */
data class TagRemoval(
    val name: String,
    val snapshotId: Long,
)

/** An existing tag that is still asked for and needs no change. [end] is null when its snapshot's commit time is unknown. */
data class TagStanding(
    val name: String,
    val snapshotId: Long,
    val end: Instant?,
)

/** A period asked for that gets no keep, with the reason. */
data class NotKept(
    val name: String,
    val reason: KeepNotKeptReason,
)

/**
 * The reconcile's result (brief 960 D1): what to create, re-age and remove, what stands unchanged, and the periods
 * reported with a reason. Each list is sorted by name. Only [hasChanges] plans are committed.
 */
data class KeepPlan(
    val creates: List<TagCreate>,
    val ageChanges: List<TagAgeChange>,
    val removals: List<TagRemoval>,
    val standing: List<TagStanding>,
    val notKept: List<NotKept>,
) {
    /** At least one create, age change or removal: the only plans [KeepTags] commits. */
    val hasChanges: Boolean
        get() = creates.isNotEmpty() || ageChanges.isNotEmpty() || removals.isNotEmpty()

    /** The keeps live once this plan is applied: created, re-aged and standing. */
    val liveNames: Set<String>
        get() = (creates.map { it.name } + ageChanges.map { it.name } + standing.map { it.name }).toSet()

    companion object {
        val EMPTY = KeepPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
