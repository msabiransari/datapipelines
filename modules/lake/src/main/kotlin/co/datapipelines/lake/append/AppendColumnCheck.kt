package co.datapipelines.lake.append

import co.datapipelines.pipeline.LakeAppendColumn
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.Schema
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.TypeUtil
import org.apache.iceberg.types.Types
import java.util.Locale

/** A schema-order binding; a missing optional column has no source index and is written as null. */
data class AppendColumnBinding(
    val field: Types.NestedField,
    val sourceIndex: Int?,
    val sourceType: Type.PrimitiveType?,
    val nativeTypeName: String? = null,
)

/** Field ids and schema order come from the applied Iceberg schema, never the incoming cursor's positions. */
data class AppendColumnPlan(
    val schema: Schema,
    val bindings: List<AppendColumnBinding>,
    val rowCount: Long = 0,
)

/**
 * Strict DuckDB-to-Iceberg binding (§8 step 4). Names use DuckDB's ASCII case comparison.
 * Native types are inspected before canonical string fallbacks
 * could hide nested values, UUIDs, 128-bit overflow or timezone loss. Only Iceberg promotions are admitted.
 * DuckDB staging is fixed to UTC; its unzoned timestamps therefore denote UTC instants. Nanosecond timestamp
 * and zoned time types are refused because the managed schema cannot represent their full precision/zone.
 */
class AppendColumnCheck {
    /** Validates names/types and counts ALL nulls in required fields, before a writer can open a file. */
    fun check(
        input: LakeAppendInput,
        schema: Schema,
        checkActive: () -> Unit = {},
    ): AppendColumnPlan {
        checkActive()
        var result: AppendColumnPlan? = null
        input.scan { rows ->
            check(result == null) { "An append scan must supply exactly one cursor." }
            val metadata = rows.metaData
            val columns =
                input.originalColumns ?: (1..metadata.columnCount).map { index ->
                    LakeAppendColumn(
                        metadata.getColumnLabel(index),
                        metadata.getColumnTypeName(index),
                        metadata.getPrecision(index),
                        metadata.getScale(index),
                    )
                }
            check(columns.size == metadata.columnCount) { "Original query columns do not match the staged relation." }
            val plan = plan(columns, schema)
            val required = plan.bindings.filter { it.field.isRequired }
            val nulls = linkedMapOf<String, Long>()
            var rowCount = 0L
            while (rows.next()) {
                checkActive()
                rowCount++
                required.forEach { binding ->
                    if (rows.getObject(requireNotNull(binding.sourceIndex)) == null) {
                        nulls.merge(binding.field.name(), 1L, Long::plus)
                    }
                }
            }
            if (nulls.isNotEmpty()) refuse(PipelineErrorCodes.Lake.APPEND_NULL_IN_REQUIRED, mapOf("null_counts" to nulls))
            result = plan.copy(rowCount = rowCount)
        }
        checkActive()
        return requireNotNull(result) { "An append scan did not supply its cursor." }
    }

    private fun plan(
        columns: List<LakeAppendColumn>,
        schema: Schema,
    ): AppendColumnPlan {
        val names = (1..columns.size).groupBy { folded(columns[it - 1].name) }
        val ambiguous = names.filterValues { it.size > 1 }.keys.sorted()
        if (ambiguous.isNotEmpty()) refuse(PipelineErrorCodes.Lake.APPEND_AMBIGUOUS_COLUMN, mapOf("columns" to ambiguous))
        val expected = schema.columns().associateBy { folded(it.name()) }
        val unknown = names.keys.minus(expected.keys).sorted()
        if (unknown.isNotEmpty()) refuse(PipelineErrorCodes.Lake.APPEND_UNKNOWN_COLUMN, mapOf("columns" to unknown))
        val missing = schema.columns().filter { it.isRequired && folded(it.name()) !in names }.map { it.name() }
        if (missing.isNotEmpty()) refuse(PipelineErrorCodes.Lake.APPEND_MISSING_REQUIRED_COLUMN, mapOf("columns" to missing))
        return AppendColumnPlan(schema, schema.columns().map { bind(columns, it, names[folded(it.name())]?.single()) })
    }

    private fun bind(
        columns: List<LakeAppendColumn>,
        field: Types.NestedField,
        index: Int?,
    ): AppendColumnBinding {
        if (index == null) return AppendColumnBinding(field, null, null)
        val column = columns[index - 1]
        val name = column.nativeType.uppercase(Locale.ROOT)
        val source = nativeType(name, column.precision, column.scale)
        if (source != null && (!field.type().isPrimitiveType || !TypeUtil.isPromotionAllowed(source, field.type().asPrimitiveType()))) {
            refuse(PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH, mapOf("column" to field.name(), "source_type" to name))
        }
        return AppendColumnBinding(field, index, source, name.substringBefore('(').trim())
    }

    private fun nativeType(
        name: String,
        precision: Int,
        scale: Int,
    ): Type.PrimitiveType? {
        val base = name.substringBefore('(').trim()
        if (base == "NULL" || base == "SQLNULL") return null
        SCALARS[base]?.let { return it }
        if (base in setOf("DECIMAL", "NUMERIC") && precision in 1..MAX_PRECISION && scale in 0..precision) {
            return Types.DecimalType.of(precision, scale)
        }
        refuse(PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH, mapOf("source_type" to base))
    }

    private fun folded(name: String): String =
        buildString(name.length) {
            name.forEach { append(if (it in 'A'..'Z') it.lowercaseChar() else it) }
        }

    private fun refuse(
        code: String,
        details: Map<String, Any>,
    ): Nothing = throw DatapipelinesException(code, "The staged result does not match the managed table schema.", details)

    private companion object {
        const val MAX_PRECISION = 38
        const val UNSIGNED_LONG_PRECISION = 20

        val SCALARS: Map<String, Type.PrimitiveType> =
            buildMap {
                listOf("TINYINT", "SMALLINT", "INTEGER", "UTINYINT", "USMALLINT").forEach { put(it, Types.IntegerType.get()) }
                listOf("BIGINT", "UINTEGER").forEach { put(it, Types.LongType.get()) }
                put("UBIGINT", Types.DecimalType.of(UNSIGNED_LONG_PRECISION, 0))
                listOf("FLOAT", "REAL").forEach { put(it, Types.FloatType.get()) }
                put("DOUBLE", Types.DoubleType.get())
                put("BOOLEAN", Types.BooleanType.get())
                put("VARCHAR", Types.StringType.get())
                put("BLOB", Types.BinaryType.get())
                put("DATE", Types.DateType.get())
                put("TIME", Types.TimeType.get())
                listOf("TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_S", "TIMESTAMP_MS")
                    .forEach { put(it, Types.TimestampType.withZone()) }
            }
    }
}
