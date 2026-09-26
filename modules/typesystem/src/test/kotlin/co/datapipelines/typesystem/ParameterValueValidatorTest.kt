package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.NullNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * `ParameterValueValidator` (parameter-engine record P28, #194 lane A) — every declaration field
 * against every refusal it can produce: `type` (the strict coercion), `precision`/`scale`,
 * `required`, `default`, each `constraints` key, and `cardinality`; and the one null policy —
 * absent, JSON null and `[]` for a `MULTI` are UNSUPPLIED, never a refusal.
 *
 * The regex read budget has its own suite ([PatternGuardTest]); the descriptor rule its own
 * ([TypeWideningTest]).
 */
class ParameterValueValidatorTest {
    private val validator = ParameterValueValidator()

    // ---------------------------------------------------------------- the null policy

    @Test
    fun `absent, JSON null and a MISSING node are unsupplied for required and optional alike`() {
        listOf(true, false).forEach { required ->
            val declaration = ParameterDeclaration(LogicalType.STRING, required = required)
            listOf(null, NullNode.instance, MissingNode.getInstance()).forEach { value ->
                withClue("required=$required value=$value") {
                    validator.validate(declaration, value) shouldBe ParameterValueOutcome.Unsupplied
                }
            }
        }
    }

    @Test
    fun `an empty array is unsupplied for a MULTI - P25's one signal for nothing chosen`() {
        val declaration = ParameterDeclaration(LogicalType.STRING, cardinality = ParameterCardinality.MULTI, required = true)

        validator.validate(declaration, json("[]")) shouldBe ParameterValueOutcome.Unsupplied
    }

    @Test
    fun `required_missing is the caller's to raise, and only for a required declaration`() {
        val refusal = validator.requiredMissing(ParameterDeclaration(LogicalType.DATE, required = true))
        refusal?.rule shouldBe ParameterValueRule.REQUIRED_MISSING
        refusal?.rule?.wire shouldBe "required_missing"

        validator.requiredMissing(ParameterDeclaration(LogicalType.DATE)).shouldBeNull()
    }

    // ---------------------------------------------------------------- type

    @Test
    fun `a wrong wire form is invalid_value_type carrying the coercion's own reason`() {
        val refusal = refused(ParameterDeclaration(LogicalType.INTEGER), "\"42\"")

        refusal.rule shouldBe ParameterValueRule.INVALID_VALUE_TYPE
        refusal.rule.wire shouldBe "invalid_value_type"
        refusal.message shouldBe "INTEGER is number-on-wire and takes a JSON number; got string"
        refusal.reason.shouldBeNull()
    }

    @Test
    fun `a padded BIG numeric is invalid_value_type - nothing is trimmed`() {
        refused(ParameterDeclaration(LogicalType.BIGDECIMAL, scale = 2), "\" 12.50 \"").rule shouldBe ParameterValueRule.INVALID_VALUE_TYPE
    }

    @Test
    fun `every declarable type accepts its wire form as the coerced value`() {
        accepted(ParameterDeclaration(LogicalType.BOOLEAN), "true") shouldBe true
        accepted(ParameterDeclaration(LogicalType.INTEGER), "7") shouldBe 7
        accepted(ParameterDeclaration(LogicalType.BIGINTEGER), "\"7\"") shouldBe BigInteger("7")
        accepted(ParameterDeclaration(LogicalType.STRING), "\"x\"") shouldBe "x"
        accepted(ParameterDeclaration(LogicalType.DATE), "\"2026-09-26\"") shouldBe LocalDate.of(2026, 9, 26)
        accepted(ParameterDeclaration(LogicalType.TIME), "\"10:00:00\"") shouldBe LocalTime.of(10, 0)
        accepted(ParameterDeclaration(LogicalType.TIMESTAMP), "\"2026-09-26T10:00:00Z\"") shouldBe Instant.parse("2026-09-26T10:00:00Z")
    }

    // ---------------------------------------------------------------- precision and scale

    @Test
    fun `more decimal places than the declared scale is scale - nothing is rounded`() {
        val amount = ParameterDeclaration(LogicalType.DECIMAL, precision = 12, scale = 2)

        val refusal = refused(amount, "12.345")
        refusal.rule shouldBe ParameterValueRule.CONSTRAINT_VIOLATION
        refusal.reason shouldBe "scale"
        refusal.message shouldContain "DECIMAL(12,2)"

        accepted(amount, "12.34") shouldBe BigDecimal("12.34")
        withClue("trailing zeros carry no digits — 12.500 is two places, as sent (never rescaled)") {
            val outcome = validator.validate(amount, DecimalNode(BigDecimal("12.500")))
            outcome.shouldBeInstanceOf<ParameterValueOutcome.Accepted>().value.toString() shouldBe "12.500"
        }
        refused(ParameterDeclaration(LogicalType.BIGDECIMAL, scale = 2), "\"1.001\"").reason shouldBe "scale"
    }

    @Test
    fun `more integer digits than precision minus scale is precision`() {
        val narrow = ParameterDeclaration(LogicalType.DECIMAL, precision = 6, scale = 2)

        accepted(narrow, "9999.99") shouldBe BigDecimal("9999.99")
        accepted(narrow, "-9999.99") shouldBe BigDecimal("-9999.99")
        refused(narrow, "99999.5").reason shouldBe "precision"
        refused(narrow, "1e5").reason shouldBe "precision"
    }

    @Test
    fun `record §6_4's counterexample at the value level - 9999_99 does not fit DECIMAL(6,4)`() {
        refused(ParameterDeclaration(LogicalType.DECIMAL, precision = 6, scale = 4), "9999.99").reason shouldBe "precision"
        accepted(ParameterDeclaration(LogicalType.DECIMAL, precision = 6, scale = 4), "99.99") shouldBe BigDecimal("99.99")
    }

    @Test
    fun `zero fits a scale-only declaration, and an unbounded or approximate one checks what it can`() {
        accepted(ParameterDeclaration(LogicalType.DECIMAL, precision = 2, scale = 2), "0") shouldBe BigDecimal("0")
        accepted(ParameterDeclaration(LogicalType.DECIMAL, precision = 2, scale = 2), "0.5") shouldBe BigDecimal("0.5")
        withClue("BIGDECIMAL with no precision is unbounded (type-system §4): only the scale binds") {
            accepted(ParameterDeclaration(LogicalType.BIGDECIMAL, scale = 2), "\"123456789012345678901234567890.12\"")
        }
        withClue("a DECIMAL with no scale is approximate (a float): no places to check") {
            accepted(ParameterDeclaration(LogicalType.DECIMAL, precision = 15), "1.23456789") shouldBe BigDecimal("1.23456789")
        }
    }

    // ---------------------------------------------------------------- default

    @Test
    fun `resolveDefault judges the declaration's own default like a supplied value`() {
        val constrained = ParameterConstraints(min = json("0"))
        validator.resolveDefault(ParameterDeclaration(LogicalType.INTEGER, default = json("5"), constraints = constrained)) shouldBe
            ParameterValueOutcome.Accepted(5)
        (
            validator.resolveDefault(ParameterDeclaration(LogicalType.INTEGER, default = json("-1"), constraints = constrained))
                as ParameterValueOutcome.Refused
        ).refusal.reason shouldBe "min"
        validator.resolveDefault(ParameterDeclaration(LogicalType.INTEGER)) shouldBe ParameterValueOutcome.Unsupplied
        validator.resolveDefault(ParameterDeclaration(LogicalType.INTEGER, default = NullNode.instance)) shouldBe
            ParameterValueOutcome.Unsupplied
    }

    // ---------------------------------------------------------------- constraints

    @Test
    fun `min and max are inclusive, in the parameter's own type, for every ordered type`() {
        val cases =
            listOf(
                Triple(LogicalType.INTEGER, "0" to "10", listOf("0", "10", "-1", "11")),
                Triple(LogicalType.BIGINTEGER, "\"0\"" to "\"10\"", listOf("\"0\"", "\"10\"", "\"-1\"", "\"11\"")),
                Triple(LogicalType.DECIMAL, "0" to "1.5", listOf("0.0", "1.50", "-0.01", "1.51")),
                Triple(LogicalType.BIGDECIMAL, "\"0\"" to "\"1.5\"", listOf("\"0.00\"", "\"1.5\"", "\"-0.01\"", "\"1.51\"")),
                Triple(
                    LogicalType.DATE,
                    "\"2026-01-01\"" to "\"2026-12-31\"",
                    listOf("\"2026-01-01\"", "\"2026-12-31\"", "\"2025-12-31\"", "\"2027-01-01\""),
                ),
                Triple(
                    LogicalType.TIME,
                    "\"09:00:00\"" to "\"17:00:00\"",
                    listOf("\"09:00:00\"", "\"17:00:00\"", "\"08:59:59\"", "\"17:00:01\""),
                ),
                Triple(
                    LogicalType.TIMESTAMP,
                    "\"2026-01-01T00:00:00Z\"" to "\"2026-01-02T00:00:00Z\"",
                    listOf("\"2026-01-01T00:00:00Z\"", "\"2026-01-02T00:00:00Z\"", "\"2025-12-31T23:59:59Z\"", "\"2026-01-02T00:00:01Z\""),
                ),
            )
        cases.forEach { (type, bounds, values) ->
            val declaration =
                ParameterDeclaration(
                    type,
                    scale =
                        if (type ==
                            LogicalType.BIGDECIMAL
                        ) {
                            2
                        } else {
                            null
                        },
                    constraints = ParameterConstraints(json(bounds.first), json(bounds.second)),
                )
            withClue("$type") {
                validator.validate(declaration, json(values[0])).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
                validator.validate(declaration, json(values[1])).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
                refused(declaration, values[2]).reason shouldBe "min"
                refused(declaration, values[3]).reason shouldBe "max"
            }
        }
    }

    @Test
    fun `record §3_5's amount - scale 2 and min 0 refuse 12_345 and -1 with their own reasons`() {
        val amount =
            ParameterDeclaration(LogicalType.DECIMAL, precision = 12, scale = 2, constraints = ParameterConstraints(min = json("0")))

        refused(amount, "12.345").reason shouldBe "scale"
        refused(amount, "-1").reason shouldBe "min"
        accepted(amount, "0") shouldBe BigDecimal("0")
    }

    @Test
    fun `lengths count characters for STRING (code points) and decoded bytes for BINARY`() {
        val text = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(minLength = 2, maxLength = 3))
        accepted(text, "\"ab\"") shouldBe "ab"
        withClue("one emoji is ONE character, though two UTF-16 units") { accepted(text, "\"a😀\"") }
        refused(text, "\"a\"").reason shouldBe "min_length"
        refused(text, "\"abcd\"").reason shouldBe "max_length"

        val bytes = ParameterDeclaration(LogicalType.BINARY, constraints = ParameterConstraints(maxLength = 2))
        validator.validate(bytes, json("\"AAE=\"")).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
        refused(bytes, "\"AAEC\"").reason shouldBe "max_length"
    }

    @Test
    fun `a configured default max_length binds an undeclared one - and pipelines configure none`() {
        val bounded = ParameterValueValidator(ParameterValueLimits(defaultMaxLength = 3))
        val outcome = bounded.validate(ParameterDeclaration(LogicalType.STRING), json("\"abcd\""))
        (outcome as ParameterValueOutcome.Refused).refusal.reason shouldBe "max_length"

        withClue("the default limits leave an undeclared STRING unbounded — a pipeline's behaviour") {
            accepted(ParameterDeclaration(LogicalType.STRING), "\"${"x".repeat(10_000)}\"")
        }
        withClue("never applied to a non-length type") {
            bounded.validate(ParameterDeclaration(LogicalType.INTEGER), json("123456")).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
        }
    }

    @Test
    fun `a pattern is anchored - the whole value, never a find`() {
        val postcode = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = "[0-9]{4}"))

        accepted(postcode, "\"1234\"") shouldBe "1234"
        refused(postcode, "\"12345\"").reason shouldBe "pattern"
        refused(postcode, "\"x1234\"").reason shouldBe "pattern"
    }

    @Test
    fun `lengths are checked before the pattern, so a too-long value never reaches the regex`() {
        val declaration = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(maxLength = 5, pattern = "(a+)+b"))

        refused(declaration, "\"${"a".repeat(40)}\"").reason shouldBe "max_length"
    }

    @Test
    fun `a refusal message never echoes an unbounded value`() {
        val declaration = ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = "[a-z]+"))

        refused(declaration, "\"${"X".repeat(5_000)}\"").message.length shouldBe "value does not match the declared pattern".length
    }

    // ---------------------------------------------------------------- cardinality

    @Test
    fun `a MULTI is a JSON array of distinct values, each judged like a SINGLE`() {
        val regions =
            ParameterDeclaration(
                LogicalType.STRING,
                cardinality = ParameterCardinality.MULTI,
                constraints = ParameterConstraints(maxLength = 2),
            )

        accepted(regions, "[\"EU\", \"US\"]") shouldBe listOf("EU", "US")
        refused(regions, "\"EU\"").rule shouldBe ParameterValueRule.INVALID_VALUE_TYPE
        refused(regions, "[\"EU\", null]").message shouldContain "member 1 is null"
        refused(regions, "[\"EU\", \"EU\"]").message shouldContain "repeats an earlier member"
        val member = refused(regions, "[\"EU\", \"USA\"]")
        member.reason shouldBe "max_length"
        member.message shouldContain "MULTI member 1:"
    }

    @Test
    fun `MULTI duplicates compare numerically - 1_0 and 1_00 are the same member`() {
        val amounts = ParameterDeclaration(LogicalType.BIGDECIMAL, scale = 2, cardinality = ParameterCardinality.MULTI)

        refused(amounts, "[\"1.0\", \"1.00\"]").rule shouldBe ParameterValueRule.INVALID_VALUE_TYPE
        val bytes = ParameterDeclaration(LogicalType.BINARY, cardinality = ParameterCardinality.MULTI)
        refused(bytes, "[\"AAE=\", \"AAE=\"]").rule shouldBe ParameterValueRule.INVALID_VALUE_TYPE
    }

    @Test
    fun `a SINGLE declaration refuses an array through the coercion`() {
        refused(ParameterDeclaration(LogicalType.STRING), "[\"a\"]").rule shouldBe ParameterValueRule.INVALID_VALUE_TYPE
    }

    // ---------------------------------------------------------------- the declaration itself

    @Test
    fun `a constraint on a type it does not apply to is constraint_not_applicable`() {
        val cases =
            listOf(
                LogicalType.STRING to ParameterConstraints(min = json("\"a\"")),
                LogicalType.BOOLEAN to ParameterConstraints(max = json("true")),
                LogicalType.BINARY to ParameterConstraints(min = json("\"AA==\"")),
                LogicalType.INTEGER to ParameterConstraints(minLength = 1),
                LogicalType.DATE to ParameterConstraints(maxLength = 1),
                LogicalType.INTEGER to ParameterConstraints(pattern = "[0-9]+"),
                LogicalType.BINARY to ParameterConstraints(pattern = "A+"),
            )
        cases.forEach { (type, constraints) ->
            withClue("$type $constraints") {
                val problem = validator.checkDeclaration(ParameterDeclaration(type, constraints = constraints)).single()
                problem.rule shouldBe DeclarationRule.CONSTRAINT_NOT_APPLICABLE
                problem.rule.wire shouldBe "constraint_not_applicable"
            }
        }
    }

    @Test
    fun `malformed constraints are constraint_invalid with the reason, every one at once`() {
        val problems =
            validator.checkDeclaration(
                ParameterDeclaration(
                    LogicalType.BIGDECIMAL,
                    scale = 2,
                    constraints = ParameterConstraints(min = json("0"), max = json("\"1\"")),
                ),
            )
        problems.map { it.constraint to it.reason } shouldBe listOf("min" to "bound_type")

        validator
            .checkDeclaration(ParameterDeclaration(LogicalType.INTEGER, constraints = ParameterConstraints(json("5"), json("1"))))
            .map { it.reason } shouldBe listOf("min_greater_than_max")
        validator
            .checkDeclaration(
                ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(minLength = -1, maxLength = -2)),
            ).map { it.reason } shouldBe listOf("negative_length", "negative_length")
        validator
            .checkDeclaration(
                ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(minLength = 3, maxLength = 2)),
            ).map { it.reason } shouldBe listOf("min_length_greater_than_max_length")
    }

    @Test
    fun `an unsafe pattern is pattern_invalid, and a sound declaration has no problems`() {
        val problem =
            validator
                .checkDeclaration(
                    ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = "(a)\\1")),
                ).single()
        problem.rule shouldBe DeclarationRule.PATTERN_INVALID
        problem.reason shouldBe "unsafe_construct"

        validator.checkDeclaration(
            ParameterDeclaration(LogicalType.INTEGER, constraints = ParameterConstraints(json("0"), json("10"))),
        ) shouldBe
            emptyList()
        validator.checkDeclaration(ParameterDeclaration(LogicalType.STRING)) shouldBe emptyList()
    }

    @Test
    fun `validate refuses to judge by a declaration that would not have saved`() {
        shouldThrow<IllegalArgumentException> {
            validator.validate(ParameterDeclaration(LogicalType.STRING, constraints = ParameterConstraints(pattern = "(")), json("\"x\""))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun json(text: String): JsonNode = MAPPER.readTree(text)

    private fun accepted(
        declaration: ParameterDeclaration,
        text: String,
    ): Any {
        val outcome = validator.validate(declaration, json(text))
        return withClue("$declaration must accept $text") { outcome.shouldBeInstanceOf<ParameterValueOutcome.Accepted>() }.value
    }

    private fun refused(
        declaration: ParameterDeclaration,
        text: String,
    ): ParameterValueRefusal {
        val outcome = validator.validate(declaration, json(text))
        return withClue("$declaration must refuse $text") { outcome.shouldBeInstanceOf<ParameterValueOutcome.Refused>() }.refusal
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
