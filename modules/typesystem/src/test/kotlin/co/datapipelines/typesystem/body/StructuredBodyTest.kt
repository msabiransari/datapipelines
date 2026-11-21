package co.datapipelines.typesystem.body

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The canonical comparison form #1039's request fingerprint reads (#1038; requirement §4): object
 * member order immaterial, array order material, absent distinct from the empty containers,
 * numbers compared by exact value.
 */
class StructuredBodyTest {
    private val decoder = StructuredBodyDecoder()

    private fun body(json: String): StructuredBody = (decoder.decode(json.toByteArray()) as StructuredBodyDecoding.Decoded).body

    @Test
    fun `object member order is immaterial`() {
        body("""{"a":1,"b":{"y":2,"x":3}}""").sameRequestBodyAs(body("""{"b":{"x":3,"y":2},"a":1}""")) shouldBe true
        body("""{"b":{"x":3,"y":2},"a":1}""").canonical() shouldBe """{"a":1,"b":{"x":3,"y":2}}"""
    }

    @Test
    fun `array order is material`() {
        body("""[{"a":1},{"b":2}]""").sameRequestBodyAs(body("""[{"b":2},{"a":1}]""")) shouldBe false
    }

    @Test
    fun `absent, the empty object and the empty array are three different inputs`() {
        val forms = listOf(StructuredBody.Absent, body("{}"), body("[]"))
        forms.forEach { a -> forms.forEach { b -> a.sameRequestBodyAs(b) shouldBe (a === b || a == b) } }
        StructuredBody.Absent.canonical() shouldBe null
        StructuredBody.Absent.present shouldBe false
        StructuredBody.Absent.value shouldBe null
    }

    @Test
    fun `numbers compare by exact value and a string never equals a number`() {
        body("""{"n":1.50}""").sameRequestBodyAs(body("""{"n":1.5}""")) shouldBe true
        body("""{"n":100}""").sameRequestBodyAs(body("""{"n":1E+2}""")) shouldBe true
        body("""{"n":9007199254740993}""").sameRequestBodyAs(body("""{"n":9007199254740992}""")) shouldBe false
        body("""{"n":"1"}""").sameRequestBodyAs(body("""{"n":1}""")) shouldBe false
        StructuredBody.canonicalNumber(BigDecimal("0.000")) shouldBe "0"
    }

    @Test
    fun `a null member differs from a missing member`() {
        body("""{"a":null}""").sameRequestBodyAs(body("{}")) shouldBe false
    }
}
