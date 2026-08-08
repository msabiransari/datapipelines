package co.datapipelines.typesystem

import java.sql.Types

/**
 * Oracle source → canonical (type-system.md §5.2).
 *
 * ## The `DATE` gotcha (§5.2, normative policy)
 *
 * Oracle's `DATE` stores date **and** time-of-day. Mapping it to canonical `DATE` would
 * silently truncate the time component — a data-loss bug. The policy is unconditional:
 * **Oracle `DATE` → canonical `TIMESTAMP`**, carrying `T00:00:00Z` when the source data
 * really is date-only. Honest and lossless.
 *
 * The §5.2 table gives `DATE` the JDBC code `TIMESTAMP` (93), which is what ojdbc
 * reports under its default `mapDateToTimestamp=true`. `Types.DATE` (91) maps to
 * canonical `TIMESTAMP` **as well**, so the policy holds even for a driver or
 * configuration that reports the other code. Canonical `DATE` is unreachable from
 * Oracle by design.
 *
 * ## `NUMBER` (§5.2, §8.5)
 *
 * Oracle funnels almost every numeric through `NUMBER(p, s)`, so the canonical decision
 * is arithmetic on the reported precision and scale — see [number]:
 *
 * | scale | precision | canonical |
 * |---|---|---|
 * | 0 | unsized (driver reports 0) | `BIGDECIMAL(38, 0)` — Oracle's documented default |
 * | 0 | ≤ 9 | `INTEGER` (fits int32) |
 * | 0 | 10–18 | `BIGINTEGER` (fits int64) |
 * | 0 | > 18 | `BIGDECIMAL(p, 0)` |
 * | > 0 | ≤ 15 | `DECIMAL(p, s)` |
 * | > 0 | > 15 | `BIGDECIMAL(p, s)` |
 *
 * **`NUMBER(1)` is NOT promoted to `BOOLEAN`** (§8.5). Some frameworks infer boolean
 * from it; we map by source type, not by guessed intent, so it lands in `INTEGER` above.
 * Authors who want boolean semantics write `CASE WHEN col = 1 THEN true ELSE false END`.
 */
object OracleTypeMapper : DialectTypeMapper() {
    /** Oracle's documented default precision for an unsized `NUMBER` (§4). */
    private const val ORACLE_DEFAULT_NUMBER_PRECISION = 38

    /** Largest `NUMBER(p, 0)` that fits int32 → canonical `INTEGER`. */
    private const val MAX_INT32_DIGITS = 9

    /** Largest `NUMBER(p, 0)` that fits int64 → canonical `BIGINTEGER`. */
    private const val MAX_INT64_DIGITS = 18

    private val UNSIZED_NUMBER =
        LogicalTypeMapping(LogicalType.BIGDECIMAL, ORACLE_DEFAULT_NUMBER_PRECISION, scale = 0)

    /** §5.2, every row decided by the JDBC code alone. */
    override val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = BY_CODE

    private val BY_CODE: Map<Int, LogicalTypeMapping> =
        mapOf(
            Types.TINYINT to AS_INTEGER,
            Types.SMALLINT to AS_INTEGER,
            Types.INTEGER to AS_INTEGER,
            Types.BIGINT to AS_BIGINTEGER,
            // Oracle FLOAT(p) counts p in binary bits and is treated as double
            // precision; BINARY_FLOAT is REAL, BINARY_DOUBLE is DOUBLE.
            Types.FLOAT to APPROXIMATE_DOUBLE,
            Types.DOUBLE to APPROXIMATE_DOUBLE,
            Types.REAL to APPROXIMATE_SINGLE,
            // 23c+ only; PL/SQL-only before that.
            Types.BOOLEAN to AS_BOOLEAN,
            // See the DATE gotcha above — 91 and 93 both land on canonical TIMESTAMP.
            Types.DATE to AS_TIMESTAMP,
            Types.TIMESTAMP to AS_TIMESTAMP,
            // TIMESTAMP WITH [LOCAL] TIME ZONE, both normalized to UTC.
            Types.TIMESTAMP_WITH_TIMEZONE to AS_TIMESTAMP,
            // CHAR/NCHAR, VARCHAR2/NVARCHAR2, CLOB/NCLOB/LONG, ROWID/UROWID, XMLType,
            // and both INTERVAL families (reported as OTHER).
            Types.CHAR to AS_STRING,
            Types.NCHAR to AS_STRING,
            Types.VARCHAR to AS_STRING,
            Types.NVARCHAR to AS_STRING,
            Types.LONGVARCHAR to AS_STRING,
            Types.LONGNVARCHAR to AS_STRING,
            Types.CLOB to AS_STRING,
            Types.NCLOB to AS_STRING,
            Types.ROWID to AS_STRING,
            Types.STRUCT to AS_STRING,
            Types.SQLXML to AS_STRING,
            Types.OTHER to AS_STRING,
            // BLOB, RAW, LONG RAW, BFILE.
            Types.BINARY to AS_BINARY,
            Types.VARBINARY to AS_BINARY,
            Types.LONGVARBINARY to AS_BINARY,
            Types.BLOB to AS_BINARY,
        )

    override fun mapRecognized(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping? =
        when (sqlType) {
            Types.NUMERIC, Types.DECIMAL -> number(precision, scale)
            else -> BY_CODE[sqlType]
        }

    private fun number(
        precision: Int,
        scale: Int,
    ): LogicalTypeMapping =
        when {
            // Unsized NUMBER: Oracle documents a 38-digit default, so — unlike PG (§4) —
            // a real bound exists and is reported. This is not the unbounded case.
            precision <= 0 -> UNSIZED_NUMBER

            // scale <= 0 also covers Oracle's legal negative scales (NUMBER(10, -2)),
            // which the §7.1 envelope cannot express; they join the integer-shaped branch.
            scale > 0 -> exactNumeric(precision, scale)

            precision <= MAX_INT32_DIGITS -> AS_INTEGER

            precision <= MAX_INT64_DIGITS -> AS_BIGINTEGER

            else -> LogicalTypeMapping(LogicalType.BIGDECIMAL, precision, scale = 0)
        }
}
