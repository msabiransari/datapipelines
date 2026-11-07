package co.datapipelines.lake.append

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Native JDBC values for the generic Parquet writer: no canonical text fallback and no JVM timezone conversion. */
internal object AppendValues {
    fun read(
        rows: ResultSet,
        binding: AppendColumnBinding,
    ): Any? {
        val index = binding.sourceIndex ?: return null
        if (rows.getObject(index) == null) return null
        if (binding.sourceType == null) mismatch(binding)
        return try {
            nativeValue(rows, index, binding)
        } catch (_: ArithmeticException) {
            // Numeric/range errors may contain the original value; expose only its column.
            mismatch(binding)
        }
    }

    private fun nativeValue(
        rows: ResultSet,
        index: Int,
        binding: AppendColumnBinding,
    ): Any =
        when (binding.field.type().typeId()) {
            Type.TypeID.BOOLEAN -> {
                rows.getBoolean(index)
            }

            Type.TypeID.INTEGER -> {
                rows.getInt(index)
            }

            Type.TypeID.LONG -> {
                rows.getLong(index)
            }

            Type.TypeID.FLOAT -> {
                rows.getFloat(index)
            }

            Type.TypeID.DOUBLE -> {
                rows.getDouble(index)
            }

            Type.TypeID.DECIMAL -> {
                decimal(rows.getBigDecimal(index), binding)
            }

            Type.TypeID.STRING -> {
                rows.getString(index)
            }

            Type.TypeID.BINARY -> {
                ByteBuffer.wrap(rows.getBytes(index))
            }

            Type.TypeID.DATE -> {
                rows.getObject(index, LocalDate::class.java).also { Math.toIntExact(it.toEpochDay()) }
            }

            Type.TypeID.TIME -> {
                time(rows.getObject(index, LocalTime::class.java), binding)
            }

            Type.TypeID.TIMESTAMP -> {
                timestamp(rows, index, binding)
            }

            else -> {
                mismatch(binding)
            }
        }

    private fun decimal(
        value: BigDecimal,
        binding: AppendColumnBinding,
    ): BigDecimal {
        val target = binding.field.type() as Types.DecimalType
        val scaled = value.setScale(target.scale(), RoundingMode.UNNECESSARY)
        if (scaled.precision() > target.precision()) mismatch(binding)
        return scaled
    }

    private fun time(
        value: LocalTime,
        binding: AppendColumnBinding,
    ): LocalTime {
        if (value.nano % NANOS_PER_MICRO != 0) mismatch(binding)
        return value
    }

    private fun timestamp(
        rows: ResultSet,
        index: Int,
        binding: AppendColumnBinding,
    ): OffsetDateTime {
        val value =
            if (binding.nativeTypeName in ZONED_TIMESTAMPS) {
                rows.getObject(index, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC)
            } else {
                rows.getObject(index, LocalDateTime::class.java).atOffset(ZoneOffset.UTC)
            }
        if (value.nano % NANOS_PER_MICRO != 0) mismatch(binding)
        // Reject values outside Iceberg's microsecond range before a library conversion could wrap.
        ChronoUnit.MICROS.between(Instant.EPOCH, value.toInstant())
        return value
    }

    private fun mismatch(binding: AppendColumnBinding): Nothing =
        throw DatapipelinesException(
            PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH,
            "A staged value cannot be represented by its managed column.",
            mapOf("column" to binding.field.name()),
        )

    private val ZONED_TIMESTAMPS = setOf("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
    private const val NANOS_PER_MICRO = 1_000
}
