package co.datapipelines.executor

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.JsonEncoder

/**
 * How many bytes a result row costs — the ONE accounting (dag-executor.md §6.4.2, F4) shared by [RedisResultStore]'s
 * size cap and the dashboard runtime's bounded collector (#10 L2, the implementation spec's §9.5: "counted the way
 * the result store counts"), so the two caps mean the same thing for the same rows.
 *
 * A row's cost is the UTF-8 length of its JSON array encoding under the type system's egress rules ([JsonEncoder],
 * §3.5) — the string the store pushes to Redis. [utf8Length] measures it without allocating a second copy (F4).
 */
object ResultBytes {
    /** One row as a JSON array, each value encoded by the type system's egress rules (§3.5). */
    fun encodeRow(
        row: List<Any?>,
        columns: List<ColumnSchema>,
    ): String = ExecutorJson.write(row.mapIndexed { index, value -> JsonEncoder.encode(value, columns[index]) })

    /** [encodeRow]'s UTF-8 length — what one row costs against a byte cap. */
    fun rowBytes(
        row: List<Any?>,
        columns: List<ColumnSchema>,
    ): Long = utf8Length(encodeRow(row, columns))

    /** UTF-8 byte length without allocating a second copy of the string (F4). */
    fun utf8Length(value: String): Long {
        var length = 0L
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            length +=
                when {
                    code < ONE_BYTE_CEILING -> {
                        1
                    }

                    code < TWO_BYTE_CEILING -> {
                        2
                    }

                    Character.isHighSurrogate(value[index]) && index + 1 < value.length &&
                        Character.isLowSurrogate(value[index + 1]) -> {
                        // A surrogate pair is one code point encoded in four bytes.
                        index++
                        SURROGATE_PAIR_BYTES
                    }

                    else -> {
                        BASIC_PLANE_BYTES
                    }
                }
            index++
        }
        return length
    }

    /** UTF-8 code-unit boundaries — the encoding's own definition, not tunable values. */
    private const val ONE_BYTE_CEILING = 0x80
    private const val TWO_BYTE_CEILING = 0x800
    private const val BASIC_PLANE_BYTES = 3
    private const val SURROGATE_PAIR_BYTES = 4
}
