package co.datapipelines.typesystem

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * The §3.5 egress serialization rules, which are **normative** and fix the exact bytes a
 * client receives on every path — SSE `data_ready`, the REST cursor, and MCP results.
 *
 * Every temporal assertion uses an explicit zone or offset, so these tests pin the same
 * behavior on a machine whose default zone is not UTC. That is deliberate: §8.4's
 * `-Duser.timezone=UTC` is a *deployment* precondition, and a test suite that only passes
 * on a UTC machine would silently stop testing the rule on anyone else's laptop.
 */
class JsonEncoderTest {
    private fun column(
        type: LogicalType,
        precision: Int? = null,
        scale: Int? = null,
    ) = ColumnSchema("c", type, precision, scale)

    @Test
    fun `TIMESTAMP renders exactly six fractional digits, including all zeros`() {
        val ts = column(LogicalType.TIMESTAMP)

        // The §3.5 worked example.
        JsonEncoder.encode(OffsetDateTime.parse("2026-08-05T19:30:00.123456Z"), ts) shouldBe
            "2026-08-05T19:30:00.123456Z"
        // The all-zeros case: never "…T19:30:00Z", never "…19:30:00.0Z". Fixed width is
        // what lets clients sort lexicographically without parsing.
        JsonEncoder.encode(OffsetDateTime.parse("2026-08-05T19:30:00Z"), ts) shouldBe
            "2026-08-05T19:30:00.000000Z"
        // Fewer digits at the source are padded, not shortened.
        JsonEncoder.encode(OffsetDateTime.parse("2026-08-05T19:30:00.5Z"), ts) shouldBe
            "2026-08-05T19:30:00.500000Z"
    }

    @Test
    fun `sub-microsecond precision truncates and never rounds up`() {
        // §3.5: rounding up could move a value into the next second — a timestamp that
        // reads later than the event it records is a worse lie than a shorter one.
        val ts = column(LogicalType.TIMESTAMP)

        JsonEncoder.encode(Instant.parse("2026-08-05T19:30:00.123456789Z"), ts) shouldBe
            "2026-08-05T19:30:00.123456Z"
        JsonEncoder.encode(Instant.parse("2026-08-05T19:30:00.999999999Z"), ts) shouldBe
            "2026-08-05T19:30:00.999999Z"
        JsonEncoder.encode(Instant.parse("2026-08-05T19:30:59.9999999Z"), ts) shouldBe
            "2026-08-05T19:30:59.999999Z"
    }

    @Test
    fun `a zoned source value is converted to UTC and loses its offset`() {
        // §10.3's worked example: 14:30 US Eastern is 19:30 UTC, and the wire carries Z.
        JsonEncoder.encode(
            OffsetDateTime.parse("2026-08-05T14:30:00.123456-05:00"),
            column(LogicalType.TIMESTAMP),
        ) shouldBe "2026-08-05T19:30:00.123456Z"
    }

    @Test
    fun `a naive source value is treated as already UTC`() {
        // §8.4: a TIMESTAMP WITHOUT TIME ZONE carries no zone, so no conversion is
        // possible. Assuming UTC is the documented convention, not a guess.
        JsonEncoder.encode(
            LocalDateTime.parse("2026-08-05T14:30:00"),
            column(LogicalType.TIMESTAMP),
        ) shouldBe "2026-08-05T14:30:00.000000Z"
    }

    @Test
    fun `TIME renders six fractional digits and no zone designator`() {
        val time = column(LogicalType.TIME)

        JsonEncoder.encode(LocalTime.parse("14:30:00.123456"), time) shouldBe "14:30:00.123456"
        JsonEncoder.encode(LocalTime.MIDNIGHT, time) shouldBe "00:00:00.000000"
        // A zone-carrying source time drops its offset rather than shifting the reading
        // (§5.1: "TZ info dropped").
        JsonEncoder.encode(
            java.time.OffsetTime.parse("14:30:00.123456-05:00"),
            time,
        ) shouldBe "14:30:00.123456"
    }

    @Test
    fun `DATE renders as a bare calendar date`() {
        JsonEncoder.encode(LocalDate.parse("2026-08-05"), column(LogicalType.DATE)) shouldBe "2026-08-05"
    }

    @Test
    fun `BINARY is standard padded base64, never the URL-safe alphabet`() {
        // §3.5 rule 4: RFC 4648 §4 with `=` padding. The URL-safe alphabet (§5) would
        // decode to different bytes in any client using a standard decoder.
        val bytes = byteArrayOf(-5, -1, 0, 62, 63, 127)
        val encoded = JsonEncoder.encode(bytes, column(LogicalType.BINARY)) as String

        encoded shouldBe Base64.getEncoder().encodeToString(bytes)
        encoded shouldNotContain "-"
        encoded shouldNotContain "_"
        encoded shouldNotContain "\n"
        Base64.getDecoder().decode(encoded) shouldBe bytes
    }

    @Test
    fun `base64 padding is present whenever the length calls for it`() {
        val single = JsonEncoder.encode(byteArrayOf(1), column(LogicalType.BINARY)) as String
        val double = JsonEncoder.encode(byteArrayOf(1, 2), column(LogicalType.BINARY)) as String

        single.endsWith("==") shouldBe true
        double.endsWith("=") shouldBe true
    }

    @Test
    fun `BIGDECIMAL is a plain decimal string with trailing zeros kept to its scale`() {
        // §3.5 rule 5: "12345.60", not "12345.6" and not "1.23456E+4".
        val column = column(LogicalType.BIGDECIMAL, precision = 18, scale = 2)

        JsonEncoder.encode(BigDecimal("12345.6"), column) shouldBe "12345.60"
        JsonEncoder.encode(BigDecimal("12345.60"), column) shouldBe "12345.60"
        JsonEncoder.encode(BigDecimal("1.23456E+4"), column) shouldBe "12345.60"
        JsonEncoder.encode(BigDecimal("0"), column) shouldBe "0.00"
    }

    @Test
    fun `BIGDECIMAL never rounds away digits the source produced`() {
        // Padding up to the declared scale is rendering; cutting below it would be data
        // loss invisible to everyone downstream.
        JsonEncoder.encode(
            BigDecimal("12345.6789"),
            column(LogicalType.BIGDECIMAL, precision = 18, scale = 2),
        ) shouldBe "12345.6789"
    }

    @Test
    fun `BIGINTEGER is a plain string that survives JSON parsing intact`() {
        // §10.2: int64 max exceeds the double-safe range, so it travels as a string.
        val column = column(LogicalType.BIGINTEGER)

        JsonEncoder.encode(Long.MAX_VALUE, column) shouldBe "9223372036854775807"
        JsonEncoder.encode(BigInteger("9223372036854775807"), column) shouldBe "9223372036854775807"
        JsonEncoder.encode(Long.MIN_VALUE, column) shouldBe "-9223372036854775808"
    }

    @Test
    fun `DECIMAL stays a JSON number on both sides of the scale rule`() {
        // Exact origin keeps its declared scale as a BigDecimal (still a JSON number);
        // approximate origin renders as a Double, since a fixed scale would be a lie.
        JsonEncoder.encode(BigDecimal("12.30"), column(LogicalType.DECIMAL, 12, 2)) shouldBe BigDecimal("12.30")
        JsonEncoder.encode(3.14, column(LogicalType.DECIMAL, precision = 15)) shouldBe 3.14
        JsonEncoder.encode(1, column(LogicalType.DECIMAL, precision = 7)) shouldBe 1.0
    }

    @Test
    fun `a NULL value is JSON null whatever the column type`() {
        // §3.5 rule 6 — including for the types that would otherwise be strings.
        LogicalType.entries.forEach { type ->
            val descriptor =
                when (type) {
                    LogicalType.DECIMAL -> column(type, precision = 12, scale = 2)
                    LogicalType.BIGDECIMAL -> column(type, precision = 20, scale = 2)
                    else -> column(type)
                }
            JsonEncoder.encode(null, descriptor) shouldBe null
        }
    }

    @Test
    fun `INTEGER and BOOLEAN keep their native JSON forms`() {
        JsonEncoder.encode(42, column(LogicalType.INTEGER)) shouldBe 42L
        JsonEncoder.encode(true, column(LogicalType.BOOLEAN)) shouldBe true
        JsonEncoder.encode(false, column(LogicalType.BOOLEAN)) shouldBe false
    }

    @Test
    fun `STRING falls back to toString, which is what §8-2 promises`() {
        JsonEncoder.encode(42, column(LogicalType.STRING)) shouldBe "42"
        JsonEncoder.encode("plain", column(LogicalType.STRING)) shouldBe "plain"
    }

    @Test
    fun `a java-sql temporal value is rejected rather than silently zone-shifted`() {
        // java.sql.Timestamp converts through the JVM default zone; accepting it would
        // make the output machine-dependent and hide a §8.4 violation instead of
        // surfacing it. Staging reads via getObject(index, OffsetDateTime::class.java).
        shouldThrow<IllegalArgumentException> {
            JsonEncoder.encode(java.sql.Timestamp.from(Instant.EPOCH), column(LogicalType.TIMESTAMP))
        }
        shouldThrow<IllegalArgumentException> {
            JsonEncoder.encode(java.sql.Date.valueOf("2026-08-05"), column(LogicalType.DATE))
        }
    }

    @Test
    fun `a non-null value in a NULL-typed column is rejected, not silently dropped`() {
        // §8.1 says a canonical NULL column holds only NULLs, so this is unreachable —
        // but "unreachable" is a claim about the mapper, and the encoder is downstream of
        // it. Returning JSON null would discard real data; returning the value would
        // break the declared wire form. Failing loudly is the only honest third option,
        // and this is the branch that was never executed.
        val thrown =
            shouldThrow<IllegalArgumentException> {
                JsonEncoder.encode("surprise", ColumnSchema("only_nulls", LogicalType.NULL))
            }

        thrown.message.orEmpty().contains("only_nulls") shouldBe true
        // A genuine null in that column is still fine.
        JsonEncoder.encode(null, ColumnSchema("only_nulls", LogicalType.NULL)) shouldBe null
    }

    @Test
    fun `a value that cannot be encoded names the column and both types`() {
        val thrown =
            shouldThrow<IllegalArgumentException> {
                JsonEncoder.encode("not bytes", ColumnSchema("logo", LogicalType.BINARY))
            }

        thrown.message.orEmpty().contains("logo") shouldBe true
        thrown.message.orEmpty().contains("BINARY") shouldBe true
    }

    @Test
    fun `the UTC epoch renders identically however it is expressed`() {
        val ts = column(LogicalType.TIMESTAMP)
        val expected = "1970-01-01T00:00:00.000000Z"

        JsonEncoder.encode(Instant.EPOCH, ts) shouldBe expected
        JsonEncoder.encode(OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC), ts) shouldBe expected
        JsonEncoder.encode(LocalDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC), ts) shouldBe expected
    }
}
