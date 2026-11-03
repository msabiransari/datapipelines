package co.datapipelines.typesystem

/**
 * Canonical → DuckDB (type-system.md §6a; staging.md §8.5).
 *
 * The DuckDB twin of [H2EgressMapper]: the DDL type a canonical column stages as when the
 * pipeline's `settings.tempdb.engine` is `DUCKDB`. Like its H2 counterpart it is **not** the
 * inverse of the ingress mapper ([DuckDbTypeMapper], §5.6) — ingress folds many DuckDB types
 * into one canonical type, egress picks the one storage type that reads back as the same
 * canonical type through that ingress table.
 *
 * Rows that deserve attention:
 *
 *  - **`BIGINTEGER` stages as `BIGINT`**, DuckDB's int64 — exactly §3's range. `HUGEINT` would
 *    read back through §5.6 as `BIGDECIMAL(38, 0)` and change the column's type across staging.
 *  - **Approximate `DECIMAL` stages as `DOUBLE`** (§4.2, as on H2): the omitted scale is the
 *    only discriminator, and `DOUBLE` round-trips an IEEE 754 value losslessly.
 *  - **`TIMESTAMP` stages as `TIMESTAMP WITH TIME ZONE`** (DuckDB's `TIMESTAMPTZ`): an instant,
 *    read back as UTC by the staging session's `TimeZone = 'UTC'` (staging.md §8.5).
 *  - **`BINARY` stages as `BLOB`**, DuckDB's only byte-string type.
 *
 * ## Precision ceiling: 38, and no unbounded decimal
 *
 * DuckDB's `DECIMAL` holds at most [MAX_DUCKDB_DECIMAL_PRECISION] digits and DuckDB has no exact
 * arbitrary-precision type, so it has no counterpart to H2's `DECFLOAT(100000)`. Two kinds of
 * column are therefore refused with `pipeline.staging.precision_overflow` before any table is
 * created (owner ruling 2026-11-03, lane #795): a declared precision above 38, and an
 * exact-unsized `BIGDECIMAL` (precision and scale omitted, §4), whose values may carry any number
 * of digits at any scale. Staging the unsized case as `DOUBLE` would round exact values, and as
 * `DECIMAL(38, s)` would invent the scale §4 forbids (defect 100). The author makes the bound
 * explicit with a `CAST` to `DECIMAL(p, s)`, p ≤ 38, in the source query.
 *
 * ## Binding
 *
 * There is no `java.sql.Types` half: DuckDB staging binds every value with the untyped
 * `setObject(index, value)` (staging.md §8.5). Measured on the pinned driver, the typed form
 * rejects `Types.VARBINARY`, `Types.DATE` and `Types.TIME` as an unknown target type and
 * silently wraps a `Long` bound as `Types.INTEGER`, while the untyped form round-trips every
 * value the source reader produces and refuses an out-of-range one with a conversion error.
 * The one silent case the untyped form leaves — a `BigDecimal` wider than 38 digits binds as
 * NULL — is closed by the staging insert path, which checks each decimal value against its
 * column before binding.
 */
object DuckDbEgressMapper {
    /** DuckDB `DECIMAL` precision ceiling (§6a). */
    const val MAX_DUCKDB_DECIMAL_PRECISION = 38

    /** The same catalogued code H2 staging raises (pipeline-contract §13.5). */
    const val PRECISION_OVERFLOW_CODE = H2EgressMapper.PRECISION_OVERFLOW_CODE

    /**
     * The DuckDB column type as written in `CREATE TABLE` — e.g. `"DECIMAL(18, 2)"`,
     * `"VARCHAR"`, `"TIMESTAMP WITH TIME ZONE"`.
     *
     * @throws DatapipelinesException with [PRECISION_OVERFLOW_CODE] when the column declares a
     *   precision above [MAX_DUCKDB_DECIMAL_PRECISION] or is an exact-unsized `BIGDECIMAL`.
     */
    fun toDuckDbType(column: ColumnSchema): String =
        when (column.type) {
            // DuckDB requires a type even for a column that will only ever hold NULLs.
            LogicalType.NULL -> "VARCHAR"

            LogicalType.BOOLEAN -> "BOOLEAN"

            LogicalType.INTEGER -> "INTEGER"

            // DuckDB BIGINT is int64, exactly BIGINTEGER's range (§3).
            LogicalType.BIGINTEGER -> "BIGINT"

            LogicalType.DECIMAL -> if (column.isApproximateNumeric) "DOUBLE" else decimalWithScale(column)

            LogicalType.BIGDECIMAL -> decimalWithScale(column)

            // Length unbounded: DuckDB VARCHAR takes no length.
            LogicalType.STRING -> "VARCHAR"

            LogicalType.BINARY -> "BLOB"

            LogicalType.DATE -> "DATE"

            LogicalType.TIME -> "TIME"

            LogicalType.TIMESTAMP -> "TIMESTAMP WITH TIME ZONE"
        }

    private fun decimalWithScale(column: ColumnSchema): String = "DECIMAL(${checkedPrecision(column)}, ${column.scale ?: 0})"

    /** The declared precision, or the refusal when DuckDB cannot store the column exactly. */
    private fun checkedPrecision(column: ColumnSchema): Int {
        val declared = column.precision ?: throw unsizedRefusal(column)
        if (declared > MAX_DUCKDB_DECIMAL_PRECISION) {
            throw DatapipelinesException(
                code = PRECISION_OVERFLOW_CODE,
                message =
                    "Column '${column.name}' declares precision $declared; DuckDB staging supports at most " +
                        "$MAX_DUCKDB_DECIMAL_PRECISION. CAST it to DECIMAL(p, s) with p ≤ $MAX_DUCKDB_DECIMAL_PRECISION " +
                        "in the source query.",
                details =
                    mapOf(
                        "column" to column.name,
                        "precision" to declared,
                        "max_precision" to MAX_DUCKDB_DECIMAL_PRECISION,
                    ),
            )
        }
        return declared
    }

    private fun unsizedRefusal(column: ColumnSchema): DatapipelinesException =
        DatapipelinesException(
            code = PRECISION_OVERFLOW_CODE,
            message =
                "Column '${column.name}' is an exact numeric with no declared precision; DuckDB staging stores " +
                    "decimals of at most $MAX_DUCKDB_DECIMAL_PRECISION digits and cannot keep an unbounded one " +
                    "exactly. CAST it to DECIMAL(p, s) with p ≤ $MAX_DUCKDB_DECIMAL_PRECISION in the source query.",
            details = mapOf("column" to column.name, "max_precision" to MAX_DUCKDB_DECIMAL_PRECISION),
        )
}
