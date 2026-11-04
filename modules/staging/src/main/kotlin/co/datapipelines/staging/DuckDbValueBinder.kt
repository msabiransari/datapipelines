package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DuckDbEgressMapper
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.UtcNormalization
import org.duckdb.DuckDBAppender
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Appends one canonical value to a DuckDB staged column (staging.md §8.5, type-system.md §6a).
 *
 * The driver's appender takes each value as exactly the Java type its column stores and refuses
 * anything else (measured, lane #795 probe 14): `Int` for `INTEGER`, `Long` for `BIGINT`, a
 * `BigDecimal` at exactly the column's scale and no wider than its precision. Values arrive in two
 * shapes — decoded off a source cursor ([SourceValueReader]: already the column's Java type) and
 * from a parent's composition or a script node's gated rows (`stageRows`: whatever the producer
 * held, e.g. a `Double` for a decimal, an `Instant` for a timestamp, text for a date). Both are
 * converted here, the H2 path's `setObject` conversions made explicit.
 *
 * Exact numerics are rounded to the column's scale half-up (H2's conversion) and then checked:
 * a value that needs more digits than the column holds is refused as `value_overflow` **before** it
 * reaches the engine. That check is not optional — through `setObject` the pinned driver binds a
 * `BigDecimal` wider than 38 digits as NULL without an error (owner ruling 2026-11-03, item 3).
 * A value of a shape its column cannot take (text that is not a date, NaN for a decimal) is
 * `value_overflow` too, as H2 reports a failed conversion (SQLState class 22). The message names
 * the table and column, never the value: staged values are user data.
 */
internal class DuckDbValueBinder(
    private val tableName: String,
    private val column: ColumnSchema,
) {
    private val exactScale: Int? = if (column.isApproximateNumeric) null else column.scale ?: 0

    /** Appends [value] (or NULL) to the appender's current row. */
    fun append(
        appender: DuckDBAppender,
        value: Any?,
    ) {
        if (value == null) {
            appender.appendNull()
            return
        }
        val failure: RuntimeException =
            try {
                appendNonNull(appender, value)
                return
            } catch (e: ArithmeticException) {
                e
            } catch (e: IllegalArgumentException) {
                e
            } catch (e: DateTimeParseException) {
                e
            }
        throw overflow(reasonFor(failure), failure)
    }

    /** Why a conversion failed, in words that name the column's type and never the value. */
    private fun reasonFor(failure: RuntimeException): String =
        when (failure) {
            is ArithmeticException -> "does not fit"
            is NumberFormatException -> "is not a number"
            is DateTimeParseException -> "is not a ${column.type.name.lowercase()}"
            else -> "cannot be stored as ${column.type.name}"
        }

    private fun appendNonNull(
        appender: DuckDBAppender,
        value: Any,
    ) {
        when (column.type) {
            // A NULL-typed column stages as VARCHAR (§6a); a stray value is kept as its text.
            LogicalType.NULL, LogicalType.STRING -> appender.append(value as? String ?: value.toString())

            LogicalType.BOOLEAN -> appender.append(booleanOf(value))

            LogicalType.INTEGER -> appender.append(intOf(value))

            LogicalType.BIGINTEGER -> appender.append(longOf(value))

            LogicalType.DECIMAL, LogicalType.BIGDECIMAL -> appendNumeric(appender, value)

            LogicalType.BINARY -> appender.append(bytesOf(value))

            LogicalType.DATE -> appender.append(dateOf(value))

            LogicalType.TIME -> appender.append(timeOf(value))

            LogicalType.TIMESTAMP -> appender.append(timestampOf(value))
        }
    }

    private fun intOf(value: Any): Int = value as? Int ?: exactWhole(value).intValueExact()

    private fun longOf(value: Any): Long = value as? Long ?: exactWhole(value).longValueExact()

    private fun bytesOf(value: Any): ByteArray = requireNotNull(value as? ByteArray) { "not bytes" }

    private fun dateOf(value: Any): LocalDate = value as? LocalDate ?: LocalDate.parse(value.toString())

    private fun timeOf(value: Any): LocalTime = if (value is String) LocalTime.parse(value) else UtcNormalization.toLocalTime(value)

    private fun timestampOf(value: Any): OffsetDateTime =
        OffsetDateTime.ofInstant(UtcNormalization.toUtcInstant(temporal(value)), ZoneOffset.UTC)

    private fun appendNumeric(
        appender: DuckDBAppender,
        value: Any,
    ) {
        val scale = exactScale
        if (scale == null) {
            // Approximate (scale omitted, §4.1): DOUBLE.
            appender.append(if (value is Number) value.toDouble() else value.toString().toDouble())
            return
        }
        val rescaled = decimalOf(value).setScale(scale, RoundingMode.HALF_UP)
        val precision = requireNotNull(column.precision) { "exact column '${column.name}' reached the binder unsized" }
        if (rescaled.precision() > precision) throw ArithmeticException("needs ${rescaled.precision()} digits")
        appender.append(rescaled)
    }

    private fun decimalOf(value: Any): BigDecimal =
        when (value) {
            is BigDecimal -> value

            is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((value as Number).toLong())

            // Double.toString is the shortest text that round-trips: 0.1 stays 0.1, not 0.1000000000000000055…
            is Double, is Float -> BigDecimal((value as Number).toDouble().toString())

            else -> BigDecimal(value.toString())
        }

    /** A whole number for an integer column: rounded half-up, as H2 converts a fractional value. */
    private fun exactWhole(value: Any): BigDecimal = decimalOf(value).setScale(0, RoundingMode.HALF_UP)

    private fun booleanOf(value: Any): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> exactWhole(value).intValueExact() != 0
            else -> value.toString().lowercase().toBooleanStrict()
        }

    /** Timestamps from `stageRows` may arrive as ISO text; [UtcNormalization] takes the java.time forms. */
    private fun temporal(value: Any): Any =
        if (value is String) {
            runCatching { OffsetDateTime.parse(value) }.getOrElse { LocalDateTime.parse(value) }
        } else {
            value
        }

    private fun overflow(
        why: String,
        cause: Exception,
    ) = StagingValueOverflowException(
        "A value for column '${column.name}' of staged table '$tableName' $why " +
            "(${DuckDbEgressMapper.toDuckDbType(column)}).",
        cause,
    )
}
