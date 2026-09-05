package co.datapipelines.calculators

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The numeric and value kinds — the modes, the boundaries, and the two refusals. */
class ValueKindsTest {
    private fun evaluate(
        kind: String,
        vararg inputs: Pair<String, Any?>,
    ): Any? = CalculatorRegistry.require(kind).evaluate(inputs.toMap())

    @Test
    fun `round honours every documented mode at the half`() {
        val half = BigDecimal("2.5")
        evaluate("round", "value" to half, "mode" to "half_up") shouldBe BigDecimal("3")
        evaluate("round", "value" to half, "mode" to "half_even") shouldBe BigDecimal("2")
        evaluate("round", "value" to BigDecimal("3.5"), "mode" to "half_even") shouldBe BigDecimal("4")
        evaluate("round", "value" to half, "mode" to "floor") shouldBe BigDecimal("2")
        evaluate("round", "value" to half, "mode" to "ceil") shouldBe BigDecimal("3")

        // Negative values are where floor and ceil stop being "round down" and "round up".
        evaluate("round", "value" to BigDecimal("-2.5"), "mode" to "floor") shouldBe BigDecimal("-3")
        evaluate("round", "value" to BigDecimal("-2.5"), "mode" to "ceil") shouldBe BigDecimal("-2")
    }

    @Test
    fun `round keeps the requested scale, including trailing zeros`() {
        // 2.35, not 2.4 or 2.3499999: the scale is part of the answer for a money figure.
        evaluate("round", "value" to BigDecimal("2.345"), "places" to 2) shouldBe BigDecimal("2.35")
        evaluate("round", "value" to BigDecimal("2"), "places" to 2) shouldBe BigDecimal("2.00")
        evaluate("round", "value" to BigDecimal("1234.5"), "places" to -2) shouldBe BigDecimal("1.2E+3")
    }

    @Test
    fun `round accepts an INTEGER-typed reference without complaint`() {
        // `round(:row_count, 0)` is legal arithmetic, not a type defect.
        evaluate("round", "value" to 7, "places" to 0) shouldBe BigDecimal("7")
    }

    @Test
    fun `percent_change is a percentage, both directions, and refuses a zero baseline`() {
        evaluate("percent_change", "current" to BigDecimal("125"), "previous" to BigDecimal("100"), "places" to 2) shouldBe
            BigDecimal("25.00")
        evaluate("percent_change", "current" to BigDecimal("75"), "previous" to BigDecimal("100"), "places" to 2) shouldBe
            BigDecimal("-25.00")
        evaluate("percent_change", "current" to BigDecimal("100"), "previous" to BigDecimal("100"), "places" to 2) shouldBe
            BigDecimal("0.00")
        // A repeating ratio must round the PERCENTAGE, not an already-rounded ratio: 1/3 of 100 is
        // 33.333333 at six places, never 33.333300.
        evaluate("percent_change", "current" to BigDecimal("400"), "previous" to BigDecimal("300"), "places" to 6) shouldBe
            BigDecimal("33.333333")

        val zero =
            shouldThrow<CalculatorEvaluationException> {
                evaluate("percent_change", "current" to BigDecimal("1"), "previous" to BigDecimal.ZERO)
            }
        zero.input shouldBe "previous"
        zero.message!!.shouldContain("zero")
    }

    @Test
    fun `coalesce takes the first non-null and is null when everything is`() {
        evaluate("coalesce", "values" to listOf(null, "GLOBAL", "IGNORED")) shouldBe "GLOBAL"
        evaluate("coalesce", "values" to listOf("FIRST", "GLOBAL")) shouldBe "FIRST"
        evaluate("coalesce", "values" to listOf(null, null)) shouldBe null
        evaluate("coalesce", "values" to emptyList<Any?>()) shouldBe null
        // `false` and `0` are values, not absences — the trap every coalesce implementation has.
        evaluate("coalesce", "values" to listOf(false, true)) shouldBe false
        evaluate("coalesce", "values" to listOf(0, 9)) shouldBe 0
    }

    @Test
    fun `if_null is coalesce for exactly two candidates`() {
        evaluate("if_null", "value" to null, "default" to "GLOBAL") shouldBe "GLOBAL"
        evaluate("if_null", "value" to "EU", "default" to "GLOBAL") shouldBe "EU"
        evaluate("if_null", "value" to false, "default" to true) shouldBe false
    }

    @Test
    fun `map translates through its parallel arrays and falls back to default`() {
        val from = listOf("GB", "US")
        val to = listOf("United Kingdom", "United States")
        evaluate("map", "value" to "GB", "from" to from, "to" to to) shouldBe "United Kingdom"
        evaluate("map", "value" to "US", "from" to from, "to" to to) shouldBe "United States"
        evaluate("map", "value" to "FR", "from" to from, "to" to to) shouldBe null
        evaluate("map", "value" to "FR", "from" to from, "to" to to, "default" to "Elsewhere") shouldBe "Elsewhere"
        // A null value matches nothing (it is not in `from`), so it takes the default.
        evaluate("map", "value" to null, "from" to from, "to" to to, "default" to "Unknown") shouldBe "Unknown"
        // The FIRST match wins when the author repeats a key.
        evaluate("map", "value" to "GB", "from" to listOf("GB", "GB"), "to" to listOf("first", "second")) shouldBe "first"
    }

    @Test
    fun `map refuses mismatched array lengths rather than silently truncating`() {
        val mismatch =
            shouldThrow<CalculatorEvaluationException> {
                evaluate("map", "value" to "GB", "from" to listOf("GB", "US"), "to" to listOf("United Kingdom"))
            }
        mismatch.input shouldBe "to"
        mismatch.message!!.shouldContain("same length")
    }

    @Test
    fun `an unknown rounding mode is refused naming the input`() {
        shouldThrow<CalculatorEvaluationException> {
            evaluate("round", "value" to BigDecimal.ONE, "mode" to "stochastic")
        }.input shouldBe "mode"
    }
}
