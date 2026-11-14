package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.PartFigures
import co.datapipelines.lake.maintenance.PartResult

/** D9's figures of one committed group. */
data class GroupFigures(
    val inputFiles: Int,
    val inputBytes: Long,
    val outputFiles: Int,
    val outputBytes: Long,
    val rows: Long,
    val readWriteSeconds: Double,
    val leaseSeconds: Double,
    val scratchPeakBytes: Long,
) {
    fun wire(): Map<String, Any> =
        linkedMapOf(
            "input_files" to inputFiles,
            "input_bytes" to inputBytes,
            "output_files" to outputFiles,
            "output_bytes" to outputBytes,
            "rows" to rows,
            "read_write_seconds" to readWriteSeconds,
            "lease_seconds" to leaseSeconds,
            "scratch_peak_bytes" to scratchPeakBytes,
        )
}

/**
 * What one compaction part did, and the outcome word it answers (brief 969 D8). Precedence: `busy` (the run lost the
 * table: 957 ends the run there), then `capacity`, then `deadline`, then `done`, else `nothing to do`. Every result
 * carries `committed` and `left_for_fresh_run` under the names 959's plan reads; a `capacity` result also carries the
 * blocked count, the largest need, the limit and its source. Confined to the run's thread.
 */
class CompactionTally {
    val groups = mutableListOf<GroupFigures>()
    var blocked = 0
        private set
    var largestNeedBytes: Long? = null
        private set
    var limit: GroupLimit? = null
        private set
    var leftForFreshRun = 0L
    var deadlineReached = false
    var busy = false

    /** Files no run can take, the largest need among them, and the whole-run limit that blocked them. */
    fun block(
        files: Int,
        largestNeed: Long,
        limit: GroupLimit,
    ) {
        if (files == 0) return
        blocked += files
        largestNeedBytes = maxOf(largestNeedBytes ?: 0L, largestNeed)
        this.limit = limit
    }

    val committed: Boolean
        get() = groups.isNotEmpty()

    fun outcome(): MaintenanceOutcome =
        when {
            busy -> MaintenanceOutcome.BUSY
            blocked > 0 -> MaintenanceOutcome.CAPACITY
            deadlineReached || leftForFreshRun > 0 -> MaintenanceOutcome.DEADLINE
            committed -> MaintenanceOutcome.DONE
            else -> MaintenanceOutcome.NOTHING_TO_DO
        }

    fun result(): PartResult {
        val details =
            linkedMapOf<String, Any?>(
                "committed" to committed,
                "left_for_fresh_run" to leftForFreshRun,
                "blocked" to blocked,
            )
        if (blocked > 0) {
            details["largest_need_bytes"] = largestNeedBytes
            details["limit_bytes"] = limit?.bytes
            details["limit_source"] = limit?.source?.wire
        }
        details["groups"] = groups.map(GroupFigures::wire)
        return PartResult(
            outcome = outcome(),
            figures = PartFigures(files = groups.sumOf { it.inputFiles.toLong() }, bytes = groups.sumOf { it.inputBytes }),
            details = details,
        )
    }
}
