package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * AUTH-SEC-9 / AU-TEST-1: `@RequiredScope` is keyed on the §7.6 matrix, the hierarchy
 * is honored, denials are audited as `auth.scope.denied`, an unauthenticated hit on a
 * scoped handler is `auth.api_key.missing` (401), and an **unannotated** handler on a
 * matrix-governed surface is denied by default rather than served.
 */
class ScopeInterceptorTest {
    private val mapper = ObjectMapper()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), auditLogger)

    /** Method-level annotations, including a deliberately unannotated handler. */
    class ProbeController {
        @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
        fun read() = Unit

        @RequiredScope(ScopeMatrix.RestOperation.MUTATE_DATASOURCES)
        fun adminOnly() = Unit

        fun unannotated() = Unit
    }

    /** Class-level annotation — the documented fallback for every handler in a controller. */
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    class AdminController {
        fun anything() = Unit
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate(vararg scopes: Scope) {
        val principal =
            AuthenticatedPrincipal(UUID.randomUUID(), "a@b.com", "A", scopes.toSet(), AuthMethod.API_KEY, "dpk_ABCDEFGHIJKL")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun invoke(
        bean: Any,
        method: String,
        path: String = "/api/v1/probe",
    ): Pair<Boolean, MockHttpServletResponse> {
        val handler = HandlerMethod(bean, bean.javaClass.getMethod(method))
        val response = MockHttpServletResponse()
        val proceed = interceptor.preHandle(MockHttpServletRequest("GET", path), response, handler)
        return proceed to response
    }

    private fun body(response: MockHttpServletResponse): Map<*, *> =
        (mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>)

    @Test
    fun `a principal holding exactly the required scope proceeds`() {
        authenticate(Scope.READ)
        invoke(ProbeController(), "read").first.shouldBeTrue()
    }

    @Test
    fun `the hierarchy applies - admin satisfies a read-minimum operation`() {
        authenticate(Scope.ADMIN)
        invoke(ProbeController(), "read").first.shouldBeTrue()
    }

    @Test
    fun `an insufficient scope is 403 auth-scope-insufficient and audited as auth-scope-denied`() {
        authenticate(Scope.READ)

        val (proceed, response) = invoke(ProbeController(), "adminOnly")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        body(response)["code"] shouldBe "auth.scope.insufficient"
        (body(response)["details"] as Map<*, *>)["required"] shouldBe "admin"
        verify { auditLogger.log("auth.scope.denied", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the class-level annotation is the fallback when the method carries none`() {
        authenticate(Scope.AUTHOR)

        val (proceed, response) = invoke(AdminController(), "anything")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        (body(response)["details"] as Map<*, *>)["required"] shouldBe "admin"
    }

    @Test
    fun `no principal on a scoped handler is 401 auth-api_key-missing`() {
        val (proceed, response) = invoke(ProbeController(), "read")

        proceed.shouldBeFalse()
        response.status shouldBe 401
        body(response)["code"] shouldBe "auth.api_key.missing"
    }

    @Test
    fun `an unannotated handler under the api prefix is denied by default`() {
        authenticate(Scope.ADMIN)

        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/api/v1/forgotten")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        body(response)["code"] shouldBe "auth.scope.insufficient"
        (body(response)["details"] as Map<*, *>)["reason"] shouldBe "handler_not_annotated"
    }

    @Test
    fun `an unannotated handler on the mcp endpoint is denied by default`() {
        authenticate(Scope.ADMIN)

        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/mcp")

        proceed.shouldBeFalse()
        response.status shouldBe 403
    }

    @Test
    fun `an unannotated handler outside the matrix-governed surfaces is left alone`() {
        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/login")

        proceed.shouldBeTrue()
        response.status shouldBe 200
    }

    @Test
    fun `a non-handler-method (static resource) is never scope-checked`() {
        val response = MockHttpServletResponse()
        interceptor
            .preHandle(MockHttpServletRequest("GET", "/api/v1/probe"), response, "not-a-handler-method")
            .shouldBeTrue()
    }
}
