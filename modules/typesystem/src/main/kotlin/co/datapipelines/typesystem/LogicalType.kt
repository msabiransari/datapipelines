package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The 11 canonical logical types (type-system.md §3) — the API contract between
 * datapipelines.co and every client.
 *
 * The set is frozen in v1 (§9.1): names, spellings and wire encodings never change,
 * and new types arrive only under a `schema_version` bump (§9.3).
 *
 * ## Reading the type name is enough
 *
 * §2 principle 2: the presence of `BIG` in a name is the wire signal — the value space
 * exceeds the IEEE 754 double-safe range, so the value arrives as a JSON **string**.
 * A client switching on the type name knows what to parse without extra metadata.
 * [wireForm] makes that machine-readable; [isStringOnWire] is the direct predicate.
 *
 * Wire values are UPPER and coincide with the constant names (enums.md §1), but the
 * `@JsonValue` / `@JsonCreator` mapping is still explicit and mandatory: the catalog
 * string, not `Enum.name`, is the source of truth (enums.md "Case & serialization
 * convention").
 */
enum class LogicalType(
    @JsonValue val wire: String,
    val wireForm: JsonWireForm,
) {
    /** Column contains only NULL values; the driver reported `java.sql.Types.NULL` (§8.1). */
    NULL("NULL", JsonWireForm.NULL),

    /** Two-valued logic: `true` / `false` / `null`. */
    BOOLEAN("BOOLEAN", JsonWireForm.BOOLEAN),

    /** Exact integer fitting in int32; entirely inside the double-safe range (§3.2). */
    INTEGER("INTEGER", JsonWireForm.NUMBER),

    /** Exact integer up to int64. Exceeds 2^53 − 1, so it travels as a string (§3.2). */
    BIGINTEGER("BIGINTEGER", JsonWireForm.STRING),

    /**
     * Exact numeric with precision ≤ 15, or an approximate numeric collapsed to its
     * representable precision (§3.4). A **present** scale means an exact-numeric origin;
     * an **omitted** scale means the source was REAL/FLOAT/DOUBLE.
     */
    DECIMAL("DECIMAL", JsonWireForm.NUMBER),

    /**
     * Exact numeric with precision > 15 or unbounded. Scale is always declared;
     * precision is omitted when the source numeric is unsized (§4 — omitted means
     * unbounded, never "unknown").
     */
    BIGDECIMAL("BIGDECIMAL", JsonWireForm.STRING),

    /** Variable-length text; also the home of JSON/XML/UUID/interval/enum/WKT sources. */
    STRING("STRING", JsonWireForm.STRING),

    /** Variable-length bytes, standard RFC 4648 §4 base64 with padding on the wire (§3.5). */
    BINARY("BINARY", JsonWireForm.STRING),

    /** Calendar date, no time component, no zone. */
    DATE("DATE", JsonWireForm.STRING),

    /** Time of day, no date, no zone — "timezone-of-day" is a non-concept. */
    TIME("TIME", JsonWireForm.STRING),

    /** Date and time normalized to UTC on ingest; always rendered with a `Z` suffix. */
    TIMESTAMP("TIMESTAMP", JsonWireForm.STRING),
    ;

    /**
     * True for the types §2 principle 2 marks as string-on-wire because their value
     * space exceeds what an IEEE 754 double represents exactly.
     *
     * Deliberately derived from [wireForm] rather than from a `BIG` name prefix: the
     * name is the *client-facing* signal, the wire form is the *contract*, and only
     * one of the two should be load-bearing in code.
     */
    val isStringOnWire: Boolean get() = wireForm == JsonWireForm.STRING

    companion object {
        /**
         * Resolves a wire value to its constant.
         *
         * Throws [IllegalArgumentException] on an unknown value rather than returning
         * null: an unrecognized canonical type in an inbound payload is a contract
         * violation, not a missing optional.
         */
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): LogicalType =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown LogicalType: $value")
    }
}

/**
 * The JSON form a canonical type takes on the wire (type-system.md §3.1).
 *
 * This is the encoding, not the type: several canonical types share `STRING`
 * (`BIGINTEGER`, `BIGDECIMAL`, `STRING`, `BINARY`, `DATE`, `TIME`, `TIMESTAMP`),
 * which is exactly the point — a client knows how to *parse* before it knows how to
 * *interpret*.
 */
enum class JsonWireForm {
    /** JSON `null`. */
    NULL,

    /** JSON `true` / `false`. */
    BOOLEAN,

    /** JSON number. */
    NUMBER,

    /** JSON string. */
    STRING,
}
