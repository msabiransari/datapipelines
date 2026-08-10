package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException

/**
 * Security NEW-7: the non-CSRF fallback must not invent a scope requirement.
 *
 * Spring's authorization layer can deny a request that never reached a
 * `@RequiredScope` handler, and the handler genuinely does not know what was needed.
 * It used to answer `required: "admin", held: []` regardless — a fabricated value in
 * an error payload, which sends anyone debugging against it after a scope the server
 * never asked for.
 *
 * The CSRF branch is exercised end-to-end in `AuthHttpBoundaryTest`.
 */
class AuthAccessDeniedHandlerTest {
    private val mapper = ObjectMapper()
    private val handler = AuthAccessDeniedHandler(AuthErrorWriter(mapper))

    private fun deny(): Map<*, *> {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        request.remoteAddr = "10.0.0.3"
        val response = MockHttpServletResponse()

        handler.handle(request, response, AccessDeniedException("Access Denied"))

        response.status shouldBe HTTP_FORBIDDEN
        return mapper.readValue(response.contentAsString, Map::class.java)
    }

    @Test
    fun `a generic authorization denial reports no fabricated required or held scopes`() {
        val error = deny()["error"] as Map<*, *>

        error["code"] shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
        // The whole point: no `required`, no `held`, rather than a plausible lie.
        (error["details"] as Map<*, *>).shouldBeEmpty()
    }

    @Test
    fun `the denial still carries the full rest-api §4-2 envelope`() {
        val body = deny()

        body["schema_version"] shouldBe 1
        (body["correlation_id"] as String).isNotBlank() shouldBe true
        val error = body["error"] as Map<*, *>
        error["message"] shouldBe "Access denied by the authorization layer"
        error["user_message"] shouldBe "You do not have permission to perform this action."
        error["doc_url"] shouldBe AuthErrorCodes.docUrl(AuthErrorCodes.SCOPE_INSUFFICIENT)
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }
}
