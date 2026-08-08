package co.datapipelines.typesystem

import java.sql.Types

/**
 * H2 → canonical (type-system.md §5.5; staging.md §5.3).
 *
 * Two callers, one table:
 *  - reading **staged** data back out for the caller node (staging.md §6), via [fromH2];
 *  - H2 used as an ordinary **source** dialect, via the [IngressTypeMapper] surface.
 *
 * This is the ingress half only. [H2EgressMapper] goes the other way and is **not** its
 * inverse — see the split rationale in staging.md §5.3.
 *
 * ## Why name-first, and why [fromH2] can do without a name
 *
 * The §5.5 table is keyed on H2 **type names**, not JDBC codes, and several of its rows
 * (`UUID`, `JSON`, `ENUM`, `GEOMETRY`, `INTERVAL *`) are reported by H2 under codes that
 * would otherwise read as something else — `UUID` in particular must not become
 * `BINARY`. So the name is consulted first ([BY_NAME]) and the code is the backstop
 * ([BY_CODE]).
 *
 * [fromH2] has no name parameter — that is staging.md §5.3's signature, and it is
 * sufficient there: a staged table's columns are created by [H2EgressMapper] and can
 * therefore only be the eleven H2 types listed in §6, every one of which is unambiguous
 * by code alone. The name-only rows are unreachable through that path.
 */
object H2IngressMapper : DialectTypeMapper() {
    private val EXACT_NUMERIC_NAMES = setOf("numeric", "decimal", "dec")

    /** §5.5, keyed on the H2 type name (parameters stripped, lowercased). */
    override val recognizedTypeNames: Map<String, LogicalTypeMapping> get() = BY_NAME

    override val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = BY_CODE

    private val BY_NAME: Map<String, LogicalTypeMapping> =
        buildMap {
            listOf("tinyint", "smallint", "integer", "int", "mediumint").forEach { put(it, AS_INTEGER) }
            put("bigint", AS_BIGINTEGER)
            put("real", APPROXIMATE_SINGLE)
            // H2's FLOAT aliases DOUBLE.
            listOf("double", "double precision", "float").forEach { put(it, APPROXIMATE_DOUBLE) }
            listOf("boolean", "bool", "bit", "true", "false").forEach { put(it, AS_BOOLEAN) }
            put("date", AS_DATE)
            listOf("time", "time without time zone").forEach { put(it, AS_TIME) }
            listOf("timestamp", "timestamp without time zone", "timestamp with time zone")
                .forEach { put(it, AS_TIMESTAMP) }
            listOf(
                "varchar",
                "varchar_ignorecase",
                "char",
                "character",
                "character varying",
                "clob",
                "text",
                "string",
                "longvarchar",
                // UUID (canonical text form), JSON, ENUM labels and GEOMETRY (WKT) are
                // all STRING in v1 — see §8.6, §8.7 and §12.
                "uuid",
                "json",
                "enum",
                "geometry",
            ).forEach { put(it, AS_STRING) }
            listOf("binary", "varbinary", "blob", "binary varying", "binary large object", "longvarbinary")
                .forEach { put(it, AS_BINARY) }
        }

    /** §5.5, keyed on the JDBC code — the only route [fromH2] has. */
    private val BY_CODE: Map<Int, LogicalTypeMapping> =
        mapOf(
            Types.TINYINT to AS_INTEGER,
            Types.SMALLINT to AS_INTEGER,
            Types.INTEGER to AS_INTEGER,
            Types.BIGINT to AS_BIGINTEGER,
            Types.REAL to APPROXIMATE_SINGLE,
            Types.DOUBLE to APPROXIMATE_DOUBLE,
            Types.FLOAT to APPROXIMATE_DOUBLE,
            Types.BOOLEAN to AS_BOOLEAN,
            Types.BIT to AS_BOOLEAN,
            Types.DATE to AS_DATE,
            Types.TIME to AS_TIME,
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

    /**
     * Builds the canonical descriptor for one column of an H2 `ResultSet`
     * (staging.md §5.3).
     *
     * `label` is already validated against the §4.5 identifier rule by the caller;
     * `jdbcType` / `precision` / `scale` come from `ResultSetMetaData`.
     */
    fun fromH2(
        label: String,
        jdbcType: Int,
        precision: Int,
        scale: Int,
    ): ColumnSchema = map(jdbcType, precision, scale, typeName = "").toColumnSchema(label)

    override fun mapRecognized(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping? {
        val name = normalizeTypeName(typeName)
        return when {
            name in EXACT_NUMERIC_NAMES -> h2ExactNumeric(precision, scale)

            // INTERVAL YEAR, INTERVAL DAY TO SECOND, … — every variant is text form.
            name.startsWith("interval") -> AS_STRING

            name.isNotEmpty() && BY_NAME.containsKey(name) -> BY_NAME.getValue(name)

            sqlType == Types.NUMERIC || sqlType == Types.DECIMAL -> h2ExactNumeric(precision, scale)

            else -> BY_CODE[sqlType]
        }
    }

    /**
     * Exact numeric read back **out of H2**, with the §6 round-trip rule applied.
     *
     * An unbounded `BIGDECIMAL` (precision omitted, §4) stages as
     * `DECIMAL(100000, s)`. Reading that column back through the generic
     * [exactNumeric] would report `BIGDECIMAL(100000, s)` — reintroducing on egress
     * exactly the fabricated bound §4 forbids on ingress, with the storage ceiling
     * masquerading as a property of the source column. So a reported precision **at or
     * above the ceiling** maps back to the unbounded encoding.
     *
     * The rule's accepted consequence (§6, normative): a genuine H2 *source* column
     * declared `DECIMAL(100000, s)` also reports unbounded. That column sits at H2's own
     * maximum, so "unbounded" is not a lie about it either.
     *
     * This is H2-only. No other dialect can declare a precision anywhere near the
     * ceiling, and generalising it would let a real bound be erased somewhere it means
     * something.
     */
    private fun h2ExactNumeric(
        precision: Int,
        scale: Int,
    ): LogicalTypeMapping =
        if (precision >= H2EgressMapper.MAX_H2_DECIMAL_PRECISION) {
            unboundedNumeric(scale)
        } else {
            exactNumeric(precision, scale)
        }
}
