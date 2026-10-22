package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/** The number rule (#8 D3, ruled 2026-10-20: by logical type). */
class JavaScriptNumberRuleTest {
    private val twoTo53 = 9_007_199_254_740_992L

    @Test
    fun `an integer within 2^53 is a number, one past it a decimal string - and comes back the same`() {
        js("return typeof input;", twoTo53) shouldBe "number"
        js("return typeof input;", -twoTo53) shouldBe "number"
        js("return typeof input;", twoTo53 + 1) shouldBe "string"
        js("return input;", twoTo53 + 1) shouldBe "9007199254740993"
        js("return input;", twoTo53) shouldBe twoTo53
    }

    @Test
    fun `a BIGINTEGER always crosses as its decimal string`() {
        js("return [typeof input, input];", BigInteger.valueOf(5)) shouldBe listOf("string", "5")
        js("return input;", BigInteger("123456789012345678901234567890")) shouldBe "123456789012345678901234567890"
    }

    @Test
    fun `a DECIMAL whose double round-trips is a number and comes back as that decimal`() {
        js("return typeof input;", BigDecimal("10.50")) shouldBe "number"
        val back = js("return input;", BigDecimal("10.50")).shouldBeInstanceOf<BigDecimal>()
        back shouldBe BigDecimal("10.5")
    }

    @Test
    fun `a DECIMAL whose double would lose digits crosses as a string, unchanged both ways`() {
        val wide = BigDecimal("12345678901234567890.12")
        js("return typeof input;", wide) shouldBe "string"
        js("return input;", wide) shouldBe "12345678901234567890.12"
    }

    @Test
    fun `a non-integral result is the BigDecimal of its shortest round-trip spelling`() {
        js("return 0.1 + 0.2;") shouldBe BigDecimal("0.30000000000000004")
    }

    @Test
    fun `an integral result is a Long, or a BigInteger past Long`() {
        js("return 2.0;") shouldBe 2L
        js("return -0;") shouldBe 0L
        js("return 1e21;") shouldBe BigInteger("1000000000000000000000")
    }

    @Test
    fun `a BigInt result is its decimal string`() {
        js("return 10n ** 30n;") shouldBe "1" + "0".repeat(30)
        js("return { big: 7n };") shouldBe mapOf("big" to "7")
    }

    @Test
    fun `a double input is a number and a non-finite one is refused`() {
        js("return input * 2;", 1.25) shouldBe BigDecimal("2.5")
        refusal<ScriptEvaluationException> { js("return input;", Double.NaN) }
        refusal<ScriptEvaluationException> { js("return input;", Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `an input that is not JSON-shaped is refused`() {
        refusal<ScriptEvaluationException> { js("return input;", java.time.Instant.EPOCH) }
        refusal<ScriptEvaluationException> { js("return input;", mapOf(1 to "a")) }
    }

    @Test
    fun `JSONata parity probe - 2^53 + 1 through each engine's identity`() {
        // Recorded in the measurements report: JSONata carries the Long through unchanged; JavaScript
        // carries it as the decimal string (the D3 rule) — neither rounds it silently.
        val jsonata = ScriptingTestSupport.engine
        jsonata.evaluate(jsonata.compile("$"), twoTo53 + 1, ScriptingTestSupport.DEFAULT_LIMITS) shouldBe twoTo53 + 1
        js("return input;", twoTo53 + 1) shouldBe (twoTo53 + 1).toString()
    }
}
