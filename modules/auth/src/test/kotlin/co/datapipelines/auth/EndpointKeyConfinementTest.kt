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
 * that also narrowed ordinary keys would be a silent outage, so `a user key is untouched` drives
 * the same routes with the same scopes and expects them all to proceed.
 */
class EndpointKeyConfinementTest {
    private val mapper = ObjectMapper()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), auditLogger)

    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
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
    fun `a user key is untouched by the confinement`() {
        // The complement: every route above, driven by an ordinary key, must still behave exactly
        // as it did before 074. A confinement that also narrowed user keys is a silent outage.
        authenticate(ApiKeyKind.USER, Scope.READ)

        assertAll(
            (REACHABLE + REFUSED).map { path ->
                { withClue(path) { invoke(path).first.shouldBeTrue() } }
            },
        )
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

    private fun authenticate(
        kind: ApiKeyKind,
        vararg scopes: Scope,
    ) {
        val principal =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "a@b.com",
                displayName = "A",
                scopes = scopes.toSet(),
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
                        MembershipFlags(author = true, promoter = true, admin = true),
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
                "/api/x/nyc/revenue/Manhattan",
                "/api/x/lending",
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
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001/cancel",
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001/events",
                "/partials/api-keys",
                "/mcp",
            )
    }
}
