package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/**
 * AU-API-1: every auth rejection carries the FULL [REST API §4.2] envelope —
 * `schema_version`, `correlation_id`, and `error{code, message, user_message, details,
 * doc_url}` — with correlation echoed on the response header (§3.4).
 */
class AuthErrorWriterTest {
    private val mapper = ObjectMapper()
    private val writer = AuthErrorWriter(mapper)

    private fun write(
        error: AuthException,
        correlationId: String? = null,
    ): Pair<MockHttpServletResponse, Map<*, *>> {
        val request = MockHttpServletRequest("POST", "/api/v1/pipelines")
        if (correlationId != null) request.addHeader(AuthErrorWriter.CORRELATION_HEADER, correlationId)
        val response = MockHttpServletResponse()
        writer.write(request, response, error)
        return response to mapper.readValue(response.contentAsString, Map::class.java)
    }

    @Test
    fun `a 401 carries every documented envelope field`() {
        val (response, body) = write(ApiKeyMissingException())

        response.status shouldBe 401
        response.contentType.orEmpty() shouldStartWith "application/json"
        body["schema_version"] shouldBe 1
        (body["correlation_id"] as String).shouldNotBeBlank()

        val error = body["error"] as Map<*, *>
        error["code"] shouldBe "auth.api_key.missing"
        error["message"] shouldBe "No credentials provided"
        error["user_message"] shouldBe "You are not signed in. Sign in and try again."
        error["details"] shouldBe emptyMap<String, Any>()
        error["doc_url"] shouldBe "https://docs.datapipelines.co/errors/auth-api-key-missing"
    }

    @Test
    fun `a 403 carries every documented envelope field including code-specific details`() {
        val (response, body) = write(ScopeInsufficientException(Scope.ADMIN, setOf(Scope.READ)))

        response.status shouldBe 403
        val error = body["error"] as Map<*, *>
        error["code"] shouldBe "auth.scope.insufficient"
        error["user_message"] shouldBe "You do not have permission to perform this action."
        error["doc_url"] shouldBe "https://docs.datapipelines.co/errors/auth-scope-insufficient"
        (error["details"] as Map<*, *>)["required"] shouldBe "admin"
        (error["details"] as Map<*, *>)["held"] shouldBe listOf("read")
    }

    @Test
    fun `a UUID-shaped inbound correlation id is echoed in the body and the response header`() {
        val id = UUID.randomUUID().toString()
        val (response, body) = write(SessionExpiredException(), correlationId = id)

        body["correlation_id"] shouldBe id
        response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe id
    }

    @Test
    fun `the MDC id wins over the inbound header`() {
        // web's CorrelationIdFilter sanitizes into the MDC ahead of the security chain;
        // the raw header — attacker-controlled text — must never be preferred over it.
        val mdcId = UUID.randomUUID().toString()
        MDC.put(AuthErrorWriter.MDC_KEY, mdcId)
        try {
            val (response, body) = write(SessionExpiredException(), correlationId = "corr-attacker-controlled")

            body["correlation_id"] shouldBe mdcId
            response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe mdcId
        } finally {
            MDC.remove(AuthErrorWriter.MDC_KEY)
        }
    }

    @Test
    fun `a non-UUID inbound correlation id is replaced, not echoed`() {
        // No MDC slot (the filter did not run): the header alone must not reflect
        // attacker-controlled text onto the response or into the envelope (§3.4).
        val (response, body) = write(SessionExpiredException(), correlationId = "corr-42-not-a-uuid")

        (body["correlation_id"] as String).shouldNotBeBlank()
        body["correlation_id"] shouldNotBe "corr-42-not-a-uuid"
        response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe body["correlation_id"]
        UUID.fromString(body["correlation_id"] as String) // parses — the replacement is a real UUID
    }

    @Test
    fun `a correlation id is generated and returned when the request carried none`() {
        val (response, body) = write(SessionInvalidException())

        response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe body["correlation_id"]
        (body["correlation_id"] as String).shouldNotBeBlank()
    }

    @Test
    fun `a committed response is left alone rather than double-written`() {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        val response = MockHttpServletResponse()
        response.writer.write("already sent")
        response.flushBuffer()

        writer.write(request, response, ApiKeyMissingException())

        response.contentAsString shouldBe "already sent"
    }

    @Test
    fun `doc urls collapse dots and underscores to hyphens`() {
        AuthErrorCodes.docUrl("auth.api_key.expired") shouldBe "https://docs.datapipelines.co/errors/auth-api-key-expired"
        AuthErrorCodes.docUrl("rate_limit.exceeded") shouldBe "https://docs.datapipelines.co/errors/rate-limit-exceeded"
    }
}
