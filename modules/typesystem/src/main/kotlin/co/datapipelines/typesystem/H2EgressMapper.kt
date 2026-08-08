package co.datapipelines.typesystem

import java.sql.Types

/**
 * Canonical → H2 (type-system.md §6; staging.md §5.3).
 *
 * Used when generating staging DDL (`CREATE TABLE`, staging.md §4.2) and when binding
 * insert parameters (§4.3). It is **not** the inverse of [H2IngressMapper]: egress must
 * choose a DDL type string *and* a `java.sql.Types` code, ingress must recover a
 * canonical descriptor from H2 metadata, and conflating the two hid that asymmetry.
 *
 * Two rows deserve attention:
 *
 *  - **Approximate `DECIMAL` stages as `DOUBLE`** (§4.2). The canonical label
 *    `DECIMAL(15)` is the API contract; the H2 storage choice is internal and invisible
 *    to clients. `DOUBLE` round-trips an IEEE 754 value losslessly where
 *    `DECIMAL(15, ?)` would need an invented scale. The discriminator is the omitted
 *    scale, nothing else.
 *  - **`TIMESTAMP` stages as `TIMESTAMP WITH TIME ZONE`** (§6, staging.md §5.1).
 *    Canonical TIMESTAMP is always UTC; storing the zone explicitly means the value
 *    reads back as UTC with no renormalization, and H2's own date functions cannot
 *    quietly shift it.
 *
 * ## Overflow
 *
 * H2 2.x supports `DECIMAL` precision up to [MAX_H2_DECIMAL_PRECISION]. Every bounded
 * precision any supported dialect can declare fits far inside it, and a `BIGDECIMAL`
 * with **omitted** precision (unbounded, §4) stages at that ceiling. A declared
 * precision above the ceiling fails with `pipeline.staging.precision_overflow` — the
 * same threshold staging.md §5.2 states, and the two MUST stay identical.
 */
object H2EgressMapper {
    /** H2 2.x `DECIMAL` precision ceiling (§6; staging.md §5.2). */
    const val MAX_H2_DECIMAL_PRECISION = 100_000

    /** Raised when a declared precision exceeds what H2 can store (§6). */
    const val PRECISION_OVERFLOW_CODE = "pipeline.staging.precision_overflow"

    /**
     * The H2 column type as written in `CREATE TABLE` — e.g. `"DECIMAL(18, 2)"`,
     * `"VARCHAR"`, `"TIMESTAMP WITH TIME ZONE"`.
     *
     * @throws DatapipelinesException with [PRECISION_OVERFLOW_CODE] when the column
     *   declares a precision H2 cannot represent.
     */
    fun toH2Type(column: ColumnSchema): String =
        when (column.type) {
            // H2 requires a type even for a column that will only ever hold NULLs.
            LogicalType.NULL -> "VARCHAR"

            LogicalType.BOOLEAN -> "BOOLEAN"

            LogicalType.INTEGER -> "INTEGER"

            // H2 BIGINT is int64, exactly BIGINTEGER's range.
            LogicalType.BIGINTEGER -> "BIGINT"

            LogicalType.DECIMAL -> decimalType(column)

            LogicalType.BIGDECIMAL -> decimalWithScale(column)

            // Length unbounded: H2 supports VARCHAR with no length spec.
            LogicalType.STRING -> "VARCHAR"

            LogicalType.BINARY -> "VARBINARY"

            LogicalType.DATE -> "DATE"

            LogicalType.TIME -> "TIME"

            LogicalType.TIMESTAMP -> "TIMESTAMP WITH TIME ZONE"
        }

    /**
     * The `java.sql.Types` constant for
     * `PreparedStatement.setObject(index, value, targetSqlType)` (staging.md §4.3).
     *
     * Must agree with [toH2Type] column for column — an approximate `DECIMAL` is stored
     * as `DOUBLE` in both, or the bind would fight the DDL.
     */
    fun h2SqlType(column: ColumnSchema): Int =
        when (column.type) {
            LogicalType.NULL -> Types.VARCHAR
            LogicalType.BOOLEAN -> Types.BOOLEAN
            LogicalType.INTEGER -> Types.INTEGER
            LogicalType.BIGINTEGER -> Types.BIGINT
            LogicalType.DECIMAL -> if (column.isApproximateNumeric) Types.DOUBLE else Types.DECIMAL
            LogicalType.BIGDECIMAL -> Types.DECIMAL
            LogicalType.STRING -> Types.VARCHAR
            LogicalType.BINARY -> Types.VARBINARY
            LogicalType.DATE -> Types.DATE
            LogicalType.TIME -> Types.TIME
            LogicalType.TIMESTAMP -> Types.TIMESTAMP_WITH_TIMEZONE
        }

    /** Approximate origin → `DOUBLE`; exact origin → `DECIMAL(p, s)` (§4.2). */
    private fun decimalType(column: ColumnSchema): String = if (column.isApproximateNumeric) "DOUBLE" else decimalWithScale(column)

    private fun decimalWithScale(column: ColumnSchema): String = "DECIMAL(${checkedPrecision(column)}, ${column.scale ?: 0})"

    /**
     * The precision to write into the DDL: the declared one, or the H2 ceiling when the
     * source numeric is unbounded (§4 — the *envelope* keeps reporting precision as
     * omitted; only the storage gets a bound).
     */
    private fun checkedPrecision(column: ColumnSchema): Int {
        val declared = column.precision ?: return MAX_H2_DECIMAL_PRECISION
        if (declared > MAX_H2_DECIMAL_PRECISION) {
            throw DatapipelinesException(
                code = PRECISION_OVERFLOW_CODE,
                message =
                    "Column '${column.name}' declares precision $declared; H2 staging supports at most " +
                        "$MAX_H2_DECIMAL_PRECISION. CAST to a smaller type in the source query.",
                details = mapOf("column" to column.name, "precision" to declared),
            )
        }
        return declared
    }
}
