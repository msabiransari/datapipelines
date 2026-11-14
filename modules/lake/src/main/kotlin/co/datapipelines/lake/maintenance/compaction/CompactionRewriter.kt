package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.append.AppendValues
import co.datapipelines.lake.append.LakeDataFiles
import co.datapipelines.lake.profiles.LakeProfile
import org.apache.iceberg.DataFile
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.Record
import org.apache.iceberg.io.RollingDataWriter
import java.util.UUID

/** One group's rewritten files, before its commit (D7, D9). */
data class RewrittenGroup(
    val outputs: List<DataFile>,
    val rows: Long,
    /** The scratch database's largest size seen while the group read (D9's temporary peak). */
    val scratchPeakBytes: Long,
)

/** The rows written differ from the inputs' record counts (D7): the group is abandoned, nothing commits, the run fails. */
class CompactionRowCountMismatch(
    expected: Long,
    written: Long,
) : IllegalStateException("a compaction group wrote $written rows where its inputs hold $expected")

/**
 * Writes one rewrite group (brief 969 D6, D7): D6's statement over the group's files on the run's scratch session, its
 * rows through the append path's row conversion into ONE [RollingDataWriter] at the profile's target size, built by the
 * append path's factory setup ([LakeDataFiles]). The outputs are written under the INPUTS' partition spec with the
 * inputs' partition value, copied from an input file and never computed from rows. No lease is held: the outputs are
 * files written before any lease (record §8a class (b)); a group that does not reach its commit leaves them as orphans
 * the remover takes after the grace. Nothing is deleted here.
 *
 * [between] runs every [CHECK_EVERY_ROWS] rows and once at the end: the part's deadline and interrupt checks. A throw
 * from it, the session or the writer abandons the group.
 */
class CompactionRewriter(
    private val between: () -> Unit,
) {
    @Suppress("LongParameterList")
    fun write(
        pinned: PinnedTable,
        inputs: List<DataFile>,
        statement: CompactionStatement,
        session: CompactionScratchSession,
        profile: LakeProfile,
        runId: UUID,
        groupOrdinal: Long,
    ): RewrittenGroup {
        require(inputs.isNotEmpty()) { "a rewrite group has at least one input" }
        val first = inputs.first()
        require(inputs.all { it.specId() == first.specId() }) { "a rewrite group is one partition spec" }
        val table = pinned.table
        val spec = table.specs().getValue(first.specId())
        val expected = inputs.sumOf { it.recordCount() }
        val factory = LakeDataFiles.writerFactory(table, statement.schema, statement.stamp, profile)
        val outputFiles = LakeDataFiles.outputFiles(table, runId, taskId = groupOrdinal)
        var rows = 0L
        var peak = session.diskBytes()
        val writer = RollingDataWriter(factory, outputFiles, table.io(), profile.targetFileBytes, spec, first.partition())
        writer.use {
            val record: Record = GenericRecord.create(statement.schema)
            session.read(statement, inputs.map { it.location() }) { cursor ->
                while (cursor.next()) {
                    AppendValues.readRow(cursor, statement.bindings, record)
                    writer.write(record)
                    rows++
                    if (rows % CHECK_EVERY_ROWS == 0L) {
                        peak = maxOf(peak, session.diskBytes())
                        between()
                    }
                }
            }
            peak = maxOf(peak, session.diskBytes())
            between()
        }
        if (rows != expected) throw CompactionRowCountMismatch(expected, rows)
        return RewrittenGroup(writer.result().dataFiles().toList(), rows, peak)
    }

    companion object {
        /** The stream's checks run every this many rows: the pinned writer checks a file's size at the same step. */
        const val CHECK_EVERY_ROWS = 1_000L
    }
}
