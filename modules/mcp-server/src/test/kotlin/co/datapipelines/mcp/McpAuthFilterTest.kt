package co.datapipelines.mcp

import co.datapipelines.auth.ApiKeyExpiredException
import co.datapipelines.auth.AuthAttributes
import co.datapipelines.auth.AuthErrorCodes
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/** §4.1/§4.2 and the §13 checklist rows about who may reach `/mcp`. */
class McpAuthFilterTest {
    private val filter = McpAuthFilter(AuthErrorWriter(ObjectMapper()))
    private val chain = mockk<FilterChain>(relaxed = true)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(principal: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun request(): MockHttpServletRequest = MockHttpServletRequest("POST", "/mcp")

    @Test
    fun `an api-key principal passes and lands in the request attributes`() {
        authenticate(McpFixtures.principal(Scope.READ))
        val request = request()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertAll(
            { response.status shouldBe 200 },
            { request.getAttribute(McpTransportKeys.PRINCIPAL) shouldBe McpFixtures.principal(Scope.READ) },
            { (request.getAttribute(McpTransportKeys.CORRELATION_ID) is UUID) shouldBe true },
        )
        verify(exactly = 1) { chain.doFilter(request, response) }
    }

    @Test
    fun `no credential is 401 auth_api_key_missing and the chain never runs`() {
        val request = request()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertAll(
            { response.status shouldBe 401 },
            { response.contentAsString shouldContain AuthErrorCodes.API_KEY_MISSING },
            { response.contentAsString shouldContain "\"schema_version\":1" },
        )
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `a valid browser session cannot call a tool`() {
        authenticate(McpFixtures.principal(Scope.ADMIN, method = AuthMethod.OIDC))
        val request = request()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertAll(
            { response.status shouldBe 401 },
            { response.contentAsString shouldContain AuthErrorCodes.API_KEY_MISSING },
            { request.getAttribute(McpTransportKeys.PRINCIPAL) shouldBe null },
        )
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `the specific rejection auth recorded is reported verbatim`() {
        val request = request()
        request.setAttribute(AuthAttributes.AUTH_ERROR, ApiKeyExpiredException())
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertAll(
            { response.status shouldBe 401 },
            { response.contentAsString shouldContain AuthErrorCodes.API_KEY_EXPIRED },
        )
    }

    @Test
    fun `an inbound correlation id is honoured and echoed`() {
        authenticate(McpFixtures.principal(Scope.READ))
        val request = request()
        val inbound = UUID.randomUUID()
        request.addHeader(AuthErrorWriter.CORRELATION_HEADER, inbound.toString())
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertAll(
            { request.getAttribute(McpTransportKeys.CORRELATION_ID) shouldBe inbound },
            { response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe inbound.toString() },
        )
    }

    @Test
    fun `a malformed correlation id is replaced, never propagated`() {
        authenticate(McpFixtures.principal(Scope.READ))
        val request = request()
        request.addHeader(AuthErrorWriter.CORRELATION_HEADER, "<script>alert(1)</script>")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertAll(
            { (request.getAttribute(McpTransportKeys.CORRELATION_ID) is UUID) shouldBe true },
            { response.getHeader(AuthErrorWriter.CORRELATION_HEADER)!! shouldContain "-" },
            {
                response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe
                    request.getAttribute(McpTransportKeys.CORRELATION_ID).toString()
            },
        )
    }
}
