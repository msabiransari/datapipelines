package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.profiles.LakeProfile

/**
 * The ONE compaction candidate rule (brief 969 D2; record §11a call 34's "Due" row and call 36 item 4). The planner's
 * measurement (`TableFigureCount`, #959) and the compaction part both decide with these functions, so a table the
 * measurement calls due is a table the part finds work in: a difference between the two would be a table that is
 * always due and never rewritten.
 *
 * - a file is **small** when it is smaller than `compact_small_file_ratio × target_file_bytes`;
 * - a partition (of one partition spec, L19) is a **candidate partition** when it holds at least
 *   `compact_min_small_files` small files;
 * - on a table with sort columns ([tableSortOrderId] not null) a file is **unsorted** when it is not stamped with the
 *   table's sort order id, whatever its size; a legacy table has no unsorted file;
 * - a file is a **candidate** when it is unsorted, or small in a candidate partition.
 */
class CompactionCandidateRule(
    profile: LakeProfile,
    private val tableSortOrderId: Int?,
) {
    private val smallBelow = profile.compactSmallFileRatio * profile.targetFileBytes
    private val minSmall = profile.compactMinSmallFiles

    fun small(sizeBytes: Long): Boolean = sizeBytes < smallBelow

    fun unsorted(fileSortOrderId: Int?): Boolean = tableSortOrderId != null && fileSortOrderId != tableSortOrderId

    fun candidatePartition(smallFiles: Long): Boolean = smallFiles >= minSmall

    /**
     * The candidates among [files], in their given order: every unsorted file, and every small file of a candidate
     * partition. [partition] names a file's partition (spec id included), [size] and [sortOrderId] read its manifest
     * entry.
     */
    fun <F> candidates(
        files: List<F>,
        partition: (F) -> String,
        size: (F) -> Long,
        sortOrderId: (F) -> Int?,
    ): List<F> {
        val smallPerPartition = files.filter { small(size(it)) }.groupingBy(partition).eachCount()
        return files.filter { file ->
            unsorted(sortOrderId(file)) ||
                (small(size(file)) && candidatePartition((smallPerPartition[partition(file)] ?: 0).toLong()))
        }
    }

    companion object {
        /** A file's partition key: the spec id and the partition's path under that spec (one partition of ONE spec, L19). */
        fun partitionKey(
            specId: Int,
            partitionPath: String,
        ): String = "$specId/$partitionPath"
    }
}
