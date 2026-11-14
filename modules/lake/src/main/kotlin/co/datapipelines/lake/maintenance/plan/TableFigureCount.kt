package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.profiles.LakeProfile

/** One live data file of the current snapshot, as a manifest entry describes it (no data file is opened). */
data class DataFileFact(
    /** The partition: the spec id and the partition's path under that spec (L19: one partition of ONE spec). */
    val partition: String,
    val sizeBytes: Long,
    val records: Long,
    /** The sort order id the file was written with; null when the writer stamped none. */
    val sortOrderId: Int?,
)

/**
 * The figures of brief 959 D3 from a snapshot's live files, under ONE profile copy (D3a), counted as the manifests are
 * streamed: memory grows with the partitions, never with the files.
 *
 * - small: a data file smaller than `compact_small_file_ratio × target_file_bytes`;
 * - a candidate partition holds at least `compact_min_small_files` small files; [TableFigures.smallFiles] counts the
 *   small files in candidate partitions;
 * - unsorted: on a table with sort columns ([tableSortOrderId] not null), a data file whose sort order id is not the
 *   table's; zero on a table without sort columns;
 * - [TableFigures.unsortedOverCap]: unsorted files larger than `max_bytes_rewritten_per_run` (they fit no run);
 * - [TableFigures.candidateBytes]: the summed size of every candidate file counted ONCE (small in a candidate partition,
 *   or unsorted), files larger than the cap left out;
 * - current bytes: data and delete files; current rows: the data files' record counts (row deletes not subtracted).
 */
class TableFigureCount(
    profile: LakeProfile,
    private val tableSortOrderId: Int?,
) {
    private val smallBelow = profile.compactSmallFileRatio * profile.targetFileBytes
    private val cap = profile.maxBytesRewrittenPerRun
    private val minSmall = profile.compactMinSmallFiles

    private class Partition {
        var small = 0L
        var smallBytes = 0L

        /** Bytes of files both small and unsorted (small files are always within the cap: cap ≥ 2 × target). */
        var smallUnsortedBytes = 0L
    }

    private val partitions = HashMap<String, Partition>()
    private var bytes = 0L
    private var rows = 0L
    private var dataFiles = 0L
    private var deleteFileCount = 0L
    private var unsorted = 0L
    private var unsortedOverCap = 0L
    private var unsortedFittingBytes = 0L

    fun addData(file: DataFileFact) {
        dataFiles++
        bytes += file.sizeBytes
        rows += file.records
        val isUnsorted = tableSortOrderId != null && file.sortOrderId != tableSortOrderId
        if (isUnsorted) {
            unsorted++
            if (file.sizeBytes > cap) unsortedOverCap++ else unsortedFittingBytes += file.sizeBytes
        }
        if (file.sizeBytes < smallBelow) {
            val partition = partitions.getOrPut(file.partition) { Partition() }
            partition.small++
            partition.smallBytes += file.sizeBytes
            if (isUnsorted && file.sizeBytes <= cap) partition.smallUnsortedBytes += file.sizeBytes
        }
    }

    fun addDelete(sizeBytes: Long) {
        deleteFileCount++
        bytes += sizeBytes
    }

    fun result(
        snapshotId: Long,
        manifests: Long,
    ): TableFigures {
        val candidates = partitions.values.filter { it.small >= minSmall }
        val smallInCandidates = candidates.sumOf { it.small }
        // Each candidate once: the small files of candidate partitions, plus the fitting unsorted files not among them.
        val candidateBytes =
            candidates.sumOf { it.smallBytes } + unsortedFittingBytes - candidates.sumOf { it.smallUnsortedBytes }
        return TableFigures(
            snapshotId = snapshotId,
            currentBytes = bytes,
            currentRows = rows,
            dataFiles = dataFiles,
            deleteFileCount = deleteFileCount,
            manifests = manifests,
            unsortedFiles = unsorted,
            smallFiles = smallInCandidates,
            unsortedOverCap = unsortedOverCap,
            candidateBytes = candidateBytes,
        )
    }

    companion object {
        /** The count over whole lists, for a caller that already holds them. */
        @Suppress("LongParameterList")
        fun of(
            snapshotId: Long,
            dataFiles: List<DataFileFact>,
            deleteFileBytes: List<Long>,
            manifests: Long,
            tableSortOrderId: Int?,
            profile: LakeProfile,
        ): TableFigures {
            val count = TableFigureCount(profile, tableSortOrderId)
            dataFiles.forEach(count::addData)
            deleteFileBytes.forEach(count::addDelete)
            return count.result(snapshotId, manifests)
        }
    }
}
