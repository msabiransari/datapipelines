package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyLimits
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * #1039 — a published mutation's request rules (published-mutation-apis §3, §5): the envelope (key, media type, body)
 * read before any lookup, then the pin's own rules; every `400` problem in one capped list that never echoes a value,
 * a key or a caller-chosen name.
 */
class MutationRequestRulesTest {
    private val rules = MutationRequestRules(ResultConfig())
    private val codec = PublishedRequestBodyCodec(StructuredBodyDecoder(StructuredBodyLimits(maxBytes = 64, maxDepth = 4)))

    // ------------------------------------------------------------------------- envelope

    @Test
    fun `a keyed JSON body is read`() {
        val read = rules.envelope(call(body = """{"a":1}"""), codec).shouldBeInstanceOf<MutationRequestRules.Envelope.Read>()
        read.body.present shouldBe true
    }

    @Test
    fun `a missing key and a malformed key are 400 entries naming the reason, never the value`() {
        val missing = refusedEnvelope(call(key = null))
        entries(missing).single()["reason"] shouldBe "missing"
        entries(missing).single()["code"] shouldBe PipelineErrorCodes.EndpointRequest.IDEMPOTENCY_KEY_INVALID

        val malformed = refusedEnvelope(call(key = "SECRET value"))
        entries(malformed).single()["reason"] shouldBe "malformed"
        malformed.toString() shouldNotContain "SECRET"
    }

    @Test
    fun `a malformed key and a malformed body are both listed in one 400`() {
        val refused = refusedEnvelope(call(key = null, body = """{"a":"""))
        refused.status shouldBe 400
        refused.code shouldBe PipelineErrorCodes.EndpointRequest.INVALID
        entries(refused).map { it["code"] } shouldBe
            listOf(PipelineErrorCodes.EndpointRequest.IDEMPOTENCY_KEY_INVALID, PipelineErrorCodes.EndpointRequest.BODY_INVALID)
    }

    @Test
    fun `an oversize body is 413 at once, whatever else is wrong`() {
        val refused = refusedEnvelope(call(key = null, body = """{"a":"${"x".repeat(100)}"}"""))
        refused.status shouldBe 413
        refused.code shouldBe PipelineErrorCodes.EndpointRequest.TOO_LARGE
    }

    @Test
    fun `a form or multipart body is 415 even when the container already drained the stream`() {
        // The stream is EMPTY (a form filter consumed it) but the declared length says a body was sent.
        val form = call(body = "", contentType = "application/x-www-form-urlencoded", declaredLength = 7)
        refusedEnvelope(form).status shouldBe 415
        val chunked = call(body = "", contentType = "multipart/form-data; boundary=x", declaredLength = MutationInput.UNKNOWN_LENGTH)
        refusedEnvelope(chunked).status shouldBe 415
    }

    @Test
    fun `a form-labelled request that declares zero length sent nothing and reads as no body`() {
        val labelled = call(body = "", contentType = "application/x-www-form-urlencoded", declaredLength = 0)
        val read = rules.envelope(labelled, codec).shouldBeInstanceOf<MutationRequestRules.Envelope.Read>()
        read.body.present shouldBe false
    }

    @Test
    fun `a non-JSON media type with a body is 415`() {
        refusedEnvelope(call(body = "a=1", contentType = "text/plain")).status shouldBe 415
    }

    // ------------------------------------------------------------------------- the pin's rules

    @Test
    fun `a body on a bodyless pin and no body on a required pin are 400 entries`() {
        val bodyless = refusedValidation(call(), pin(requestBody = null), body("""{"a":1}"""))
        entries(bodyless).single()["reason"] shouldBe "not_accepted"

        val required = refusedValidation(call(), pin(requestBody = """{"required":true}"""), StructuredBody.Absent)
        entries(required).single()["reason"] shouldBe "required"
    }

    @Test
    fun `an invalid declared parameter is named, its value never quoted`() {
        val refused =
            refusedValidation(call(query = mapOf("n" to listOf("SECRET-abc"))), pin(parameters = INT_PARAMETER), StructuredBody.Absent)
        val entry = entries(refused).single()
        entry["parameter"] shouldBe "n"
        entry["message"] shouldBe "Parameter 'n' is invalid (${entry["code"]})."
        refused.toString() shouldNotContain "SECRET"
    }

    @Test
    fun `an undeclared parameter's caller-chosen name is dropped, and the list is capped at 20 with truncated`() {
        val query = (1..25).associate { "SECRET_name_$it" to listOf("v") }
        val refused = refusedValidation(call(query = query), pin(), StructuredBody.Absent)
        entries(refused) shouldHaveSize 20
        refused.details["truncated"] shouldBe true
        entries(refused).forEach { it["parameter"] shouldBe null }
        refused.toString() shouldNotContain "SECRET"
        refused.message shouldBe "The request has 25 problem(s); details.errors lists them (at most 20)."
    }

    @Test
    fun `an unacceptable Accept is 406`() {
        val refused = refusedValidation(call(accept = "text/csv"), pin(), StructuredBody.Absent)
        refused.status shouldBe 406
        refused.code shouldBe PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE
    }

    @Test
    fun `a valid request passes the pin's rules`() {
        rules
            .validate(
                call(),
                pin(requestBody = """{"required":false}"""),
                body("""{"a":1}"""),
            ).shouldBeInstanceOf<MutationRequestRules.Validated.Valid>()
    }

    // ------------------------------------------------------------------------- fixture

    private fun refusedEnvelope(call: MutationCall): Outcome.Refused =
        rules
            .envelope(
                call,
                codec,
            ).shouldBeInstanceOf<MutationRequestRules.Envelope.Refused>()
            .outcome
            .shouldBeInstanceOf<Outcome.Refused>()

    private fun refusedValidation(
        call: MutationCall,
        pin: MutationPin,
        body: StructuredBody,
    ): Outcome.Refused =
        rules
            .validate(call, pin, body)
            .shouldBeInstanceOf<MutationRequestRules.Validated.Refused>()
            .outcome
            .shouldBeInstanceOf<Outcome.Refused>()

    @Suppress("UNCHECKED_CAST")
    private fun entries(refused: Outcome.Refused): List<Map<String, Any?>> = refused.details["errors"] as List<Map<String, Any?>>

    private fun body(json: String): StructuredBody =
        codec
            .read(
                "application/json",
                null,
                ByteArrayInputStream(json.toByteArray()),
                PublishedRequestBodyCodec.Route(acceptsBody = true, required = false),
            ).shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>()
            .body

    @Suppress("LongParameterList") // one request, every envelope fact a case may vary
    private fun call(
        key: String? = "k-1",
        body: String? = null,
        contentType: String? = body?.let { "application/json" },
        declaredLength: Long = body.orEmpty().length.toLong(),
        query: Map<String, List<String>> = emptyMap(),
        accept: String = "application/json",
    ): MutationCall =
        MutationCall(
            endpoint = ENDPOINT,
            method = EndpointMethod.POST,
            path = "/x/v1/orders",
            pathVariables = emptyMap(),
            principal = PRINCIPAL,
            request =
                EndpointRequestValidator.Request(
                    pathVariables = emptyMap(),
                    queryParameters = query,
                    accept = accept,
                    resultTtlSecondsHeader = null,
                    resultPageRowsHeader = null,
                ),
            input =
                MutationInput(
                    idempotencyKey = key,
                    contentType = contentType,
                    contentEncoding = null,
                    entity = ByteArrayInputStream(body.orEmpty().toByteArray()),
                    declaredLength = declaredLength,
                ),
        )

    private fun pin(
        requestBody: String? = null,
        parameters: String = "{}",
    ): MutationPin {
        val bodyField = requestBody?.let { """"request_body":$it,""" } ?: ""
        val json =
            """{"name":"x/orders","display_name":"o","description":"d","parameters":$parameters,$bodyField""" +
                """"nodes":[{"id":"n","type":"DML","source":"s","template":{"id":"t.sql","version":1},""" +
                """"operation":"update","output":{"target":"discard"},"depends_on":[]}]}"""
        val record =
            PipelineRecord(
                id = PIPELINE_ID,
                name = "x/orders",
                displayName = "o",
                description = "",
                ownerId = PRINCIPAL.userId,
                currentVersion = 1,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        return MutationPin(
            1,
            30,
            PIPELINE_ID,
            PipelineService.ExecutablePipeline(record, 1, json, PipelineDeserializer().readOrThrow(json)),
        )
    }

    private companion object {
        val WORKSPACE_ID: UUID = UUID.randomUUID()
        val PIPELINE_ID: UUID = UUID.randomUUID()
        const val INT_PARAMETER = """{"n":{"type":"INTEGER","required":false}}"""

        val ENDPOINT =
            PublishedEndpoint.of(
                id = UUID.randomUUID(),
                workspaceId = WORKSPACE_ID,
                pathPattern = "/x/v1/orders",
                isEnabled = true,
                createdBy = UUID.randomUUID(),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                method = EndpointMethod.POST,
            )

        val PRINCIPAL =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "dpk_rules01@keys.invalid",
                displayName = "rules",
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_RULES01",
                workspaceName = "w",
                workspace = WorkspaceContext(WORKSPACE_ID, "w", WorkspaceRole.VIEWER),
                keyKind = ApiKeyKind.ENDPOINT,
                keyRole = KeyRole.API_CALLER,
            )
    }
}
