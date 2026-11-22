package co.datapipelines.typesystem.body

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [StructuredBodyDecoder.decodeFixture] (#1038, review C1/C2): a full-run or authored fixture is
 * counted by its CANONICAL bytes — the one budget framework execute, `pipelines_execute` and the
 * authored runner share — while every other rule (syntax, duplicates at any depth, the root, depth,
 * exact numbers) is the raw decoder's.
 */
class StructuredBodyFixtureDecoderTest {
    private fun fixture(
        text: String,
        limits: StructuredBodyLimits = StructuredBodyLimits(),
    ): StructuredBodyDecoding = StructuredBodyDecoder(limits).decodeFixture(text)

    private fun reasonOf(
        text: String,
        limits: StructuredBodyLimits = StructuredBodyLimits(),
    ): StructuredBodyRefusalReason = fixture(text, limits).shouldBeInstanceOf<StructuredBodyDecoding.Refused>().refusal.reason

    /** `{"n":1.000…(120 zeros),"p":"x…"}`: 135 + [pad] bytes as written, 14 + [pad] canonical (`1`). */
    private fun paddedDecimal(pad: Int): String = """{"n":1.${"0".repeat(TRAILING_ZEROS)},"p":"${"x".repeat(pad)}"}"""

    @Test
    fun `a fixture is budgeted by canonical bytes, so a 4197-byte spelling of a 4076-byte body fits a 4096 budget`() {
        val limits = StructuredBodyLimits(maxBytes = BUDGET)
        val text = paddedDecimal(CANONICAL_4076_PAD)
        text.toByteArray().size shouldBe SERIALIZED_4197
        val body = fixture(text, limits).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body
        body.canonical()!!.toByteArray().size shouldBe CANONICAL_4076
        withClue("the raw entity decoder still counts what was sent") {
            StructuredBodyDecoder(limits)
                .decode(text.toByteArray())
                .shouldBeInstanceOf<StructuredBodyDecoding.Refused>()
                .refusal.reason shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
        }
    }

    @Test
    fun `the canonical budget admits exactly its bytes and refuses one more`() {
        val limits = StructuredBodyLimits(maxBytes = BUDGET)
        val exact = paddedDecimal(BUDGET.toInt() - CANONICAL_OVERHEAD)
        fixture(exact, limits)
            .shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
            .body
            .canonical()!!
            .toByteArray()
            .size shouldBe
            BUDGET.toInt()
        reasonOf(paddedDecimal(BUDGET.toInt() - CANONICAL_OVERHEAD + 1), limits) shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
    }

    @Test
    fun `a fixture keeps the raw decoder's syntax, duplicate, root and depth rules`() {
        reasonOf("""{"a":1,"a":2}""") shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        reasonOf("""{"o":{"k":1,"k":1}}""") shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        reasonOf("""[{"x":1},{"x":2,"x":3}]""") shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        reasonOf("42") shouldBe StructuredBodyRefusalReason.INVALID_ROOT
        reasonOf("""[{"a":1},2]""") shouldBe StructuredBodyRefusalReason.INVALID_ROOT
        reasonOf("""{"a":1} {}""") shouldBe StructuredBodyRefusalReason.MALFORMED
        reasonOf("""{"a":""") shouldBe StructuredBodyRefusalReason.MALFORMED

        val shallow = StructuredBodyLimits(maxDepth = 2)
        fixture("""{"a":{"b":1}}""", shallow).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
        reasonOf("""{"a":{"b":{"c":1}}}""", shallow) shouldBe StructuredBodyRefusalReason.TOO_DEEP
    }

    @Test
    fun `a fixture keeps exact values, member order and its root form`() {
        val body =
            fixture("""{"z":$LONG_FRACTION,"id":"9007199254740993","a":[{"q":9007199254740993}]}""")
                .shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
                .body
                .shouldBeInstanceOf<StructuredBody.ObjectRoot>()
        body.members.keys.toList() shouldBe listOf("z", "id", "a")
        body.members["z"] shouldBe BigDecimal(LONG_FRACTION)
        body.members["id"] shouldBe "9007199254740993"
        fixture("""[{"only":true}]""").shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body shouldBe
            StructuredBody.ArrayRoot(listOf(mapOf("only" to true)))
        fixture("[]").shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body shouldBe StructuredBody.ArrayRoot(emptyList())
        fixture("{}").shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body shouldBe StructuredBody.ObjectRoot(emptyMap())
    }

    private companion object {
        const val BUDGET = 4096L
        const val TRAILING_ZEROS = 120
        const val CANONICAL_OVERHEAD = 14
        const val CANONICAL_4076 = 4076
        const val SERIALIZED_4197 = 4197
        const val CANONICAL_4076_PAD = CANONICAL_4076 - CANONICAL_OVERHEAD
        const val LONG_FRACTION = "0.123456789012345678901234567890"
    }
}
