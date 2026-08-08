package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Pins the canonical type vocabulary (type-system.md §3, enums.md §1).
 *
 * §9.1 freezes the eleven names, their spellings and their wire encodings. Every
 * assertion here is a v1 contract clause; if one fails, either the freeze was broken or
 * this test was edited to match a break. Both are review-stopping events.
 */
class LogicalTypeTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `the canonical set is exactly the eleven frozen names`() {
        LogicalType.entries.map { it.name } shouldContainExactly
            listOf(
                "NULL",
                "BOOLEAN",
                "INTEGER",
                "BIGINTEGER",
                "DECIMAL",
                "BIGDECIMAL",
                "STRING",
                "BINARY",
                "DATE",
                "TIME",
                "TIMESTAMP",
            )
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LogicalType::class)
    fun `every constant serializes to its enums-md wire value`(type: LogicalType) {
        // @JsonValue, not Enum.name — the catalog string is the source of truth even
        // where the two coincide (enums.md serialization convention).
        mapper.writeValueAsString(type) shouldBe "\"${type.wire}\""
        mapper.readValue("\"${type.wire}\"", LogicalType::class.java) shouldBe type
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("wireForms")
    fun `every type declares the §3-1 wire encoding`(
        type: LogicalType,
        expected: JsonWireForm,
    ) {
        type.wireForm shouldBe expected
    }

    @Test
    fun `the BIG types are the string-on-wire types`() {
        // §2 principle 2: the `BIG` in a name IS the wire signal. Any type whose name
        // starts with BIG must be string-encoded, and the two integer/decimal pairs must
        // sit on opposite sides of the line.
        LogicalType.entries.filter { it.name.startsWith("BIG") }.forEach {
            it.isStringOnWire shouldBe true
        }
        LogicalType.INTEGER.isStringOnWire shouldBe false
        LogicalType.BIGINTEGER.isStringOnWire shouldBe true
        LogicalType.DECIMAL.isStringOnWire shouldBe false
        LogicalType.BIGDECIMAL.isStringOnWire shouldBe true
    }

    @Test
    fun `an unrecognized wire value is rejected, not silently defaulted`() {
        // A canonical type we do not know is a contract violation, not a missing
        // optional — swallowing it would let a vN payload deserialize as something else.
        shouldThrow<IllegalArgumentException> { LogicalType.fromWire("VARCHAR") }
            .message shouldBe "Unknown LogicalType: VARCHAR"
    }

    @Test
    fun `wire values are case-sensitive`() {
        shouldThrow<IllegalArgumentException> { LogicalType.fromWire("string") }
    }

    private companion object {
        @JvmStatic
        fun wireForms(): List<Array<Any>> =
            listOf(
                arrayOf(LogicalType.NULL, JsonWireForm.NULL),
                arrayOf(LogicalType.BOOLEAN, JsonWireForm.BOOLEAN),
                arrayOf(LogicalType.INTEGER, JsonWireForm.NUMBER),
                arrayOf(LogicalType.BIGINTEGER, JsonWireForm.STRING),
                arrayOf(LogicalType.DECIMAL, JsonWireForm.NUMBER),
                arrayOf(LogicalType.BIGDECIMAL, JsonWireForm.STRING),
                arrayOf(LogicalType.STRING, JsonWireForm.STRING),
                arrayOf(LogicalType.BINARY, JsonWireForm.STRING),
                arrayOf(LogicalType.DATE, JsonWireForm.STRING),
                arrayOf(LogicalType.TIME, JsonWireForm.STRING),
                arrayOf(LogicalType.TIMESTAMP, JsonWireForm.STRING),
            )
    }
}
