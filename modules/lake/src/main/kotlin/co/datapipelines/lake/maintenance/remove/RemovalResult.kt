package co.datapipelines.lake.maintenance.remove

import co.datapipelines.pipeline.PipelineErrorCodes

/** Why a listed key was not deleted by this pass (D8, D9a). [wire] is the word a result, a log line and 964 use. */
enum class LeftReason(
    val wire: String,
    /** Whether this key can still be deleted by a later pass: such a key keeps a purge at *more to do* (D8). */
    val blocksPurge: Boolean,
) {
    YOUNG("young", true),
    REACHED("reached", true),
    MARKER_RUN_LIVE("marker_run_live", true),
    MARKER_RUN_UNKNOWN("marker_run_unknown", true),
    OTHER_DP_KEY("other_dp_key", true),
    DELETE_FAILED("delete_failed", true),
    OUTCOME_UNKNOWN("outcome_unknown", true),
    NOT_ATTEMPTED("not_attempted", true),

    /** Under `dp/recipes/`: never deleted, by either pass, at any age (§8b); the one reason that lets a purge finish. */
    RECIPE("recipe", false),
}

/** Why a queued library hint was not deleted (D4: hints are decided by the same tests after the fresh read). */
enum class HintReason(
    val wire: String,
) {
    OUTSIDE_LOCATION("outside_location"),
    UNDER_DP("under_dp"),
    REACHED("reached"),
    YOUNG("young"),

    /** Passed every test but was not in the listing (already gone): nothing to delete. */
    NOT_LISTED("not_listed"),
}

/** Why a pass stopped before its last batch (D9a). */
enum class StopReason(
    val wire: String,
) {
    LEASE_LOST("lease_lost"),
    MODE_CHANGED("mode_changed"),
    DELETE_FAILED("delete_failed"),
}

/** What happened to the marker this pass PUT (D5). */
sealed interface MarkerFate {
    /** The pass never wrote one (refused before the probe). */
    data object NotWritten : MarkerFate

    /** Its delete was confirmed by the store's `deleted()`. */
    data object Removed : MarkerFate

    /** Still in the store (or not known to be gone): a later pass clears it once this run is terminal (D5). */
    data class Left(
        val reason: MarkerLeftReason,
    ) : MarkerFate
}

enum class MarkerLeftReason(
    val wire: String,
) {
    LEASE_LOST("lease_lost"),
    MODE_CHANGED("mode_changed"),
    CHECK_FAILED("check_failed"),
    DELETE_UNCONFIRMED("delete_unconfirmed"),
}

/** Why a pass did nothing (no listing, no inventory, no visits — D10). */
enum class RefusalReason(
    val wire: String,
    /** The catalogued code, where the refusal has one (pipeline-contract §13.25). */
    val code: String?,
) {
    /** `lake_control.mode` is not `normal` (`restoring`, `replaying`): nothing listed, nothing written. */
    MODE_NOT_NORMAL("mode_not_normal", null),

    /** The caller's lease did not answer at the start: nothing listed, nothing written. */
    LEASE_LOST("lease_lost", null),

    /** The skew interval left ±3600 s (D6, the cut's L7 spelling). */
    CLOCK_SKEW("clock_skew", PipelineErrorCodes.Lake.MAINTENANCE_CLOCK_SKEW),

    /** A purge of a table whose status is not `purged` (D8). */
    NOT_PURGED("not_purged", null),
}

/** A purge pass's answer (record §11a "When a purge deletion is finished"). */
enum class PurgeAnswer(
    val wire: String,
) {
    FINISHED("finished"),
    MORE_TO_DO("more_to_do"),
}

/** A pass's outcome. Neither shape throws for a delete failure; a walk or listing failure throws and deletes nothing. */
sealed interface RemovalResult {
    data class Refused(
        val reason: RefusalReason,
        val marker: MarkerFate,
        /** The probe's interval for [RefusalReason.CLOCK_SKEW]; null otherwise. */
        val skew: SkewInterval? = null,
    ) : RemovalResult

    /**
     * A pass that listed. [inventory] is the listing minus the keys whose deletion was confirmed (the location as the pass
     * leaves it); [visits] is the walk, one per (key, revision, snapshot). [purge] is null for an orphan pass.
     */
    data class Completed(
        val listed: Int,
        val reachedKeys: Int,
        val deleted: Int,
        val bytesDeleted: Long,
        val left: Map<LeftReason, Int>,
        val deferredHints: Map<HintReason, Int>,
        val stopped: StopReason?,
        val marker: MarkerFate,
        val purge: PurgeAnswer?,
        val inventory: List<ListedObject>,
        val visits: List<ReachVisit>,
    ) : RemovalResult {
        fun left(reason: LeftReason): Int = left[reason] ?: 0

        fun deferred(reason: HintReason): Int = deferredHints[reason] ?: 0
    }
}
