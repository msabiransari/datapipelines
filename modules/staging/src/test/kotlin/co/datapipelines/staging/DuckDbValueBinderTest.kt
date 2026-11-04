package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [DuckDbValueBinder] through a real appender: each shape a producer may hand `stageRows` lands as
 * its column's value (the H2 path's `setObject` conversions made explicit), and a value its column
 * cannot hold is `value_overflow` naming the column — never a silent NULL, never the value.
 */
class DuckDbValueBinderTest {
    @TempDir
    lateinit var scratch: Path

    private lateinit var staging: DuckDbStaging
    private var n = 0

    @BeforeEach
    fun open() {
        staging = duckFactory(scratch).open()
    }

    @AfterEach
    fun close() {
        staging.close()
    }

    /** Stages [value] into a one-column table of [column] and reads it back as the engine's text. */
    private fun roundTrip(
        column: ColumnSchema,
        value: Any?,
    ): String? {
        val table = "stg_v${n++}"
        runBlocking { staging.stageRows(table, listOf(column), sequenceOf(listOf(value))) }
        return staging.scalarText("SELECT CAST(\"${column.name}\" AS VARCHAR) FROM \"$table\"")
    }

    @Test
    fun `numbers convert to the column's exact type, rounding half-up as H2 does`() {
        roundTrip(ColumnSchema("i", LogicalType.INTEGER), 7L) shouldBe "7"
        roundTrip(ColumnSchema("i", LogicalType.INTEGER), BigDecimal("2.5")) shouldBe "3"
        roundTrip(ColumnSchema("b", LogicalType.BIGINTEGER), 9007199254740993L) shouldBe "9007199254740993"
        roundTrip(ColumnSchema("b", LogicalType.BIGINTEGER), "42") shouldBe "42"
        roundTrip(ColumnSchema("d", LogicalType.DECIMAL, 10, 2), 0.1) shouldBe "0.10"
        roundTrip(ColumnSchema("d", LogicalType.DECIMAL, 10, 2), BigDecimal("1.005")) shouldBe "1.01"
        roundTrip(ColumnSchema("d", LogicalType.DECIMAL, 10, 2), 12) shouldBe "12.00"
        roundTrip(ColumnSchema("x", LogicalType.DECIMAL, 15), BigDecimal("3.25")) shouldBe "3.25"
        roundTrip(ColumnSchema("w", LogicalType.BIGDECIMAL, 38, 4), BigDecimal("1234567890123456789012345678901234.5678")) shouldBe
            "1234567890123456789012345678901234.5678"
    }

    @Test
    fun `booleans, text and bytes take their documented shapes`() {
        roundTrip(ColumnSchema("f", LogicalType.BOOLEAN), "TRUE") shouldBe "true"
        roundTrip(ColumnSchema("f", LogicalType.BOOLEAN), 0) shouldBe "false"
        roundTrip(ColumnSchema("s", LogicalType.STRING), 12.5) shouldBe "12.5"
        roundTrip(ColumnSchema("z", LogicalType.NULL), null) shouldBe null
        roundTrip(ColumnSchema("y", LogicalType.BINARY), byteArrayOf(1, 2)) shouldBe "\\x01\\x02"
    }

    @Test
    fun `temporal values land in UTC whatever shape they arrive in`() {
        roundTrip(ColumnSchema("d", LogicalType.DATE), "2026-11-03") shouldBe "2026-11-03"
        roundTrip(ColumnSchema("d", LogicalType.DATE), LocalDate.of(2026, 11, 3)) shouldBe "2026-11-03"
        roundTrip(ColumnSchema("t", LogicalType.TIME), "04:05:06") shouldBe "04:05:06"
        roundTrip(ColumnSchema("t", LogicalType.TIME), LocalTime.of(4, 5, 6)) shouldBe "04:05:06"
        val utc = "2026-11-03 04:05:06+00"
        roundTrip(ColumnSchema("ts", LogicalType.TIMESTAMP), Instant.parse("2026-11-03T04:05:06Z")) shouldBe utc
        roundTrip(ColumnSchema("ts", LogicalType.TIMESTAMP), "2026-11-02T23:05:06-05:00") shouldBe utc
        roundTrip(ColumnSchema("ts", LogicalType.TIMESTAMP), OffsetDateTime.of(2026, 11, 3, 9, 5, 6, 0, ZoneOffset.ofHours(5))) shouldBe utc
        roundTrip(ColumnSchema("ts", LogicalType.TIMESTAMP), LocalDateTime.of(2026, 11, 3, 4, 5, 6)) shouldBe utc
    }

    @Test
    fun `a value its column cannot hold is value_overflow naming the column, never the value`() {
        val cases =
            listOf(
                ColumnSchema("i", LogicalType.INTEGER) to 3_000_000_000L,
                ColumnSchema("d", LogicalType.DECIMAL, 5, 2) to BigDecimal("1234.5"),
                ColumnSchema("d", LogicalType.DECIMAL, 5, 2) to Double.NaN,
                ColumnSchema("x", LogicalType.DECIMAL, 15) to "not-a-number",
                ColumnSchema("dt", LogicalType.DATE) to "03/11/2026",
                ColumnSchema("f", LogicalType.BOOLEAN) to "maybe",
                ColumnSchema("y", LogicalType.BINARY) to "deadbeef",
            )
        cases.forEachIndexed { i, (column, value) ->
            val thrown =
                shouldThrow<StagingValueOverflowException> {
                    runBlocking { staging.stageRows("stg_bad$i", listOf(column), sequenceOf(listOf(value))) }
                }
            thrown.code shouldBe StagingErrorCodes.VALUE_OVERFLOW
            thrown.message.orEmpty() shouldContain "column '${column.name}'"
            thrown.message.orEmpty() shouldContain "stg_bad$i"
        }
        runBlocking { staging.stats() }.tableCount shouldBe 0
    }
}
