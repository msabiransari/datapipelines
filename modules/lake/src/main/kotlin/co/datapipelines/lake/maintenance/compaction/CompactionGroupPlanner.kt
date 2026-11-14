package co.datapipelines.lake.maintenance.compaction

/** What set a rewrite group's limit (brief 969 D3; the cut's L18). [wire] is the word the run row carries. */
enum class LimitSource(
    val wire: String,
) {
    /** The profile's `maintenance.max_bytes_rewritten_per_run`. */
    CAP("cap"),

    /** The scratch granted at admission ÷ `compaction-scratch-factor`. */
    SCRATCH("scratch"),

    /** What a whole `job-timeout` writes at `compaction-bytes-per-second`. */
    TIME("time"),
}

/** A limit in bytes and the source that set it. */
data class GroupLimit(
    val bytes: Long,
    val source: LimitSource,
)

/**
 * One candidate file as the planner sees it: no Iceberg type, no I/O. [key] is unique (the file's location);
 * [partition] is the partition of ONE spec ([CompactionCandidateRule.partitionKey]); [needsSort] is true when the file
 * is not stamped with the table's sort order (worth rewriting alone); otherwise it is a small file already in order,
 * worth rewriting only together with another candidate of its partition (call 34).
 */
data class CompactionCandidate(
    val key: String,
    val partition: String,
    val sizeBytes: Long,
    val sequenceNumber: Long,
    val needsSort: Boolean,
) {
    init {
        require(sizeBytes >= 0) { "a file size is never negative: $this" }
    }
}

/**
 * The limits one plan is made under (D3). [capBytes] is the profile's bytes cap; [scratchGrantBytes] the run's granted
 * scratch and [scratchFactor] the scratch needed per byte of a group's input; [wholeRunBytes] what a whole
 * `job-timeout` writes; [bytesLeftNow] what is left of THIS run writes.
 */
data class PlanLimits(
    val capBytes: Long,
    val scratchGrantBytes: Long,
    val scratchFactor: Int,
    val wholeRunBytes: Long,
    val bytesLeftNow: Long,
) {
    init {
        require(capBytes >= 0 && scratchGrantBytes >= 0 && wholeRunBytes >= 0 && bytesLeftNow >= 0) { "limits are never negative: $this" }
        require(scratchFactor >= 1) { "the scratch factor is at least 1: $scratchFactor" }
    }

    /** The smallest of the cap, the grant ÷ the factor and a whole run's bytes; on a tie the source in that order. */
    val wholeRun: GroupLimit
        get() =
            listOf(
                GroupLimit(capBytes, LimitSource.CAP),
                GroupLimit(scratchGrantBytes / scratchFactor, LimitSource.SCRATCH),
                GroupLimit(wholeRunBytes, LimitSource.TIME),
            ).minBy { it.bytes } // minBy keeps the first of equal values: the tie order above

    /** The whole-run limit, and what is left of this run. */
    val now: Long
        get() = minOf(wholeRun.bytes, bytesLeftNow)
}

/** A file no run can take (`capacity`): its NEED (its own size, or its smallest merge) is above the whole-run limit. */
data class BlockedFile(
    val candidate: CompactionCandidate,
    val needBytes: Long,
)

/**
 * What COULD run (D3): [groups] in the order to run them (each one partition of one spec, worth rewriting, within the
 * limit now); [blocked] files no run can take, with the whole-run [limit] that blocked them; [leftForFreshRun] files a
 * fresh run can take but this one cannot; [waiting] small in-order files with no partner (none in their partition, or
 * left over alone by the grouping): neither blocked nor left for a fresh run.
 */
data class GroupPlan(
    val groups: List<List<CompactionCandidate>>,
    val blocked: List<BlockedFile>,
    val leftForFreshRun: List<CompactionCandidate>,
    val waiting: List<CompactionCandidate>,
    val limit: GroupLimit,
) {
    val largestBlockedNeed: Long?
        get() = blocked.maxOfOrNull { it.needBytes }
}

/**
 * The pure group planner (brief 969 D3; record §11a call 34 "Progress inside a table"; the cut's L18, L19). No I/O and no
 * clock: candidates and limits in, a [GroupPlan] out.
 *
 * Per file, by its NEED: an unsorted file's need is its size; a small in-order file's need is its size plus the smallest
 * OTHER candidate of its partition (no other → no need: it waits). Above the whole-run limit → blocked; above the limit
 * now → left for a fresh run; otherwise it may be grouped. Groups are formed per partition from the eligible files
 * oldest first (data sequence number, then key), each taking files while their sizes stay within the limit now; a group
 * that is not worth rewriting (one small in-order file) is not formed and its file waits. The group holding the oldest
 * candidate runs first.
 */
object CompactionGroupPlanner {
    private val OLDEST_FIRST = compareBy<CompactionCandidate>({ it.sequenceNumber }, { it.key })

    fun plan(
        candidates: List<CompactionCandidate>,
        limits: PlanLimits,
    ): GroupPlan {
        require(candidates.map { it.key }.toSet().size == candidates.size) { "candidate keys are unique" }
        val wholeRun = limits.wholeRun
        val now = limits.now
        val blocked = mutableListOf<BlockedFile>()
        val left = mutableListOf<CompactionCandidate>()
        val waiting = mutableListOf<CompactionCandidate>()
        val groups = mutableListOf<List<CompactionCandidate>>()
        candidates.groupBy { it.partition }.values.forEach { partition ->
            val eligible = mutableListOf<CompactionCandidate>()
            partition.sortedWith(OLDEST_FIRST).forEach { file ->
                val need = need(file, partition)
                when {
                    need == null -> waiting += file
                    need > wholeRun.bytes -> blocked += BlockedFile(file, need)
                    need > now -> left += file
                    else -> eligible += file
                }
            }
            group(eligible, now, groups, waiting)
        }
        return GroupPlan(
            groups = groups.sortedWith(compareBy(OLDEST_FIRST) { it.first() }),
            blocked = blocked,
            leftForFreshRun = left,
            waiting = waiting,
            limit = wholeRun,
        )
    }

    /** A file's need in bytes (D3); null for a small in-order file with no other candidate in its partition. */
    private fun need(
        file: CompactionCandidate,
        partition: List<CompactionCandidate>,
    ): Long? {
        if (file.needsSort) return file.sizeBytes
        val partner = partition.filter { it.key != file.key }.minOfOrNull { it.sizeBytes } ?: return null
        return saturatingAdd(file.sizeBytes, partner)
    }

    /** Groups [eligible] (one partition, oldest first) within [limit]; an unworthy group's file waits. */
    private fun group(
        eligible: List<CompactionCandidate>,
        limit: Long,
        groups: MutableList<List<CompactionCandidate>>,
        waiting: MutableList<CompactionCandidate>,
    ) {
        var current = mutableListOf<CompactionCandidate>()
        var bytes = 0L
        fun closeCurrent() {
            if (worthRewriting(current)) groups += current.toList() else waiting += current
            current = mutableListOf()
            bytes = 0L
        }
        eligible.forEach { file ->
            if (current.isNotEmpty() && saturatingAdd(bytes, file.sizeBytes) > limit) closeCurrent()
            current += file
            bytes = saturatingAdd(bytes, file.sizeBytes)
        }
        if (current.isNotEmpty()) closeCurrent()
    }

    /** Call 34: a group sorts at least one unsorted file, or merges at least two files. */
    fun worthRewriting(group: List<CompactionCandidate>): Boolean = group.size >= MIN_FILES_TO_MERGE || group.any { it.needsSort }

    /** Call 34: a merge is worth a rewrite from two files on (a record rule, not a tuning value). */
    const val MIN_FILES_TO_MERGE = 2

    internal fun saturatingAdd(
        a: Long,
        b: Long,
    ): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
}
