package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * §7.7 — where an `endpoint`-kind key may go at all.
 *
 * This is the central confinement, and it is central on purpose: an endpoint key carries NO
 * scopes, so the §7.6 matrix cannot judge it, and if each handler were left to check the kind
 * itself then a new route would become reachable to endpoint keys the first time someone forgot.
 * The allowlist is therefore enforced once, in the interceptor, and every test below is a route
 * that must NOT open.
 *
 * The complement matters as much: a `user` key must be unaffected by any of this. A confinement
 * applies to the MCP key too since #215 B2 — it is confined to `/mcp` — so the MCP-key test drives
 * the same routes and expects them all refused.
 */
class EndpointKeyConfinementTest {
    private val mapper = ObjectMapper()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), auditLogger)

    @RequiredScope(Permission.PIPELINE_READ)
    class ProbeController {
        fun anything() = Unit
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `an endpoint key reaches the published-endpoint surface and its own result cursor`() {
        authenticate(ApiKeyKind.ENDPOINT)

        assertAll(
            REACHABLE.map { path ->
                { withClue(path) { invoke(path).first.shouldBeTrue() } }
            },
        )
    }

    @Test
    fun `an endpoint key is refused everywhere else`() {
        authenticate(ApiKeyKind.ENDPOINT)

        assertAll(
            REFUSED.map { path ->
                {
                    withClue(path) {
                        val (proceed, response) = invoke(path)
                        proceed.shouldBeFalse()
                        response.status shouldBe 403
                        error(response)["code"] shouldBe "endpoint.key_kind_refused"
                    }
                }
            },
        )
    }

    @Test
    fun `the MCP key is confined to mcp - every route above refuses it (B2)`() {
        // Until #215 slice (b) this was the complement — an ordinary key untouched by the
        // endpoint confinement. The owner's B2 ruling ("MCP key should be only MCP") made the
        // MCP key a confined kind too: every MVC route is off its surface, the endpoint key's
        // included, and `/mcp` is its whole reach.
        authenticate(ApiKeyKind.USER)

        assertAll(
            (REACHABLE + REFUSED).filterNot { it == "/mcp" || it.startsWith("/mcp/") }.map { path ->
                { withClue(path) { invoke(path).first.shouldBeFalse() } }
            },
        )
        invoke("/mcp").first.shouldBeTrue()
    }

    @Test
    fun `the execution allowlist matches only the two reads, not cancel or events`() {
        // Cancel and the event stream are writes-or-streams over someone's execution; an endpoint
        // key has no business on either, and the regex — not a handler — is what says so.
        assertAll(
            { ScopeInterceptor.EXECUTION_READ.matches("/api/v1/executions/abc") shouldBe true },
            { ScopeInterceptor.EXECUTION_READ.matches("/api/v1/executions/abc/result") shouldBe true },
            { ScopeInterceptor.EXECUTION_READ.matches("/api/v1/executions/abc/cancel") shouldBe false },
            { ScopeInterceptor.EXECUTION_READ.matches("/api/v1/executions/abc/events") shouldBe false },
            { ScopeInterceptor.EXECUTION_READ.matches("/api/v1/executions") shouldBe false },
            // No path traversal into the allowlist through a nested segment.
            { ScopeInterceptor.EXECUTION_READ.matches("/api/v1/executions/abc/result/../../pipelines") shouldBe false },
        )
    }

    private fun authenticate(kind: ApiKeyKind) {
        val principal =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "a@b.com",
                displayName = "A",
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_ABCDEFGHIJKL",
                keyKind = kind,
                // Since RBAC round 1 `ScopeMatrix.allowed` judges BOTH axes, so a principal
                // with no resolved workspace is refused with `workspace.not_found` before any
                // scope is read (D-R5). These suites are about the KIND confinement, so the
                // context is a workspace admin's: everything the confinement lets through must
                // then reach the handler rather than dying on an unrelated refusal.
                workspaceName = "acme",
                workspace =
                    WorkspaceContext(
                        UUID.randomUUID(),
                        "acme",
                        WorkspaceRole.WORKSPACE_ADMIN,
                    ),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun invoke(path: String): Pair<Boolean, MockHttpServletResponse> {
        val bean = ProbeController()
        val handler = HandlerMethod(bean, bean.javaClass.getMethod("anything"))
        val response = MockHttpServletResponse()
        return interceptor.preHandle(MockHttpServletRequest("GET", path), response, handler) to response
    }

    private fun error(response: MockHttpServletResponse): Map<*, *> =
        mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>

    private companion object {
        val REACHABLE =
            listOf(
                "/api/nyc/v1/revenue/Manhattan",
                "/api/lending",
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001",
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001/result",
            )

        /** Every one of these would be open to an endpoint key without the central allowlist. */
        val REFUSED =
            listOf(
                "/api/v1/pipelines",
                "/api/v1/pipelines/2f1c9c2e-0000-0000-0000-000000000001",
                "/api/v1/datasources",
                "/api/v1/templates",
                "/api/v1/auth/api-keys",
                "/api/v1/workspaces",
                "/api/v1/endpoints",
                "/api/v1/executions",
                // R-EP5 — a reserved first segment is the product's tree, whatever follows it.
                "/api/v1/revenue/Manhattan",
                "/api/v2/anything",
                "/api/api/v1/x",
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001/cancel",
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001/events",
                "/partials/api-keys",
                "/mcp",
            )
    }
}
