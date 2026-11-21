package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.RequestBodyDeclaration
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import co.datapipelines.typesystem.body.StructuredBodyLimits
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The published-request body codec (#1038; published-mutation-apis §3.2, §4, §5): 415 for media type, charset and
 * content encoding; 400 (one `endpoint.request.body_invalid` entry) for syntax, duplicates, roots, a missing required
 * body and a body on a bodyless route; 413 for the byte and depth caps — a trickled chunked body stops at the cap.
 * The decoded value is the same one framework execute's fixture yields.
 */
class PublishedRequestBodyCodecTest {
    private val limits = StructuredBodyLimits(maxBytes = 64, maxDepth = 3)
    private val codec = PublishedRequestBodyCodec(StructuredBodyDecoder(limits))
    private val mutation = PublishedRequestBodyCodec.Route(acceptsBody = true, required = true)
    private val optional = PublishedRequestBodyCodec.Route(acceptsBody = true, required = false)
    private val bodyless = PublishedRequestBodyCodec.Route(acceptsBody = false, required = false)

    private fun read(
        body: String,
        contentType: String? = "application/json",
        contentEncoding: String? = null,
        route: PublishedRequestBodyCodec.Route = mutation,
    ) = codec.read(contentType, contentEncoding, ByteArrayInputStream(body.toByteArray()), route)

    private fun refused(outcome: PublishedRequestBodyCodec.Outcome) =
        outcome.shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Refused>()

    private fun entryReason(outcome: PublishedRequestBodyCodec.Outcome): Any? {
        val refusal = refused(outcome)
        refusal.status shouldBe HttpStatus.BAD_REQUEST
        refusal.code shouldBe PipelineErrorCodes.EndpointRequest.INVALID
        val entry = (refusal.details["errors"] as List<*>).single() as Map<*, *>
        entry["code"] shouldBe PipelineErrorCodes.EndpointRequest.BODY_INVALID
        entry["parameter"] shouldBe null
        return entry["reason"]
    }

    @Test
    fun `a JSON object or array of objects is admitted with the media types v1 accepts`() {
        listOf("application/json", "application/json; charset=UTF-8", "Application/JSON;charset=\"utf-8\"").forEach { type ->
            withClue(type) {
                read("""[{"a":1},{"b":[]}]""", contentType = type)
                    .shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>()
                    .body shouldBe StructuredBody.ArrayRoot(listOf(mapOf("a" to 1L), mapOf("b" to emptyList<Any?>())))
            }
        }
        read("{}", contentEncoding = "identity").shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>()
    }

    @Test
    fun `the published body decodes to exactly what the framework fixture decodes to`() {
        val raw = """{"id":"9007199254740993","n":1.10,"l":[{"x":null}]}"""
        val published = read(raw).shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>().body
        val fixture =
            StructuredBodyDecoder(limits)
                .decodeMember("""{"parameters":{},"request_body":$raw}""", "request_body")
                .shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
                .body
        published shouldBe fixture
        published.canonical() shouldBe fixture.canonical()
    }

    @Test
    fun `media type, charset and encoding refusals are 415 naming the reason, never the header`() {
        val cases =
            listOf(
                Triple("text/plain", null, PublishedRequestBodyCodec.REASON_MEDIA_TYPE),
                Triple("application/vnd.secret+json", null, PublishedRequestBodyCodec.REASON_MEDIA_TYPE),
                Triple(null, null, PublishedRequestBodyCodec.REASON_MEDIA_TYPE),
                Triple("not a media type ;;", null, PublishedRequestBodyCodec.REASON_MEDIA_TYPE),
                Triple("application/json; charset=ISO-8859-1", null, PublishedRequestBodyCodec.REASON_CHARSET),
                Triple("application/json", "gzip", PublishedRequestBodyCodec.REASON_CONTENT_ENCODING),
            )
        cases.forEach { (type, encoding, reason) ->
            withClue("$type $encoding") {
                val refusal = refused(read("{}", contentType = type, contentEncoding = encoding))
                refusal.status shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
                refusal.code shouldBe PipelineErrorCodes.EndpointRequest.UNSUPPORTED_MEDIA_TYPE
                refusal.details["reason"] shouldBe reason
                refusal.details.toString() shouldNotContain "secret"
            }
        }
    }

    @Test
    fun `syntax, duplicates, roots, a missing required body and a body on a bodyless route are one 400 entry`() {
        entryReason(read("""{"a":1""")) shouldBe "malformed_json"
        entryReason(read("""{"sku":"secret","sku":"secret"}""")) shouldBe "duplicate_member"
        entryReason(read("\"secret\"")) shouldBe "invalid_root"
        entryReason(read("null")) shouldBe "invalid_root"
        entryReason(read("""[{"a":1},"secret"]""")) shouldBe "invalid_root"
        entryReason(read("", route = mutation)) shouldBe PublishedRequestBodyCodec.REASON_REQUIRED
        entryReason(read("{}", route = bodyless)) shouldBe PublishedRequestBodyCodec.REASON_NOT_ACCEPTED
        codec
            .read(
                "application/json",
                null,
                ByteArrayInputStream(byteArrayOf(0x7B, 0x22, 0xC3.toByte(), 0x28, 0x22, 0x3A, 0x31, 0x7D)),
                mutation,
            ).let(::entryReason) shouldBe "invalid_encoding"
        refused(read("""{"sku":"secret","sku":"secret"}""")).details.toString() shouldNotContain "secret"
    }

    @Test
    fun `no body on an optional or bodyless route is absent, whatever the headers say`() {
        read("", contentType = null, route = optional).shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>().body shouldBe
            StructuredBody.Absent
        read(
            "",
            contentType = "text/plain",
            route = bodyless,
        ).shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>().body shouldBe
            StructuredBody.Absent
    }

    @Test
    fun `a chunked body crossing the byte cap is 413 and is not read past the cap`() {
        val trickle = Trickle(("""{"note":"""" + "x".repeat(10_000) + "\"}").toByteArray())
        val refusal = refused(codec.read("application/json", null, trickle, mutation))
        refusal.status shouldBe HttpStatus.PAYLOAD_TOO_LARGE
        refusal.code shouldBe PipelineErrorCodes.EndpointRequest.TOO_LARGE
        refusal.details shouldBe mapOf("reason" to "too_many_bytes", "limit" to 64L)
        trickle.served shouldBeLessThan 200
        refused(read("""{"a":{"b":{"c":{}}}}""")).details shouldBe mapOf("reason" to "too_deep", "limit" to 3L)
    }

    @Test
    fun `a route takes a body only on a mutation method of a pipeline that declares one`() {
        val plain = Pipeline(1, "shop/orders/submit", "Submit", "", PipelineSettings(), emptyMap(), emptyList())
        val required = plain.copy(requestBody = RequestBodyDeclaration(required = true))
        val optionalBody = plain.copy(requestBody = RequestBodyDeclaration(required = false))
        PublishedRequestBodyCodec.Route.of(EndpointMethod.GET, required) shouldBe bodyless
        PublishedRequestBodyCodec.Route.of(EndpointMethod.POST, plain) shouldBe bodyless
        PublishedRequestBodyCodec.Route.of(EndpointMethod.POST, required) shouldBe mutation
        PublishedRequestBodyCodec.Route.of(EndpointMethod.DELETE, optionalBody) shouldBe optional
    }

    /** An entity served one byte per read, as a chunked transfer arrives. */
    private class Trickle(
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
