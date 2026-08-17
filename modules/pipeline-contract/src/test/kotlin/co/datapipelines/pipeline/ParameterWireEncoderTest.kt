package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

/**
 * `ParameterWireEncoder` is the inverse of `ParameterCoercion` (pipeline-contract §6.3): for every
 * declarable type, a bound value must encode to a wire JSON form that coercion reads back to the
 * SAME value. That round trip is the whole contract — the composition runtime re-encodes a
 * parent's bound `${parent_param}` value for the child's own bind pass.
 */
class ParameterWireEncoderTest {
    @Test
    fun `every declarable type round-trips a bound value through the wire form`() {
        roundTrip(LogicalType.INTEGER, 42)
        roundTrip(LogicalType.DECIMAL, BigDecimal("12345.6789"))
        roundTrip(LogicalType.BOOLEAN, true)
        roundTrip(LogicalType.BIGINTEGER, BigInteger("9223372036854775807"))
        roundTrip(LogicalType.BIGDECIMAL, BigDecimal("12345.60"))
        roundTrip(LogicalType.STRING, "EU")
        roundTrip(LogicalType.BINARY, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        roundTrip(LogicalType.DATE, LocalDate.of(2026, 8, 5))
        roundTrip(LogicalType.TIME, LocalTime.of(14, 30, 0))
        roundTrip(LogicalType.TIME, LocalTime.of(14, 30, 0, 123456000))
        roundTrip(LogicalType.TIMESTAMP, Instant.parse("2026-08-05T19:30:00.123456Z"))
        roundTrip(LogicalType.TIMESTAMP, Instant.parse("2026-08-05T19:30:00Z"))
    }

    @Test
    fun `the string-on-wire types encode as JSON strings, never numbers`() {
        ParameterWireEncoder.encode(LogicalType.BIGINTEGER, BigInteger.TEN).isTextual shouldBe true
        ParameterWireEncoder.encode(LogicalType.BIGDECIMAL, BigDecimal("1.50")).asText() shouldBe "1.50"
        ParameterWireEncoder
            .encode(LogicalType.BINARY, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
            .asText() shouldBe Base64.getEncoder().encodeToString(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
    }

    @Test
    fun `a whole-second TIME keeps the seconds the wire shape requires`() {
        // LocalTime.toString() would render "14:30" — §6.3's HH:MM:SS shape rejects that.
        ParameterWireEncoder.encode(LogicalType.TIME, LocalTime.of(14, 30)).asText() shouldBe "14:30:00"
    }

    @Test
    fun `null encodes as JSON null, which a binder reads as not supplied`() {
        ParameterWireEncoder.encode(LogicalType.STRING, null).isNull shouldBe true
    }

    private fun roundTrip(
        type: LogicalType,
        value: Any,
    ) {
        val encoded = ParameterWireEncoder.encode(type, value)
        val outcome = ParameterCoercion.coerce(type, encoded)
        // Kotest's shouldBe compares ByteArray by content, so one assertion serves every type.
        (outcome as? ParameterCoercion.Outcome.Coerced)?.value shouldBe value
    }
}
