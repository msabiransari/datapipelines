package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.lake.profiles.LakeTablePause
import java.time.Instant
import java.util.UUID

/**
 * The four part names the planner decides for (the P6 lane cut §3). A part registered under another name is run by
 * the worker but never made due by the planner (L16 reads the other way too: a name here with no registered part is
 * never due).
 */
object MaintenancePartNames {
    const val CLEANUP = "cleanup"
    const val MANIFEST_REWRITE = "manifest_rewrite"
    const val COMPACTION = "compaction"
    const val PURGE_DELETION = "purge_deletion"

    /** Every operation the planner decides, in the record's order (call 34, "One task carries everything due"). */
    val ALL: List<String> = listOf(CLEANUP, MANIFEST_REWRITE, COMPACTION, PURGE_DELETION)

    /** The rewrite operations: decided from a measurement, held back by the compaction pause (call 38). */
    val REWRITES: Set<String> = setOf(MANIFEST_REWRITE, COMPACTION)
}

/** `lake_tables_managed.status` words the planner reads (V54). */
object PlanStatus {
    const val ACTIVE = "active"
    const val DEPRECATED = "deprecated"
    const val ARCHIVED = "archived"
    const val PURGED = "purged"

    /** The statuses whose tables are measured and cleaned (the record's "Which table gets which work"). */
    val MEASURED: List<String> = listOf(ACTIVE, DEPRECATED, ARCHIVED)

    /** The statuses whose tables get rewrites, when they have a snapshot. */
    val REWRITTEN: Set<String> = setOf(ACTIVE, DEPRECATED)
}

/**
 * A table's `lake_table_measurements` row (brief 959 D3). Figures are null when their source was not read (never
 * zero, call 46): every figure is null for a table measured with no current snapshot, and the whole figure set is
 * null for a table never measured successfully.
 */
data class StoredMeasurement(
    val attemptId: UUID,
    val attemptedAt: Instant,
    val attemptProfileId: UUID,
    val attemptProfileUpdatedAt: Instant,
    /** The last attempt's error code, null when the last attempt succeeded. */
    val errorCode: String?,
    val measuredAt: Instant?,
    val figures: TableFigures?,
    val figuresProfileId: UUID?,
    val figuresProfileUpdatedAt: Instant?,
) {
    /** D3a: the profile-dependent figures may be used only under the profile pair they were computed under. */
    fun current(
        profileId: UUID,
        profileUpdatedAt: Instant,
    ): Boolean = measuredAt != null && figuresProfileId == profileId && figuresProfileUpdatedAt == profileUpdatedAt
}

/**
 * What one successful measurement read from the current snapshot's manifests (D3). [snapshotId] is null for a table
 * with no current snapshot; then every other figure is null too.
 */
data class TableFigures(
    val snapshotId: Long?,
    val currentBytes: Long?,
    val currentRows: Long?,
    val dataFiles: Long?,
    val deleteFiles: Long?,
    val manifests: Long?,
    /** Data files not stamped with the table's sort order; zero on a table with no sort columns. */
    val unsortedFiles: Long?,
    /** Profile-dependent: small files in candidate partitions. */
    val smallFiles: Long?,
    /** Profile-dependent: unsorted files larger than the profile's bytes cap (they fit no run). */
    val unsortedOverCap: Long?,
    /** Profile-dependent: every candidate file counted once, the cap-blocked ones left out. */
    val candidateBytes: Long?,
) {
    companion object {
        /** A table with no current snapshot: measured, nothing to read. */
        val NO_SNAPSHOT = TableFigures(null, null, null, null, null, null, null, null, null, null)
    }
}

/** A compaction attempt as D5 reads it: its run's end, its outcome and 969's two fields (null when absent). */
data class RewriteAttempt(
    val at: Instant,
    val outcome: MaintenanceOutcome,
    val committed: Boolean?,
    val leftForFreshRun: Long?,
)

/** The run history D5 projects per table, from `finished` runs and attempted part entries only. */
data class RunHistory(
    val lastCleanup: Instant?,
    val lastFinishedRewrite: Instant?,
    val lastRewriteAttempt: RewriteAttempt?,
) {
    companion object {
        val NONE = RunHistory(null, null, null)
    }
}

/** Everything [MaintenanceDue] reads for one table (D4). The profile is the table's current one. */
data class DueInput(
    val tableId: UUID,
    val status: String,
    val createdAt: Instant,
    val profileId: UUID,
    val profileUpdatedAt: Instant,
    val profileAssignedAt: Instant,
    val profile: LakeProfile,
    val pause: LakeTablePause,
    val measurement: StoredMeasurement?,
    val purgeDeletedAt: Instant?,
    /** The final revision's `protected_until`; null when the table has no dropped revision or it is not finalised. */
    val finalProtectedUntil: Instant?,
    val history: RunHistory,
)

/** A due part held back by the gate (D6): the outcome and reason the run would record. */
data class PartHold(
    val outcome: MaintenanceOutcome,
    val reason: String,
    val until: Instant? = null,
)

/** Why an operation is not due, when the plan reports it (the planner's words, lake.md "Maintenance runs"). */
object NotDueReason {
    const val NOT_MEASURED = "not measured"
    const val PROFILE_CHANGED = "not measured (profile changed)"
    const val NOT_REGISTERED = "not registered"
    const val CAPACITY = "capacity"
    const val CAPACITY_WAIT = "capacity (waiting)"
    const val RULE_SILENT = "rule silent"
}

/** [MaintenanceDue]'s answer for one table. */
data class TableDue(
    val tableId: UUID,
    /** Due, registered and not held: the parts the table's task is for. */
    val due: Set<String>,
    /** Due and registered but held by the gate. */
    val held: Map<String, PartHold>,
    /** Operations not due, with the reason the plan reports (only the reported cases). */
    val notDue: Map<String, String>,
    /** True when cleanup or purge deletion is in [due]: the first group of the order. */
    val light: Boolean,
    /** For the first group: the earliest time its due cleanup or purge deletion became due. */
    val overdueSince: Instant?,
    /** The record's score; profile-dependent terms are zero while the figures are stale. */
    val score: Long,
    /** D3a: the stored figures were computed under another profile pair. */
    val stale: Boolean,
    /** The silence rule applied: a rule did not say whether work is due. */
    val silent: Boolean,
) {
    val hasWork: Boolean get() = due.isNotEmpty()
}

/** The tables' answers and the order of those with work (D7: rank 0 first). */
data class DuePlan(
    val tables: List<TableDue>,
    val order: List<UUID>,
)
