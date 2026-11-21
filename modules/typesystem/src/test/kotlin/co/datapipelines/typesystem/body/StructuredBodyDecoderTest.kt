package co.datapipelines.typesystem.body

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The structured-body decoder (#1038; published-mutation requirement §3.2, §4, §5): the root
 * rule, duplicate members, strict UTF-8, the byte and depth caps (a trickled "chunked" entity
 * included), exact numbers and the value-free refusal.
 */
class StructuredBodyDecoderTest {
    private val decoder = StructuredBodyDecoder()

    private fun decoded(json: String): StructuredBody {
        val outcome = decoder.decode(json.toByteArray(Charsets.UTF_8))
        return outcome.shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body
    }

    private fun refused(
        bytes: ByteArray,
        limits: StructuredBodyLimits = StructuredBodyLimits(),
    ): StructuredBodyRefusal = StructuredBodyDecoder(limits).decode(bytes).shouldBeInstanceOf<StructuredBodyDecoding.Refused>().refusal

    private fun refused(json: String): StructuredBodyRefusal = refused(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `an object root keeps member order, nesting, nulls and unknown fields`() {
        val body = decoded("""{"z":1,"a":{"lines":[{"sku":"A","qty":2},{"note":null}]},"empty":{},"none":[]}""")
        val root = body.shouldBeInstanceOf<StructuredBody.ObjectRoot>().members
        root.keys.toList() shouldBe listOf("z", "a", "empty", "none")
        root["a"] shouldBe mapOf("lines" to listOf(mapOf("sku" to "A", "qty" to 2L), mapOf("note" to null)))
        root["empty"] shouldBe emptyMap<String, Any?>()
        root["none"] shouldBe emptyList<Any?>()
        (root.containsKey("a") && (root["a"] as Map<*, *>).containsKey("lines")) shouldBe true
    }

    @Test
    fun `a heterogeneous array, a singleton array and the empty containers keep their root form`() {
        decoded("""[{"a":1},{"b":"x","c":[1,2]}]""").shouldBeInstanceOf<StructuredBody.ArrayRoot>().items shouldBe
            listOf(mapOf("a" to 1L), mapOf("b" to "x", "c" to listOf(1L, 2L)))
        decoded("""[{"only":true}]""").shouldBeInstanceOf<StructuredBody.ArrayRoot>().items shouldBe listOf(mapOf("only" to true))
        decoded("{}") shouldBe StructuredBody.ObjectRoot(emptyMap())
        decoded(" [ ] ") shouldBe StructuredBody.ArrayRoot(emptyList())
    }

    @Test
    fun `zero bytes is absent and whitespace alone is malformed`() {
        decoder.decode(ByteArray(0)) shouldBe StructuredBodyDecoding.Decoded(StructuredBody.Absent)
        refused("  \n").reason shouldBe StructuredBodyRefusalReason.MALFORMED
    }

    @Test
    fun `a scalar root, a null root and a root array with a non-object element are invalid roots`() {
        listOf("1", "\"text\"", "true", "null").forEach { json ->
            withClue(json) { refused(json).reason shouldBe StructuredBodyRefusalReason.INVALID_ROOT }
        }
        val finalItem = refused("""[{"a":1},{"b":2},3]""")
        finalItem.reason shouldBe StructuredBodyRefusalReason.INVALID_ROOT
        finalItem.index shouldBe 2
        refused("""[{"a":1},null]""").index shouldBe 1
        refused("""[[{"a":1}]]""").index shouldBe 0
    }

    @Test
    fun `duplicate members are refused at any depth, while equal names in sibling objects are not duplicates`() {
        refused("""{"a":1,"a":2}""").reason shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        refused("""[{"x":{"k":1,"k":1}}]""").reason shouldBe StructuredBodyRefusalReason.DUPLICATE_MEMBER
        decoded("""[{"k":1},{"k":2}]""").shouldBeInstanceOf<StructuredBody.ArrayRoot>()
    }

    @Test
    fun `malformed text and trailing tokens are malformed`() {
        listOf("""{"a":""", """{"a":1}}""", """{} {}""", """{'a':1}""", """{"a":NaN}""", """[{"a":1},]""").forEach { json ->
            withClue(json) { refused(json).reason shouldBe StructuredBodyRefusalReason.MALFORMED }
        }
    }

    @Test
    fun `the depth cap admits its own depth and refuses one more`() {
        val limits = StructuredBodyLimits(maxDepth = 3)
        StructuredBodyDecoder(limits).decode("""{"a":{"b":{}}}""".toByteArray()).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
        refused("""{"a":{"b":{"c":{}}}}""".toByteArray(), limits).reason shouldBe StructuredBodyRefusalReason.TOO_DEEP
        refused("""[{"a":{"b":[]}}]""".toByteArray(), limits).reason shouldBe StructuredBodyRefusalReason.TOO_DEEP
        StructuredBodyRefusalReason.TOO_DEEP.tooLarge shouldBe true
    }

    @Test
    fun `the byte cap admits exactly its bytes and refuses one more`() {
        val json = """{"k":"vvvv"}""" // 12 bytes
        StructuredBodyDecoder(
            StructuredBodyLimits(maxBytes = 12),
        ).decode(json.toByteArray()).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
        refused(json.toByteArray(), StructuredBodyLimits(maxBytes = 11)).reason shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
        // Multi-byte characters count as their UTF-8 bytes, not as characters.
        val unicode = """{"k":"é"}""" // 10 bytes, 9 chars
        refused(unicode.toByteArray(), StructuredBodyLimits(maxBytes = 9)).reason shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
    }

    @Test
    fun `a trickled over-cap entity is refused without draining it`() {
        val cap = 64L
        val source = TrickleStream(("[" + "{\"a\":1},".repeat(10_000) + "{}]").toByteArray())
        val refusal =
            StructuredBodyDecoder(StructuredBodyLimits(maxBytes = cap))
                .decode(source)
                .shouldBeInstanceOf<StructuredBodyDecoding.Refused>()
                .refusal
        refusal.reason shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
        withClue("bytes pulled from the source") { (source.served <= cap + 1) shouldBe true }
    }

    @Test
    fun `invalid UTF-8 bytes and an unpaired surrogate escape are invalid encoding`() {
        val badBytes =
            byteArrayOf(
                '{'.code.toByte(),
                '"'.code.toByte(),
                0xC3.toByte(),
                0x28,
                '"'.code.toByte(),
                ':'.code.toByte(),
                '1'.code.toByte(),
                '}'.code.toByte(),
            )
        refused(badBytes).reason shouldBe StructuredBodyRefusalReason.INVALID_ENCODING
        refused("""{"k":"\ud800"}""").reason shouldBe StructuredBodyRefusalReason.INVALID_ENCODING
        refused("""{"\udc00":1}""").reason shouldBe StructuredBodyRefusalReason.INVALID_ENCODING
    }

    @Test
    fun `nested Unicode arrives unchanged, escaped or literal`() {
        val body = decoded("""{"name":"Zoë 名前 😀","nested":[{"emoji":"😀"}]}""")
        val root = body.shouldBeInstanceOf<StructuredBody.ObjectRoot>().members
        root["name"] shouldBe "Zoë 名前 😀"
        root["nested"] shouldBe listOf(mapOf("emoji" to "😀"))
    }

    @Test
    fun `numbers stay exact - longs, big integers, decimals with their scale, and string identifiers untouched`() {
        val root =
            decoded("""{"i":9007199254740993,"big":123456789012345678901234567890,"d":12.50,"e":1E+3,"id":"9007199254740993"}""")
                .shouldBeInstanceOf<StructuredBody.ObjectRoot>()
                .members
        root["i"] shouldBe 9_007_199_254_740_993L
        root["big"] shouldBe BigInteger("123456789012345678901234567890")
        root["d"] shouldBe BigDecimal("12.50")
        root["e"] shouldBe BigDecimal("1E+3")
        root["id"] shouldBe "9007199254740993"
    }

    @Test
    fun `an exponent past the digit bound and an over-long number token are refused`() {
        refused("""{"n":1e999999999}""").reason shouldBe StructuredBodyRefusalReason.NUMBER_OUT_OF_RANGE
        refused("""{"n":1e-1001}""").reason shouldBe StructuredBodyRefusalReason.NUMBER_OUT_OF_RANGE
        decoded("""{"n":1e-1000}""").shouldBeInstanceOf<StructuredBody.ObjectRoot>()
        refused("{\"n\":" + "9".repeat(1_001) + "}").reason shouldBe StructuredBodyRefusalReason.TOKEN_TOO_LARGE
    }

    @Test
    fun `a refusal never carries a supplied value or member name`() {
        val marker = "s3cr3t-marker"
        listOf(
            """{"$marker":1,"$marker":2}""",
            """[{"a":"$marker"},"$marker"]""",
            """{"a":"$marker"""",
            """"$marker"""",
        ).forEach { json ->
            withClue(json) { refused(json).toString() shouldNotContain marker }
        }
    }

    @Test
    fun `an embedded fixture decodes with the same rules and leaves the parser on its last token`() {
        val outer = """{"params":{"id":7},"body":[{"a":1},{"b":[true]}],"after":1}"""
        val parser =
            com.fasterxml.jackson.core
                .JsonFactory()
                .createParser(outer)
        while (parser.nextToken() != null) {
            if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && parser.currentName() == "body") break
        }
        parser.nextToken()
        val body = decoder.decodeEmbedded(parser).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body
        body shouldBe StructuredBody.ArrayRoot(listOf(mapOf("a" to 1L), mapOf("b" to listOf(true))))
        parser.currentToken() shouldBe com.fasterxml.jackson.core.JsonToken.END_ARRAY
        parser.nextToken() shouldBe com.fasterxml.jackson.core.JsonToken.FIELD_NAME
        parser.currentName() shouldBe "after"

        fun embedded(
            json: String,
            limits: StructuredBodyLimits = StructuredBodyLimits(),
        ): StructuredBodyDecoding {
            val p =
                com.fasterxml.jackson.core
                    .JsonFactory()
                    .createParser(json)
            p.nextToken()
            return StructuredBodyDecoder(limits).decodeEmbedded(p)
        }
        embedded("""{"a":1,"a":2}""").shouldBeInstanceOf<StructuredBodyDecoding.Refused>().refusal.reason shouldBe
            StructuredBodyRefusalReason.DUPLICATE_MEMBER
        embedded("null").shouldBeInstanceOf<StructuredBodyDecoding.Refused>().refusal.reason shouldBe
            StructuredBodyRefusalReason.INVALID_ROOT
        embedded("""{"k":"vvvv"}""", StructuredBodyLimits(maxBytes = 11))
            .shouldBeInstanceOf<StructuredBodyDecoding.Refused>()
            .refusal.reason shouldBe StructuredBodyRefusalReason.TOO_MANY_BYTES
    }

    @Test
    fun `the limits refuse an out-of-range configuration`() {
        shouldThrow<IllegalArgumentException> { StructuredBodyLimits(maxBytes = 0) }
        shouldThrow<IllegalArgumentException> { StructuredBodyLimits(maxDepth = 0) }
        shouldThrow<IllegalArgumentException> { StructuredBodyLimits(maxDepth = 65) }
        StructuredBodyLimits() shouldBe StructuredBodyLimits(1_048_576L, 32)
    }

    /** Serves one byte per read, like a chunked entity arriving slowly, and counts what it served. */
    private class TrickleStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        var served = 0
            private set

        override fun read(): Int = if (served < bytes.size) bytes[served++].toInt() and 0xFF else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            val next = read()
            if (next < 0) return -1
            b[off] = next.toByte()
            return 1
        }
    }
}
