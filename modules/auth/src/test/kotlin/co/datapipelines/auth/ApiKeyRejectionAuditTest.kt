package co.datapipelines.auth

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
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
    private val filter = ApiKeyFilter(apiKeyService, apiKeyRepository, auditLogger, ClientAddressResolver(emptyList()))

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

    private companion object {
        const val MALFORMED_FLOOD = 500
        const val HTTP_UNAUTHORIZED = 401
    }
}
