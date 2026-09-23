package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.column
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The type gate's §5.3/R1 table, case by case. Each case is one
 * `(declared column, engine value) → stored value | refusal` row — the table IS the
 * contract; the refusal payloads name the row, the column, and why.
 */
class TypeGateTest {
    /** One table row: the declared column, the engine's value, and the expectation. */
    private sealed interface Expected {
        /** The gate accepts and stores exactly this value. */
        data class Stored(
            val value: Any?,
        ) : Expected

        /** The gate refuses with this class of refusal. */
        data class Refused(
            val kind: Class<out GateRefusal>,
        ) : Expected
    }

    private data class Case(
        val name: String,
        val column: ColumnSchema,
        val engineValue: Any?,
        val expected: Expected,
    )

    private fun stored(value: Any?): Expected = Expected.Stored(value)

    private fun refused(kind: Class<out GateRefusal>): Expected = Expected.Refused(kind)

    private val cases: List<Case> by lazy {
        buildList {
            // --- INTEGER: int32, integral only ---------------------------------------
            add(Case("int integral", column("n", LogicalType.INTEGER), 5, stored(5)))
            add(
                Case(
                    "int fractional refused as wrong type",
                    column("n", LogicalType.INTEGER),
                    2.5,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )
            add(
                Case(
                    "int at int32 max",
                    column("n", LogicalType.INTEGER),
                    2147483647,
                    stored(2147483647),
                ),
            )
            add(
                Case(
                    "int past int32 max is precision lost",
                    column("n", LogicalType.INTEGER),
                    2147483648L,
                    refused(GateRefusal.PrecisionLost::class.java),
                ),
            )
            add(
                Case(
                    "int negative min",
                    column("n", LogicalType.INTEGER),
                    -2147483648L,
                    stored(-2147483648L),
                ),
            )

            // --- BIGINTEGER: int64 string, or an integer number within 2^53 ----------
            add(
                Case(
                    "bigint as int64 string",
                    column("n", LogicalType.BIGINTEGER),
                    "9223372036854775807",
                    stored("9223372036854775807"),
                ),
            )
            add(
                Case(
                    "bigint string normalises leading zeros",
                    column("n", LogicalType.BIGINTEGER),
                    "00042",
                    stored("42"),
                ),
            )
            add(
                Case(
                    "bigint string beyond int64 is wrong type",
                    column("n", LogicalType.BIGINTEGER),
                    "92233720368547758080",
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )
            add(
                Case(
                    "bigint number at 2^53 accepted",
                    column("n", LogicalType.BIGINTEGER),
                    9007199254740992.0,
                    stored("9007199254740992"),
                ),
            )
            add(
                Case(
                    "bigint beyond 2^53 as a number is precision lost (2^53+2 - the nearest double)",
                    column("n", LogicalType.BIGINTEGER),
                    9007199254740994.0,
                    refused(GateRefusal.PrecisionLost::class.java),
                ),
            )
            add(
                Case(
                    "bigint fractional number is wrong type",
                    column("n", LogicalType.BIGINTEGER),
                    2.5,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )

            // --- DECIMAL(p, s): rounded half-even to s, then checked against p -------
            add(
                Case(
                    "0.1 * 3 rounds to 0.30 under scale 2 (R1)",
                    column("d", LogicalType.DECIMAL, 12, 2),
                    0.30000000000000004,
                    stored(BigDecimal("0.30")),
                ),
            )
            add(
                Case(
                    "2.345 is an exact tie at scale 2 - half-even goes to the even digit, 2.34",
                    column("d", LogicalType.DECIMAL, 12, 2),
                    2.345,
                    stored(BigDecimal("2.34")),
                ),
            )
            add(
                Case(
                    "2.335 is a tie with an odd digit below - half-even goes up to 2.34",
                    column("d", LogicalType.DECIMAL, 12, 2),
                    2.335,
                    stored(BigDecimal("2.34")),
                ),
            )
            add(
                Case(
                    "1.005 is a tie with an even digit below - half-even stays 1.00",
                    column("d", LogicalType.DECIMAL, 12, 2),
                    1.005,
                    stored(BigDecimal("1.00")),
                ),
            )
            add(
                Case(
                    "exact scale value passes through",
                    column("d", LogicalType.DECIMAL, 12, 2),
                    12.34,
                    stored(BigDecimal("12.34")),
                ),
            )
            add(
                Case(
                    "overflow of p after rounding is precision lost",
                    column("d", LogicalType.DECIMAL, 3, 2),
                    1234.567,
                    refused(GateRefusal.PrecisionLost::class.java),
                ),
            )
            add(
                Case(
                    "decimal rounds a long fraction",
                    column("d", LogicalType.DECIMAL, 10, 4),
                    0.123456,
                    stored(BigDecimal("0.1235")),
                ),
            )
            add(
                Case(
                    "decimal string is wrong type - numbers only",
                    column("d", LogicalType.DECIMAL, 12, 2),
                    "12.34",
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )

            // --- DECIMAL without scale: approximate origin, stored as-is -------------
            add(
                Case(
                    "scale-less decimal stored as-is",
                    column("d", LogicalType.DECIMAL, 15),
                    0.30000000000000004,
                    stored(0.30000000000000004),
                ),
            )

            // --- BIGDECIMAL(p, s): a string, exact, normalised to s ------------------
            add(
                Case(
                    "bigdecimal at declared scale",
                    column("b", LogicalType.BIGDECIMAL, 12, 2),
                    "12.34",
                    stored("12.34"),
                ),
            )
            add(
                Case(
                    "bigdecimal 12.5 normalises UP to 12.50 (excess digits are what is lost)",
                    column("b", LogicalType.BIGDECIMAL, 12, 2),
                    "12.5",
                    stored("12.50"),
                ),
            )
            add(
                Case(
                    "bigdecimal 12.505 has excess scale - precision lost, never rounded",
                    column("b", LogicalType.BIGDECIMAL, 12, 2),
                    "12.505",
                    refused(GateRefusal.PrecisionLost::class.java),
                ),
            )
            add(
                Case(
                    "bigdecimal overflow of p",
                    column("b", LogicalType.BIGDECIMAL, 4, 2),
                    "12345.67",
                    refused(GateRefusal.PrecisionLost::class.java),
                ),
            )
            add(
                Case(
                    "bigdecimal beyond int64 exact as string",
                    column("b", LogicalType.BIGDECIMAL, 30, 6),
                    "123456789012345678901234.567891",
                    stored("123456789012345678901234.567891"),
                ),
            )
            add(
                Case(
                    "bigdecimal as a JSON number is wrong type (the wire form is string)",
                    column("b", LogicalType.BIGDECIMAL, 12, 2),
                    12.34,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )
            add(
                Case(
                    "bigdecimal unparseable string is wrong type",
                    column("b", LogicalType.BIGDECIMAL, 12, 2),
                    "12,34",
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )

            // --- STRING ---------------------------------------------------------------
            add(Case("string passes", column("s", LogicalType.STRING), "héllo", stored("héllo")))
            add(
                Case(
                    "string number is wrong type",
                    column("s", LogicalType.STRING),
                    12,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )

            // --- BOOLEAN ---------------------------------------------------------------
            add(Case("boolean true", column("b", LogicalType.BOOLEAN), true, stored(true)))
            add(
                Case(
                    "boolean 1 is wrong type",
                    column("b", LogicalType.BOOLEAN),
                    1,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )

            // --- DATE / TIME / TIMESTAMP: canonical strings ----------------------------
            add(
                Case(
                    "date canonical",
                    column("d", LogicalType.DATE),
                    "2026-08-05",
                    stored("2026-08-05"),
                ),
            )
            add(
                Case(
                    "date unparseable is wrong type",
                    column("d", LogicalType.DATE),
                    "05/08/2026",
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )
            add(
                Case(
                    "time canonical - six fractional digits, no zone",
                    column("t", LogicalType.TIME),
                    "14:30:00.123456",
                    stored("14:30:00.123456"),
                ),
            )
            add(
                Case(
                    "time short fraction pads to six",
                    column("t", LogicalType.TIME),
                    "14:30:00.5",
                    stored("14:30:00.500000"),
                ),
            )
            add(
                Case(
                    "timestamp Z canonical",
                    column("ts", LogicalType.TIMESTAMP),
                    "2026-08-05T19:30:00.123456Z",
                    stored("2026-08-05T19:30:00.123456Z"),
                ),
            )
            add(
                Case(
                    "timestamp naive reads as UTC and gains Z",
                    column("ts", LogicalType.TIMESTAMP),
                    "2026-08-05T19:30:00",
                    stored("2026-08-05T19:30:00.000000Z"),
                ),
            )
            add(
                Case(
                    "timestamp offset normalises to UTC",
                    column("ts", LogicalType.TIMESTAMP),
                    "2026-08-05T21:30:00+02:00",
                    stored("2026-08-05T19:30:00.000000Z"),
                ),
            )
            add(
                Case(
                    "timestamp with sub-micro digits truncates",
                    column("ts", LogicalType.TIMESTAMP),
                    "2026-08-05T19:30:00.1234569Z",
                    stored("2026-08-05T19:30:00.123456Z"),
                ),
            )

            // --- NULL against the CONTRACT's nullability --------------------------------
            add(
                Case(
                    "null accepted on a positively nullable column",
                    column("n", LogicalType.INTEGER, nullable = true),
                    null,
                    stored(null),
                ),
            )
            add(
                Case(
                    "null refused where the contract is silent - absence is false HERE",
                    column("n", LogicalType.INTEGER),
                    null,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )
            add(
                Case(
                    "null refused on a declared non-null column",
                    column("n", LogicalType.INTEGER, nullable = false),
                    null,
                    refused(GateRefusal.ValueTypeMismatch::class.java),
                ),
            )
        }
    }

    @Test
    fun `the section-5-3 table behaves as declared, every case`() {
        cases.forEach { case ->
            withClue("case '${case.name}'") {
                val gate = TypeGate.over(listOf(case.column))
                val verdict = gate.gateRow(mapOf(case.column.name to case.engineValue), rowNumber = 1)
                when (val expected = case.expected) {
                    is Expected.Stored -> {
                        verdict shouldBe TypeGate.GateResult.Pass(mapOf(case.column.name to expected.value))
                    }

                    is Expected.Refused -> {
                        val refusal = (verdict as TypeGate.GateResult.Refuse).refusal
                        (refusal::class.java == expected.kind) shouldBe true
                    }
                }
            }
        }
    }

    @Test
    fun `the table is not vacuous - 42 cases, every refusal kind covered both ways`() {
        cases.size shouldBe 42
        cases.count { it.expected is Expected.Stored } shouldBe 26
        cases.count { it.expected is Expected.Refused } shouldBe 16
        cases.mapNotNull { (it.expected as? Expected.Refused)?.kind }.toSet() shouldBe
            setOf(
                GateRefusal.ValueTypeMismatch::class.java,
                GateRefusal.PrecisionLost::class.java,
            )
    }

    // --- Row shape and the single-value / object entries -----------------------------

    @Test
    fun `row shape mismatch names missing and extra keys`() {
        val gate = TypeGate.over(listOf(column("a", LogicalType.INTEGER), column("b", LogicalType.INTEGER)))
        val verdict = gate.gateRow(mapOf("a" to 1, "c" to 2), rowNumber = 7, batch = 2)
        (verdict as TypeGate.GateResult.Refuse).refusal shouldBe
            GateRefusal.RowShapeMismatch(2, 7, listOf("b"), listOf("c"))
    }

    @Test
    fun `gate value refuses with a null row number`() {
        val gate = TypeGate.over(listOf(column("v", LogicalType.INTEGER)))
        val verdict = gate.gateValue("x", column("v", LogicalType.INTEGER))
        (verdict as TypeGate.GateResult.Refuse).refusal shouldBe
            GateRefusal.ValueTypeMismatch(null, "v", "number", "String")
    }

    @Test
    fun `gate object bounds the canonical bytes and refuses non-objects`() {
        val gate = TypeGate.over(emptyList())
        gate.gateObject(linkedMapOf("a" to 1), 64) shouldBe TypeGate.GateResult.Pass(linkedMapOf("a" to 1))
        (gate.gateObject("nope", 64) as TypeGate.GateResult.Refuse).refusal shouldBe
            GateRefusal.ValueTypeMismatch(null, "<object>", "object", "String")
        // {"k":" + 64 x's + "} = 72 canonical bytes.
        (gate.gateObject(linkedMapOf("k" to "x".repeat(64)), 64) as TypeGate.GateResult.Refuse).refusal shouldBe
            GateRefusal.ValueTooLarge(null, "<object>", 72)
    }

    @Test
    fun `a string over maxStringBytes is value too large with the byte count`() {
        val gate = TypeGate.over(listOf(column("s", LogicalType.STRING)), maxStringBytes = 8)
        val verdict = gate.gateRow(mapOf("s" to "é".repeat(5)), rowNumber = 3)
        (verdict as TypeGate.GateResult.Refuse).refusal shouldBe GateRefusal.ValueTooLarge(3, "s", 10)
    }

    @Test
    fun `BINARY and NULL as declared types are refused at construction`() {
        binaryConstructionRefusal().message shouldContain "BINARY"
        nullConstructionRefusal().message shouldContain "NULL"
    }

    private fun binaryConstructionRefusal(): IllegalArgumentException = constructionRefusal(LogicalType.BINARY)

    private fun nullConstructionRefusal(): IllegalArgumentException = constructionRefusal(LogicalType.NULL)

    private fun constructionRefusal(type: LogicalType): IllegalArgumentException {
        val caught = runCatching { TypeGate.over(listOf(column("x", type))) }.exceptionOrNull()
        return caught.shouldNotBeNull() as IllegalArgumentException
    }
}
