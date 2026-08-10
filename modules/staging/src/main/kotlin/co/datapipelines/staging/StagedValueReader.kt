package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Reads one value **back out of a staged H2 `ResultSet`** for egress (staging.md §6), typed so
 * the typesystem `JsonEncoder` can render it. Returns `null` for SQL NULL.
 *
 * The staged column's canonical descriptor comes from `H2IngressMapper.fromH2` over the H2
 * metadata; this reader turns the raw cursor value into the `java.time` / `Number` / `String`
 * / `ByteArray` shape `JsonEncoder.encode(value, column)` accepts.
 *
 * The same two normative read rules as [SourceValueReader] apply (§5.3): `STRING` via
 * [ResultSet.getString], temporal via JDBC 4.2 `getObject(index, java.time-type)`. A staged
 * `TIMESTAMP` column is H2 `TIMESTAMP WITH TIME ZONE` holding UTC, so `getObject(OffsetDateTime)`
 * reads it back unshifted — no renormalization on egress.
 */
internal object StagedValueReader {
    fun readValue(
        rs: ResultSet,
        index: Int,
        column: ColumnSchema,
    ): Any? =
        when (column.type) {
            LogicalType.NULL -> {
                null
            }

            LogicalType.BOOLEAN -> {
                rs.getBoolean(index).nullIfNull(rs)
            }

            LogicalType.INTEGER -> {
                rs.getInt(index).nullIfNull(rs)
            }

            LogicalType.BIGINTEGER -> {
                rs.getLong(index).nullIfNull(rs)
            }

            // Approximate DECIMAL stages as H2 DOUBLE (§4.2); read it back as a Double.
            LogicalType.DECIMAL -> {
                if (column.isApproximateNumeric) rs.getDouble(index).nullIfNull(rs) else rs.getBigDecimal(index)
            }

            LogicalType.BIGDECIMAL -> {
                rs.getBigDecimal(index)
            }

            LogicalType.STRING -> {
                rs.getString(index)
            }

            LogicalType.BINARY -> {
                rs.getBytes(index)
            }

            LogicalType.DATE -> {
                rs.getObject(index, LocalDate::class.java)
            }

            LogicalType.TIME -> {
                rs.getObject(index, LocalTime::class.java)
            }

            LogicalType.TIMESTAMP -> {
                rs.getObject(index, OffsetDateTime::class.java)
            }
        }

    private fun <T> T.nullIfNull(rs: ResultSet): T? = if (rs.wasNull()) null else this
}
