package co.datapipelines.typesystem.body

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * [StructuredBodyDecoder.decodeMember] — the `request_body` fixture framework execute carries beside `parameters`
 * (#1038): read from the RAW request text so a duplicate is refused rather than resolved last-wins, numbers stay
 * exact, `null` is not absence, and the fixture gets the same bounds as a published body.
 */
class StructuredBodyMemberTest {
    private val decoder = StructuredBodyDecoder()

    private fun member(
        json: String,
        limits: StructuredBodyLimits = StructuredBodyLimits(),
    ): StructuredBodyDecoding = StructuredBodyDecoder(limits).decodeMember(json, "request_body")

    private fun refusal(json: String): StructuredBodyRefusalReason =
        member(json).shouldBeInstanceOf<StructuredBodyDecoding.Refused>().refusal.reason

    @Test
    fun `an absent member is absent, and the request's other members are skipped unread`() {
        member("""{"parameters":{"a":{"deep":[1,2,{"x":null}]}},"version":3}""") shouldBe
            StructuredBodyDecoding.Decoded(StructuredBody.Absent)
    }

    @Test
    fun `the member decodes to the same value a published body decodes to`() {
        val raw = """[{"sku":"A","qty":2,"id":"9007199254740993","n":9007199254740993,"price":1.10},{"other":{"k":[]}}]"""
        val fixture = member("""{"parameters":{},"request_body":$raw}""").shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body
        val published = decoder.decode(raw.toByteArray()).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body
        fixture shouldBe published
        fixture.canonical() shouldBe published.canonical()
        val first = fixture.shouldBeInstanceOf<StructuredBody.ArrayRoot>().items.first()
        first["id"] shouldBe "9007199254740993"
        first["n"] shouldBe 9007199254740993L
        first["price"] shouldBe BigDecimal("1.10")
        member("""{"request_body":{"big":123456789012345678901234567890}}""")
            .shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
            .body
            .shouldBeInstanceOf<StructuredBody.ObjectRoot>()
            .members["big"] shouldBe BigInteger("123456789012345678901234567890")
    }

    @Test
    fun `the member given twice, or a duplicate inside it, is refused`() {
        refusal("""{"request_body":{},"parameters":{},"request_body":[]}""") shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        refusal("""{"request_body":{"a":1,"a":2}}""") shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        refusal("""{"request_body":[{"x":{"y":1,"y":1}}]}""") shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
    }

    @Test
    fun `null, a scalar and a mixed array are invalid roots, never absence`() {
        listOf("null", "\"text\"", "42", """[{"a":1},2]""", """[null]""").forEach { value ->
            withClue(value) { refusal("""{"request_body":$value}""") shouldBe StructuredBodyRefusalReason.INVALID_ROOT }
        }
    }

    @Test
    fun `the fixture is bounded by the same byte and depth limits as a published body`() {
        val limits = StructuredBodyLimits(maxBytes = 32, maxDepth = 2)
        member("""{"request_body":{"a":{"b":1}}}""", limits).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
        member("""{"request_body":{"a":{"b":{"c":1}}}}""", limits)
            .shouldBeInstanceOf<StructuredBodyDecoding.Refused>()
            .refusal.reason shouldBe StructuredBodyRefusalReason.TOO_DEEP
        member("""{"request_body":{"note":"${"x".repeat(40)}"}}""", limits)
            .shouldBeInstanceOf<StructuredBodyDecoding.Refused>()
            .refusal.reason shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
    }

    @Test
    fun `a request that is not an object is malformed`() {
        refusal("""[{"request_body":{}}]""") shouldBe StructuredBodyRefusalReason.MALFORMED
        refusal("""{"request_body":{}""") shouldBe StructuredBodyRefusalReason.MALFORMED
    }
}
