package co.datapipelines.lake.maintenance.compaction

/**
 * The profile's bytes cap as the run's TOTAL (record §11a call 34 "What a run does"; brief 969 D3's last rule): before
 * every group the part asks [fits]; a group that would cross what is left is not started and not shrunk, and the part
 * stops there. Confined to the run's thread.
 */
class CompactionRunBudget(
    val capBytes: Long,
) {
    var spentBytes: Long = 0
        private set

    val leftBytes: Long
        get() = (capBytes - spentBytes).coerceAtLeast(0)

    fun fits(group: List<CompactionCandidate>): Boolean = group.sumOf { it.sizeBytes } <= leftBytes

    fun spend(bytes: Long) {
        require(bytes >= 0) { "spent bytes are never negative: $bytes" }
        spentBytes = CompactionGroupPlanner.saturatingAdd(spentBytes, bytes)
    }
}
