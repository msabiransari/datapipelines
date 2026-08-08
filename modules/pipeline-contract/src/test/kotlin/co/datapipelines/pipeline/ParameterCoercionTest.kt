package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * pipeline-contract §6.3 — the strict coercion matrix.
 *
 * Every accepted case and every named rejection, per canonical type. §6.3 is the symmetric
 * half of the type system's wire contract: data flows in and out of a pipeline under the
 * same rules, and "wrong wire encoding is rejected, never silently converted".
 */
class ParameterCoercionTest {
    @Test
    fun `every type accepts its documented wire form`() {
        val accepted =
            mapOf(
                LogicalType.INTEGER to ("42" to 42),
                LogicalType.DECIMAL to ("1.5" to BigDecimal("1.5")),
                LogicalType.BOOLEAN to ("true" to true),
                LogicalType.BIGINTEGER to ("\"9007199254740993\"" to BigInteger("9007199254740993")),
                LogicalType.BIGDECIMAL to ("\"12345678901234567890.55\"" to BigDecimal("12345678901234567890.55")),
                LogicalType.STRING to ("\"hello\"" to "hello"),
                LogicalType.DATE to ("\"2026-08-01\"" to LocalDate.of(2026, 8, 1)),
                LogicalType.TIME to ("\"23:59:59\"" to LocalTime.of(23, 59, 59)),
                LogicalType.TIMESTAMP to ("\"2026-08-01T10:00:00Z\"" to Instant.parse("2026-08-01T10:00:00Z")),
            )

        accepted.forEach { (type, case) ->
            val (json, expected) = case
            withClue("$type accepts $json") { coerced(type, json) shouldBe expected }
        }
        // BINARY compares by content, not identity.
        (coerced(LogicalType.BINARY, "\"AAEC\"") as ByteArray).toList() shouldBe listOf<Byte>(0, 1, 2)
    }

    @Test
    fun `a JSON number where a string-on-wire type is declared is rejected`() {
        // "Accepting it would silently lose precision for values beyond IEEE 754 safe range."
        rejected(LogicalType.BIGINTEGER, "9007199254740993")
        rejected(LogicalType.BIGDECIMAL, "1.5")
    }

    @Test
    fun `a JSON string where a number-or-boolean-on-wire type is declared is rejected`() {
        rejected(LogicalType.INTEGER, "\"42\"")
        rejected(LogicalType.DECIMAL, "\"1.5\"")
        rejected(LogicalType.BOOLEAN, "\"true\"")
    }

    @Test
    fun `a zone-less TIMESTAMP is rejected - the server never guesses the client's timezone`() {
        rejected(LogicalType.TIMESTAMP, "\"2026-08-01T10:00:00\"")
        rejected(LogicalType.TIMESTAMP, "\"2026-08-01\"")
    }

    @Test
    fun `an explicit offset is accepted and normalized to UTC`() {
        coerced(LogicalType.TIMESTAMP, "\"2026-08-01T12:00:00+02:00\"") shouldBe Instant.parse("2026-08-01T10:00:00Z")
    }

    @Test
    fun `DATE and TIME must be exact ISO 8601`() {
        // Locale-ambiguous forms are the reason: 01/02/2026 is two different days.
        listOf("\"01/02/2026\"", "\"2026-8-1\"", "\"2026-08-01T00:00:00Z\"", "\"yesterday\"").forEach {
            rejected(LogicalType.DATE, it)
        }
        // ISO_LOCAL_TIME would accept "23:59"; §6.3 writes HH:MM:SS[.ffffff].
        listOf("\"23:59\"", "\"11:59 PM\"", "\"25:00:00\"").forEach { rejected(LogicalType.TIME, it) }
        coerced(LogicalType.TIME, "\"23:59:59.123456\"") shouldBe LocalTime.of(23, 59, 59, 123_456_000)
    }

    @Test
    fun `the DATE shape guard rejects the expanded-year forms LocalDate would accept`() {
        // Without the anchored `\d{4}-\d{2}-\d{2}` guard these parse: LocalDate.parse takes the
        // ISO expanded-year forms `+12026-08-01` and `-0001-08-01`. Every other case in the
        // rejection list above dies inside LocalDate.parse anyway, so these two are the only
        // ones that discriminate — without them the guard could be deleted and the suite would
        // stay green.
        listOf("\"+12026-08-01\"", "\"-0001-08-01\"").forEach { rejected(LogicalType.DATE, it) }
        LocalDate.parse("+12026-08-01").year shouldBe 12026
    }

    @Test
    fun `TIME fractional seconds are 1-6 digits - sub-microsecond input is rejected`() {
        // §6.3 (2026-08-08): "sub-microsecond input is rejected, not silently truncated".
        // LocalTime holds nanoseconds and would keep them while §3.5's egress emits
        // microseconds, so a 9-digit input would come back out shortened — a silent
        // transformation of the caller's value.
        listOf("\"23:59:59.1234567\"", "\"23:59:59.123456789\"", "\"23:59:59.\"").forEach {
            rejected(LogicalType.TIME, it)
        }
        coerced(LogicalType.TIME, "\"23:59:59.1\"") shouldBe LocalTime.of(23, 59, 59, 100_000_000)
    }

    @Test
    fun `BINARY must be PADDED base64`() {
        // §6.3 (2026-08-08). Base64.getDecoder() accepts unpadded input on its own, so the
        // length check is what makes ingress match §3.5's padded egress. Accepting on the way in
        // what we refuse to emit is exactly the asymmetry §6.3 calls the "symmetric contract".
        listOf("\"AAE\"", "\"A\"", "\"AAECA\"").forEach { rejected(LogicalType.BINARY, it) }
        (coerced(LogicalType.BINARY, "\"AAE=\"") as ByteArray).toList() shouldBe listOf<Byte>(0, 1)
        (coerced(LogicalType.BINARY, "\"\"") as ByteArray).toList() shouldBe emptyList()
    }

    @Test
    fun `INTEGER is int32 and whole`() {
        rejected(LogicalType.INTEGER, "1.5")
        rejected(LogicalType.INTEGER, "2147483648")
        coerced(LogicalType.INTEGER, "-2147483648") shouldBe Int.MIN_VALUE
    }

    @Test
    fun `BIGINTEGER is int64`() {
        // enums.md §1: "Exact integer, int64 range". A value past it would silently truncate
        // wherever it was bound to a SQL BIGINT.
        rejected(LogicalType.BIGINTEGER, "\"9223372036854775808\"")
        coerced(LogicalType.BIGINTEGER, "\"9223372036854775807\"") shouldBe BigInteger.valueOf(Long.MAX_VALUE)
    }

    @Test
    fun `malformed string payloads are rejected per type`() {
        rejected(LogicalType.BIGINTEGER, "\"12abc\"")
        rejected(LogicalType.BIGDECIMAL, "\"not-a-number\"")
        rejected(LogicalType.BINARY, "\"not base64!!\"")
    }

    @Test
    fun `a rejection reason never echoes an unbounded inbound value (CF-2)`() {
        val outcome = ParameterCoercion.coerce(LogicalType.DATE, Fixtures.json("\"${"x".repeat(500)}\""))

        outcome.shouldBeInstanceOf<ParameterCoercion.Outcome.Rejected>()
        outcome.reason.contains("x".repeat(MAX_REFLECTED_VALUE_LENGTH + 1)) shouldBe false
    }

    private fun coerced(
        type: LogicalType,
        json: String,
    ): Any {
        val outcome = ParameterCoercion.coerce(type, Fixtures.json(json))
        outcome.shouldBeInstanceOf<ParameterCoercion.Outcome.Coerced>()
        return outcome.value
    }

    private fun rejected(
        type: LogicalType,
        json: String,
    ) {
        withClue("$type must reject $json") {
            ParameterCoercion
                .coerce(type, Fixtures.json(json))
                .shouldBeInstanceOf<ParameterCoercion.Outcome.Rejected>()
        }
    }
}
