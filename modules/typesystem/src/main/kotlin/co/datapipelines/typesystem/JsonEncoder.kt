package co.datapipelines.typesystem

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Canonical value → wire representation (type-system.md §3.5, normative).
 *
 * These rules fix the exact bytes a client receives, and they apply to **every** egress
 * path uniformly — SSE `data_ready` payloads, the REST result cursor, and MCP tool
 * results. One encoder, so the three cannot drift.
 *
 * [encode] returns a JSON-ready value: `null`, a `Boolean`, a `Number`, or a `String`.
 * Handing Jackson a `BigDecimal` (rather than a pre-rendered string) is what keeps
 * `DECIMAL` a JSON *number* while staying exact.
 *
 * ## The rules, and why each is written the way it is
 *
 *  - **`TIMESTAMP`** — `2026-08-05T19:30:00.123456Z`: always `Z`, always exactly six
 *    fractional digits, zero-padded, sub-microsecond input truncated (never rounded).
 *    Fixed width is what lets clients sort lexicographically without parsing.
 *  - **`TIME`** — `14:30:00.123456`: six fractional digits, **no** zone designator.
 *  - **`DATE`** — `2026-08-05`: no time, no zone.
 *  - **`BINARY`** — standard RFC 4648 §4 base64 **with** `=` padding. Not the URL-safe
 *    alphabet (no `-`, no `_`), no line breaks, no `data:` prefix.
 *  - **`BIGINTEGER` / `BIGDECIMAL`** — a JSON string holding the plain decimal form: no
 *    exponent, no separators. `BIGDECIMAL` pads to its declared scale, so `12345.6` at
 *    scale 2 is emitted as `"12345.60"`.
 *  - **NULL values** — JSON `null` in any column, whatever its canonical type.
 *
 * ## Accepted input types
 *
 * Temporal columns take `java.time` values (`Instant`, `OffsetDateTime`,
 * `ZonedDateTime`, `LocalDateTime`, `LocalTime`, `OffsetTime`, `LocalDate`), which is
 * what a JDBC 4.2 `getObject(index, type)` read yields. The `java.sql.*` temporal
 * classes are rejected on purpose: their conversions resolve against the JVM default
 * zone, and silently machine-dependent output is exactly the failure §8.4 exists to
 * prevent. An unusable value raises [IllegalArgumentException] naming the column — a
 * caller bug, surfaced, not a data condition to paper over.
 */
object JsonEncoder {
    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS")

    /**
     * Encodes one value for one column.
     *
     * @return `null`, `Boolean`, `Number` or `String` — never a nested structure.
     */
    fun encode(
        value: Any?,
        column: ColumnSchema,
    ): Any? {
        // §3.5 rule 6: a NULL is JSON null regardless of the column's canonical type.
        if (value == null) return null
        return when (column.type) {
            LogicalType.NULL -> throw mismatch(column, value, "an all-NULL column carried a non-null value")
            LogicalType.BOOLEAN -> asBoolean(value, column)
            LogicalType.INTEGER -> asNumber(value, column).toLong()
            LogicalType.BIGINTEGER -> asBigInteger(value, column).toString()
            LogicalType.DECIMAL -> decimal(value, column)
            LogicalType.BIGDECIMAL -> bigDecimal(value, column)
            LogicalType.STRING -> value.toString()
            LogicalType.BINARY -> base64(value, column)
            LogicalType.DATE -> asLocalDate(value, column).format(DateTimeFormatter.ISO_LOCAL_DATE)
            LogicalType.TIME -> UtcNormalization.toLocalTime(value).format(TIME_FORMAT)
            LogicalType.TIMESTAMP -> timestamp(value)
        }
    }

    /**
     * `DECIMAL` is a JSON number either way; the omitted scale only decides how exact it
     * is allowed to pretend to be (§4.1). An approximate origin renders as a `Double` —
     * declaring fixed fractional digits for an IEEE 754 value would be a lie.
     */
    private fun decimal(
        value: Any,
        column: ColumnSchema,
    ): Number =
        if (column.scale == null) {
            asNumber(value, column).toDouble()
        } else {
            asBigDecimal(value, column)
        }

    /**
     * `BIGDECIMAL` is a plain-decimal JSON string with trailing zeros preserved **to its
     * declared scale** — `"12345.60"`, not `"12345.6"`.
     *
     * A value carrying MORE fractional digits than the declared scale is left alone
     * rather than rounded: the encoder's job is rendering, and quietly discarding digits
     * a source produced would be data loss invisible to everyone downstream.
     */
    private fun bigDecimal(
        value: Any,
        column: ColumnSchema,
    ): String {
        val decimal = asBigDecimal(value, column)
        val declaredScale = column.scale ?: 0
        val padded = if (decimal.scale() < declaredScale) decimal.setScale(declaredScale) else decimal
        return padded.toPlainString()
    }

    private fun timestamp(value: Any): String =
        LocalDateTime.ofInstant(UtcNormalization.toUtcInstant(value), ZoneOffset.UTC).format(TIMESTAMP_FORMAT)

    /** Standard base64 with `=` padding (RFC 4648 §4) — never the URL-safe alphabet. */
    private fun base64(
        value: Any,
        column: ColumnSchema,
    ): String =
        when (value) {
            is ByteArray -> Base64.getEncoder().encodeToString(value)
            else -> throw mismatch(column, value, "BINARY requires a ByteArray")
        }

    private fun asBoolean(
        value: Any,
        column: ColumnSchema,
    ): Boolean = value as? Boolean ?: throw mismatch(column, value, "BOOLEAN requires a Boolean")

    private fun asNumber(
        value: Any,
        column: ColumnSchema,
    ): Number = value as? Number ?: throw mismatch(column, value, "a numeric column requires a Number")

    private fun asBigInteger(
        value: Any,
        column: ColumnSchema,
    ): BigInteger =
        when (value) {
            is BigInteger -> value
            is BigDecimal -> value.toBigInteger()
            is Number -> BigInteger.valueOf(value.toLong())
            else -> throw mismatch(column, value, "BIGINTEGER requires a Number")
        }

    private fun asBigDecimal(
        value: Any,
        column: ColumnSchema,
    ): BigDecimal =
        when (value) {
            is BigDecimal -> value

            is BigInteger -> BigDecimal(value)

            is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((value as Number).toLong())

            // A Double reaching an exact column already lost precision upstream; render
            // what it actually is rather than inventing digits with valueOf(double).
            is Number -> BigDecimal(value.toString())

            else -> throw mismatch(column, value, "an exact numeric column requires a Number")
        }

    private fun asLocalDate(
        value: Any,
        column: ColumnSchema,
    ): LocalDate = value as? LocalDate ?: throw mismatch(column, value, "DATE requires a java.time.LocalDate")

    private fun mismatch(
        column: ColumnSchema,
        value: Any,
        expectation: String,
    ): IllegalArgumentException =
        IllegalArgumentException(
            "Column '${column.name}' is ${column.type.wire} but the value was a " +
                "${value.javaClass.name}: $expectation.",
        )
}
