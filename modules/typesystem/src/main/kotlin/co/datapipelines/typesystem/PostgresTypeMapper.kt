package co.datapipelines.typesystem

import java.sql.Types

/**
 * PostgreSQL source → canonical (type-system.md §5.1).
 *
 * The §5.1 table is transcribed as a literal lookup ([BY_CODE]) for every row whose
 * answer depends on nothing but the JDBC code. Only three rows need more, and each is
 * handled explicitly above the lookup:
 *
 *  - `money` has no distinct JDBC code (the table's code column is `—`), so it is
 *    matched by **name** and fixed at `BIGDECIMAL(19, 2)` (§8.8).
 *  - `numeric`/`decimal` depend on the reported precision (see [numeric]).
 *  - `Types.BIT` is reported both for `bool` (canonical `BOOLEAN`) and for `bit(n)` /
 *    `varbit(n)` (canonical `STRING`, the bit-string text form), so the name decides.
 *
 * ## Unsized `numeric` (§4, adjudicated in v1.1)
 *
 * PostgreSQL's `numeric`/`decimal` declared without precision is genuinely unbounded,
 * and the driver reports precision `0`. The envelope then carries `BIGDECIMAL` with the
 * **precision key absent** and `scale: 0`. That is the one rule for the unbounded case:
 * neither PostgreSQL's internal maximum nor another dialect's default may stand in — a
 * fabricated bound would be a lie about the source column and would break clients that
 * size local decimal buffers from it.
 *
 * ## What is deliberately NOT mapped
 *
 * Only rows present in §5.1 are recognized. Anything else — including
 * `TIME_WITH_TIMEZONE` (2013), which some driver versions report for `timetz` where the
 * table says `TIME` (92) — returns `null` and takes the §8.2 fallback: `STRING` plus one
 * warning, never a throw. Widening the table is a spec change, not an implementation
 * decision.
 */
object PostgresTypeMapper : DialectTypeMapper() {
    private const val MONEY_PRECISION = 19
    private const val MONEY_SCALE = 2

    private val MONEY = LogicalTypeMapping(LogicalType.BIGDECIMAL, MONEY_PRECISION, MONEY_SCALE)

    /** `bit(n)` / `varbit(n)` — bit strings, not booleans, despite sharing `Types.BIT`. */
    private val BIT_STRING_TYPE_NAMES = setOf("bit", "varbit")

    /** §5.1, every row decided by the JDBC code alone. */
    override val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = BY_CODE

    private val BY_CODE: Map<Int, LogicalTypeMapping> =
        mapOf(
            // int2/smallint/int2vector, int4/integer/serial.
            Types.SMALLINT to AS_INTEGER,
            Types.INTEGER to AS_INTEGER,
            // int8/bigint/bigserial, plus oid and the system integers.
            Types.BIGINT to AS_BIGINTEGER,
            Types.REAL to APPROXIMATE_SINGLE,
            Types.DOUBLE to APPROXIMATE_DOUBLE,
            Types.BOOLEAN to AS_BOOLEAN,
            // char/bpchar, varchar/text — and uuid, json/jsonb, xml, interval, enum
            // labels and the geometric/network types, all reported as OTHER.
            Types.CHAR to AS_STRING,
            Types.VARCHAR to AS_STRING,
            Types.OTHER to AS_STRING,
            Types.ARRAY to AS_STRING,
            Types.BINARY to AS_BINARY,
            Types.DATE to AS_DATE,
            // timetz's zone is dropped: canonical TIME has no zone.
            Types.TIME to AS_TIME,
            Types.TIMESTAMP to AS_TIMESTAMP,
            Types.TIMESTAMP_WITH_TIMEZONE to AS_TIMESTAMP,
        )

    override fun mapRecognized(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping? {
        val name = normalizeTypeName(typeName)
        return when {
            name == "money" -> MONEY

            // exactNumeric routes an unreported precision (PG's unsized numeric) to the
            // §4 unbounded encoding — precision omitted, scale kept.
            sqlType == Types.NUMERIC -> exactNumeric(precision, scale)

            sqlType == Types.BIT -> if (name in BIT_STRING_TYPE_NAMES) AS_STRING else AS_BOOLEAN

            else -> BY_CODE[sqlType]
        }
    }
}
