package co.datapipelines.lake.append

import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.DataFile
import org.apache.iceberg.FileFormat
import org.apache.iceberg.PartitionKey
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
import java.util.UUID

/** Uncommitted immutable files. Publication owns the catalog transaction; no outcome ever deletes these files. */
data class WrittenAppend(
    val rows: Long,
    val files: List<DataFile>,
)

/**
 * Streams a stable staged result into partitioned Parquet/zstd (§8 step 4), after checking every required value.
 * Small row groups bound each open writer's buffering; file rotation bounds footer accumulation. Neither is an
 * operator key or a promise about total process RSS. No sort order is claimed, and abort/delete is never called.
 */
class ParquetAppendWriter {
    /** [checkActive] enforces the caller's fixed deadline, cancellation and lost-lease signal on both scans. */
    fun write(
        table: Table,
        input: LakeAppendInput,
        operationId: UUID,
        maxOpenPartitions: Int,
        checkActive: () -> Unit,
    ): WrittenAppend {
        require(maxOpenPartitions > 0)
        val plan = AppendColumnCheck().check(input, table.schema(), table.spec(), checkActive)
        if (plan.rowCount == 0L) return WrittenAppend(0, emptyList())
        val factory =
            GenericFileWriterFactory
                .Builder(table)
                .dataFileFormat(FileFormat.PARQUET)
                .dataSchema(plan.schema)
                .dataSortOrder(SortOrder.unsorted())
                .writerProperties(
                    mapOf(
                        TableProperties.PARQUET_COMPRESSION to "zstd",
                        TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES to ROW_GROUP_BYTES.toString(),
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
                PlainWriter(table, factory, output)
            } else {
                BoundedFanout(table, factory, output, maxOpenPartitions)
            }
        task.use { writer ->
            val row = GenericRecord.create(plan.schema)
            input.scan { rows ->
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
    ) : UnpartitionedWriter<Record>(table.spec(), FileFormat.PARQUET, factory, output, table.io(), FILE_BYTES) {
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
    ) : PartitionedFanoutWriter<Record>(table.spec(), FileFormat.PARQUET, factory, output, table.io(), FILE_BYTES) {
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

    private companion object {
        const val ROW_GROUP_BYTES = 1024L * 1024
        const val FILE_BYTES = 64L * 1024 * 1024
    }
}
