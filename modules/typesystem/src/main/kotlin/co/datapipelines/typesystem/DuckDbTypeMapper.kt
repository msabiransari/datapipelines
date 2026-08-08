package co.datapipelines.typesystem

import java.sql.Types

/**
 * DuckDB source → canonical (type-system.md §5.6).
 *
 * The §5.6 table is keyed on DuckDB **type names** (it declares no JDBC codes), so
 * [BY_NAME] is primary and [BY_CODE] is the backstop.
 *
 * ## 128-bit integers
 *
 * `HUGEINT` / `UHUGEINT` are 128-bit: no canonical integer type can hold them, and
 * `BIGINTEGER` promises int64. They map to `BIGDECIMAL(38, 0)` — a decimal string on the
 * wire, which is lossless, rather than an integer type that would silently overflow.
 *
 * ## Nested and geospatial types
 *
 * `LIST`, `STRUCT`, `MAP`, `UNION` serialize to `STRING` (§5.6, v1 fallback; canonical
 * nested types are a §12 v2 item). DuckDB reports a list as its element type with a
 * `[]` suffix (`INTEGER[]`), which is why the suffix is checked **before** anything
 * else — otherwise `INTEGER[]` would read as `INTEGER` and lie about the column.
 */
object DuckDbTypeMapper : DialectTypeMapper() {
    /** 128-bit integers exceed int64; §5.6 fixes them at this precision. */
    private const val HUGEINT_PRECISION = 38

    private val HUGEINT = LogicalTypeMapping(LogicalType.BIGDECIMAL, HUGEINT_PRECISION, scale = 0)

    private val EXACT_NUMERIC_NAMES = setOf("decimal", "numeric")

    /** §5.6, keyed on the DuckDB type name (parameters stripped, lowercased). */
    override val recognizedTypeNames: Map<String, LogicalTypeMapping> get() = BY_NAME

    override val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = BY_CODE

    private val BY_NAME: Map<String, LogicalTypeMapping> =
        buildMap {
            listOf("tinyint", "smallint", "integer", "int", "signed").forEach { put(it, AS_INTEGER) }
            put("bigint", AS_BIGINTEGER)
            listOf("hugeint", "uhugeint").forEach { put(it, HUGEINT) }
            put("float", APPROXIMATE_SINGLE)
            put("double", APPROXIMATE_DOUBLE)
            listOf("boolean", "bool", "logical").forEach { put(it, AS_BOOLEAN) }
            put("date", AS_DATE)
            // `time with time zone` drops its zone: canonical TIME has none (§3).
            listOf("time", "time without time zone", "time with time zone").forEach { put(it, AS_TIME) }
            listOf("timestamp", "timestamp without time zone", "timestamp with time zone", "timestamptz")
                .forEach { put(it, AS_TIMESTAMP) }
            listOf(
                "varchar",
                "char",
                "text",
                "string",
                "bpchar",
                "uuid",
                "json",
                "interval",
                // Nested types serialize as text in v1.
                "list",
                "struct",
                "map",
                "union",
            ).forEach { put(it, AS_STRING) }
            listOf("blob", "bytea", "varbinary", "binary").forEach { put(it, AS_BINARY) }
        }

    /** §5.6 by JDBC code, for a driver build that reports a name this table lacks. */
    private val BY_CODE: Map<Int, LogicalTypeMapping> =
        mapOf(
            Types.TINYINT to AS_INTEGER,
            Types.SMALLINT to AS_INTEGER,
            Types.INTEGER to AS_INTEGER,
            Types.BIGINT to AS_BIGINTEGER,
            // DuckDB FLOAT is 4-byte — unlike Oracle's FLOAT, which is double precision.
            Types.REAL to APPROXIMATE_SINGLE,
            Types.FLOAT to APPROXIMATE_SINGLE,
            Types.DOUBLE to APPROXIMATE_DOUBLE,
            Types.BOOLEAN to AS_BOOLEAN,
            Types.BIT to AS_BOOLEAN,
            Types.DATE to AS_DATE,
            Types.TIME to AS_TIME,
            Types.TIME_WITH_TIMEZONE to AS_TIME,
            Types.TIMESTAMP to AS_TIMESTAMP,
            Types.TIMESTAMP_WITH_TIMEZONE to AS_TIMESTAMP,
            Types.VARCHAR to AS_STRING,
            Types.CHAR to AS_STRING,
            Types.LONGVARCHAR to AS_STRING,
            Types.CLOB to AS_STRING,
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
            // A list is reported as `<element>[]`; checked first so INTEGER[] is not
            // mistaken for INTEGER.
            name.endsWith("[]") -> AS_STRING

            name in EXACT_NUMERIC_NAMES -> exactNumeric(precision, scale)

            name.isNotEmpty() && BY_NAME.containsKey(name) -> BY_NAME.getValue(name)

            sqlType == Types.DECIMAL || sqlType == Types.NUMERIC -> exactNumeric(precision, scale)

            else -> BY_CODE[sqlType]
        }
    }
}
