package co.datapipelines.staging

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.LogicalTypeMapping
import co.datapipelines.typesystem.UtcNormalization
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Reads one value from the **source** `ResultSet` per the canonical mapping (staging.md §4.4,
 * §5.3), ready to bind into the staged H2 row. Returns `null` for SQL NULL.
 *
 * ## The two normative read rules (staging.md §4.4, §5.3 — from the P1 reviews)
 *
 *  - **`STRING`-canonical columns are read with [ResultSet.getString], never `getObject`.**
 *    Several dialect mappings assign binary-coded or driver-object JDBC columns to canonical
 *    `STRING` (geometry as WKT, arrays, XMLType, CLOBs); `getObject(...).toString()` on those
 *    yields Java identity text (`[B@6d06d69c`) shipped as a plausible value. `getString` makes
 *    the driver do the conversion.
 *  - **Temporal columns are read with JDBC 4.2 `getObject(index, java.time-type)`, never
 *    `getTimestamp`/`getDate`/`getTime`.** The `java.sql` temporal types convert through the
 *    JVM default zone, and the typesystem's `JsonEncoder`/`UtcNormalization` reject them by
 *    design (type-system.md §8.4, machine-independence).
 *
 * `TIMESTAMP` is additionally UTC-normalized here (§5.1): the source `OffsetDateTime` collapses
 * to a UTC instant, re-expressed as an `OffsetDateTime` at `Z` so it stores into H2's
 * `TIMESTAMP WITH TIME ZONE` and reads back UTC with no renormalization.
 */
internal object SourceValueReader {
    fun readValue(
        rs: ResultSet,
        index: Int,
        mapping: LogicalTypeMapping,
    ): Any? =
        when (mapping.type) {
            LogicalType.NULL -> {
                null
            }

            LogicalType.BOOLEAN -> {
                rs.getBoolean(index).nullIfSourceNull(rs)
            }

            LogicalType.INTEGER -> {
                rs.getInt(index).nullIfSourceNull(rs)
            }

            LogicalType.BIGINTEGER -> {
                rs.getLong(index).nullIfSourceNull(rs)
            }

            // Scale omitted ⇒ approximate origin (§4.1) ⇒ read as a Double to match H2 DOUBLE.
            LogicalType.DECIMAL -> {
                if (mapping.scale == null) rs.getDouble(index).nullIfSourceNull(rs) else rs.getBigDecimal(index)
            }

            LogicalType.BIGDECIMAL -> {
                rs.getBigDecimal(index)
            }

            // Critical read rule: getString, never getObject (WKT/arrays/CLOB identity-text trap).
            LogicalType.STRING -> {
                rs.getString(index)
            }

            LogicalType.BINARY -> {
                rs.getBytes(index)
            }

            // Critical read rule: JDBC 4.2 java.time reads, never getDate/getTime/getTimestamp.
            LogicalType.DATE -> {
                rs.getObject(index, LocalDate::class.java)
            }

            LogicalType.TIME -> {
                rs.getObject(index, LocalTime::class.java)
            }

            LogicalType.TIMESTAMP -> {
                readUtcTimestamp(rs, index)
            }
        }

    private fun readUtcTimestamp(
        rs: ResultSet,
        index: Int,
    ): OffsetDateTime? {
        val source = rs.getObject(index, OffsetDateTime::class.java) ?: return null
        return OffsetDateTime.ofInstant(UtcNormalization.toUtcInstant(source), ZoneOffset.UTC)
    }

    /** Collapses a primitive read to `null` when the driver reports the column was SQL NULL. */
    private fun <T> T.nullIfSourceNull(rs: ResultSet): T? = if (rs.wasNull()) null else this
}
