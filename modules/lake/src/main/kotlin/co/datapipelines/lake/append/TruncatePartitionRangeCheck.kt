package co.datapipelines.lake.append

import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.PartitionField
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.transforms.PartitionSpecVisitor
import org.apache.iceberg.transforms.Transform
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.apache.iceberg.util.SerializableFunction
import java.math.BigDecimal
import java.sql.ResultSet

/**
 * Counts the values a `truncate[N]` partition over an `int`, `long` or `decimal` column cannot hold (#967).
 *
 * Iceberg's `truncate` keeps the source type and rounds toward negative infinity, so at the lowest values of a
 * column's range its result leaves that type: a decimal gains a digit (`decimal(15,3)` `truncate[2]` of
 * `-999999999999.999` is `-1000000000000.000`, precision 16), and an `int` or `long` wraps to a value far above
 * the row (`int` `truncate[10]` of `-2147483648` is `2147483646`). A decimal delivery then fails inside the
 * catalog commit, and an integer one publishes a partition value that partition pruning skips. Iceberg's own
 * bound function decides every value, never a re-implementation, so this check follows whatever the pinned
 * Iceberg does.
 *
 * Temporary: the refusal goes when Iceberg keeps the result inside its type at these edges — decimal in
 * apache/iceberg issue 17027, `int` and `long` in issue 13105 (closed as not planned) and 17027's discussion.
 * `TruncateEdgeTripwireTest` goes red when either half changes. Strings, `identity`, `bucket` and the time
 * transforms are not checked; an unpartitioned table checks nothing. Counts only: no value is kept.
 */
internal class TruncatePartitionRangeCheck private constructor(
    val guards: List<Guard>,
) {
    private val counts = linkedMapOf<String, Long>()
    private var unreadable: DatapipelinesException? = null

    /**
     * Reads each guarded column of the current row as [AppendValues.read] would write it and counts the values
     * whose partition value leaves the type. A value that cannot be read at all is kept for [counts] instead,
     * so a required null later in the cursor is still the first refusal (#967 D4).
     */
    fun observe(rows: ResultSet) {
        guards.forEach { guard ->
            val value =
                try {
                    AppendValues.read(rows, guard.binding)
                } catch (failure: DatapipelinesException) {
                    if (unreadable == null) unreadable = failure
                    null
                } ?: return@forEach
            if (guard.outOfRange(value)) counts.merge(guard.key, 1L, Long::plus)
        }
    }

    /** The unreadable value's own refusal first (the writing scan would have raised it), then the counts. */
    fun counts(): Map<String, Long> {
        unreadable?.let { throw it }
        return counts
    }

    /** One `truncate` partition field over a numeric column, bound to the column's Iceberg type. */
    class Guard(
        val key: String,
        val binding: AppendColumnBinding,
        private val type: Type.PrimitiveType,
        private val function: SerializableFunction<Any, Any>,
    ) {
        /** Iceberg's partition value is above the value (int, long) or wider than the column (decimal). */
        fun outOfRange(value: Any): Boolean {
            val partition = function.apply(value)
            return when (type.typeId()) {
                Type.TypeID.INTEGER -> (partition as Int) > (value as Int)
                Type.TypeID.LONG -> (partition as Long) > (value as Long)
                Type.TypeID.DECIMAL -> (partition as BigDecimal).precision() > (type as Types.DecimalType).precision()
                else -> false
            }
        }
    }

    companion object {
        private val CHECKED = setOf(Type.TypeID.INTEGER, Type.TypeID.LONG, Type.TypeID.DECIMAL)

        /** Guards only the bound columns a numeric `truncate` partitions; no other column is ever read. */
        fun of(
            spec: PartitionSpec,
            bindings: List<AppendColumnBinding>,
        ): TruncatePartitionRangeCheck {
            val bySource = bindings.filter { it.sourceIndex != null }.associateBy { it.field.fieldId() }
            val guards =
                spec.fields().mapNotNull { field ->
                    val binding = bySource[field.sourceId()] ?: return@mapNotNull null
                    val type = binding.field.type()
                    if (!type.isPrimitiveType || type.typeId() !in CHECKED || !isTruncate(spec, field)) return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val transform = field.transform() as Transform<Any, Any>
                    Guard("${field.transform()}(${binding.field.name()})", binding, type.asPrimitiveType(), transform.bind(type))
                }
            return TruncatePartitionRangeCheck(guards)
        }

        private fun isTruncate(
            spec: PartitionSpec,
            field: PartitionField,
        ): Boolean = PartitionSpecVisitor.visit(spec.schema(), field, TruncateOnly)
    }

    /** Iceberg's public way to tell a transform's kind; every kind but `truncate` answers false. */
    private object TruncateOnly : PartitionSpecVisitor<Boolean> {
        override fun identity(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
        ) = false

        override fun bucket(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
            numBuckets: Int,
        ) = false

        override fun truncate(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
            width: Int,
        ) = true

        override fun year(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
        ) = false

        override fun month(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
        ) = false

        override fun day(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
        ) = false

        override fun hour(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
        ) = false

        override fun alwaysNull(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
        ) = false

        override fun unknown(
            fieldId: Int,
            sourceName: String,
            sourceId: Int,
            transform: String,
        ) = false
    }
}
