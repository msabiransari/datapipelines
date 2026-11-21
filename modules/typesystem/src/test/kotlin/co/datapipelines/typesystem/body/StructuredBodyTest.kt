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

    private fun nested(depth: Int): Map<String, Any?> {
        var value: Map<String, Any?> = emptyMap()
        repeat(depth - 1) { value = mapOf("a" to value) }
        return value
    }

    @Test
    fun `a value tree meets the budget exactly at the depth cap and is refused one past it`() {
        val limits = StructuredBodyLimits(maxDepth = 3)
        limits.refusalOf(nested(3)) shouldBe null
        limits.refusalOf(nested(4)) shouldBe StructuredBodyRefusalReason.TOO_DEEP
        limits.refusalOf(listOf(nested(2))) shouldBe null
        limits.refusalOf(listOf(nested(3))) shouldBe StructuredBodyRefusalReason.TOO_DEEP
    }

    @Test
    fun `a value tree meets the byte cap exactly and is refused one byte past it`() {
        val tree = mapOf("k" to "é")
        val bytes =
            StructuredBody
                .ObjectRoot(tree)
                .canonical()!!
                .toByteArray()
                .size
                .toLong()
        StructuredBodyLimits(maxBytes = bytes).refusalOf(tree) shouldBe null
        StructuredBodyLimits(maxBytes = bytes - 1).refusalOf(tree) shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
    }

    @Test
    fun `a hand-built tree far past the cap is refused without overflowing the stack`() {
        StructuredBodyLimits().refusalOf(nested(50_000)) shouldBe StructuredBodyRefusalReason.TOO_DEEP
    }
}
