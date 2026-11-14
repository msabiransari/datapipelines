package co.datapipelines.lake.append

import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.DataFile
import org.apache.iceberg.FileFormat
import org.apache.iceberg.NullOrder
import org.apache.iceberg.PartitionKey
import org.apache.iceberg.SortDirection
import org.apache.iceberg.SortOrder
import org.apache.iceberg.Table
import org.apache.iceberg.TableProperties
import org.apache.iceberg.data.GenericFileWriterFactory
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.InternalRecordWrapper
import org.apache.iceberg.data.Record
import org.apache.iceberg.io.BaseTaskWriter
import org.apache.iceberg.io.OutputFileFactory
import org.apache.iceberg.io.PartitionedFanoutWriter
import org.apache.iceberg.io.UnpartitionedWriter
import org.apache.iceberg.types.Type
import java.util.UUID

/** Uncommitted immutable files. Publication owns the catalog transaction; no outcome ever deletes these files. */
data class WrittenAppend(
    val rows: Long,
    val files: List<DataFile>,
)

/**
 * The order an append is written in and the order its files are stamped with: ONE value, so a file is never stamped
 * with an order it was not written in (#968 D4; dp-lake design record §11a call 36 item 4, call 37). A table with sort
 * columns: its [SortOrder] and the staged [positions] of those columns ([AppendOrder.positions]). A legacy table
 * (unsorted): no positions and [SortOrder.unsorted], today's arrival-order append.
 */
class AppendOrder private constructor(
    val stamp: SortOrder,
    val positions: List<Int>,
) {
    companion object {
        /** The value [ParquetAppendWriter] reads both its scan order and its files' stamp from. */
        fun of(
            order: SortOrder,
            bindings: List<AppendColumnBinding>,
        ): AppendOrder =
            if (order.isUnsorted) {
                AppendOrder(SortOrder.unsorted(), emptyList())
            } else {
                AppendOrder(order, positions(order, bindings))
            }

        /**
         * #968 D2: the 1-based staged position of each sort field, in the sort order's order, from the binding of its
         * source field id. A sort column the delivery does not supply (an optional column left out) is skipped: all its
         * values are null, so the order by the others is the table's order.
         *
         * An order the definition body cannot spell (descending, nulls last, a transform; #968 D6) and a floating sort
         * column (D5: DuckDB's sort loses the sign of zero, duckdb/duckdb#25417; brief 935 refuses such a table until
         * #971) fail with [IllegalStateException] naming the field: the definition rule keeps such a table from
         * existing, so reaching here is a defect, not a delivery's refusal.
         */
        fun positions(
            order: SortOrder,
            bindings: List<AppendColumnBinding>,
        ): List<Int> {
            val byFieldId = bindings.associateBy { it.field.fieldId() }
            return order.fields().mapNotNull { sort ->
                val binding = byFieldId[sort.sourceId()]
                val name = binding?.field?.name() ?: "field id ${sort.sourceId()}"
                val ascending = sort.direction() == SortDirection.ASC && sort.nullOrder() == NullOrder.NULLS_FIRST
                val spellable = ascending && sort.transform<Any, Any>().isIdentity
                check(spellable) {
                    "The sort order of '$name' ($sort) is not ascending, nulls first, by the column's own value."
                }
                checkNotNull(binding) { "The sort column '$name' is not a top-level column of the table." }
                val type = binding.field.type().typeId()
                check(type != Type.TypeID.FLOAT && type != Type.TypeID.DOUBLE) {
                    "The sort column '$name' is a floating-point column; an append never orders by one (#971)."
                }
                binding.sourceIndex
            }
        }
    }
}

/**
 * Streams a stable staged result into partitioned Parquet/zstd (§8 step 4), after checking every required value.
 * Small row groups bound each open writer's buffering; file rotation bounds footer accumulation. Neither is a promise
 * about total process RSS. Abort/delete is never called.
 *
 * #968 (record call 37): the check scan reads in arrival order; the write scan reads the staged result in the table's
 * sort order (ascending, nulls first, sorted by the staging engine inside the delivery's scratch reservation) and every
 * file is stamped with that order ([AppendOrder]). A legacy table is written and stamped as before. The writer classes
 * are unchanged: rows that arrive in order leave every partition's files in order.
 *
 * #956 (record call 35, brief D9): the file size the writer rolls at and the Parquet row group size are the table's
 * [LakeProfile] values (`maintenance.target_file_bytes`, `write.row_group_bytes`), read by the caller at the start of
 * each append and passed in; no size is a constant here (`LakeTuningSourceGuardTest`). The pinned writer checks a
 * file's size every 1,000 rows (`BaseTaskWriter`, Iceberg 1.12.0), so a file can pass its target by those rows.
 */
class ParquetAppendWriter {
    /**
     * [profile] is the table's profile as read for this append. [checkActive] enforces the caller's fixed deadline,
     * cancellation and lost-lease signal on both scans; it stays the last parameter (a trailing lambda at every call).
     */
    fun write(
        table: Table,
        input: LakeAppendInput,
        operationId: UUID,
        maxOpenPartitions: Int,
        profile: LakeProfile,
        checkActive: () -> Unit,
    ): WrittenAppend {
        require(maxOpenPartitions > 0)
        val plan = AppendColumnCheck().check(input, table.schema(), table.spec(), checkActive)
        if (plan.rowCount == 0L) return WrittenAppend(0, emptyList())
        val order = AppendOrder.of(table.sortOrder(), plan.bindings)
        val factory =
            GenericFileWriterFactory
                .Builder(table)
                .dataFileFormat(FileFormat.PARQUET)
                .dataSchema(plan.schema)
                .dataSortOrder(order.stamp)
                .writerProperties(
                    mapOf(
                        TableProperties.PARQUET_COMPRESSION to "zstd",
                        TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES to profile.rowGroupBytes.toString(),
                    ),
                ).build()
        val output =
            OutputFileFactory
                .builderFor(table, 0, 0L)
                .operationId(operationId.toString())
                .format(FileFormat.PARQUET)
                .build()
        var count = 0L
        val task: BaseTaskWriter<Record> =
            if (table.spec().isUnpartitioned) {
                PlainWriter(table, factory, output, profile.targetFileBytes)
            } else {
                BoundedFanout(table, factory, output, maxOpenPartitions, profile.targetFileBytes)
            }
        task.use { writer ->
            val row = GenericRecord.create(plan.schema)
            input.scan(order.positions) { rows ->
                while (rows.next()) {
                    checkActive()
                    plan.bindings.forEachIndexed { index, binding -> row.set(index, AppendValues.read(rows, binding)) }
                    writer.write(row)
                    count++
                }
            }
            checkActive()
            check(count == plan.rowCount) { "The staged append result changed between its stable scans." }
            val result = writer.complete()
            return WrittenAppend(count, result.dataFiles().toList())
        }
    }

    private class PlainWriter(
        table: Table,
        factory: GenericFileWriterFactory,
        output: OutputFileFactory,
        targetFileBytes: Long,
    ) : UnpartitionedWriter<Record>(table.spec(), FileFormat.PARQUET, factory, output, table.io(), targetFileBytes) {
        private var closed = false

        override fun close() {
            if (!closed) {
                super.close()
                closed = true
            }
        }
    }

    private class BoundedFanout(
        table: Table,
        factory: GenericFileWriterFactory,
        output: OutputFileFactory,
        private val maxOpenPartitions: Int,
        targetFileBytes: Long,
    ) : PartitionedFanoutWriter<Record>(table.spec(), FileFormat.PARQUET, factory, output, table.io(), targetFileBytes) {
        private val key = PartitionKey(table.spec(), table.schema())
        private val internalRow = InternalRecordWrapper(table.schema().asStruct())
        private val partitions = HashSet<PartitionKey>()
        private var closed = false

        override fun partition(row: Record): PartitionKey {
            key.partition(internalRow.wrap(row))
            if (key !in partitions) {
                if (partitions.size >= maxOpenPartitions) {
                    throw DatapipelinesException(
                        PipelineErrorCodes.Lake.APPEND_TOO_MANY_PARTITIONS,
                        "The append exceeds its open partition limit.",
                        mapOf("limit" to maxOpenPartitions),
                    )
                }
                partitions += key.copy()
            }
            return key
        }

        override fun close() {
            if (!closed) {
                super.close()
                closed = true
            }
        }
    }
}
