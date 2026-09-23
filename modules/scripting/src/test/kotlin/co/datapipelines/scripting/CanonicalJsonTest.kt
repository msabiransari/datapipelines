package co.datapipelines.scripting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The canonical form (transform-nodes design §5.5), pinned case by case: key order
 * never matters, number spelling does. These bytes are the test runner's equality
 * currency (D-T12), so the expected strings here are the CONTRACT, not an
 * implementation detail.
 */
class CanonicalJsonTest {
    @Test
    fun `object keys are sorted recursively`() {
        val value =
            linkedMapOf(
                "b" to linkedMapOf("y" to 1, "a" to 2),
                "a" to 3,
            )
        CanonicalJson.write(value) shouldBe """{"a":3,"b":{"a":2,"y":1}}"""
    }

    @Test
    fun `arrays keep the order the function returned`() {
        CanonicalJson.write(listOf(3, 1, 2)) shouldBe "[3,1,2]"
    }

    @Test
    fun `integers are integers - longs and big integers stay exact`() {
        CanonicalJson.write(1) shouldBe "1"
        CanonicalJson.write(java.lang.Long.MAX_VALUE) shouldBe "9223372036854775807"
        CanonicalJson.write(BigInteger.TEN.pow(20)) shouldBe "100000000000000000000"
    }

    @Test
    fun `doubles render shortest round-trip`() {
        CanonicalJson.write(0.1) shouldBe "0.1"
        CanonicalJson.write(2.345) shouldBe "2.345"
        CanonicalJson.write(-0.0) shouldBe "-0.0"
    }

    @Test
    fun `big decimals are plain - never exponent notation`() {
        CanonicalJson.write(BigDecimal("12.50")) shouldBe "12.50"
        CanonicalJson.write(BigDecimal("1E+2")) shouldBe "100"
        CanonicalJson.write(BigDecimal("0.30")) shouldBe "0.30"
    }

    @Test
    fun `strings are escaped per json - nulls and booleans are literal`() {
        CanonicalJson.write("a\"b\nc") shouldBe "\"a\\\"b\\nc\""
        CanonicalJson.write(null) shouldBe "null"
        CanonicalJson.write(true) shouldBe "true"
        CanonicalJson.write(false) shouldBe "false"
    }

    @Test
    fun `equal ignores key order and decimal spelling - not value`() {
        CanonicalJson.equal(linkedMapOf("a" to 1, "b" to 2), linkedMapOf("b" to 2, "a" to 1)) shouldBe true
        CanonicalJson.equal(BigDecimal("12.5"), BigDecimal("12.50")) shouldBe true
        CanonicalJson.equal(1, 1.0) shouldBe true
        CanonicalJson.equal("1", 1) shouldBe false
        CanonicalJson.equal(listOf(1, 2), listOf(2, 1)) shouldBe false
        CanonicalJson.equal(null, 0) shouldBe false
    }
}
