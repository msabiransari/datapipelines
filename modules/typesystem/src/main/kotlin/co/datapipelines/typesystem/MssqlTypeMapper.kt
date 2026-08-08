package co.datapipelines.typesystem

import java.sql.Types

/**
 * Microsoft SQL Server source → canonical (type-system.md §5.3).
 *
 * Most of §5.3 is a plain code lookup ([BY_CODE]). Four rows need more:
 *
 *  - `money` → `BIGDECIMAL(19, 4)` and `smallmoney` → `DECIMAL(10, 4)` (§8.8) have no
 *    distinct JDBC code, so they are matched by **name**. Note the scale differs from
 *    PostgreSQL `money` (19, 2) — per-dialect facts, not a shared "money" concept.
 *  - `decimal`/`numeric` split on precision (§3.3).
 *  - `float(p)` splits on the declared width (see below).
 *  - `sql_variant` maps to `STRING` **plus** a `type_mapping.sql_variant` warning.
 *
 * ## `sql_variant` (§5.3 policy)
 *
 * A `sql_variant` column can hold a different type in every row, so no single canonical
 * type is truthful. The mapper picks `STRING`, values serialize via their underlying
 * type's `toString`, and the author is told so in the response `warnings` array. This is
 * the one case where a *recognized* type still raises a warning — the mapping succeeded,
 * the fidelity did not. `Types.OTHER` carrying any other name is left unrecognized so it
 * takes the §8.2 fallback instead of being waved through as text.
 *
 * ## `float(p)` (§5.3, implementation caveat)
 *
 * The table splits on the **declared bit width**: `p ≤ 24` is single precision
 * (`DECIMAL(7)`), `25–53` and the unparameterised `float` are double (`DECIMAL(15)`).
 * That is implemented literally against the `precision` argument. Be aware that
 * `ResultSetMetaData.getPrecision()` reports *decimal digits* (7 or 15), not bits — in
 * practice SQL Server reports `float(1..24)` under `Types.REAL`, which the REAL row
 * already maps to `DECIMAL(7)`, so the two paths agree. Flagged rather than "corrected"
 * here: changing the discriminator is a spec change, not an implementation call.
 */
object MssqlTypeMapper : DialectTypeMapper() {
    private const val MONEY_PRECISION = 19
    private const val MONEY_SCALE = 4
    private const val SMALLMONEY_PRECISION = 10
    private const val SMALLMONEY_SCALE = 4

    /** Largest `float(p)` SQL Server stores as single precision. */
    private const val MAX_SINGLE_PRECISION_BITS = 24

    private const val SQL_VARIANT = "sql_variant"

    private val MONEY = LogicalTypeMapping(LogicalType.BIGDECIMAL, MONEY_PRECISION, MONEY_SCALE)
    private val SMALLMONEY = LogicalTypeMapping(LogicalType.DECIMAL, SMALLMONEY_PRECISION, SMALLMONEY_SCALE)

    /** §5.3, every row decided by the JDBC code alone. */
    override val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = BY_CODE

    private val BY_CODE: Map<Int, LogicalTypeMapping> =
        mapOf(
            // `bit` is semantically boolean in MSSQL (0/1/null).
            Types.BIT to AS_BOOLEAN,
            // tinyint is unsigned 8-bit (0-255).
            Types.TINYINT to AS_INTEGER,
            Types.SMALLINT to AS_INTEGER,
            Types.INTEGER to AS_INTEGER,
            Types.BIGINT to AS_BIGINTEGER,
            Types.REAL to APPROXIMATE_SINGLE,
            Types.DATE to AS_DATE,
            Types.TIME to AS_TIME,
            // datetime, datetime2, smalldatetime.
            Types.TIMESTAMP to AS_TIMESTAMP,
            // datetimeoffset.
            Types.TIMESTAMP_WITH_TIMEZONE to AS_TIMESTAMP,
            // char/nchar (also uniqueidentifier), varchar/nvarchar, text/ntext, xml.
            Types.CHAR to AS_STRING,
            Types.NCHAR to AS_STRING,
            Types.VARCHAR to AS_STRING,
            Types.NVARCHAR to AS_STRING,
            Types.LONGVARCHAR to AS_STRING,
            Types.LONGNVARCHAR to AS_STRING,
            Types.CLOB to AS_STRING,
            Types.SQLXML to AS_STRING,
            // binary, varbinary, image.
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
    ): LogicalTypeMapping? {
        val name = normalizeTypeName(typeName)
        return when {
            name == "money" -> MONEY
            name == "smallmoney" -> SMALLMONEY
            sqlType == Types.DECIMAL || sqlType == Types.NUMERIC -> exactNumeric(precision, scale)
            sqlType == Types.FLOAT -> float(precision)
            sqlType == Types.OTHER -> if (name == SQL_VARIANT) AS_STRING else null
            else -> BY_CODE[sqlType]
        }
    }

    override fun warningForRecognized(
        name: String,
        sqlType: Int,
        typeName: String,
    ): TypeMappingWarning? = if (normalizeTypeName(typeName) == SQL_VARIANT) TypeMappingWarning.sqlVariant(name) else null

    private fun float(precision: Int): LogicalTypeMapping =
        if (precision in 1..MAX_SINGLE_PRECISION_BITS) APPROXIMATE_SINGLE else APPROXIMATE_DOUBLE
}
