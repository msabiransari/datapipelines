package co.datapipelines.application.endpoints

import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import co.datapipelines.typesystem.body.StructuredBodyLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * #1039 — the reservation's two keyed digests (metadata-db §4.61) and the `Idempotency-Key` grammar
 * (published-mutation-apis §5): what makes two requests the same scope, what makes them the same request, and that
 * neither digest carries the key or the body it was made from.
 */
class PublishedMutationDigestsTest {
    private val digests = PublishedMutationDigests(ByteArray(32) { it.toByte() })
    private val workspace = UUID.randomUUID()
    private val endpoint = UUID.randomUUID()

    private fun scope(
        key: String = "k-1",
        apiKeyId: String = "dpk_A",
        endpointId: UUID = endpoint,
        method: EndpointMethod = EndpointMethod.POST,
        path: String = "/x/v1/orders",
    ) = digests.scope(workspace, apiKeyId, endpointId, method, path, key)

    private fun body(json: String): StructuredBody =
        StructuredBodyDecoder(StructuredBodyLimits(maxBytes = 1024, maxDepth = 8))
            .decode(ByteArrayInputStream(json.toByteArray()))
            .shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
            .body

    private fun fingerprint(
        body: StructuredBody = body("""{"a":1,"b":[1,2]}"""),
        parameters: Map<String, Any?> = emptyMap(),
        path: String = "/x/v1/orders",
    ) = digests.fingerprint(EndpointMethod.POST, path, parameters, body)

    @Test
    fun `the scope is stable and changes with every one of its facts`() {
        scope() shouldBe scope()
        listOf(
            scope(key = "k-2"),
            scope(apiKeyId = "dpk_B"),
            scope(endpointId = UUID.randomUUID()),
            scope(method = EndpointMethod.PUT),
            scope(path = "/x/v1/orders/2"),
        ).forEach { it shouldNotBe scope() }
    }

    @Test
    fun `member order is immaterial, array order and value are material`() {
        fingerprint(body("""{"b":[1,2],"a":1}""")) shouldBe fingerprint()
        fingerprint(body("""{"a":1,"b":[2,1]}""")) shouldNotBe fingerprint()
        fingerprint(body("""{"a":2,"b":[1,2]}""")) shouldNotBe fingerprint()
    }

    @Test
    fun `an absent body, an empty object and an empty array are three different requests`() {
        val prints = setOf(fingerprint(StructuredBody.Absent), fingerprint(body("{}")), fingerprint(body("[]")))
        prints.size shouldBe 3
    }

    @Test
    fun `parameter names are order-free, typed values decide, and the path counts`() {
        val p = mapOf("a" to 1, "b" to "x")
        fingerprint(parameters = p) shouldBe fingerprint(parameters = linkedMapOf("b" to "x", "a" to 1))
        fingerprint(parameters = mapOf("a" to 2, "b" to "x")) shouldNotBe fingerprint(parameters = p)
        // The TYPE is part of the value: the INTEGER 1 is not the STRING "1", and a bound null is not the text "null".
        fingerprint(parameters = mapOf("a" to "1", "b" to "x")) shouldNotBe fingerprint(parameters = p)
        fingerprint(parameters = mapOf("a" to null)) shouldNotBe fingerprint(parameters = mapOf("a" to "null"))
        // A declared name bound to null is still a declared name: not the same as a request binding nothing.
        fingerprint(parameters = mapOf("a" to null)) shouldNotBe fingerprint(parameters = emptyMap())
        // Length-prefixed fields: moving a boundary changes the digest.
        fingerprint(parameters = mapOf("ab" to "")) shouldNotBe fingerprint(parameters = mapOf("a" to "b"))
        fingerprint(path = "/x/v1/orders/2") shouldNotBe fingerprint()
    }

    @Test
    fun `canonical values are equal exactly when the typed values are`() {
        val instant = Instant.parse("2026-01-01T00:00:00Z")
        val sameInstant = OffsetDateTime.parse("2026-01-01T01:00:00+01:00").toInstant()
        fingerprint(parameters = mapOf("t" to instant)) shouldBe fingerprint(parameters = mapOf("t" to sameInstant))
        fingerprint(parameters = mapOf("b" to byteArrayOf(1, 2))) shouldBe fingerprint(parameters = mapOf("b" to byteArrayOf(1, 2)))
        fingerprint(parameters = mapOf("b" to byteArrayOf(1, 2))) shouldNotBe fingerprint(parameters = mapOf("b" to byteArrayOf(2, 1)))
        fingerprint(parameters = mapOf("d" to LocalDate.parse("2026-01-02"))) shouldBe
            fingerprint(parameters = mapOf("d" to LocalDate.of(2026, 1, 2)))
        // A DECIMAL keeps its scale: a template may render 1.0 and 1.00 differently, so they are different requests.
        fingerprint(parameters = mapOf("m" to BigDecimal("1.0"))) shouldNotBe fingerprint(parameters = mapOf("m" to BigDecimal("1.00")))
        fingerprint(parameters = mapOf("m" to BigDecimal("1.50"))) shouldBe fingerprint(parameters = mapOf("m" to BigDecimal("1.50")))
    }

    @Test
    fun `neither digest carries the key or the body, and a different secret gives different digests`() {
        val marker = "CANARY1039"
        scope(key = marker) shouldNotContain marker
        fingerprint(body("""{"v":"$marker"}""")) shouldNotContain marker
        PublishedMutationDigests(
            ByteArray(32) { 7 },
        ).scope(workspace, "dpk_A", endpoint, EndpointMethod.POST, "/x/v1/orders", "k-1") shouldNotBe
            scope()
    }

    @Test
    fun `a short secret is refused`() {
        shouldThrow<IllegalArgumentException> { PublishedMutationDigests(ByteArray(31)) }
    }

    @Test
    fun `the key grammar is 1 to 128 characters of letters, digits, dot, underscore, colon and dash`() {
        PublishedIdempotencyKey.refusal("aZ0._:-") shouldBe null
        PublishedIdempotencyKey.refusal("x".repeat(128)) shouldBe null
        PublishedIdempotencyKey.refusal(null) shouldBe "missing"
        listOf("", "x".repeat(129), "has space", "é", "a/b", "a\u0000").forEach {
            PublishedIdempotencyKey.refusal(it) shouldBe "malformed"
        }
    }
}
