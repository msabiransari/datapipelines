package co.datapipelines.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import co.datapipelines.logcapture.capturingLogEvents
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Security NEW-2: a **malformed** credential must not cost a durable `audit_log` write.
 *
 * `auth.api_key.rejected` (§10.1) means "a credential was validated and refused". A
 * value failing the shape gate never reached validation, so auditing it would both
 * misreport the event and hand an unauthenticated attacker a one-header-per-INSERT
 * amplification against the database. Well-shaped-but-unknown keys are a real
 * attempt and stay audited.
 *
 * The service is stubbed to reject everything, so the only thing under test is the
 * filter's own decision about what is worth recording.
 */
class ApiKeyRejectionAuditTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val filter =
        ApiKeyFilter(apiKeyService, apiKeyRepository, auditLogger, ClientAddressResolver(emptyList()), mockk(relaxed = true))

    init {
        every { apiKeyService.validate(any()) } throws ApiKeyInvalidException()
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun present(credential: String): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        request.remoteAddr = "10.0.0.7"
        request.addHeader(ApiKeyCredential.HEADER, credential)
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        return request
    }

    @Test
    fun `a flood of malformed credentials writes no audit rows`() {
        repeat(MALFORMED_FLOOD) { i -> present("garbage-$i") }
        // Shape-valid prefix but a bad key id, and an over-long value: both still malformed.
        present("dpk_lowercase12.${"A".repeat(48)}")
        present("dpk_ABCDEFGHIJKL.${"A".repeat(200)}")

        verify(exactly = 0) {
            auditLogger.log(
                event = any(),
                userId = any(),
                keyId = any(),
                sourceIp = any(),
                userAgent = any(),
                details = any(),
            )
        }
    }

    @Test
    fun `a well-shaped but unknown key is still audited as a rejection`() {
        present("dpk_ABCDEFGHIJKL.${"A".repeat(48)}")

        verify(exactly = 1) {
            auditLogger.log(
                event = "auth.api_key.rejected",
                userId = null,
                keyId = null,
                sourceIp = "10.0.0.7",
                userAgent = null,
                details = mapOf("code" to AuthErrorCodes.API_KEY_INVALID),
            )
        }
    }

    @Test
    fun `the caller still gets the same rejection for a malformed credential`() {
        // Skipping the audit must not change what the boundary reports (§13.7).
        val request = present("garbage")

        val stashed = request.getAttribute(AuthAttributes.AUTH_ERROR) as? AuthException
        stashed.shouldNotBeNull()
        stashed.code shouldBe AuthErrorCodes.API_KEY_INVALID
        stashed.status shouldBe HTTP_UNAUTHORIZED
    }

    /**
     * #847 — the server could not CHECK the secret (a native verification fault). Not a rejection:
     * no `auth.api_key.rejected` row, one ERROR line without the credential, and the caller gets the
     * 503 envelope from the entry point, never `auth.api_key.invalid`.
     */
    @Test
    fun `a native verification fault is a 503 with no audit row and an ERROR line that carries no credential`() {
        val service = mockk<ApiKeyService>()
        every { service.validate(any()) } throws
            SecretVerificationUnavailableException(Argon2SecretHasher.MEMORY_ALLOCATION_ERROR, IllegalStateException("Memory allocation error (-22)"))
        val writer = AuthErrorWriter(ObjectMapper())
        val unavailable = ApiKeyFilter(service, apiKeyRepository, auditLogger, ClientAddressResolver(emptyList()), writer)
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        request.remoteAddr = "10.0.0.7"
        request.addHeader(ApiKeyCredential.HEADER, WELL_SHAPED_KEY)

        val events =
            capturingLogEvents(LoggerFactory.getLogger(ApiKeyFilter::class.java) as Logger) {
                unavailable.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            }

        verify(exactly = 0) {
            auditLogger.log(event = any(), userId = any(), keyId = any(), sourceIp = any(), userAgent = any(), details = any())
        }
        SecurityContextHolder.getContext().authentication shouldBe null
        val errors = events.filter { it.level == Level.ERROR }
        errors shouldHaveSize 1
        errors.single().formattedMessage shouldContain "event=auth.secret_verification.unavailable"
        events.forEach { event ->
            event.formattedMessage shouldNotContain WELL_SHAPED_KEY
            event.formattedMessage shouldNotContain SECRET_PART
            event.throwableProxy?.message.orEmpty() shouldNotContain SECRET_PART
        }

        // The entry point answers the stashed fault: 503, the catalogued envelope, no native text.
        val response = MockHttpServletResponse()
        AuthEntryPoint(writer).commence(request, response, InsufficientAuthenticationException("unauthenticated"))
        response.status shouldBe HTTP_SERVICE_UNAVAILABLE
        val body = ObjectMapper().readTree(response.contentAsString)
        body.path("error").path("code").asText() shouldBe AuthErrorCodes.SECRET_VERIFICATION_UNAVAILABLE
        body.path("correlation_id").asText().isNotBlank() shouldBe true
        response.contentAsString shouldNotContain "emory"
        response.contentAsString shouldNotContain "-22"
    }

    private companion object {
        const val SECRET_PART = "S3cretPartOfTheKeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val WELL_SHAPED_KEY = "dpk_ABCDEFGHIJKL.$SECRET_PART"
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val MALFORMED_FLOOD = 500
        const val HTTP_UNAUTHORIZED = 401
    }
}
