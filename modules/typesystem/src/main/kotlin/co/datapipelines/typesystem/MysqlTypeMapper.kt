package co.datapipelines.typesystem

import java.sql.Types

/**
 * MySQL / MariaDB source → canonical (type-system.md §5.4).
 *
 * **We map by what the driver reports, not by the declared name** (§5.4 preamble).
 * MySQL's `BOOLEAN` is an alias for `TINYINT(1)`, and whether the driver surfaces it as
 * `BIT`/`BOOLEAN` or as `TINYINT` is a connection-property decision; both routes are
 * handled, and neither is second-guessed.
 *
 * Four rows need a discriminator beyond the JDBC code:
 *
 *  - `bit(n)` shares `Types.BIT` with the boolean alias. The reported precision is `n`:
 *    `n ≤ 1` is the boolean, `n > 1` is a bit-string and maps to `BINARY`.
 *  - `year(2)` / `year(4)` are reported under `Types.DATE` but are 4-digit years, not
 *    calendar dates → `INTEGER`. Matched by name.
 *  - Geometry types are reported under `Types.BINARY` but map to `STRING` (WKT text, the
 *    v1 fallback for geospatial — §12). Matched by name, so real `binary`/`varbinary`
 *    columns still reach `BINARY`.
 *  - `decimal`/`numeric` split on precision (§3.3).
 */
object MysqlTypeMapper : DialectTypeMapper() {
    /** `bit(n)` with n above this is a bit-string, not the boolean alias. */
    private const val MAX_BOOLEAN_BIT_WIDTH = 1

    private val GEOMETRY_TYPE_NAMES =
        setOf(
            "geometry",
            "point",
            "linestring",
            "polygon",
            "multipoint",
            "multilinestring",
            "multipolygon",
            "geomcollection",
            "geometrycollection",
        )

    /** §5.4, every row decided by the JDBC code alone. */
    override val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = BY_CODE

    private val BY_CODE: Map<Int, LogicalTypeMapping> =
        mapOf(
            Types.BOOLEAN to AS_BOOLEAN,
            // tinyint (signed 8-bit), smallint, mediumint (24-bit), int.
            Types.TINYINT to AS_INTEGER,
            Types.SMALLINT to AS_INTEGER,
            Types.INTEGER to AS_INTEGER,
            Types.BIGINT to AS_BIGINTEGER,
            // MySQL `float` is reported as REAL; `double`/`double precision`/`real` as DOUBLE.
            Types.REAL to APPROXIMATE_SINGLE,
            Types.DOUBLE to APPROXIMATE_DOUBLE,
            Types.DATE to AS_DATE,
            // Fractional seconds (fsp) survive into the ISO string; see §3.5.
            Types.TIME to AS_TIME,
            // datetime and timestamp alike, normalized to UTC.
            Types.TIMESTAMP to AS_TIMESTAMP,
            // char, varchar, the four text sizes, enum/set labels, and json.
            Types.CHAR to AS_STRING,
            Types.VARCHAR to AS_STRING,
            Types.LONGVARCHAR to AS_STRING,
            Types.CLOB to AS_STRING,
            // binary, varbinary and the four blob sizes.
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
            name == "year" -> AS_INTEGER

            name in GEOMETRY_TYPE_NAMES -> AS_STRING

            // bit(1)/bool/tinyint(1) → BOOLEAN; bit(n>1) → BINARY.
            sqlType == Types.BIT -> if (precision > MAX_BOOLEAN_BIT_WIDTH) AS_BINARY else AS_BOOLEAN

            sqlType == Types.DECIMAL || sqlType == Types.NUMERIC -> exactNumeric(precision, scale)

            else -> BY_CODE[sqlType]
        }
    }
}
