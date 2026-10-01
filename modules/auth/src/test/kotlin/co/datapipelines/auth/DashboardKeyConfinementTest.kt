package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
 * §7.7 — where a `dashboard`-kind key may go at all (L5, #367): `EndpointKeyConfinementTest`'s
 * twin for the fourth kind. The allowlist is the two runtime prefixes, enforced ONCE in the
 * interceptor; every route below that is not the runtime surface must NOT open — the lifecycle
 * REST family, the pages, the partials, the binding routes themselves, the published tree, the
 * executions and promotion families. The refusals over real HTTP (every family, one body) are
 * `DashboardKeyE2eTest`'s; this is the interceptor's own statement of the same table.
 */
class DashboardKeyConfinementTest {
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val interceptor = ScopeInterceptor(AuthErrorWriter(ObjectMapper()), auditLogger)

    /** The runtime route's own row: what a kind-admitted request is then judged by. */
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    class ProbeController {
        fun anything() = Unit
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `a dashboard key reaches exactly the runtime and refreshes prefixes`() {
        authenticate()

        val board = "11111111-1111-4111-8111-111111111111"
        val reachable =
            listOf(
                "/api/v1/dashboards/$board/runtime/config",
                "/api/v1/dashboards/$board/runtime/parameters",
                "/api/v1/dashboards/$board/runtime/visualizations",
                "/api/v1/dashboards/$board/runtime/refreshes/22222222-2222-4222-8222-222222222222/abort",
                "/api/v1/dashboards/$board/refreshes",
                "/api/v1/dashboards/$board/refreshes/22222222-2222-4222-8222-222222222222",
            )
        assertAll(reachable.map { path -> { withClue(path) { invoke(path).first.shouldBeTrue() } } })
    }

    @Test
    fun `a dashboard key is refused everywhere else - the whole app, not a list of families`() {
        authenticate()

        val board = "11111111-1111-4111-8111-111111111111"
        val refused =
            listOf(
                // The lifecycle REST family — including the BINDING routes themselves.
                "/api/v1/dashboards",
                "/api/v1/dashboards/bindings",
                "/api/v1/dashboards/$board",
                "/api/v1/dashboards/$board/versions",
                "/api/v1/dashboards/$board/export",
                // The pages and the partials.
                "/dashboards",
                "/dashboards/$board",
                "/partials/dashboards/tree",
                "/partials/dashboards/$board/refreshes",
                "/partials/api-keys",
                // Every other REST family.
                "/api/v1/pipelines",
                "/api/v1/executions",
                "/api/v1/parameter-sets",
                "/api/v1/templates",
                "/api/v1/schedules",
                // The published tree and the MCP surface.
                "/api/nyc/v1/revenue",
                "/mcp",
            )
        assertAll(refused.map { path -> { withClue(path) { invoke(path).first.shouldBeFalse() } } })
    }

    /** `(proceeded, response)` — the interceptor's verdict for a `dashboard` key at [path]. */
    private fun invoke(path: String): Pair<Boolean, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", path)
        val response = MockHttpServletResponse()
        val handler = HandlerMethod(ProbeController(), ProbeController::class.java.getDeclaredMethod("anything"))
        return interceptor.preHandle(request, response, handler) to response
    }

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "app@example.test",
                displayName = "host app",
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_DASHBOARDK01",
                workspaceName = "ops",
                workspace = WorkspaceContext(UUID.randomUUID(), "ops", WorkspaceRole.VIEWER),
                keyKind = ApiKeyKind.DASHBOARD,
                keyRole = KeyRole.DASHBOARD_VIEWER,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }
}
