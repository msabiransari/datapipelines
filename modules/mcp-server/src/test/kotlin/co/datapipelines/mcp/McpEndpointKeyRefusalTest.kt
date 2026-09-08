package co.datapipelines.mcp

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * §7.7 — a SCOPELESS key (`endpoint`, and since 091 `server`) cannot reach `/mcp`.
 *
 * This refusal is made HERE as well as in `ScopeInterceptor` because `/mcp` is a **servlet**, not
 * an MVC handler, so the interceptor's central confinement never sees it. Without this filter
 * branch a scopeless endpoint key could not CALL any tool — every tool's scope check fails — but
 * it could still reach `tools/list` and read the whole tool catalogue, which is more than
 * "exactly the endpoints it is bound to".
 *
 * Found by probing the live stack with a real endpoint key, not by any test; this is the test
 * that keeps it found.
 */
class McpEndpointKeyRefusalTest {
    private val mapper = ObjectMapper()
    private val filter = McpAuthFilter(AuthErrorWriter(mapper))

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `an endpoint key is refused on mcp, and the chain never runs`() {
        authenticate(ApiKeyKind.ENDPOINT)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(MockHttpServletRequest("POST", "/mcp"), response, chain)

        assertAll(
            { response.status shouldBe 403 },
            { error(response)["code"] shouldBe "endpoint.key_kind_refused" },
            // The transport never saw the request: nothing downstream can leak the tool list.
            { chain.request shouldBe null },
        )
    }

    @Test
    fun `a server key is refused on mcp too, and the chain never runs`() {
        // 091 — the same P32 shape for the third kind. `/mcp` is a SERVLET, so the interceptor's
        // central confinement never sees it; without this branch a promotion credential could
        // read the whole tool catalogue through `tools/list`.
        authenticate(ApiKeyKind.SERVER)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(MockHttpServletRequest("POST", "/mcp"), response, chain)

        assertAll(
            { response.status shouldBe 403 },
            { error(response)["code"] shouldBe "endpoint.key_kind_refused" },
            { (error(response)["details"] as Map<*, *>)["reason"] shouldBe "server_key_off_surface" },
            { chain.request shouldBe null },
        )
    }

    @Test
    fun `a user key still reaches the transport`() {
        // The complement — the confinement must not close /mcp for ordinary agent keys.
        authenticate(ApiKeyKind.USER)
        val chain = MockFilterChain()

        filter.doFilter(MockHttpServletRequest("POST", "/mcp"), MockHttpServletResponse(), chain)

        (chain.request != null) shouldBe true
    }

    private fun authenticate(kind: ApiKeyKind) {
        val principal =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "a@b.c",
                displayName = "A",
                scopes = if (kind in ApiKeyKind.SCOPELESS) emptySet() else setOf(Scope.AUTHOR),
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_ABCDEFGHIJKL",
                workspaceName = "default",
                workspace = WorkspaceContext(UUID.randomUUID(), "default"),
                keyKind = kind,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun error(response: MockHttpServletResponse): Map<*, *> =
        mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>
}
