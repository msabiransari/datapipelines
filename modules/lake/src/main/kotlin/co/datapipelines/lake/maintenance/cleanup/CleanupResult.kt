package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.PartFigures
import co.datapipelines.lake.maintenance.keep.KeepNotKeptReason
import co.datapipelines.lake.maintenance.remove.HintReason
import co.datapipelines.lake.maintenance.remove.LeftReason
import co.datapipelines.lake.maintenance.remove.PurgeAnswer
import co.datapipelines.lake.maintenance.remove.SkewInterval
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/** Whether the cleanup's observer was called and how it ended (D2b, D3). */
enum class ObserverOutcome(
    val wire: String,
) {
    OK("ok"),
    FAILED("failed"),
    NOT_CALLED("not_called"),
}

/**
 * What a cleanup or a purge deletion did (brief 962 D3), in counts only: never a key, never a credential. It goes on the
 * run row twice: [figures] (957's sums: files = keys deleted, bytes = bytes deleted, deferred deletions = [deferred]) and
 * [details] (the part's own fields in its `parts_json` entry). A part that throws writes the same counts in
 * `lake.cleanup.stopped` instead, because a failed run records no figures (957).
 */
data class CleanupFigures(
    val tagsCreated: Int = 0,
    val tagsChanged: Int = 0,
    val tagsRemoved: Int = 0,
    val notKept: Map<KeepNotKeptReason, Int> = emptyMap(),
    val tagCommitted: Boolean = false,
    val snapshotsExpired: Int = 0,
    val expiryCommitted: Boolean = false,
    /** The floor step 3 used; null until the expiry step was reached. */
    val floor: ExpiryFloor? = null,
    val listed: Int = 0,
    val deleted: Int = 0,
    val bytesDeleted: Long = 0,
    val left: Map<LeftReason, Int> = emptyMap(),
    val hintsDeferred: Map<HintReason, Int> = emptyMap(),
    val observer: ObserverOutcome = ObserverOutcome.NOT_CALLED,
    /** A purge deletion's answer; null for a cleanup and for a purge that did not list. */
    val purge: PurgeAnswer? = null,
    /** True when this pass stamped `purge_deleted_at`. */
    val stamped: Boolean = false,
) {
    /**
     * The keys left that a later pass may still delete (record §11a "When a purge deletion is finished": young, a marker
     * whose run is live or unknown, a failed or unknown delete, a batch not attempted). A reached key is the table's own
     * file and a recipe is never deleted: neither is a deferred deletion.
     */
    val deferred: Long
        get() = left.filterKeys { it in DEFERRED }.values.sumOf { it.toLong() }

    /** A tag commit, an expiry commit or a deletion happened: the part answers `done` rather than `nothing to do`. */
    val didWork: Boolean
        get() = tagCommitted || expiryCommitted || deleted > 0

    fun figures(): PartFigures = PartFigures(files = deleted.toLong(), bytes = bytesDeleted, deferredDeletions = deferred)

    /**
     * The part's own `parts_json` fields ([co.datapipelines.lake.maintenance.PartResult.details]: scalars only, named
     * `[a-z_]+`). A count by reason is one key per reason that occurred (`left_young`, `not_kept_history_not_available`,
     * `hints_deferred_reached`); a value not known yet (the floor before the expiry step, a cleanup's purge answer) is
     * absent, never null.
     */
    fun details(): Map<String, Any> =
        buildMap {
            put("tags_created", tagsCreated)
            put("tags_changed", tagsChanged)
            put("tags_removed", tagsRemoved)
            notKept.forEach { (reason, count) -> put("not_kept_${reason.wire}", count) }
            put("snapshots_expired", snapshotsExpired)
            floor?.let { put("floor", it.wire) }
            put("listed", listed)
            put("deleted", deleted)
            left.forEach { (reason, count) -> put("left_${reason.wire}", count) }
            hintsDeferred.forEach { (reason, count) -> put("hints_deferred_${reason.wire}", count) }
            put("observer", observer.wire)
            purge?.let { put("purge", it.wire) }
            put("purge_stamped", stamped)
        }

    companion object {
        val DEFERRED: Set<LeftReason> =
            setOf(
                LeftReason.YOUNG,
                LeftReason.MARKER_RUN_LIVE,
                LeftReason.MARKER_RUN_UNKNOWN,
                LeftReason.DELETE_FAILED,
                LeftReason.OUTCOME_UNKNOWN,
                LeftReason.NOT_ATTEMPTED,
            )
    }
}

/** `details.reason` of `lake.maintenance.cleanup_incomplete` (D2a). */
enum class IncompleteReason(
    val wire: String,
) {
    /** The catalog left `normal` mode: before the part's first write, at the remover's start, or between two batches. */
    MODE("mode"),

    /** A delete failed or its outcome is unknown, or the pass's own marker could not be removed as planned. */
    DELETE_FAILED("delete_failed"),
}

/**
 * `lake.maintenance.cleanup_incomplete` (D2a): the removal did not finish. The run ends `failed` with this code; what was
 * committed before stays, nothing more is deleted, and the next plan finds the work due again.
 */
class CleanupIncomplete(
    val reason: IncompleteReason,
) : DatapipelinesException(
        PipelineErrorCodes.Lake.MAINTENANCE_CLEANUP_INCOMPLETE,
        "the cleanup did not finish its removal (${reason.wire})",
        mapOf("reason" to reason.wire),
    )

/** `lake.maintenance.clock_skew` (D2a): the remover's probe refused the pass; nothing was listed or deleted. */
class CleanupClockSkew(
    skew: SkewInterval?,
) : DatapipelinesException(
        PipelineErrorCodes.Lake.MAINTENANCE_CLOCK_SKEW,
        "the remover refused the pass: the catalog and the object store disagree beyond the allowed skew",
        mapOf("skew_lower_s" to skew?.lower?.seconds, "skew_upper_s" to skew?.upper?.seconds),
    )
