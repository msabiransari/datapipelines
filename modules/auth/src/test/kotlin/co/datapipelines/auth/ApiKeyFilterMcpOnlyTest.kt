package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * #215 B2's FIRST line, on its own: [ApiKeyFilter] refuses a valid MCP (`user`) key on every path
 * but `/mcp` — `403 endpoint.key_kind_refused`, `details.reason = user_key_off_surface` — and the
 * chain STOPS, so nothing downstream answers. `ScopeInterceptor`'s kind table refuses the same
 * key on every MVC route as the second line, which is why this suite also probes paths no
 * interceptor sees (a public route, a static asset): only the filter can refuse there, so a filter
 * that lost the rule goes red here even while the interceptor keeps the MVC routes closed.
 *
 * The other kinds pass the filter untouched — their confinement is the interceptor's and the
 * serve path's — and `/mcp` itself is where the MCP key lives.
 */
class ApiKeyFilterMcpOnlyTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val filter =
        ApiKeyFilter(
            apiKeyService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            ClientAddressResolver(emptyList()),
            AuthErrorWriter(ObjectMapper()),
        )
    private val mapper = ObjectMapper()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun keyOf(kind: ApiKeyKind): String {
        val credential = "dpk_B2FILTERPROBE.${"A".repeat(SECRET_CHARS)}"
        every { apiKeyService.validate(credential) } returns
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "member@company.com",
                displayName = "Member",
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_B2FILTERPROBE",
                keyKind = kind,
            )
        return credential
    }

    /** The response, and the request the chain received (null when the filter stopped it). */
    private fun present(
        path: String,
        credential: String,
    ): Pair<MockHttpServletResponse, Any?> {
        val request = MockHttpServletRequest("GET", path)
        request.addHeader(ApiKeyCredential.HEADER, credential)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain.request
    }

    @Test
    fun `the MCP key is refused on every path off mcp - the chain stops, the confinement code answers`() {
        val key = keyOf(ApiKeyKind.USER)
        OFF_MCP.forEach { path ->
            val (response, reached) = present(path, key)
            reached.shouldBeNull()
            response.status shouldBe HTTP_FORBIDDEN
            val error = mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>
            error["code"] shouldBe ScopeInterceptor.ENDPOINT_KEY_KIND_REFUSED
            (error["details"] as Map<*, *>)["reason"] shouldBe "user_key_off_surface"
            // …and the refused request leaves no authentication behind for a later filter.
            SecurityContextHolder.getContext().authentication.shouldBeNull()
        }
    }

    @Test
    fun `the MCP key passes the filter on mcp - its one surface`() {
        val (response, reached) = present("/mcp", keyOf(ApiKeyKind.USER))
        reached.shouldNotBeNull()
        response.status shouldBe HTTP_OK
    }

    @Test
    fun `the other kinds pass the filter - their confinement is the interceptor's and the serve path's`() {
        listOf(ApiKeyKind.ENDPOINT, ApiKeyKind.SERVER).forEach { kind ->
            val (_, reached) = present("/api/v1/pipelines", keyOf(kind))
            reached.shouldNotBeNull()
        }
    }

    private companion object {
        const val SECRET_CHARS = 48
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403

        /** REST, a page, a partial, a published path, a PUBLIC route and a static asset — the last two no interceptor sees. */
        val OFF_MCP =
            listOf("/api/v1/pipelines", "/dashboard", "/partials/recent-executions", "/api/nyc/v1/report", "/skill.md", "/css/app.css")
    }
}
