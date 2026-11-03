package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.security.web.csrf.InvalidCsrfTokenException
import org.springframework.security.web.csrf.MissingCsrfTokenException

/**
 * Security NEW-7: the non-CSRF fallback must not invent a scope requirement.
 *
 * Spring's authorization layer can deny a request that never reached a
 * `@RequiredScope` handler, and the handler genuinely does not know what was needed.
 * It used to answer `required: "admin", held: []` regardless — a fabricated value in
 * an error payload, which sends anyone debugging against it after a scope the server
 * never asked for.
 *
 * The CSRF branch is exercised end-to-end in `AuthHttpBoundaryTest`; these unit
 * cases pin #782's SPLIT inside it — which requests get the forwarded error PAGE
 * (attribute stash + forward to /error) and which keep the §13.7 JSON envelope —
 * without booting the chain, so the machine-surface byte-pins stay provable.
 */
class AuthAccessDeniedHandlerTest {
    private val mapper = ObjectMapper()
    private val handler = AuthAccessDeniedHandler(AuthErrorWriter(mapper), ClientAddressResolver(emptyList()))

    private fun deny(): Map<*, *> {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        request.remoteAddr = "10.0.0.3"
        val response = MockHttpServletResponse()

        handler.handle(request, response, AccessDeniedException("Access Denied"))

        response.status shouldBe HTTP_FORBIDDEN
        return mapper.readValue(response.contentAsString, Map::class.java)
    }

    @Test
    fun `a generic authorization denial reports no fabricated required or held permission`() {
        val error = deny()["error"] as Map<*, *>

        error["code"] shouldBe AuthErrorCodes.PERMISSION_UNDECLARED
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
        error["doc_url"] shouldBe AuthErrorCodes.docUrl(AuthErrorCodes.PERMISSION_UNDECLARED)
    }

    @Test
    fun `a browser-shaped CSRF refusal forwards to the error page with the refusal stashed`() {
        val request = MockHttpServletRequest("POST", "/workspace/switch")
        request.addHeader("Accept", "text/html,application/xhtml+xml")
        request.addParameter(CSRF_PARAMETER, "a-presented-token")
        val response = MockHttpServletResponse()

        val expected = DefaultCsrfToken("DP-CSRF-Token", CSRF_PARAMETER, "expected")
        handler.handle(request, response, InvalidCsrfTokenException(expected, "a-presented-token"))

        response.status shouldBe HTTP_FORBIDDEN
        response.contentAsString shouldBe ""
        response.forwardedUrl shouldBe AuthAttributes.ERROR_PAGE_PATH
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) shouldBe HTTP_FORBIDDEN
        val stashed = request.getAttribute(AuthAttributes.CSRF_REFUSAL) as CsrfInvalidException
        stashed.userMessage shouldBe "Your page is out of date. Reload it and try again."
    }

    @Test
    fun `a CSRF refusal on a machine surface keeps the JSON envelope`() {
        val request = MockHttpServletRequest("POST", "/api/v1/probe")
        request.addHeader("Accept", "text/html,application/xhtml+xml")
        val response = MockHttpServletResponse()

        handler.handle(request, response, MissingCsrfTokenException(""))

        response.forwardedUrl shouldBe null
        val error = mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>
        error["code"] shouldBe AuthErrorCodes.CSRF_INVALID
        (error["details"] as Map<*, *>)["reason"] shouldBe MISSING
    }

    @Test
    fun `a CSRF refusal of an htmx request keeps the JSON envelope`() {
        val request = MockHttpServletRequest("POST", "/workspace/switch")
        request.addHeader("Accept", "text/html,application/xhtml+xml")
        request.addHeader("HX-Request", "true")
        val response = MockHttpServletResponse()

        handler.handle(request, response, MissingCsrfTokenException(""))

        response.forwardedUrl shouldBe null
        response.contentAsString shouldContain AuthErrorCodes.CSRF_INVALID
    }

    @Test
    fun `a CSRF refusal without an html-admitting Accept keeps the JSON envelope`() {
        val request = MockHttpServletRequest("POST", "/workspace/switch")
        request.addHeader("Accept", "application/json")
        val response = MockHttpServletResponse()

        handler.handle(request, response, MissingCsrfTokenException(""))

        response.forwardedUrl shouldBe null
        response.contentAsString shouldContain AuthErrorCodes.CSRF_INVALID
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val CSRF_PARAMETER = "_csrf"
        const val MISSING = "missing"
    }
}
