package co.datapipelines.web.workspaces

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeInterceptor
import co.datapipelines.auth.WorkspaceService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * The 025 review's blocking finding, pinned at the interceptor: an API key authenticates
 * on EVERY path and is CSRF-exempt, so before the floor raise a `read`-scoped key drove
 * all five `/api/v1/workspaces` mutations (create, rename, soft-delete, add/remove
 * member) — the in-handler `requireOwnerOrAdmin` checks the USER's role, never the
 * credential's scope. `WORKSPACE_CREATE` and `MANAGE_WORKSPACE` are now floored at
 * `author` (auth.md §7.6), and this suite runs the REAL [ScopeInterceptor] against the
 * REAL [WorkspacesController] handler methods with `DP-API-Key` principals, so a floor
 * regression on any of the five turns red here.
 *
 * The handler-annotation layer is what this class proves; the pinned-workspace rule for
 * key principals (the same finding's second half) is proven where the rule lives, in
 * auth's `WorkspaceKeyPinTest`. `WorkspacesControllerTest` covers the payloads; this
 * class covers who may reach them.
 */
class WorkspacesControllerApiKeyTest {
    private val mapper = ObjectMapper()
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), mockk<AuditLogger>(relaxed = true))
    private val controller = WorkspacesController(mockk<WorkspaceService>(relaxed = true))

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticateKey(scope: Scope) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "agent@company.com",
                "Agent",
                scope.expand(),
                AuthMethod.API_KEY,
                keyId = "dpk_TESTKEY",
                workspaceName = "acme",
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The five mutations the finding named, each with its HTTP verb and path. */
    private fun mutations(): List<Triple<String, HandlerMethod, String>> =
        listOf(
            Triple("POST", handler("create", JsonNode::class.java), "/api/v1/workspaces"),
            Triple("PUT", handler("update", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme"),
            Triple("DELETE", handler("delete", String::class.java), "/api/v1/workspaces/acme"),
            Triple("POST", handler("addMember", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme/members"),
            Triple("DELETE", handler("removeMember", String::class.java, UUID::class.java), "/api/v1/workspaces/acme/members/$USER_ID"),
        )

    private fun handler(
        name: String,
        vararg params: Class<*>,
    ): HandlerMethod = HandlerMethod(controller, WorkspacesController::class.java.getMethod(name, *params))

    private fun invoke(
        method: String,
        handler: HandlerMethod,
        path: String,
    ): Pair<Boolean, MockHttpServletResponse> {
        val response = MockHttpServletResponse()
        val proceed = interceptor.preHandle(MockHttpServletRequest(method, path), response, handler)
        return proceed to response
    }

    @Test
    fun `a read-scoped api key is 403 on all five workspace mutations`() {
        authenticateKey(Scope.READ)

        mutations().forEach { (method, handler, path) ->
            val (proceed, response) = invoke(method, handler, path)

            proceed.shouldBeFalse()
            response.status shouldBe 403
            val body = mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>
            body["code"] shouldBe "auth.scope.insufficient"
        }
    }

    @Test
    fun `an author-scoped api key passes the floor on all five workspace mutations`() {
        // The floor is the interceptor's whole say: ownership and the key's workspace pin
        // are the service's gates (WorkspaceKeyPinTest), reached only past this point.
        authenticateKey(Scope.AUTHOR)

        mutations().forEach { (method, handler, path) ->
            invoke(method, handler, path).first.shouldBeTrue()
        }
    }

    @Test
    fun `a read-scoped api key still reads the workspace surface`() {
        // WORKSPACES_READ stays read-floored (§7.6 "List / read own workspaces & members").
        authenticateKey(Scope.READ)

        invoke("GET", handler("list"), "/api/v1/workspaces").first.shouldBeTrue()
        invoke("GET", handler("get", String::class.java), "/api/v1/workspaces/acme").first.shouldBeTrue()
        invoke("GET", handler("members", String::class.java), "/api/v1/workspaces/acme/members").first.shouldBeTrue()
    }

    private companion object {
        val USER_ID: UUID = UUID.randomUUID()
    }
}
